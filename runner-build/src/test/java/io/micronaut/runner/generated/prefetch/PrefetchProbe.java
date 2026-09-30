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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What the fixture classes of {@link DefinitionPrefetchTest} report to. The fixtures are compiled by the test and
 * defined by a class loader of their own, so they can only tell the test what happened through a class of the
 * test's class loader: this one.
 */
public final class PrefetchProbe {

    /** A static initialiser that ran. */
    public static final String INITIALISED = "initialised";

    /** A constructor that ran. */
    public static final String CONSTRUCTED = "constructed";

    private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();
    private static final Map<String, Throwable> THROWN = new ConcurrentHashMap<>();
    private static volatile CountDownLatch entered = new CountDownLatch(1);
    private static volatile CountDownLatch released = new CountDownLatch(1);

    private PrefetchProbe() {
    }

    /**
     * One thing a fixture did.
     *
     * @param kind          {@link #INITIALISED} or {@link #CONSTRUCTED}
     * @param name          the fixture's own name for itself
     * @param thread        the thread it happened on
     * @param threadName    that thread's name at the time
     * @param contextLoader that thread's context class loader at the time
     */
    public record Event(String kind, String name, Thread thread, String threadName, ClassLoader contextLoader) {
    }

    /**
     * Records that a static initialiser is running.
     *
     * @param name the fixture
     * @return nothing of interest: it lets an interface call this from a field initialiser
     */
    public static Object initialised(String name) {
        record(INITIALISED, name);
        return name;
    }

    /**
     * Records that a constructor is running.
     *
     * @param name the fixture
     */
    public static void constructed(String name) {
        record(CONSTRUCTED, name);
    }

    /**
     * Records that a static initialiser is running, and fails it with an exception the test can find again.
     *
     * @param name the fixture
     * @return never
     */
    public static Object failRuntime(String name) {
        IllegalStateException failure = new IllegalStateException("probe failure of " + name);
        THROWN.put(name, failure);
        record(INITIALISED, name);
        throw failure;
    }

    /**
     * Records that a static initialiser is running, and fails it the way a missing class does.
     *
     * @param name the fixture
     * @return never
     */
    public static Object failLinkage(String name) {
        record(INITIALISED, name);
        throw new NoClassDefFoundError("probe/Missing");
    }

    /**
     * Records that a static initialiser is running, says so to {@link #awaitEntered()} and keeps it running
     * until {@link #release()}.
     *
     * @param name the fixture
     * @return nothing of interest
     */
    public static Object block(String name) {
        record(INITIALISED, name);
        entered.countDown();
        try {
            if (!released.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released " + name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return name;
    }

    static void reset() {
        EVENTS.clear();
        THROWN.clear();
        entered = new CountDownLatch(1);
        released = new CountDownLatch(1);
    }

    static boolean awaitEntered() throws InterruptedException {
        return entered.await(60, TimeUnit.SECONDS);
    }

    static void release() {
        released.countDown();
    }

    static Throwable thrown(String name) {
        return THROWN.get(name);
    }

    static List<Event> events(String kind, String name) {
        List<Event> found = new ArrayList<>();
        for (Event event : EVENTS) {
            if (event.kind().equals(kind) && event.name().equals(name)) {
                found.add(event);
            }
        }
        return found;
    }

    static List<Event> events() {
        return List.copyOf(EVENTS);
    }

    private static void record(String kind, String name) {
        Thread thread = Thread.currentThread();
        EVENTS.add(new Event(kind, name, thread, thread.getName(), thread.getContextClassLoader()));
    }
}
