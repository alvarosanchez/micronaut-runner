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
package io.micronaut.runner.generated.prefetch;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.BeanContextConfiguration;
import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.core.order.Ordered;
import io.micronaut.inject.BeanDefinitionReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.InvokeDynamicEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the two classes runner-build copies into a runner jar to prefetch the bean definitions, run here
 * against the micronaut-inject of the test class path.
 *
 * <p>The fixtures are bean definition references the test compiles, each registered with its own entry under
 * {@code META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/} and defined by a class loader made for
 * one test, so that every test sees their static initialisers run. They report to {@link PrefetchProbe}. The
 * class path of the test contributes references of its own, micronaut-inject's, which both sides of every
 * comparison load alike.</p>
 *
 * <p>Two things are made observable with a stand-in for {@code io.micronaut.core.convert.ConversionService},
 * which the fixture class loader defines itself in the tests that ask for one: what the task's own thread does
 * first, and what the configurer's guard waits for. Micronaut runs the reference initialisers on whichever common
 * pool threads pick them up, so only that first step is tied to the thread the task runs on.</p>
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class DefinitionPrefetchTest {

    private static final String REFERENCES = "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/";
    private static final String CONVERSION_SERVICE = "io.micronaut.core.convert.ConversionService";
    private static final String SERVICE_LOADING_EXCEPTION =
            "io.micronaut.core.io.service.SoftServiceLoader$ServiceLoadingException";
    private static final String WORKER_PREFIX = "ForkJoinPool.commonPool-worker-";
    private static final String FIRST = "fixture.First";
    private static final String SECOND = "fixture.Second";
    private static final String RUNTIME = "fixture.FailsAtRuntime";
    private static final String LINKAGE = "fixture.FailsToLink";

    @TempDir
    static Path fixtures;

    private static Path references;
    private static final Map<String, Path> CONVERSION_SERVICES = new LinkedHashMap<>();
    private static int counter;

    private final List<URLClassLoader> loaders = new ArrayList<>();
    private PrintStream originalErr;
    private ByteArrayOutputStream err;

    @BeforeAll
    static void compileFixtures() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        references = fixtures.resolve("references");
        Map<String, String> sources = new TreeMap<>();
        sources.put("fixture/Base.java", """
                package fixture;

                public abstract class Base implements io.micronaut.inject.BeanDefinitionReference<Object> {
                    public String getBeanDefinitionName() {
                        return getClass().getName();
                    }

                    public io.micronaut.inject.BeanDefinition<Object> load() {
                        throw new UnsupportedOperationException();
                    }

                    public boolean isPresent() {
                        return true;
                    }

                    public Class<Object> getBeanType() {
                        return Object.class;
                    }

                    public boolean isEnabled(io.micronaut.context.BeanContext context,
                            io.micronaut.context.BeanResolutionContext resolution) {
                        return true;
                    }
                }
                """);
        sources.put("fixture/First.java", reference("First", "initialised"));
        sources.put("fixture/Second.java", reference("Second", "initialised"));
        sources.put("fixture/FailsAtRuntime.java", reference("FailsAtRuntime", "failRuntime"));
        sources.put("fixture/FailsToLink.java", reference("FailsToLink", "failLinkage"));
        compile(compiler, fixtures.resolve("sources/references"), references, sources);
        for (String behaviour : List.of("initialised", "block", "failRuntime")) {
            Path target = fixtures.resolve("conversion-" + behaviour);
            compile(compiler, fixtures.resolve("sources/conversion-" + behaviour), target, Map.of(
                    "io/micronaut/core/convert/ConversionService.java", """
                            package io.micronaut.core.convert;

                            public interface ConversionService {
                                Object SHARED = io.micronaut.runner.generated.prefetch.PrefetchProbe.%s("%s");
                            }
                            """.formatted(behaviour, CONVERSION_SERVICE)));
            CONVERSION_SERVICES.put(behaviour, target);
        }
    }

    @BeforeEach
    void start() {
        DefinitionPrefetchConfigurer.reset();
        PrefetchProbe.reset();
        System.clearProperty(DefinitionPrefetchConfigurer.OPT_OUT_PROPERTY);
        System.clearProperty(DefinitionPrefetchConfigurer.STATIC_SERVICES_VERIFY_PROPERTY);
        System.clearProperty(DefinitionPrefetch.TRACE_PROPERTY);
        originalErr = System.err;
        err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stop() throws IOException {
        System.setErr(originalErr);
        // A fixture left blocked would hold a common pool thread for the rest of the test JVM.
        PrefetchProbe.release();
        DefinitionPrefetchConfigurer.reset();
        System.clearProperty(DefinitionPrefetchConfigurer.OPT_OUT_PROPERTY);
        System.clearProperty(DefinitionPrefetchConfigurer.STATIC_SERVICES_VERIFY_PROPERTY);
        System.clearProperty(DefinitionPrefetch.TRACE_PROPERTY);
        for (URLClassLoader loader : loaders) {
            loader.close();
        }
    }

    /**
     * What is copied into applications: the two classes and nothing else, compiled for Java 25 and free of
     * invokedynamic, which would make the first use of each of them load the lambda and string concatenation
     * machinery before {@code main}.
     */
    @Test
    void theJarHoldsTheTwoClassesCompiledForJava25WithoutInvokedynamic() throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (InputStream in = DefinitionPrefetchTest.class.getResourceAsStream(
                "/META-INF/micronaut-runner/prefetch.jar")) {
            assertNotNull(in, "runner-build carries no prefetch jar");
            ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory() && !entry.getName().equals("META-INF/MANIFEST.MF")) {
                    classes.put(entry.getName(), zip.readAllBytes());
                }
            }
        }

        assertEquals(List.of("io/micronaut/runner/generated/prefetch/DefinitionPrefetch.class",
                        "io/micronaut/runner/generated/prefetch/DefinitionPrefetchConfigurer.class"),
                List.copyOf(classes.keySet()));
        for (Map.Entry<String, byte[]> item : classes.entrySet()) {
            ClassModel model = ClassFile.of().parse(item.getValue());
            assertEquals(69, model.majorVersion(), item.getKey());
            assertEquals(0, model.minorVersion(), item.getKey());
            for (PoolEntry entry : model.constantPool()) {
                assertFalse(entry instanceof InvokeDynamicEntry, () -> item.getKey() + " uses invokedynamic");
            }
            assertTrue(model.findAttribute(Attributes.bootstrapMethods()).isEmpty(), item.getKey());
            assertTrue(model.findAttribute(Attributes.innerClasses()).isEmpty(),
                    () -> item.getKey() + " has a nested class");
            assertEquals(List.of(), ClassFile.of().verify(item.getValue()), item.getKey());
            try (InputStream compiled = DefinitionPrefetchTest.class.getResourceAsStream("/" + item.getKey())) {
                assertNotNull(compiled, item.getKey());
                assertArrayEquals(compiled.readAllBytes(), item.getValue(),
                        () -> item.getKey() + " in the jar is not the class these tests run");
            }
        }
    }

    @Test
    void handsOverWhatMicronautsOwnProviderReturnsOnce() throws Exception {
        URLClassLoader loader = loader(null, FIRST, SECOND);
        URLClassLoader other = loader(null, FIRST, SECOND);
        ApplicationContextBuilder builder = ApplicationContext.builder();
        BeanDefinitionsProvider original = provider(builder);
        assertInstanceOf(DefaultBeanDefinitionsProvider.class, original);

        DefinitionPrefetchConfigurer.start(loader);
        new DefinitionPrefetchConfigurer().configure(builder);

        DefinitionPrefetch task = assertInstanceOf(DefinitionPrefetch.class, provider(builder));
        assertSame(loader, task.loader);
        awaitDone(task);
        List<BeanDefinitionReference<?>> handedOver = task.provide(loader);

        List<BeanDefinitionReference<?>> expected = new DefaultBeanDefinitionsProvider().provide(loader);
        assertEquals(names(expected), names(handedOver));
        assertTrue(names(handedOver).containsAll(List.of(FIRST, SECOND)), names(handedOver)::toString);
        for (String fixture : List.of(FIRST, SECOND)) {
            List<PrefetchProbe.Event> initialised = PrefetchProbe.events(PrefetchProbe.INITIALISED, fixture);
            assertEquals(1, initialised.size(), () -> fixture + " was initialised " + initialised);
            assertTrue(initialised.get(0).threadName().startsWith(WORKER_PREFIX), initialised::toString);
            // Once by the prefetch and once by the provider the expectation came from.
            assertEquals(2, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, fixture).size());
            assertSame(loader, reference(handedOver, fixture).getClass().getClassLoader());
        }

        // A second call, as after a context refresh, goes to Micronaut's provider: new instances.
        List<BeanDefinitionReference<?>> again = task.provide(loader);
        assertEquals(names(expected), names(again));
        assertNotSame(reference(handedOver, FIRST), reference(again, FIRST));
        assertEquals(3, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, FIRST).size());

        // So does a call for another class loader, which gets that loader's references.
        List<BeanDefinitionReference<?>> elsewhere = task.provide(other);
        assertEquals(names(expected), names(elsewhere));
        assertSame(other, reference(elsewhere, FIRST).getClass().getClassLoader());
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    /**
     * A call for another class loader does not use the task up: the task's own class loader still gets the
     * prefetched references afterwards.
     */
    @Test
    void aCallForAnotherClassLoaderDoesNotClaimTheTask() throws Exception {
        URLClassLoader loader = loader(null, FIRST);
        URLClassLoader other = loader(null, FIRST);
        DefinitionPrefetch task = started(loader);
        assertEquals(1, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, FIRST).size());

        assertSame(other, reference(task.provide(other), FIRST).getClass().getClassLoader());
        assertEquals(2, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, FIRST).size());
        List<BeanDefinitionReference<?>> handedOver = task.provide(loader);

        assertSame(loader, reference(handedOver, FIRST).getClass().getClassLoader());
        // One instance from the prefetch and one for the other loader: the hand-over constructed nothing.
        assertEquals(2, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, FIRST).size());
    }

    @Test
    void tracesTheHandOverWhenAsked() throws Exception {
        System.setProperty(DefinitionPrefetch.TRACE_PROPERTY, "true");
        URLClassLoader loader = loader(null, FIRST, SECOND);
        DefinitionPrefetch task = started(loader);

        int count = task.provide(loader).size();
        new DefinitionPrefetchConfigurer().configure((ApplicationContext) null);

        assertEquals("[micronaut-runner] definition prefetch handed over " + count + " bean definition references"
                + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void installsNothingOnABuilderWhoseProviderIsNotMicronautsDefault() throws Exception {
        URLClassLoader loader = loader(null, FIRST);
        BeanDefinitionsProvider own = classLoader -> List.of();
        ApplicationContextBuilder builder = ApplicationContext.builder().beanDefinitionsProvider(own);

        DefinitionPrefetchConfigurer.start(loader);
        new DefinitionPrefetchConfigurer().configure(builder);

        assertSame(own, provider(builder));
    }

    @Test
    void doesNothingAtAllBeforeItWasStarted() {
        ApplicationContextBuilder builder = ApplicationContext.builder();
        BeanDefinitionsProvider original = provider(builder);
        DefinitionPrefetchConfigurer configurer = new DefinitionPrefetchConfigurer();

        configurer.configure(builder);
        configurer.configure((ApplicationContext) null);

        assertSame(original, provider(builder));
        assertEquals(Ordered.HIGHEST_PRECEDENCE, configurer.getOrder());
        assertEquals(List.of(), PrefetchProbe.events());
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    /**
     * A reference whose static initialiser throws stops the application: Micronaut's provider threw on the
     * task's thread, and the context gets that very exception, with the original at the bottom of it.
     */
    @Test
    void rethrowsTheOriginalFailureOfAReferenceThatStopsTheApplication() throws Exception {
        URLClassLoader loader = loader(null, FIRST, RUNTIME);
        DefinitionPrefetch task = started(loader);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> task.provide(loader));

        assertEquals(SERVICE_LOADING_EXCEPTION, thrown.getClass().getName());
        ExceptionInInitializerError initialiser =
                assertInstanceOf(ExceptionInInitializerError.class, thrown.getCause());
        assertNotNull(PrefetchProbe.thrown(RUNTIME));
        assertSame(PrefetchProbe.thrown(RUNTIME), initialiser.getCause());
        assertEquals(1, PrefetchProbe.events(PrefetchProbe.INITIALISED, RUNTIME).size());

        // What Micronaut does without the prefetch, on a class loader that has not met the fixture yet.
        URLClassLoader fresh = loader(null, FIRST, RUNTIME);
        RuntimeException expected = assertThrows(RuntimeException.class,
                () -> new DefaultBeanDefinitionsProvider().provide(fresh));
        assertEquals(expected.getClass(), thrown.getClass());
        assertEquals(expected.getMessage(), thrown.getMessage());
        assertEquals(expected.getCause().getClass(), thrown.getCause().getClass());
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    /**
     * A reference whose static initialiser throws {@code NoClassDefFoundError} is one Micronaut skips, and the
     * prefetch skips it in the same way, because it is Micronaut that did.
     */
    @Test
    void skipsAReferenceMicronautSkips() throws Exception {
        URLClassLoader loader = loader(null, FIRST, LINKAGE, SECOND);
        DefinitionPrefetch task = started(loader);

        List<String> handedOver = names(task.provide(loader));

        assertEquals(1, PrefetchProbe.events(PrefetchProbe.INITIALISED, LINKAGE).size());
        assertTrue(handedOver.containsAll(List.of(FIRST, SECOND)), handedOver::toString);
        assertFalse(handedOver.contains(LINKAGE), handedOver::toString);
        // What Micronaut does without the prefetch, on a class loader that has not met the fixture yet.
        URLClassLoader fresh = loader(null, FIRST, LINKAGE, SECOND);
        assertEquals(names(new DefaultBeanDefinitionsProvider().provide(fresh)), handedOver);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    /**
     * The task's first step, on its own thread: it initialises {@code ConversionService} before any reference,
     * with the application's class loader as that thread's context class loader, and puts the thread's own
     * loader back when it is done.
     */
    @Test
    void initialisesConversionServiceFirstUnderTheApplicationsClassLoader() throws Exception {
        URLClassLoader loader = loader("initialised", FIRST, SECOND);
        ApplicationContextBuilder builder = ApplicationContext.builder();
        DefinitionPrefetchConfigurer.start(loader);
        // The guard initialises ConversionService itself when it gets there first, so it waits here.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (PrefetchProbe.events(PrefetchProbe.INITIALISED, CONVERSION_SERVICE).isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the task never reached ConversionService");
            Thread.sleep(2);
        }
        new DefinitionPrefetchConfigurer().configure(builder);
        DefinitionPrefetch task = assertInstanceOf(DefinitionPrefetch.class, provider(builder));
        awaitDone(task);
        task.provide(loader);

        List<PrefetchProbe.Event> events = PrefetchProbe.events();
        PrefetchProbe.Event first = events.get(0);
        assertEquals(CONVERSION_SERVICE, first.name(), events::toString);
        assertEquals(1, PrefetchProbe.events(PrefetchProbe.INITIALISED, CONVERSION_SERVICE).size());
        assertTrue(first.threadName().startsWith(WORKER_PREFIX), first::toString);
        assertSame(loader, first.contextLoader());
        // Only this first step is certain to run on the task's thread. Micronaut forks one task per reference,
        // and the task's thread runs a reference initialiser only when it takes that fork back while it waits for
        // them; other pool threads run the rest under their own context class loader, as they do without the
        // prefetch. Whatever did run on the task's thread saw the application's loader too, but a run may have
        // none, so this checks the ConversionService step and nothing more is promised.
        List<PrefetchProbe.Event> onTheTaskThread = new ArrayList<>();
        for (PrefetchProbe.Event event : events) {
            if (event.thread() == first.thread()) {
                onTheTaskThread.add(event);
            }
        }
        assertSame(first, onTheTaskThread.get(0));
        for (PrefetchProbe.Event event : onTheTaskThread) {
            assertSame(loader, event.contextLoader(), event::toString);
        }
        assertNotSame(loader, first.thread().getContextClassLoader());
        assertSame(ClassLoader.getSystemClassLoader(), first.thread().getContextClassLoader(),
                "a common pool thread carries the system class loader, which the task has to put back");
    }

    /**
     * The guard: while the task is inside the initialiser of {@code ConversionService}, a thread that configures
     * a builder waits for it, however long it takes, and only then installs the task.
     */
    @Test
    void theConfigurerWaitsForTheTaskToInitialiseConversionService() throws Exception {
        URLClassLoader loader = loader("block", FIRST);
        ApplicationContextBuilder builder = ApplicationContext.builder();
        BeanDefinitionsProvider original = provider(builder);
        DefinitionPrefetchConfigurer.start(loader);
        assertTrue(PrefetchProbe.awaitEntered(), "the task never reached ConversionService");

        Thread configuring = new Thread(() -> new DefinitionPrefetchConfigurer().configure(builder),
                "configures-the-builder");
        configuring.start();
        configuring.join(500);

        assertTrue(configuring.isAlive(), "the guard did not wait for the task");
        assertSame(original, provider(builder));
        assertEquals(List.of(), PrefetchProbe.events(PrefetchProbe.INITIALISED, FIRST),
                "a reference was initialised before ConversionService was");

        PrefetchProbe.release();
        configuring.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(configuring.isAlive(), "the guard never returned");
        DefinitionPrefetch task = assertInstanceOf(DefinitionPrefetch.class, provider(builder));
        awaitDone(task);
        assertTrue(names(task.provide(loader)).contains(FIRST));
        assertEquals(1, PrefetchProbe.events(PrefetchProbe.INITIALISED, CONVERSION_SERVICE).size());
    }

    /**
     * When {@code ConversionService} cannot be initialised, the task loads no reference and the guard fails on
     * the thread that builds the context, as the context itself would have.
     */
    @Test
    void aConversionServiceThatCannotBeInitialisedFailsTheGuardAndLoadsNothing() throws Exception {
        URLClassLoader loader = loader("failRuntime", FIRST);
        ApplicationContextBuilder builder = ApplicationContext.builder();
        BeanDefinitionsProvider original = provider(builder);
        DefinitionPrefetchConfigurer.start(loader);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (PrefetchProbe.thrown(CONVERSION_SERVICE) == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertNotNull(PrefetchProbe.thrown(CONVERSION_SERVICE), "the task never reached ConversionService");

        LinkageError thrown = assertThrows(LinkageError.class,
                () -> new DefinitionPrefetchConfigurer().configure(builder));

        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(String.valueOf(root.getMessage()).contains("probe failure of " + CONVERSION_SERVICE)
                || root == PrefetchProbe.thrown(CONVERSION_SERVICE), () -> "unexpected root cause: " + thrown);
        assertSame(original, provider(builder));
        assertEquals(List.of(), PrefetchProbe.events(PrefetchProbe.INITIALISED, FIRST));
    }

    /**
     * A context that did not take the task's result loaded the references itself, and a reference that already
     * failed on the task's thread reached it as one Micronaut skips. That is said once.
     */
    @Test
    void reportsAnUnclaimedFailedTaskExactlyOnce() throws Exception {
        URLClassLoader loader = loader(null, FIRST, RUNTIME);
        awaitDone(started(loader));
        DefinitionPrefetchConfigurer configurer = new DefinitionPrefetchConfigurer();

        configurer.configure((ApplicationContext) null);
        configurer.configure((ApplicationContext) null);

        List<String> lines = err.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, lines.size(), lines::toString);
        assertTrue(lines.get(0).startsWith("[micronaut-runner] The bean definition prefetch was not handed to the"
                + " application context, and it recorded a failure that Micronaut may have ignored: "), lines.get(0));
        assertTrue(lines.get(0).contains(SERVICE_LOADING_EXCEPTION), lines.get(0));
        assertTrue(lines.get(0).contains("(root cause: " + PrefetchProbe.thrown(RUNTIME) + ")"), lines.get(0));
        assertTrue(lines.get(0).endsWith("Start with -Dmicronaut.runner.prefetch=false to see Micronaut's own"
                + " handling."), lines.get(0));
    }

    /**
     * A finished task whose result the context did not take gives it up when that context reports, instead of
     * keeping the references for the life of the JVM; a later call for its class loader goes to Micronaut's
     * provider.
     */
    @Test
    void anUnclaimedTaskGivesItsResultUpWhenTheContextReports() throws Exception {
        URLClassLoader loader = loader(null, FIRST);
        DefinitionPrefetch task = started(loader);
        assertNotNull(held(task, "result"));
        assertEquals(1, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, FIRST).size());

        new DefinitionPrefetchConfigurer().configure((ApplicationContext) null);

        assertNull(held(task, "result"), "the unclaimed task still keeps the references");
        assertTrue(names(task.provide(loader)).contains(FIRST));
        // Micronaut's provider constructed the reference again: nothing was handed over.
        assertEquals(2, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, FIRST).size());
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    /** A failed task that was reported keeps neither its result nor its failure. */
    @Test
    void aReportedTaskKeepsNoFailure() throws Exception {
        URLClassLoader loader = loader(null, FIRST, RUNTIME);
        DefinitionPrefetch task = started(loader);
        assertNotNull(held(task, "failure"));

        new DefinitionPrefetchConfigurer().configure((ApplicationContext) null);

        assertNull(held(task, "failure"));
        assertNull(held(task, "result"));
        assertEquals(1, err.toString(StandardCharsets.UTF_8).lines().count());
    }

    /**
     * A report never waits for the task, and it leaves one that is still running alone: a later context can
     * still take its result.
     */
    @Test
    void aReportLeavesARunningTaskForALaterContext() throws Exception {
        URLClassLoader loader = loader("block", FIRST);
        DefinitionPrefetchConfigurer configurer = new DefinitionPrefetchConfigurer();
        DefinitionPrefetchConfigurer.start(loader);
        assertTrue(PrefetchProbe.awaitEntered(), "the task never reached ConversionService");

        configurer.configure((ApplicationContext) null);

        PrefetchProbe.release();
        ApplicationContextBuilder builder = ApplicationContext.builder();
        configurer.configure(builder);
        DefinitionPrefetch task = assertInstanceOf(DefinitionPrefetch.class, provider(builder));
        awaitDone(task);
        assertTrue(names(task.provide(loader)).contains(FIRST));
        // The prefetch constructed it once, and the hand-over constructed nothing.
        assertEquals(1, PrefetchProbe.events(PrefetchProbe.CONSTRUCTED, FIRST).size());
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsNothingForAClaimedOrASuccessfulTask() throws Exception {
        DefinitionPrefetchConfigurer configurer = new DefinitionPrefetchConfigurer();

        URLClassLoader failing = loader(null, FIRST, RUNTIME);
        DefinitionPrefetch claimed = started(failing);
        assertThrows(RuntimeException.class, () -> claimed.provide(failing));
        configurer.configure((ApplicationContext) null);

        DefinitionPrefetchConfigurer.reset();
        awaitDone(started(loader(null, FIRST)));
        configurer.configure((ApplicationContext) null);

        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void tracesATaskThatWasNotHandedOverOnce() throws Exception {
        System.setProperty(DefinitionPrefetch.TRACE_PROPERTY, "true");
        awaitDone(started(loader(null, FIRST)));
        DefinitionPrefetchConfigurer configurer = new DefinitionPrefetchConfigurer();

        configurer.configure((ApplicationContext) null);
        configurer.configure((ApplicationContext) null);

        assertEquals("[micronaut-runner] definition prefetch not handed over" + System.lineSeparator(),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theOptOutPropertyStartsNothing() {
        System.setProperty(DefinitionPrefetchConfigurer.OPT_OUT_PROPERTY, "false");
        ApplicationContextBuilder builder = ApplicationContext.builder();
        BeanDefinitionsProvider original = provider(builder);

        DefinitionPrefetchConfigurer.start();
        new DefinitionPrefetchConfigurer().configure(builder);

        assertSame(original, provider(builder));
        assertEquals("micronaut.runner.prefetch", DefinitionPrefetchConfigurer.OPT_OUT_PROPERTY);
        assertEquals("micronaut.runner.prefetch.trace", DefinitionPrefetch.TRACE_PROPERTY);
    }

    /**
     * The static service table's check runs on its first lookup, which with the prefetch would be on a pool thread;
     * the prefetch stands down so that it stays on the main thread.
     */
    @Test
    void verifyingTheStaticServiceTableStartsNothing() {
        System.setProperty(DefinitionPrefetchConfigurer.STATIC_SERVICES_VERIFY_PROPERTY, "true");
        ApplicationContextBuilder builder = ApplicationContext.builder();
        BeanDefinitionsProvider original = provider(builder);

        DefinitionPrefetchConfigurer.start();
        new DefinitionPrefetchConfigurer().configure(builder);

        assertSame(original, provider(builder));
        assertEquals("micronaut.runner.static-services.verify",
                DefinitionPrefetchConfigurer.STATIC_SERVICES_VERIFY_PROPERTY);
    }

    /**
     * What the entry stub calls: with nothing turned off and a common pool that has at least three threads, the
     * prefetch starts for the class loader that defined the configurer.
     */
    @Test
    void startsForItsOwnClassLoaderWhenNothingTurnsItOff() throws Exception {
        Assumptions.assumeTrue(
                ForkJoinPool.getCommonPoolParallelism() >= DefinitionPrefetchConfigurer.MINIMUM_PARALLELISM,
                "the prefetch stands down when the common pool has fewer than three threads");
        assertEquals(3, DefinitionPrefetchConfigurer.MINIMUM_PARALLELISM);
        ApplicationContextBuilder builder = ApplicationContext.builder();

        DefinitionPrefetchConfigurer.start();
        new DefinitionPrefetchConfigurer().configure(builder);

        DefinitionPrefetch task = assertInstanceOf(DefinitionPrefetch.class, provider(builder));
        ClassLoader own = DefinitionPrefetchConfigurer.class.getClassLoader();
        assertSame(own, task.loader);
        awaitDone(task);
        assertEquals(names(new DefaultBeanDefinitionsProvider().provide(own)), names(task.provide(own)));
    }

    private DefinitionPrefetch started(ClassLoader loader) throws InterruptedException {
        ApplicationContextBuilder builder = ApplicationContext.builder();
        DefinitionPrefetchConfigurer.start(loader);
        new DefinitionPrefetchConfigurer().configure(builder);
        DefinitionPrefetch task = assertInstanceOf(DefinitionPrefetch.class, provider(builder));
        awaitDone(task);
        return task;
    }

    /**
     * Waits for a task without joining it: a join from this thread could run the task here instead of on the
     * common pool, and the tests are about what happens there.
     */
    private static void awaitDone(ForkJoinTask<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!task.isDone()) {
            assertTrue(System.nanoTime() < deadline, "the prefetch did not finish");
            Thread.sleep(2);
        }
    }

    /** What the task still holds in one of its private fields, read without going through its API. */
    private static Object held(DefinitionPrefetch task, String field) throws ReflectiveOperationException {
        Field declared = DefinitionPrefetch.class.getDeclaredField(field);
        declared.setAccessible(true);
        return declared.get(task);
    }

    private static BeanDefinitionsProvider provider(ApplicationContextBuilder builder) {
        return ((BeanContextConfiguration) builder).getBeanDefinitionsProvider();
    }

    private static List<String> names(List<BeanDefinitionReference<?>> references) {
        List<String> names = new ArrayList<>();
        for (BeanDefinitionReference<?> reference : references) {
            names.add(reference.getClass().getName());
        }
        return names;
    }

    private static BeanDefinitionReference<?> reference(List<BeanDefinitionReference<?>> references, String name) {
        for (BeanDefinitionReference<?> reference : references) {
            if (reference.getClass().getName().equals(name)) {
                return reference;
            }
        }
        throw new AssertionError(name + " is not among " + names(references));
    }

    /**
     * A class loader for one test: the fixture references, the entries that register the chosen ones, and the
     * stand-in for {@code ConversionService} when the test wants one.
     */
    private URLClassLoader loader(String conversionService, String... registered) throws IOException {
        counter++;
        Path services = fixtures.resolve("services-" + counter);
        Path directory = services.resolve(REFERENCES);
        Files.createDirectories(directory);
        for (String name : registered) {
            Files.createFile(directory.resolve(name));
        }
        List<URL> urls = new ArrayList<>(List.of(references.toUri().toURL(), services.toUri().toURL()));
        if (conversionService != null) {
            urls.add(CONVERSION_SERVICES.get(conversionService).toUri().toURL());
        }
        URLClassLoader loader = new FixtureLoader(urls.toArray(new URL[0]), conversionService != null);
        loaders.add(loader);
        return loader;
    }

    private static String reference(String simpleName, String behaviour) {
        return """
                package fixture;

                public class %1$s extends Base {
                    static {
                        io.micronaut.runner.generated.prefetch.PrefetchProbe.%2$s("fixture.%1$s");
                    }

                    public %1$s() {
                        io.micronaut.runner.generated.prefetch.PrefetchProbe.constructed("fixture.%1$s");
                    }
                }
                """.formatted(simpleName, behaviour);
    }

    private static void compile(JavaCompiler compiler, Path sources, Path target, Map<String, String> files)
            throws IOException {
        Files.createDirectories(target);
        String classPath = String.join(File.pathSeparator, System.getProperty("java.class.path"),
                location(BeanDefinitionReference.class), location(Ordered.class), location(PrefetchProbe.class));
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-proc:none", "-classpath", classPath,
                "-d", target.toString()));
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture sources under " + sources);
        }
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Defines the fixtures, and the stand-in for {@code ConversionService} before its parent gets to answer with
     * the real one.
     */
    private static final class FixtureLoader extends URLClassLoader {

        private final boolean ownConversionService;

        private FixtureLoader(URL[] urls, boolean ownConversionService) {
            super(urls, DefinitionPrefetchTest.class.getClassLoader());
            this.ownConversionService = ownConversionService;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ownConversionService && CONVERSION_SERVICE.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
