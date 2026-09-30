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

import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.inject.BeanDefinitionReference;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs Micronaut's own bean definition loading on the common pool, before the application's {@code main}, and
 * hands its result to the first application context.
 *
 * <p>runner-build copies this class into a runner jar's application layer byte for byte. The generated entry
 * stub starts it through {@link DefinitionPrefetchConfigurer#start()}, so it overlaps with everything the main
 * thread does before it builds a context: the application's static initialisers, logging configuration and the
 * context builder. {@link DefinitionPrefetchConfigurer} then installs it as the builder's
 * {@link BeanDefinitionsProvider}.</p>
 *
 * <h2>Error parity</h2>
 * <p>The task never loads or initialises a bean definition reference itself. It calls
 * {@link DefaultBeanDefinitionsProvider#provide(ClassLoader)}, which is what the context would have called, and
 * the first {@link #provide(ClassLoader)} for the same class loader returns exactly what that call returned or
 * rethrows exactly what it threw. Which failures of a reference Micronaut ignores and which ones stop the
 * application is therefore decided by Micronaut, on the thread that saw the original failure.</p>
 *
 * <p>It uses no lambdas, no method references and no nested classes, and only Micronaut API that micronaut-inject
 * has had since 5.0.</p>
 */
@SuppressWarnings("serial")
public final class DefinitionPrefetch extends RecursiveAction implements BeanDefinitionsProvider {

    /** The class whose initialisation the task starts with and the configurer waits for. */
    static final String CONVERSION_SERVICE = "io.micronaut.core.convert.ConversionService";

    /** Set to {@code true} to print whether the context took the result of the task. */
    static final String TRACE_PROPERTY = "micronaut.runner.prefetch.trace";

    /** What every line this class prints starts with. */
    private static final String PREFIX = "[micronaut-runner] ";

    /** The class loader the references are loaded with: the one that defined this class. */
    final ClassLoader loader;

    private final BeanDefinitionsProvider fallback = new DefaultBeanDefinitionsProvider();
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicBoolean traced = new AtomicBoolean();

    /** What Micronaut's provider returned. Written by the task, read only once it is done. */
    private List<BeanDefinitionReference<?>> result;

    /** What the task threw, exactly as it was thrown. Written by the task, read only once it is done. */
    private Throwable failure;

    private DefinitionPrefetch(ClassLoader loader) {
        this.loader = loader;
    }

    /**
     * Starts a task on the common pool and returns without waiting for it.
     *
     * @param loader the class loader of the application
     * @return the running task
     */
    static DefinitionPrefetch launch(ClassLoader loader) {
        DefinitionPrefetch task = new DefinitionPrefetch(loader);
        ForkJoinPool.commonPool().execute(task);
        return task;
    }

    /**
     * Builds {@code ConversionService.SHARED} and then runs Micronaut's provider, with the application's class
     * loader as the thread's context class loader: {@code StaticOptimizations} looks its loaders up there, and a
     * common pool thread otherwise carries the system class loader.
     */
    @Override
    protected void compute() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            // First, because the main thread needs it before it reads the definitions, and because it is the
            // one class initialisation the main thread must not overlap: see the configurer.
            Class.forName(CONVERSION_SERVICE, true, loader);
            result = fallback.provide(loader);
        } catch (Throwable t) {
            // The original, never wrapped: provide() rethrows it on the thread that builds the context.
            failure = t;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Hands over what the task loaded, once, to the first caller that asks with the task's class loader, waiting
     * for the task when it is still running. Every other call, such as a context refresh, a later context or
     * another class loader, goes to Micronaut's default provider.
     *
     * @param classLoader the class loader to load the references with
     * @return the bean definition references
     */
    @Override
    public List<BeanDefinitionReference<?>> provide(ClassLoader classLoader) {
        if (classLoader != loader || !claimed.compareAndSet(false, true)) {
            return fallback.provide(classLoader);
        }
        // compute() catches everything, so this returns normally whatever the task met.
        join();
        List<BeanDefinitionReference<?>> list = result;
        result = null;
        if (failure != null) {
            throw DefinitionPrefetch.<RuntimeException>sneaky(failure);
        }
        if ("true".equals(System.getProperty(TRACE_PROPERTY))) {
            System.err.println(PREFIX + "definition prefetch handed over " + list.size()
                    + " bean definition references");
        }
        return list;
    }

    /**
     * Says so, once, when the context did not take the result of a task that failed: Micronaut then loaded the
     * references itself, and a reference whose static initialiser already failed on the task's thread reaches it
     * as a class that cannot be initialised, which it skips. Never waits for the task.
     */
    void reportIfUnclaimed() {
        if (claimed.get()) {
            return;
        }
        if (isDone() && failure != null && reported.compareAndSet(false, true)) {
            // Micronaut wraps what a reference threw, and its own message does not always name it.
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.err.println(PREFIX + "The bean definition prefetch was not handed to the application context,"
                    + " and it recorded a failure that Micronaut may have ignored: " + failure
                    + (root == failure ? "" : " (root cause: " + root + ")") + ". Start with -D"
                    + DefinitionPrefetchConfigurer.OPT_OUT_PROPERTY + "=false to see Micronaut's own handling.");
        }
        if ("true".equals(System.getProperty(TRACE_PROPERTY)) && traced.compareAndSet(false, true)) {
            System.err.println(PREFIX + "definition prefetch not handed over");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
