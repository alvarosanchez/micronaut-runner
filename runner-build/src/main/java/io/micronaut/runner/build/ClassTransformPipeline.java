/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.runner.build;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Rewrites dependency classes at packaging time: it parses each class once, runs the enabled steps in a fixed
 * order, verifies the result and writes it once.
 *
 * <h2>Steps</h2>
 * <p>A {@link Step} supplies a pre-filter that sees the entry name and the bytes as read from the jar, a check
 * of whether it changes a parsed class, and the {@link ClassTransform} that makes the change. Enabled steps run
 * in a fixed order: {@linkplain LambdaDesugarer desugaring lambdas} first, then
 * {@linkplain LocalVariableStripper stripping}; their transforms are composed with
 * {@link ClassTransform#andThen(ClassTransform)}, so a class is parsed and written once whatever runs.</p>
 *
 * <h2>Planned nests</h2>
 * <p>Desugaring changes several classes together: a host, its nest host and the classes it generates. It
 * therefore {@linkplain JarRun#plan(JarClasses) plans} a whole jar before any entry is written, and the
 * pipeline treats each planned nest as a unit. Every enabled step runs over the unit's existing classes, the
 * generated classes skip the later steps, and the gate verifies every class of the unit. When a step throws on
 * one of them, or one verifies worse, the unit falls back under the rule below: it starts again from the
 * original bytes without that step, and a unit without desugaring has no generated class and no bridge. The
 * accepted bytes are kept until the entry loop reaches them ({@link JarRun#planned(String)}), and a host's
 * generated classes are written right after it.</p>
 *
 * <h2>Rules the pipeline owns</h2>
 * <ol type="a">
 *     <li><b>Pool.</b> {@code NEW_POOL}, {@code DROP_DEBUG} and {@code PASS_LINE_NUMBERS} apply only to a class
 *     that a {@linkplain Step#rebuildsConstantPool() pool-rebuilding} step, stripping, actually rewrites. Every
 *     other rewritten class keeps {@code SHARED_POOL} for every step: a rebuilt pool would silently break the
 *     raw pool indexes of an attribute the JDK does not know, which stripping declines and another step
 *     would not.</li>
 *     <li><b>Frames.</b> Every rewritten class is written with {@code DROP_STACK_MAPS}, and each method's
 *     original frames are attached again, by label, after the last step ({@link OriginalFrames}). Steps keep
 *     their edits length- and stack-neutral.</li>
 *     <li><b>Size.</b> A step that {@linkplain Step#skipsLargerOutput() skips a larger output} is skipped when
 *     its output is not smaller only when it is the only step that changed the class.</li>
 *     <li><b>Signed jars.</b> No step applies to any entry of a signed jar.</li>
 * </ol>
 *
 * <h2>The gate</h2>
 * <p>Every rewritten class is verified with {@link ClassFile#verify(byte[])} against the class path model. A
 * class whose rewritten form verifies cleanly is accepted at once. Otherwise the original is verified too, and
 * the rewrite is accepted only when its errors are among the original's: a dependency can already fail to
 * verify, typically because it references an optional dependency that is not on the class path, or because
 * another jar's copy of a class it uses wins. Two errors are the same when their messages differ at most in
 * the bytecode offset they name ({@link #grown(List, List)}): a rebuilt constant pool moves the instructions
 * of a method, and an error the class already had moves with them.</p>
 *
 * <p>The fallback rule is the same for every step. When step S throws, or the rewrite verifies worse, the class
 * starts again from its original bytes without S (for a verification failure, without the earliest step that
 * changed the class) and is gated again. Only if that attempt fails too is the class written as it was. One note
 * names the class, the dropped step and the first error; a false positive costs one step on one class, or on
 * one nest. A generated class has no original, so any verification error in it is a failure of desugaring.</p>
 *
 * <p>The pipeline never logs. Each jar's {@link JarRun} counts and notes what happened, the stage task hands
 * its {@link JarReport} back with its result, and the calling thread reports them in class-path order. The
 * output does not depend on the thread count, and a pipeline is safe to share between stage tasks: it holds
 * immutable ClassFile contexts and the class path model, and all per-jar state lives in the jar's run.</p>
 */
final class ClassTransformPipeline {

    /**
     * The largest class the pipeline reads into memory; a larger one keeps the streaming path, so a worker's
     * memory stays bounded. The largest class of a typical Micronaut application is under 200 KB.
     */
    static final int MAX_CLASS_SIZE = 8 * 1024 * 1024;

    /** The entry suffix of a class. */
    private static final String CLASS_SUFFIX = ".class";

    /** The longest first error a note quotes. */
    private static final int MAX_NOTE_ERROR = 300;

    /** The bytecode offset in a verifier message, such as the {@code @41} of {@code in Foo::bar() @41}. */
    private static final Pattern BYTECODE_OFFSET = Pattern.compile("@\\d+");

    private final List<Step> steps;
    /** The step that plans whole nests, and its position among the steps; {@code null} and -1 without one. */
    private final LambdaDesugarer desugarer;
    private final int desugarIndex;
    private final ClassFile rebuilt;
    private final ClassFile shared;
    private final Function<byte[], List<String>> verifier;

    /**
     * A pipeline that verifies against a class hierarchy.
     *
     * @param steps     the enabled steps, in the order they run
     * @param hierarchy the class hierarchy of the runtime class path, normally the {@link ClassPathModel}
     */
    ClassTransformPipeline(List<Step> steps, ClassHierarchyResolver hierarchy) {
        this(steps, hierarchy, verifierOf(hierarchy));
    }

    /**
     * A pipeline with its own verifier, which tests use to see what is verified.
     *
     * @param steps     the enabled steps, in the order they run
     * @param hierarchy the class hierarchy of the runtime class path
     * @param verifier  returns the verification errors of a class, as messages
     */
    ClassTransformPipeline(List<Step> steps, ClassHierarchyResolver hierarchy,
                           Function<byte[], List<String>> verifier) {
        this.steps = List.copyOf(steps);
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("A class transform pipeline needs at least one step");
        }
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        LambdaDesugarer planning = null;
        int planningIndex = -1;
        for (int i = 0; i < this.steps.size(); i++) {
            if (this.steps.get(i) instanceof LambdaDesugarer found) {
                planning = found;
                planningIndex = i;
            }
        }
        this.desugarer = planning;
        this.desugarIndex = planningIndex;
        ClassFile.ClassHierarchyResolverOption resolver = ClassFile.ClassHierarchyResolverOption.of(hierarchy);
        List<ClassFile.Option> rebuiltOptions = new ArrayList<>(LocalVariableStripper.OPTIONS);
        rebuiltOptions.add(ClassFile.StackMapsOption.DROP_STACK_MAPS);
        rebuiltOptions.add(resolver);
        this.rebuilt = ClassFile.of(rebuiltOptions.toArray(ClassFile.Option[]::new));
        this.shared = ClassFile.of(ClassFile.ConstantPoolSharingOption.SHARED_POOL,
                ClassFile.DebugElementsOption.PASS_DEBUG,
                ClassFile.LineNumbersOption.PASS_LINE_NUMBERS,
                ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES,
                ClassFile.StackMapsOption.DROP_STACK_MAPS,
                resolver);
    }

    /**
     * The verifier the gate uses: {@link ClassFile#verify(byte[])} with the given class hierarchy, reduced to
     * the errors' messages so that the errors of two classes can be compared.
     *
     * @param hierarchy the class hierarchy
     * @return the verifier
     */
    static Function<byte[], List<String>> verifierOf(ClassHierarchyResolver hierarchy) {
        ClassFile context = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy));
        return bytes -> {
            List<VerifyError> errors = context.verify(bytes);
            List<String> messages = new ArrayList<>(errors.size());
            for (VerifyError error : errors) {
                messages.add(String.valueOf(error.getMessage()));
            }
            return messages;
        };
    }

    /**
     * The first verification error of a rewritten class that its original does not have.
     *
     * <p>The verifier names the failing instruction by its bytecode offset, {@code @41}, and a rebuilt constant
     * pool moves offsets: it chooses {@code ldc} or {@code ldc_w} afresh, and switch padding follows. An error
     * the original already had therefore comes back at another offset, and is not growth, so two messages are
     * the same error when they are equal without their offsets. Each error of the original accounts for one
     * error of the rewrite, so a second error with the same text is growth.</p>
     *
     * @param rewritten the errors of the rewritten class, as {@link #verifierOf(ClassHierarchyResolver)} gives them
     * @param original  the errors of the class it replaces
     * @return the first error of {@code rewritten} that {@code original} does not account for, as the verifier
     * worded it, or {@code null} when the rewrite verifies no worse
     */
    static String grown(List<String> rewritten, List<String> original) {
        Map<String, Integer> known = new HashMap<>();
        for (String error : original) {
            known.merge(withoutOffset(error), 1, Integer::sum);
        }
        for (String error : rewritten) {
            String key = withoutOffset(error);
            Integer left = known.get(key);
            if (left == null || left == 0) {
                return error;
            }
            known.put(key, left - 1);
        }
        return null;
    }

    private static String withoutOffset(String error) {
        return BYTECODE_OFFSET.matcher(error).replaceAll("@");
    }

    /**
     * The enabled steps, in the order they run.
     *
     * @return the steps
     */
    List<Step> steps() {
        return steps;
    }

    /**
     * Starts the run over one jar. The run is confined to the thread that stages the jar.
     *
     * @param layer the jar
     * @return its run
     */
    JarRun start(Layer layer) {
        return new JarRun(layer);
    }

    /**
     * Adds up the reports of every jar, one total per step, in step order.
     *
     * @param reports one report per jar, in class-path order
     * @return the totals
     */
    List<TransformReport> totals(List<JarReport> reports) {
        List<TransformReport> totals = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            int rewritten = 0;
            int unchanged = 0;
            int fallbacks = 0;
            long saved = 0;
            for (JarReport report : reports) {
                StepCount count = report.counts().get(i);
                rewritten += count.rewritten();
                unchanged += count.unchanged();
                fallbacks += count.fallbacks();
                saved += count.bytesSaved();
            }
            totals.add(new TransformReport(steps.get(i).name(), rewritten, unchanged, fallbacks, saved));
        }
        return totals;
    }

    /**
     * The line the build logs for each step once every jar is staged, in step order.
     *
     * @param reports one report per jar, in class-path order
     * @return one line per step
     */
    List<String> summaries(List<JarReport> reports) {
        List<TransformReport> totals = totals(reports);
        List<String> lines = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            lines.add(steps.get(i).summary(totals.get(i), reports));
        }
        return lines;
    }

    /**
     * Whether a step of this pipeline plans the application layer too, so the builder has to run it there.
     *
     * @return whether lambdas are desugared
     */
    boolean plansApplication() {
        return desugarer != null;
    }

    /**
     * Writes {@code MICRONAUT-INF/transforms.txt}: tab-separated lines in class-path order, with nested entry
     * names and no absolute path or timestamp. The first line names the Runner version, the second the columns,
     * then one line per jar and step with its counts, then one {@code lambdas} line per jar with a lambda call
     * site, which gives the sites rewritten, the classes generated, the bridges added, the nests that fell back
     * and the sites left as {@code invokedynamic}, in total and by reason, and last one line per note.
     *
     * @param version the Runner version, or {@code null} when it is unknown
     * @param reports one report per jar, in class-path order
     * @return the content, or {@code null} when no step changed any class and nothing was noted
     */
    byte[] describe(String version, List<JarReport> reports) {
        boolean anything = false;
        for (JarReport report : reports) {
            anything |= !report.notes().isEmpty();
            for (StepCount count : report.counts()) {
                anything |= count.rewritten() > 0 || count.fallbacks() > 0;
            }
        }
        if (!anything) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        text.append("Micronaut-Runner-Version\t").append(version == null ? "unknown" : version).append('\n');
        text.append("jar\tstep\trewritten\tunchanged\tfallbacks\tbytesSaved\n");
        for (JarReport report : reports) {
            for (StepCount count : report.counts()) {
                if (report.application() && count.rewritten() + count.unchanged() + count.fallbacks() == 0) {
                    // A step that never runs over the application layer has nothing to say about it.
                    continue;
                }
                text.append(report.jar()).append('\t').append(count.step()).append('\t')
                        .append(count.rewritten()).append('\t').append(count.unchanged()).append('\t')
                        .append(count.fallbacks()).append('\t').append(count.bytesSaved()).append('\n');
            }
        }
        for (JarReport report : reports) {
            Desugared desugared = report.desugared();
            if (desugared == null || desugared.sites() + desugared.leftTotal() == 0) {
                continue;
            }
            text.append("lambdas\t").append(report.jar())
                    .append("\trewritten=").append(desugared.sites())
                    .append("\tgenerated=").append(desugared.generated())
                    .append("\tbridges=").append(desugared.bridges())
                    .append("\tnestFallbacks=").append(desugared.nestFallbacks())
                    .append("\tleft=").append(desugared.leftTotal());
            for (Map.Entry<LambdaDesugarer.Reason, Integer> left : desugared.left().entrySet()) {
                text.append("\tleft.").append(left.getKey().label()).append('=').append(left.getValue());
            }
            text.append('\n');
        }
        for (JarReport report : reports) {
            for (String note : report.notes()) {
                text.append("fallback\t").append(note).append('\n');
            }
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Whether an entry is a class the pipeline considers: not a directory, and named {@code *.class}.
     *
     * @param entry the entry
     * @return whether it is a class
     */
    static boolean isClass(ZipEntryInfo entry) {
        return !entry.directory() && entry.name().endsWith(CLASS_SUFFIX);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String oneLine(String text) {
        String line = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return line.length() > MAX_NOTE_ERROR ? line.substring(0, MAX_NOTE_ERROR) + "..." : line;
    }

    private static boolean rebuildsPool(List<Step> steps) {
        for (Step step : steps) {
            if (step.rebuildsConstantPool()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One step of the pipeline.
     */
    interface Step {

        /**
         * The step's name, which is the name of the option that enables it.
         *
         * @return the name
         */
        String name();

        /**
         * Whether the step applies to any class of a jar at all.
         *
         * @param layer the jar
         * @return whether it applies
         */
        boolean appliesTo(Layer layer);

        /**
         * A cheap pre-filter on the bytes as read from the jar. A class no enabled step's filter matches is
         * written byte for byte, without being parsed.
         *
         * @param entryName the entry name
         * @param bytes     the class bytes
         * @return whether the step might change the class
         */
        boolean matches(String entryName, byte[] bytes);

        /**
         * Whether the step changes a class. It declines here: returning {@code false} leaves the class to the
         * other steps.
         *
         * @param model the class, parsed with the options the pipeline chose
         * @return whether the step changes it
         */
        boolean changes(ClassModel model);

        /**
         * The transform that makes the step's change. It must keep every edit length- and stack-neutral,
         * because the class's original frames are attached again afterwards.
         *
         * @param model the class, parsed with the options the pipeline chose
         * @return the transform
         */
        ClassTransform transform(ClassModel model);

        /**
         * Whether a class the step rewrites gets a rebuilt constant pool, without its debug elements but with
         * its line numbers (rule a).
         *
         * @return whether the step rebuilds the pool
         */
        default boolean rebuildsConstantPool() {
            return false;
        }

        /**
         * Whether the class is left alone when the step is the only change and its output is not smaller
         * (rule c).
         *
         * @return whether a larger output is skipped
         */
        default boolean skipsLargerOutput() {
            return false;
        }

        /**
         * The line the build logs for the step once every jar is staged.
         *
         * @param report  what the step did
         * @param reports what the pipeline did to each jar, in class-path order
         * @return the line
         */
        String summary(TransformReport report, List<JarReport> reports);
    }

    /**
     * The jar a run works on.
     *
     * @param name          its nested entry name, which notes and {@code transforms.txt} use
     * @param index         its position in the class path model: {@code 0} for the application layer, then one
     *                      per dependency
     * @param application   whether it is the application layer
     * @param signed        whether it carried signature files
     * @param projectModule whether the build that packages the application also produced it
     */
    record Layer(String name, int index, boolean application, boolean signed, boolean projectModule) {
    }

    /**
     * The classes of one jar, or of the application layer, as a planning step reads them before any entry is
     * written.
     */
    interface JarClasses {

        /**
         * Every class entry, in entry order; a name the jar carries twice is listed once.
         *
         * @return the entries
         */
        List<ClassEntry> classes();

        /**
         * The uncompressed size of an entry.
         *
         * @param entryName the entry name
         * @return its size, or {@code -1} when the jar has no entry of that name
         */
        long size(String entryName);

        /**
         * Whether the jar carries an entry name more than once.
         *
         * @param entryName the entry name
         * @return whether it is repeated
         */
        boolean repeated(String entryName);

        /**
         * Reads a class, checked against its recorded size and CRC-32 where the source records them.
         *
         * @param entryName the entry name
         * @return its bytes
         * @throws IOException if it cannot be read
         */
        byte[] read(String entryName) throws IOException;
    }

    /**
     * One class entry of a jar.
     *
     * @param name its entry name
     * @param size its uncompressed size
     */
    record ClassEntry(String name, long size) {
    }

    /**
     * A class a step generated.
     *
     * @param name  its entry name
     * @param bytes its content
     */
    record Generated(String name, byte[] bytes) {
    }

    /**
     * What is written for a class of a planned nest.
     *
     * @param bytes     the class's accepted bytes, the original array when nothing changed it
     * @param generated the classes written right after it, in order
     */
    record Planned(byte[] bytes, List<Generated> generated) {
    }

    /**
     * What desugaring did to the lambda call sites of one jar.
     *
     * @param sites         the sites rewritten
     * @param generated     the classes generated
     * @param bridges       the bridge methods added
     * @param nestFallbacks the planned nests that fell back to their original classes
     * @param left          the sites left as {@code invokedynamic}, by reason, without the reasons that have none
     */
    record Desugared(int sites, int generated, int bridges, int nestFallbacks,
                     Map<LambdaDesugarer.Reason, Integer> left) {

        /**
         * Makes the map immutable, in the order of the reasons.
         *
         * @param sites         the sites rewritten
         * @param generated     the classes generated
         * @param bridges       the bridges added
         * @param nestFallbacks the nests that fell back
         * @param left          the sites left, by reason
         */
        Desugared {
            Map<LambdaDesugarer.Reason, Integer> ordered = new EnumMap<>(LambdaDesugarer.Reason.class);
            ordered.putAll(left);
            left = Collections.unmodifiableMap(ordered);
        }

        /**
         * The number of sites left as {@code invokedynamic}.
         *
         * @return the sum over every reason
         */
        int leftTotal() {
            int total = 0;
            for (int count : left.values()) {
                total += count;
            }
            return total;
        }
    }

    /**
     * What one step did to one jar.
     *
     * @param step       the step's name
     * @param rewritten  the classes it rewrote
     * @param unchanged  the classes it left alone
     * @param fallbacks  the classes it gave up on
     * @param bytesSaved how much smaller the classes it rewrote became
     */
    record StepCount(String step, int rewritten, int unchanged, int fallbacks, long bytesSaved) {
    }

    /**
     * What the pipeline did to one jar, handed back to the calling thread with the jar's stage result.
     *
     * @param jar         the jar's nested entry name
     * @param application whether it is the application layer, which counts only for the steps that run over it
     * @param counts      one count per enabled step, in step order
     * @param notes       one line per fallback, tab-separated: the jar, the class or {@code the nest of} its
     *                    nest host, the dropped step and the first error
     * @param desugared   what desugaring did to the jar's lambda call sites, which in a signed jar is only to
     *                    count them, or {@code null} when it did not run over the jar
     */
    record JarReport(String jar, boolean application, List<StepCount> counts, List<String> notes,
                     Desugared desugared) {

        /**
         * Makes the lists immutable.
         *
         * @param jar         the jar
         * @param application whether it is the application layer
         * @param counts      the counts
         * @param notes       the notes
         * @param desugared   what desugaring did
         */
        JarReport {
            counts = List.copyOf(counts);
            notes = List.copyOf(notes);
        }
    }

    /**
     * The pipeline's work on one jar. It is confined to one thread: it holds the jar's counters, its notes and
     * the {@link CRC32} that checksums the classes it rewrites.
     */
    final class JarRun {

        private final Layer layer;
        private final boolean[] applies;
        /** Whether a step counts this layer's classes: the application layer counts only for the steps run over it. */
        private final boolean[] counted;
        /** Whether a step other than the planning one applies, so classes are worth reading in the entry loop. */
        private final boolean any;
        private final int[] rewritten;
        private final int[] unchanged;
        private final int[] fallbacks;
        private final long[] saved;
        private final List<String> notes = new ArrayList<>();
        private final CRC32 crc = new CRC32();
        /** The accepted classes of the planned nests, by entry name, until the entry loop takes them. */
        private final Map<String, Planned> planned = new HashMap<>();
        /** The classes of the nests that fell back from desugaring, which the entry loop processes as usual. */
        private final Set<String> declined = new HashSet<>();
        private final int[] left = new int[LambdaDesugarer.Reason.values().length];
        private int sites;
        private int generated;
        private int bridges;
        private int nestFallbacks;

        private JarRun(Layer layer) {
            this.layer = Objects.requireNonNull(layer, "layer");
            int count = steps.size();
            applies = new boolean[count];
            counted = new boolean[count];
            boolean applicable = false;
            for (int i = 0; i < count; i++) {
                // Rule d: nothing in a signed jar is rewritten, whatever the step says.
                applies[i] = !layer.signed() && steps.get(i).appliesTo(layer);
                counted[i] = !layer.application() || steps.get(i).appliesTo(layer);
                applicable |= applies[i] && i != desugarIndex;
            }
            any = applicable;
            rewritten = new int[count];
            unchanged = new int[count];
            fallbacks = new int[count];
            saved = new long[count];
        }

        /**
         * Whether a class of this size is read into memory and given to {@link #process(String, byte[])}. A
         * class that is not must be passed to {@link #pass(String)} instead. A class of a planned nest is
         * neither: {@link #planned(String)} hands back what to write for it.
         *
         * @param size the class's uncompressed size
         * @return whether the class goes through the pipeline
         */
        boolean reads(long size) {
            return any && size <= MAX_CLASS_SIZE;
        }

        /**
         * Counts a class that is written without being read: it is too large, no step applies to the jar, or no
         * step but the planning one does and the class is not part of an accepted nest. A class whose nest fell
         * back from desugaring counts as a fallback of that step.
         *
         * @param entryName the class's entry name
         */
        void pass(String entryName) {
            for (int i = 0; i < unchanged.length; i++) {
                if (!counted[i]) {
                    continue;
                }
                if (i == desugarIndex && declined.contains(entryName)) {
                    fallbacks[i]++;
                } else {
                    unchanged[i]++;
                }
            }
        }

        /**
         * Plans the jar before any of its entries is written, when a step plans whole nests: each planned nest
         * is rewritten, verified and either accepted or given up on here, and its classes wait for
         * {@link #planned(String)}. In a signed jar, which no step rewrites, the planning step only counts the
         * lambda call sites it leaves.
         *
         * @param classes the jar's classes
         * @throws IOException if a class cannot be read
         */
        void plan(JarClasses classes) throws IOException {
            if (desugarer == null) {
                return;
            }
            LambdaDesugarer.JarPlan plan = desugarer.plan(layer, classes);
            for (int reason = 0; reason < left.length; reason++) {
                left[reason] += plan.left()[reason];
            }
            if (!applies[desugarIndex]) {
                // Rule d: whatever the plan says, nothing of a signed jar is rewritten.
                return;
            }
            for (LambdaDesugarer.Unit unit : plan.units()) {
                run(unit);
            }
        }

        /**
         * Whether this run plans nests, or counts the lambda call sites of a signed jar, so
         * {@link #plan(JarClasses)} has work to do.
         *
         * @return whether the pipeline has a planning step
         */
        boolean plans() {
            return desugarer != null;
        }

        /**
         * Takes what is written for a class of an accepted nest. Each class is handed out once.
         *
         * @param entryName the class's entry name
         * @return the class's bytes and the generated classes that follow it, or {@code null} when the class
         * is not part of an accepted nest
         */
        Planned planned(String entryName) {
            return planned.isEmpty() ? null : planned.remove(entryName);
        }

        /**
         * Rewrites one planned nest as a unit, under the fallback rule.
         */
        private void run(LambdaDesugarer.Unit unit) {
            UnitAttempt first = attempt(unit, null);
            if (first.failed == null) {
                accept(unit, first, null, null);
                return;
            }
            String error = first.error;
            UnitAttempt second = null;
            if (first.failed != desugarer) {
                // Without desugaring the nest is no unit any more: the entry loop processes its classes one by
                // one. Without another step, the nest is desugared again, and gated again.
                second = attempt(unit, first.failed);
                if (second.failed != null) {
                    error = oneLine(error + "; the nest was kept as it was because " + second.failed.name()
                            + " failed too: " + second.error);
                }
            }
            notes.add(String.join("\t", oneLine(layer.name()), "the nest of " + oneLine(unit.nestHostEntry()),
                    first.failed.name(), error));
            if (first.failed == desugarer) {
                declined.addAll(unit.classes().keySet());
                abandon(unit);
            } else if (second.failed == null) {
                accept(unit, second, first.failed, null);
            } else {
                accept(unit, null, first.failed, second.failed);
                abandon(unit);
            }
        }

        private void abandon(LambdaDesugarer.Unit unit) {
            nestFallbacks++;
            left[LambdaDesugarer.Reason.NEST_FALLBACK.ordinal()] += unit.sites();
        }

        /**
         * Runs every enabled step but one over the existing classes of a nest, generates its classes and
         * verifies them all.
         *
         * @param without the step to leave out, or {@code null}
         */
        private UnitAttempt attempt(LambdaDesugarer.Unit unit, Step without) {
            Map<String, Attempt> results = new HashMap<>();
            for (Map.Entry<String, LambdaDesugarer.ClassPlan> entry : unit.classes().entrySet()) {
                byte[] original = entry.getValue().original();
                List<Step> candidates = candidates(entry.getKey(), original, without);
                candidates.add(0, desugarer);
                Attempt result = attempt(original, candidates, entry.getValue());
                if (result.failed != null) {
                    return UnitAttempt.failed(result.failed, entry.getKey() + ": " + result.error);
                }
                results.put(entry.getKey(), result);
            }
            Map<String, List<Generated>> generatedClasses;
            try {
                generatedClasses = unit.generate();
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                return UnitAttempt.failed(desugarer, unit.nestHostEntry() + ": " + describe(failure));
            }
            for (List<Generated> classes : generatedClasses.values()) {
                for (Generated generatedClass : classes) {
                    // A generated class has no original: any error in it is growth.
                    List<String> errors = verifier.apply(generatedClass.bytes());
                    if (!errors.isEmpty()) {
                        return UnitAttempt.failed(desugarer, generatedClass.name() + ": verification: "
                                + errors.get(0));
                    }
                }
            }
            return new UnitAttempt(results, generatedClasses, null, null);
        }

        /**
         * Keeps a nest's classes for the entry loop and counts them.
         *
         * @param accepted the attempt that was accepted, or {@code null} to keep every class as it was
         * @param first    the step the nest dropped, or {@code null}
         * @param second   the second step it dropped, or {@code null}
         */
        private void accept(LambdaDesugarer.Unit unit, UnitAttempt accepted, Step first, Step second) {
            for (Map.Entry<String, LambdaDesugarer.ClassPlan> entry : unit.classes().entrySet()) {
                String entryName = entry.getKey();
                byte[] original = entry.getValue().original();
                Attempt result = accepted == null ? Attempt.UNCHANGED : accepted.results.get(entryName);
                byte[] output = result.bytes == null ? original : result.bytes;
                for (int i = 0; i < steps.size(); i++) {
                    Step step = steps.get(i);
                    if (!counted[i]) {
                        continue;
                    }
                    if ((step == first || step == second)
                            && (step == desugarer || applies[i] && step.matches(entryName, original))) {
                        fallbacks[i]++;
                    } else if (result.active.contains(step)) {
                        rewritten[i]++;
                        saved[i] += original.length - output.length;
                    } else {
                        unchanged[i]++;
                    }
                }
                List<Generated> following = accepted == null ? List.of()
                        : accepted.generated.getOrDefault(entryName, List.of());
                for (Generated generatedClass : following) {
                    saved[desugarIndex] -= generatedClass.bytes().length;
                }
                generated += following.size();
                planned.put(entryName, new Planned(output, following));
            }
            if (accepted != null) {
                sites += unit.sites();
                bridges += unit.bridges();
            }
        }

        /**
         * The steps, other than the planning one and {@code without}, whose pre-filter matches a class.
         */
        private List<Step> candidates(String entryName, byte[] original, Step without) {
            List<Step> candidates = new ArrayList<>(steps.size());
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (applies[i] && i != desugarIndex && step != without && step.matches(entryName, original)) {
                    candidates.add(step);
                }
            }
            return candidates;
        }

        /**
         * The CRC-32 of a rewritten class, through the run's own checksum.
         *
         * @param bytes the class
         * @return its CRC-32
         */
        long crc32(byte[] bytes) {
            crc.reset();
            crc.update(bytes, 0, bytes.length);
            return crc.getValue();
        }

        /**
         * Runs the enabled steps over one class.
         *
         * @param entryName the class's entry name
         * @param original  its bytes, as read from the jar
         * @return the bytes to write: {@code original} itself when no step changed the class
         */
        byte[] process(String entryName, byte[] original) {
            // The planning step is no candidate here: it changes a class only as part of a planned nest.
            List<Step> candidates = candidates(entryName, original, null);
            if (candidates.isEmpty()) {
                pass(entryName);
                return original;
            }
            Attempt first = attempt(original, candidates, null);
            if (first.failed == null) {
                return settle(entryName, original, first, null, null);
            }
            List<Step> remaining = new ArrayList<>(candidates);
            remaining.remove(first.failed);
            Attempt second = remaining.isEmpty() ? Attempt.UNCHANGED : attempt(original, remaining, null);
            String error = first.error;
            if (second.failed != null) {
                error = oneLine(error + "; the class was kept as it was because " + second.failed.name()
                        + " failed too: " + second.error);
            }
            notes.add(String.join("\t", oneLine(layer.name()), oneLine(entryName), first.failed.name(), error));
            return settle(entryName, original, second.failed == null ? second : Attempt.UNCHANGED, first.failed,
                    second.failed);
        }

        /**
         * Counts what happened to one class and returns the bytes to write.
         */
        private byte[] settle(String entryName, byte[] original, Attempt accepted, Step firstFailure,
                              Step secondFailure) {
            byte[] output = accepted.bytes == null ? original : accepted.bytes;
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (!counted[i]) {
                    continue;
                }
                if (step == firstFailure || step == secondFailure
                        || i == desugarIndex && declined.contains(entryName)) {
                    fallbacks[i]++;
                } else if (accepted.active.contains(step)) {
                    rewritten[i]++;
                    saved[i] += original.length - output.length;
                } else {
                    unchanged[i]++;
                }
            }
            return output;
        }

        /**
         * Parses, transforms and gates one class with some steps.
         *
         * @param nest what the planning step does to the class as part of its nest, or {@code null} when
         *             the class is processed on its own
         */
        private Attempt attempt(byte[] original, List<Step> candidates, LambdaDesugarer.ClassPlan nest) {
            Attribution attribution = new Attribution();
            Step blame = candidates.get(0);
            try {
                boolean rebuild = rebuildsPool(candidates);
                ClassFile context = rebuild ? rebuilt : shared;
                ClassModel model = context.parse(original);
                List<Step> active = new ArrayList<>(candidates.size());
                for (Step step : candidates) {
                    blame = step;
                    if (step == desugarer ? nest != null : step.changes(model)) {
                        active.add(step);
                    }
                }
                if (active.isEmpty()) {
                    return Attempt.UNCHANGED;
                }
                blame = active.get(0);
                if (rebuild && !rebuildsPool(active)) {
                    // Rule a: the step that rebuilds the pool declined, so every other step keeps it shared, and
                    // the class must be parsed again with its debug elements.
                    context = shared;
                    model = context.parse(original);
                }
                ClassTransform transform = null;
                for (Step step : active) {
                    blame = step;
                    ClassTransform own = step == desugarer ? nest.transform() : step.transform(model);
                    ClassTransform stepTransform = new Gate(step, attribution).andThen(own);
                    transform = transform == null ? stepTransform : transform.andThen(stepTransform);
                }
                blame = active.get(0);
                transform = transform.andThen(new Gate(null, attribution))
                        .andThen(OriginalFrames.of(model).reattaching());
                byte[] output = context.transformClass(model, transform);
                attribution.current = null;
                if (active.size() == 1 && active.get(0).skipsLargerOutput() && output.length >= original.length) {
                    return Attempt.UNCHANGED;
                }
                List<String> errors = verifier.apply(output);
                if (!errors.isEmpty()) {
                    String grown = grown(errors, verifier.apply(original));
                    if (grown != null) {
                        return Attempt.failed(active.get(0), "verification: " + grown);
                    }
                }
                return new Attempt(output, active, null, null);
            } catch (Gate.Failure failure) {
                return Attempt.failed(failure.step == null ? blame : failure.step, describe(failure.getCause()));
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                Step step = attribution.current == null ? blame : attribution.current;
                return Attempt.failed(step, describe(failure));
            }
        }

        /**
         * What the pipeline did to this jar.
         *
         * @return the report
         */
        JarReport report() {
            List<StepCount> counts = new ArrayList<>(steps.size());
            for (int i = 0; i < steps.size(); i++) {
                counts.add(new StepCount(steps.get(i).name(), rewritten[i], unchanged[i], fallbacks[i], saved[i]));
            }
            Desugared desugared = null;
            if (plans()) {
                Map<LambdaDesugarer.Reason, Integer> reasons = new EnumMap<>(LambdaDesugarer.Reason.class);
                for (LambdaDesugarer.Reason reason : LambdaDesugarer.Reason.values()) {
                    if (left[reason.ordinal()] > 0) {
                        reasons.put(reason, left[reason.ordinal()]);
                    }
                }
                desugared = new Desugared(sites, generated, bridges, nestFallbacks, reasons);
            }
            return new JarReport(layer.name(), layer.application(), counts, notes, desugared);
        }
    }

    /**
     * The outcome of one attempt at a planned nest: every class's attempt and the generated classes of each
     * host, or the step to drop and why.
     */
    private static final class UnitAttempt {

        private final Map<String, Attempt> results;
        private final Map<String, List<Generated>> generated;
        private final Step failed;
        private final String error;

        private UnitAttempt(Map<String, Attempt> results, Map<String, List<Generated>> generated, Step failed,
                            String error) {
            this.results = results;
            this.generated = generated;
            this.failed = failed;
            this.error = error;
        }

        private static UnitAttempt failed(Step step, String error) {
            return new UnitAttempt(Map.of(), Map.of(), step, oneLine(error));
        }
    }

    /**
     * The outcome of one attempt at a class: accepted bytes and the steps that changed them, no change at all,
     * or the step to drop and why.
     */
    private static final class Attempt {

        private static final Attempt UNCHANGED = new Attempt(null, List.of(), null, null);

        private final byte[] bytes;
        private final List<Step> active;
        private final Step failed;
        private final String error;

        private Attempt(byte[] bytes, List<Step> active, Step failed, String error) {
            this.bytes = bytes;
            this.active = active;
            this.failed = failed;
            this.error = error;
        }

        private static Attempt failed(Step step, String error) {
            return new Attempt(null, List.of(), step, oneLine(error));
        }
    }

    /**
     * Which step's start or end handler is running, for a failure that no gate sees: the handlers of a chain
     * run one after the other, not inside each other.
     */
    private static final class Attribution {
        private Step current;
    }

    /**
     * Placed in front of each step, and in front of the frame re-attachment, to tell which one failed.
     *
     * <p>Transforms composed with {@code andThen} push each element downstream from inside the upstream
     * transform's own call, so a failure in a step unwinds through the gate in front of it before it reaches
     * any gate further up; the innermost gate names the step and the others pass the failure on. A gate is an
     * ordinary transform that forwards every element, which keeps the steps' own transforms, resolved or not,
     * untouched.</p>
     */
    private static final class Gate implements ClassTransform {

        private final Step step;
        private final Attribution attribution;

        private Gate(Step step, Attribution attribution) {
            this.step = step;
            this.attribution = attribution;
        }

        @Override
        public void accept(ClassBuilder builder, ClassElement element) {
            try {
                builder.with(element);
            } catch (Failure failure) {
                throw failure;
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                throw new Failure(step, failure);
            }
        }

        @Override
        public void atStart(ClassBuilder builder) {
            attribution.current = step;
        }

        @Override
        public void atEnd(ClassBuilder builder) {
            attribution.current = step;
        }

        /**
         * A failure a gate attributed; a {@code null} step means the frame re-attachment.
         */
        private static final class Failure extends RuntimeException {

            private final transient Step step;

            private Failure(Step step, Throwable cause) {
                super(cause.getMessage(), cause, false, false);
                this.step = step;
            }
        }
    }
}
