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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Forked HTTP fixture for {@link StartupHarnessTest}. */
public final class StartupHarnessFixture {

    private StartupHarnessFixture() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path lifecycle = args.length > 1 ? Path.of(args[1]) : null;
        if (lifecycle != null) {
            Files.writeString(lifecycle, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> write(lifecycle.resolveSibling(
                    lifecycle.getFileName() + ".stopped"), "stopped")));
        }
        if (mode.equals("early-exit")) {
            System.exit(7);
        }
        if (mode.equals("hang")) {
            Thread.sleep(60_000);
            return;
        }

        int port = Integer.parseInt(System.getenv("SERVER_PORT"));
        ServerSocket server = new ServerSocket(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (mode.equals("diagnostic")) {
                ShutdownMarker.touch();
            }
            try {
                server.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }));
        Thread responder = new Thread(() -> serve(server, mode), "startup-harness-fixture-http");
        responder.setDaemon(true);
        responder.start();

        if (mode.equals("delayed-log")) {
            Thread.sleep(150);
            System.out.println("Startup completed in 123ms");
        } else if (!mode.equals("missing-log")) {
            System.out.println("Startup completed in 12ms");
        }
        Thread.currentThread().join();
    }

    private static void serve(ServerSocket server, String mode) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.getInputStream().readNBytes(1);
                boolean injected = System.getProperty("fixture.injected") != null;
                int status = mode.equals("non-200") || injected ? 503 : 200;
                byte[] body = (injected ? "inherited option reached child" : "ready")
                        .getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Unavailable")
                        + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (IOException e) {
                if (!server.isClosed()) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void write(Path file, String value) {
        try {
            Files.writeString(file, value, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best-effort fixture signal during JVM shutdown.
        }
    }

    /** Loaded only by the shutdown hook so diagnostics prove their actual observation horizon. */
    static final class ShutdownMarker {
        static void touch() {
            System.out.println(ShutdownMarker.class.getName());
        }
    }
}
