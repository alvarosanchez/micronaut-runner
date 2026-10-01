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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * The application {@link AotCacheBuilderTest} trains caches for: a JDK {@code HttpServer} on the loopback port
 * {@code MICRONAUT_SERVER_PORT} names, which serves {@code /ready}, answers {@code /work} with the help of a class
 * from its one dependency, {@link AotCacheFixtureLibrary}, and exits with status 0 on {@code POST /stop}.
 *
 * <p>The test packs this class into the application layer of a Runner JAR and the library into a nested JAR. It
 * prints the code-source location of the library's class, and halts itself after three minutes, so a test that
 * fails to reap it leaves nothing behind for long.</p>
 */
public final class AotCacheFixture {

    /** What precedes the library class's code-source location on standard output. */
    public static final String CODE_SOURCE = "AOT CACHE FIXTURE dependency code source: ";

    private AotCacheFixture() {
    }

    /**
     * Serves until it is stopped.
     *
     * @param args ignored
     * @throws IOException if the port cannot be bound
     */
    public static void main(String[] args) throws IOException {
        Thread lifetime = new Thread(() -> {
            try {
                Thread.sleep(180_000);
            } catch (InterruptedException ignored) {
                // halt either way
            }
            Runtime.getRuntime().halt(99);
        }, "fixture-lifetime");
        lifetime.setDaemon(true);
        lifetime.start();
        int port = Integer.parseInt(System.getenv("MICRONAUT_SERVER_PORT"));
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 0);
        server.createContext("/ready", exchange -> respond(exchange, 200, "ready"));
        server.createContext("/work", exchange -> respond(exchange, 200, AotCacheFixtureLibrary.greet("cache")));
        server.createContext("/stop", exchange -> {
            boolean post = "POST".equals(exchange.getRequestMethod());
            respond(exchange, post ? 200 : 405, "stop");
            if (post) {
                new Thread(() -> System.exit(0), "fixture-stop").start();
            }
        });
        server.start();
        System.out.println(CODE_SOURCE
                + AotCacheFixtureLibrary.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("AOT CACHE FIXTURE listening on " + port);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
