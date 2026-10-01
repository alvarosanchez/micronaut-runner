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
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.BeanContextConfiguration;
import io.micronaut.context.DefaultBeanDefinitionsProvider;

import java.util.concurrent.ForkJoinPool;

/**
 * Starts the {@link DefinitionPrefetch} for the generated entry stub, and hands it to every application context
 * builder as an {@link ApplicationContextConfigurer} service.
 *
 * <p>runner-build copies this class into a runner jar's application layer byte for byte and registers it in
 * {@code META-INF/services/io.micronaut.context.ApplicationContextConfigurer}. Until {@link #start()} has started
 * a task it does nothing: that is the case in the extracted layout, which has no entry stub, with
 * {@code -Dmicronaut.runner.prefetch=false} or {@code -Dmicronaut.runner.static-services.verify=true}, and when
 * the common pool has fewer than three threads.</p>
 *
 * <p>This class names {@link DefinitionPrefetch} only as the type of its own field, as the result of
 * {@link DefinitionPrefetch#launch(ClassLoader)}, as a receiver and where an interface is expected. Verifying this
 * class therefore never loads that one, and nothing else loads it while the prefetch is off. Keep it that way:
 * passing the task where a class such as {@code ForkJoinTask} is expected would make the verifier load it.</p>
 */
public final class DefinitionPrefetchConfigurer implements ApplicationContextConfigurer {

    /** Set to {@code false} on the command line to start without the prefetch. */
    static final String OPT_OUT_PROPERTY = "micronaut.runner.prefetch";

    /**
     * The static service table's check against Micronaut's scan, which runs on the first lookup the table answers.
     * With the prefetch that lookup would usually be on a pool thread, inside {@code ConversionService}'s
     * initialiser, and a mismatch would reach {@code main} only as a {@code NoClassDefFoundError}. Without the
     * prefetch it stays on the main thread, whose {@code IllegalStateException} names the differences.
     */
    static final String STATIC_SERVICES_VERIFY_PROPERTY = "micronaut.runner.static-services.verify";

    /**
     * The fewest common pool threads the prefetch starts with, which is what a JVM with four processors has.
     *
     * <p>Micronaut loads the bean definitions on the pool's threads and on the thread that asked for them. With
     * the prefetch the thread that asks is a pool thread, and the main thread only waits for it, so one thread
     * fewer does that work. On the benchmark sample that costs more than the prefetch gains when the pool has two
     * threads, where a start took about 50 ms longer, and less when it has three or more.</p>
     */
    static final int MINIMUM_PARALLELISM = 3;

    /** The running or finished task, or {@code null} while none was started. */
    private static volatile DefinitionPrefetch prefetch;

    /**
     * Creates the configurer. Micronaut's service loader calls this.
     */
    public DefinitionPrefetchConfigurer() {
    }

    /**
     * Starts the prefetch unless it was turned off, the static service table is to be verified, or the common pool
     * has fewer than {@value #MINIMUM_PARALLELISM} threads. Only the generated entry stub calls this, before the
     * application's {@code main}.
     */
    public static void start() {
        // The switches are read first, so that they do not create the common pool either.
        if ("false".equals(System.getProperty(OPT_OUT_PROPERTY))
                || "true".equals(System.getProperty(STATIC_SERVICES_VERIFY_PROPERTY))
                || ForkJoinPool.getCommonPoolParallelism() < MINIMUM_PARALLELISM) {
            return;
        }
        start(DefinitionPrefetchConfigurer.class.getClassLoader());
    }

    /**
     * Starts the prefetch for one class loader, whatever the switches say.
     *
     * @param loader the class loader of the application
     */
    static void start(ClassLoader loader) {
        prefetch = DefinitionPrefetch.launch(loader);
    }

    /**
     * Forgets the task, for tests.
     */
    static void reset() {
        prefetch = null;
    }

    @Override
    public int getOrder() {
        // Before every other configurer, while the builder still has the provider it was created with.
        return HIGHEST_PRECEDENCE;
    }

    /**
     * Makes sure {@code ConversionService} is initialised, and then installs the task as the builder's bean
     * definitions provider, unless the builder already has one that is not Micronaut's default.
     *
     * <p>The first step is a guard against a class initialisation deadlock, and it must not be bounded. A context
     * constructs a {@code DefaultMutableConversionService} on the thread that builds it, which initialises that
     * class and then its superinterface {@code ConversionService}; the task initialises {@code ConversionService}
     * first, whose initialiser constructs a {@code DefaultMutableConversionService}. Started at the same moment,
     * each thread would wait for the class the other one is initialising. Here the building thread holds no
     * initialisation lock yet, so it initialises {@code ConversionService} itself or waits until the task has,
     * and the context then finds both classes initialised.</p>
     *
     * @param builder the builder, inside its constructor
     */
    @Override
    public void configure(ApplicationContextBuilder builder) {
        DefinitionPrefetch task = prefetch;
        if (task == null) {
            return;
        }
        try {
            Class.forName(DefinitionPrefetch.CONVERSION_SERVICE, true, task.loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The bean definition prefetch was packaged for an application without "
                    + DefinitionPrefetch.CONVERSION_SERVICE + "; start with -D" + OPT_OUT_PROPERTY + "=false", e);
        }
        if (builder instanceof BeanContextConfiguration configuration
                && configuration.getBeanDefinitionsProvider() instanceof DefaultBeanDefinitionsProvider) {
            builder.beanDefinitionsProvider(task);
        }
    }

    /**
     * Makes a finished task whose result the context did not take give that result up, and reports the task if
     * it failed.
     *
     * @param context the context, after it read its bean definitions
     */
    @Override
    public void configure(ApplicationContext context) {
        DefinitionPrefetch task = prefetch;
        if (task != null) {
            task.reportIfUnclaimed();
        }
    }
}
