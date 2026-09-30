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
package io.micronaut.runner.generated.services;

import io.micronaut.core.io.service.SoftServiceLoader;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The implementations of one service type, in the order Micronaut's scan finds them.
 *
 * <p>Only discovery is precomputed. Each implementation is instantiated the way the API that asks for it
 * instantiates it after a scan:</p>
 * <ul>
 *     <li>a type read only from {@code META-INF/micronaut} ({@link #MICRONAUT_ONLY}) repeats
 *     {@code MicronautMetaServiceLoaderUtils.instantiate}: a public constructor through a method handle,
 *     where a class that cannot be loaded or has no such constructor is skipped;</li>
 *     <li>any other type goes through {@code SoftServiceLoader.StaticDefinition}, Micronaut's own method
 *     handle then reflection, and skips what the scanning {@code collectAll} skips.</li>
 * </ul>
 *
 * <p>A {@link #PARALLEL} type keeps the fork-join fan-out of the scan, which is what keeps hundreds of bean
 * definition static initialisers off the calling thread; see {@link RunnerParallelLoad}.</p>
 *
 * @since 1.0
 */
final class RunnerServiceLoader implements SoftServiceLoader.StaticServiceLoader<Object> {

    /** Flag of a type whose implementations are instantiated on the fork-join common pool. */
    static final int PARALLEL = 1;

    /** Flag of a type Micronaut reads only from {@code META-INF/micronaut}. */
    static final int MICRONAUT_ONLY = 2;

    /** Flag of a type the table does not serve: Micronaut scans for it. */
    static final int DYNAMIC = 4;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();
    private static final MethodType VOID_TYPE = MethodType.methodType(void.class);

    private final String type;
    private final String[] names;
    private final int first;
    private final int count;
    private final int flags;
    private final ClassLoader loader;

    /**
     * @param type   the service type
     * @param names  every slot of the table
     * @param first  the type's first slot
     * @param count  the number of its slots
     * @param flags  {@link #PARALLEL}, {@link #MICRONAUT_ONLY} and {@link #DYNAMIC}
     * @param loader the class loader that defines the implementations
     */
    RunnerServiceLoader(String type, String[] names, int first, int count, int flags, ClassLoader loader) {
        this.type = type;
        this.names = names;
        this.first = first;
        this.count = count;
        this.flags = flags;
        this.loader = loader;
    }

    /**
     * @return the service type
     */
    String type() {
        return type;
    }

    /**
     * @return the number of implementations the table lists
     */
    int size() {
        return count;
    }

    /**
     * @return whether the table leaves the type to Micronaut's scan
     */
    boolean dynamic() {
        return (flags & DYNAMIC) != 0;
    }

    /**
     * @return whether Micronaut reads the type only from {@code META-INF/micronaut}
     */
    boolean micronautOnly() {
        return (flags & MICRONAUT_ONLY) != 0;
    }

    /**
     * @return the implementation names, in table order
     */
    List<String> names() {
        ArrayList<String> list = new ArrayList<>(count);
        for (int slot = first; slot < first + count; slot++) {
            list.add(names[slot]);
        }
        return list;
    }

    @Override
    public Stream<SoftServiceLoader.StaticDefinition<Object>> findAll(Predicate<String> predicate) {
        ArrayList<SoftServiceLoader.StaticDefinition<Object>> definitions = new ArrayList<>(count);
        for (int slot = first; slot < first + count; slot++) {
            String name = names[slot];
            if (predicate == null || predicate.test(name)) {
                definitions.add(SoftServiceLoader.StaticDefinition.of(name,
                        new RunnerServiceSupplier(name, loader)));
            }
        }
        return definitions.stream();
    }

    @Override
    public List<Object> load(Predicate<Object> predicate) {
        return load(null, predicate);
    }

    @Override
    public List<Object> load(Predicate<String> condition, Predicate<Object> predicate) {
        ArrayList<Object> values = new ArrayList<>(count);
        if ((flags & PARALLEL) != 0 && count > 1 && ForkJoinPool.getCommonPoolParallelism() > 1) {
            RunnerParallelLoad.load(this, names, first, count, condition, predicate, values);
            return values;
        }
        for (int slot = first; slot < first + count; slot++) {
            String name = names[slot];
            if (condition != null && !condition.test(name)) {
                continue;
            }
            Object value = instantiate(name);
            if (value != null && (predicate == null || predicate.test(value))) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Creates one implementation, or skips it exactly where the scanning path would.
     *
     * @param name the binary name of the implementation
     * @return the instance, or {@code null} when the scanning path would have ignored the class
     */
    @SuppressWarnings("unchecked")
    Object instantiate(String name) {
        if ((flags & MICRONAUT_ONLY) != 0) {
            try {
                Class<?> implementation = Class.forName(name, false, loader);
                return LOOKUP.findConstructor(implementation, VOID_TYPE).invoke();
            } catch (NoClassDefFoundError | ClassNotFoundException | NoSuchMethodException
                     | IllegalAccessException | IllegalAccessError e) {
                return null;
            } catch (Throwable e) {
                // Unchanged, as Micronaut's own sneaky throw leaves it.
                throw RunnerServiceLoader.<RuntimeException>unchecked(e);
            }
        }
        Class<Object> implementation;
        try {
            implementation = (Class<Object>) Class.forName(name, false, loader);
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            return null;
        }
        try {
            return SoftServiceLoader.StaticDefinition.of(name, implementation).load();
        } catch (RuntimeException e) {
            // Micronaut's package-private ServiceLoadingException around what the constructor threw.
            Throwable cause = e.getCause();
            if (cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException
                    || cause instanceof NoSuchMethodException || cause instanceof IllegalAccessException) {
                return null;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T unchecked(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
