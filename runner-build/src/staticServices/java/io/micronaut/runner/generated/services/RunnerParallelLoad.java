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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.function.Predicate;

/**
 * Instantiates the implementations of one service type on the fork-join common pool, as Micronaut's own
 * collector does after a scan: one task per name, forked from inside the pool and joined in table order, so
 * the result keeps that order whichever worker finishes first.
 *
 * <p>Loading the bean definitions one after the other instead would run every one of their static
 * initialisers on the thread that starts the application.</p>
 *
 * @since 1.0
 */
final class RunnerParallelLoad extends RecursiveAction {

    private static final long serialVersionUID = 1L;

    private final transient ArrayList<RunnerSlotLoad> tasks;

    private RunnerParallelLoad(ArrayList<RunnerSlotLoad> tasks) {
        this.tasks = tasks;
    }

    /**
     * Instantiates the names the condition accepts and appends the instances the predicate accepts.
     *
     * @param loader    the type's loader, which instantiates a name
     * @param names     every slot of the table
     * @param first     the type's first slot
     * @param count     the number of its slots
     * @param condition tested on each name first, or {@code null}
     * @param predicate tested on each instance, on the worker that created it, or {@code null}
     * @param values    where the instances go, in table order
     * @throws RuntimeException if an implementation could not be created, with Micronaut's message
     */
    static void load(RunnerServiceLoader loader, String[] names, int first, int count,
                     Predicate<String> condition, Predicate<Object> predicate, List<Object> values) {
        ArrayList<RunnerSlotLoad> tasks = new ArrayList<>(count);
        for (int slot = first; slot < first + count; slot++) {
            String name = names[slot];
            if (condition == null || condition.test(name)) {
                tasks.add(new RunnerSlotLoad(loader, name, predicate));
            }
        }
        ForkJoinPool.commonPool().invoke(new RunnerParallelLoad(tasks));
        int size = tasks.size();
        for (int i = 0; i < size; i++) {
            RunnerSlotLoad task = tasks.get(i);
            task.join();
            task.collect(values);
        }
    }

    @Override
    protected void compute() {
        int size = tasks.size();
        for (int i = 0; i < size; i++) {
            tasks.get(i).fork();
        }
    }
}
