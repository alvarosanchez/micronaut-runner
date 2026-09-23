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
package io.micronaut.runner.benchmarks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Forked application used to exercise the complete CDS training lifecycle. */
public final class CdsCacheFixture {

    private CdsCacheFixture() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv("SERVER_PORT"));
        AtomicBoolean workload = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/ready", exchange -> respond(exchange, 200, "ready"));
        server.createContext("/work", exchange -> {
            workload.set(true);
            respond(exchange, 200, "worked");
        });
        server.createContext("/stop", exchange -> {
            if (!workload.get()) {
                respond(exchange, 409, "workload not exercised");
                return;
            }
            respond(exchange, 200, "stopping");
            server.stop(0);
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
