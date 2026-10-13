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

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The lambda fixtures of the desugaring tests, and the harness that runs Micronaut AOT's engine over fixture layers
 * the way a build does: each layer is written into a jar, and the engine desugars them all in one run, writing a
 * copy of each layer it rewrites with the classes it generated right after their hosts.
 */
final class LambdaFixtures {

    /** The application class whose static {@code run()} runs the scenario and its own lambdas. */
    static final String APPLICATION = "app.Main";

    private static final String SCENARIO_SOURCE = """
            package fix;

            import fix.util.Texts;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.List;
            import java.util.Objects;
            import java.util.function.BiFunction;
            import java.util.function.Consumer;
            import java.util.function.Function;
            import java.util.function.IntToLongFunction;
            import java.util.function.Supplier;
            import java.util.function.ToIntFunction;

            public class Scenario {

                @Target(ElementType.TYPE_USE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Note {
                }

                public interface Shape {
                    String name();

                    default Supplier<String> described() {
                        return () -> "shape:" + name();
                    }

                    static Function<String, String> wrapping() {
                        return value -> "[" + value + "]";
                    }
                }

                public static final class Nested {
                    private final String label;

                    private Nested(String label) {
                        this.label = label;
                    }

                    private static String secret(String value) {
                        return "secret:" + value;
                    }

                    private String tagged(String value) {
                        return label + "/" + value;
                    }
                }

                public final class Inner {
                    private final int base;

                    Inner(int base) {
                        this.base = base;
                    }

                    List<String> compute() {
                        List<String> out = new ArrayList<>();
                        Function<Integer, Integer> add = value -> value + base + prefix.length();
                        out.add("inner=" + add.apply(10));
                        Supplier<String> outer = Scenario.this::hidden;
                        out.add("innerOuter=" + outer.get());
                        return out;
                    }
                }

                private final String prefix;

                public Scenario(String prefix) {
                    this.prefix = prefix;
                }

                static String staticImpl(String value) {
                    return "s:" + value;
                }

                String virtualImpl(String value) {
                    return prefix + ":" + value;
                }

                private String hidden() {
                    return "hidden:" + prefix;
                }

                private String privateImpl(String value) {
                    return "p:" + prefix + value;
                }

                static int increment(int value) {
                    return value + 1;
                }

                static long toLong(long value) {
                    return value * 2L;
                }

                int count() {
                    return prefix.length();
                }

                long wide() {
                    return 1L << 40;
                }

                static double half(float value) {
                    return value / 2.0;
                }

                static Supplier<String> constant() {
                    return () -> "constant";
                }

                static String annotated(List<String> values) {
                    Function<String, String> mark = value -> value + "!";
                    @Note String first = values.get(0);
                    String second = mark.apply(first);
                    return second;
                }

                Supplier<String> capturing(int number, long big, double ratio, String text) {
                    return () -> prefix + number + big + ratio + text;
                }

                public static List<String> run() throws Exception {
                    List<String> out = new ArrayList<>();
                    Scenario scenario = new Scenario("x");
                    Function<String, String> f1 = Scenario::staticImpl;
                    out.add("static=" + f1.apply("a"));
                    Function<String, String> f2 = scenario::virtualImpl;
                    out.add("bound=" + f2.apply("b"));
                    BiFunction<Scenario, String, String> f3 = Scenario::virtualImpl;
                    out.add("unbound=" + f3.apply(scenario, "c"));
                    Function<List<String>, Integer> f4 = List::size;
                    out.add("interface=" + f4.apply(Arrays.asList("1", "2")));
                    Function<String, String> f5 = scenario::privateImpl;
                    out.add("private=" + f5.apply("d"));
                    Function<String, String> f6 = Nested::secret;
                    out.add("nestmateStatic=" + f6.apply("e"));
                    Function<String, Nested> f7 = Nested::new;
                    Nested nested = f7.apply("n");
                    Function<String, String> f8 = nested::tagged;
                    out.add("nestmate=" + f8.apply("f"));
                    Supplier<ArrayList<String>> f9 = ArrayList::new;
                    Function<String, Scenario> f10 = Scenario::new;
                    out.add("constructors=" + f9.get().size() + f10.apply("y").virtualImpl("z"));
                    Function<Integer, Integer> f11 = Scenario::increment;
                    out.add("boxing=" + f11.apply(41));
                    Function<Integer, Long> f12 = Scenario::toLong;
                    out.add("widening=" + f12.apply(21));
                    IntToLongFunction f13 = Scenario::toLong;
                    out.add("primitiveWidening=" + f13.applyAsLong(4));
                    ToIntFunction<String> f14 = String::length;
                    out.add("checkcast=" + f14.applyAsInt("four"));
                    Supplier<Object> f15 = scenario::count;
                    out.add("boxedResult=" + f15.get());
                    Function<Float, Object> f16 = Scenario::half;
                    out.add("floatToDouble=" + f16.apply(3.0f));
                    List<String> sink = new ArrayList<>();
                    Consumer<String> f17 = sink::add;
                    f17.accept("dropped");
                    Runnable f18 = scenario::wide;
                    f18.run();
                    out.add("discarded=" + sink);
                    out.add("capturedThis=" + scenario.capturing(3, 5L, 1.5, "t").get());
                    out.add("identity=" + (constant() == constant()) + constant().get());
                    out.add("annotated=" + annotated(Arrays.asList("first")));
                    out.addAll(scenario.new Inner(7).compute());
                    Shape shape = () -> "square";
                    out.add("interfaceHost=" + shape.described().get() + Shape.wrapping().apply("w"));
                    Function<String, String> f19 = Texts::shout;
                    out.add("samePackageJar=" + f19.apply("loud"));
                    Function<String, String> f20 = other.Helper::twice;
                    out.add("otherJar=" + f20.apply("ab"));
                    Function<Object, String> f21 = String::valueOf;
                    Function<String, String> f22 = Objects::requireNonNull;
                    out.add("jdk=" + f21.apply(12) + f22.apply("nn"));
                    out.add("serializable=" + new Ser().make().get()
                            + java.io.ObjectStreamClass.lookup(Ser.class).getSerialVersionUID());
                    // Class.isHidden() is newer than the oldest release this source is compiled at.
                    java.lang.reflect.Method hidden = Class.class.getMethod("isHidden");
                    out.add("hidden=" + hidden.invoke(f1.getClass()) + hidden.invoke(constant().getClass()));
                    return out;
                }
            }
            """;

    private static final String SERIALIZABLE_SOURCE = """
            package fix;

            import java.io.Serializable;
            import java.util.function.Supplier;

            public class Ser implements Serializable {
                private static final long serialVersionUID = 7L;

                public Supplier<String> make() {
                    String local = "ser";
                    return () -> local + hidden();
                }

                private String hidden() {
                    return "!";
                }
            }
            """;

    private static final String TEXTS_SOURCE = """
            package fix.util;

            public class Texts {
                public static String shout(String value) {
                    return value.toUpperCase() + "!";
                }
            }
            """;

    private static final String HELPER_SOURCE = """
            package other;

            public class Helper {
                public static String twice(String value) {
                    return value + value;
                }
            }
            """;

    private static final String APPLICATION_SOURCE = """
            package app;

            import fix.Scenario;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Function;

            public class Main {
                public static List<String> run() throws Exception {
                    List<String> out = new ArrayList<>(Scenario.run());
                    Function<String, String> own = value -> "app:" + value;
                    Function<String, Scenario> dependency = Scenario::new;
                    out.add("application=" + own.apply("k") + (dependency.apply("q") != null));
                    return out;
                }

                public static void main(String[] args) throws Exception {
                    for (String line : run()) {
                        System.out.println(line);
                    }
                }
            }
            """;

    private LambdaFixtures() {
    }

    /**
     * Compiles the scenario at a release, with {@code -g}: an application layer, a library and a second
     * library the first one's lambdas call into.
     *
     * @param directory where the sources and the classes go
     * @param release   the {@code --release} to compile at
     * @return the three layers, the application layer first
     * @throws IOException if the fixture does not compile
     */
    static List<Layer> scenario(Path directory, int release) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fix/Scenario.java", SCENARIO_SOURCE);
        sources.put("fix/Ser.java", SERIALIZABLE_SOURCE);
        sources.put("fix/util/Texts.java", TEXTS_SOURCE);
        sources.put("other/Helper.java", HELPER_SOURCE);
        sources.put("app/Main.java", APPLICATION_SOURCE);
        Map<String, byte[]> compiled = ClassFixtures.classes(ClassFixtures.compile(directory.resolve("src"),
                directory.resolve("classes"), List.of("-g", "--release", Integer.toString(release)), sources));
        return List.of(
                Layer.application(select(compiled, "app/")),
                Layer.dependency(select(compiled, "fix/")),
                Layer.dependency(select(compiled, "other/")));
    }

    /**
     * The entries of a compilation whose names start with a prefix, in name order.
     *
     * @param compiled the compiled classes
     * @param prefix   the prefix
     * @return the matching entries
     */
    static Map<String, byte[]> select(Map<String, byte[]> compiled, String prefix) {
        Map<String, byte[]> selected = new LinkedHashMap<>();
        compiled.forEach((name, bytes) -> {
            if (name.startsWith(prefix)) {
                selected.put(name, bytes);
            }
        });
        return selected;
    }

    /**
     * Desugars the lambdas of fixture layers with {@link ClassPathRewrite}, the first layer first on the class path.
     *
     * @param directory where the layers' jars and the transform's copies go
     * @param layers    the layers, in class-path order
     * @return what each layer became and what the transform reports
     * @throws IOException if a jar cannot be written or read
     */
    static Outcome desugar(Path directory, List<Layer> layers) throws IOException {
        return rewrite(directory, layers, false);
    }

    /**
     * Desugars the lambdas of fixture layers with {@link ClassPathRewrite}, the first layer first on the class path,
     * and strips every layer after the first when asked to, in the same run.
     *
     * @param directory where the layers' jars and the transform's copies go
     * @param layers    the layers, in class-path order
     * @param strip     whether the layers after the first are stripped
     * @return what each layer became and what the transform reports
     * @throws IOException if a jar cannot be written or read
     */
    static Outcome rewrite(Path directory, List<Layer> layers, boolean strip) throws IOException {
        List<Path> jars = new ArrayList<>(layers.size());
        for (int index = 0; index < layers.size(); index++) {
            jars.add(layers.get(index).write(directory.resolve("layers").resolve(index + ".jar")));
        }
        List<Path> dependencies = jars.subList(1, jars.size());
        ClassPathRewrite.Result result = ClassPathRewrite.run(jars.get(0), dependencies, true,
                strip ? dependencies : List.of(), directory.resolve("rewritten"), 1);
        List<Map<String, byte[]>> outputs = new ArrayList<>(layers.size());
        for (Path source : result.classPath()) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (ZipReader reader = ZipReader.open(source)) {
                for (ZipEntryInfo entry : reader.entries()) {
                    if (!entry.directory() && !entry.name().equals("META-INF/MANIFEST.MF")) {
                        entries.put(entry.name(), reader.read(entry));
                    }
                }
            }
            outputs.add(entries);
        }
        return new Outcome(outputs, result.counts(), jars, result.classPath());
    }

    /**
     * The classes a runtime would load from some layers: the first copy of each base entry, by binary name.
     *
     * @param layers what each layer holds, in class-path order
     * @return the classes
     */
    static Map<String, byte[]> classPath(List<Map<String, byte[]>> layers) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (Map<String, byte[]> layer : layers) {
            layer.forEach((name, bytes) -> {
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    classes.putIfAbsent(name.substring(0, name.length() - 6).replace('/', '.'), bytes);
                }
            });
        }
        return classes;
    }

    /**
     * A loader that defines the given classes itself, and verifies them as any application loader does.
     *
     * @param classes the classes, by binary name
     * @return the loader
     */
    static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /**
     * Calls the static {@code run()} of a class and returns the lines it produced.
     *
     * @param classes the class path
     * @param type    the class to run
     * @return the lines
     * @throws Exception if the class cannot be loaded or its {@code run()} fails
     */
    @SuppressWarnings("unchecked")
    static List<String> run(Map<String, byte[]> classes, String type) throws Exception {
        return (List<String>) loader(classes).loadClass(type).getMethod("run").invoke(null);
    }

    /**
     * The number of call sites of a class that a bootstrap method of the given class links.
     *
     * @param bytes     the class
     * @param bootstrap the internal name of the bootstrap method's class, such as
     *                  {@code java/lang/invoke/LambdaMetafactory}
     * @return the number of such {@code invokedynamic} instructions
     */
    static int sites(byte[] bytes, String bootstrap) {
        ClassModel model = ClassFile.of().parse(bytes);
        int sites = 0;
        for (MethodModel method : model.methods()) {
            if (method.code().isEmpty()) {
                continue;
            }
            for (CodeElement element : method.code().get()) {
                if (element instanceof InvokeDynamicInstruction indy && indy.bootstrapMethod().owner()
                        .descriptorString().equals("L" + bootstrap + ";")) {
                    sites++;
                }
            }
        }
        return sites;
    }

    /**
     * One layer of a fixture class path.
     */
    static final class Layer {

        private final Map<String, byte[]> entries;
        private final boolean signed;

        private Layer(Map<String, byte[]> entries, boolean signed) {
            this.entries = entries;
            this.signed = signed;
        }

        static Layer application(Map<String, byte[]> entries) {
            return new Layer(entries, false);
        }

        static Layer dependency(Map<String, byte[]> entries) {
            return new Layer(entries, false);
        }

        static Layer signed(Map<String, byte[]> entries) {
            return new Layer(entries, true);
        }

        Map<String, byte[]> entries() {
            return entries;
        }

        /**
         * Writes the layer into a jar. A signed layer carries the signature files of a signer, which the engine
         * does not verify.
         *
         * @param jar the jar to write
         * @return the jar
         * @throws IOException if it cannot be written
         */
        Path write(Path jar) throws IOException {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            Map<String, byte[]> written = new LinkedHashMap<>();
            if (signed) {
                written.put("META-INF/SIGNER.SF", "Signature-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                written.put("META-INF/SIGNER.RSA", new byte[] {1, 2, 3});
            }
            written.putAll(entries);
            Files.createDirectories(jar.getParent());
            return ClassFixtures.jar(jar, manifest, written);
        }
    }

    /**
     * What a run of the transform produced.
     *
     * @param outputs the entries of what each layer became, in the order the jar holds them, without its manifest
     * @param counts  what the transform did to each layer, {@code null} for a layer it did not run over
     * @param jars    the jar each layer was written into
     * @param sources what a build nests for each layer: the transform's copy, or the layer's own jar
     */
    record Outcome(List<Map<String, byte[]>> outputs, List<ClassPathRewrite.Counts> counts, List<Path> jars,
                   List<Path> sources) {
    }
}
