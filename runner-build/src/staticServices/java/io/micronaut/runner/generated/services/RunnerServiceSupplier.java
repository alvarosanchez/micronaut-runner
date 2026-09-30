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

import java.util.ServiceConfigurationError;
import java.util.function.Supplier;

/**
 * Creates the implementation behind one {@code ServiceDefinition} that {@code SoftServiceLoader.iterator()}
 * hands out, as the definition of the scanning path does: through the declared no-argument constructor, with
 * any failure reported as a {@link ServiceConfigurationError} that carries the same message.
 *
 * @since 1.0
 */
final class RunnerServiceSupplier implements Supplier<Object> {

    private final String name;
    private final ClassLoader loader;

    /**
     * @param name   the binary name of the implementation
     * @param loader the class loader that defines it
     */
    RunnerServiceSupplier(String name, ClassLoader loader) {
        this.name = name;
        this.loader = loader;
    }

    @Override
    public Object get() {
        Class<?> implementation;
        try {
            implementation = Class.forName(name, false, loader);
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            throw new ServiceConfigurationError("Call to load() when class '" + name + "' is not present");
        }
        try {
            return implementation.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            throw new ServiceConfigurationError("Error loading service [" + name + "]: " + e.getMessage(), e);
        }
    }
}
