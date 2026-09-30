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

import java.util.List;
import java.util.concurrent.RecursiveAction;
import java.util.function.Predicate;

/**
 * One task of {@link RunnerParallelLoad}: creates one implementation and tests it on a pool worker, and keeps
 * what happened until the thread that started the load collects it.
 *
 * @since 1.0
 */
final class RunnerSlotLoad extends RecursiveAction {

    private static final long serialVersionUID = 1L;

    private final transient RunnerServiceLoader loader;
    private final String name;
    private final transient Predicate<Object> predicate;
    private transient Object result;
    private transient Throwable failure;

    /**
     * @param loader    the loader of the service type, which instantiates the name
     * @param name      the binary name of the implementation
     * @param predicate tested on the instance, or {@code null}
     */
    RunnerSlotLoad(RunnerServiceLoader loader, String name, Predicate<Object> predicate) {
        this.loader = loader;
        this.name = name;
        this.predicate = predicate;
    }

    @Override
    protected void compute() {
        try {
            Object value = loader.instantiate(name);
            if (value != null && predicate != null && !predicate.test(value)) {
                value = null;
            }
            result = value;
        } catch (Throwable e) {
            failure = e;
        }
    }

    /**
     * Appends the instance, if there is one. It is called after {@code join()}, which makes what the worker
     * wrote visible to the caller.
     *
     * @param values where the instance goes
     * @throws RuntimeException if the implementation could not be created. The message is the one Micronaut's
     *                          collector gives; the type is not, because Micronaut's is package-private.
     */
    void collect(List<Object> values) {
        if (failure != null) {
            throw new RuntimeException("Failed to load a service: " + failure.getMessage(), failure);
        }
        if (result != null) {
            values.add(result);
        }
    }
}
