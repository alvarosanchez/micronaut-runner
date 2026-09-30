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
package io.micronaut.runner.build.training;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The application the {@link TrainingDriver} tests launch, and the workload command they run. The tests pack
 * this one class into a jar, so it has no nested class.
 *
 * <p>What it does is a set of comma-separated modes, read from the first argument or else from the
 * environment variable {@value #MODE}. Without a mode it serves {@code /ready}, {@code /work} and
 * {@code /stop} on the loopback port that {@code MICRONAUT_SERVER_PORT} names. Before it does anything that a
 * test waits for, it writes what it knows about itself to the report file named by the second argument or
 * else by {@value #REPORT}: its process id, the process id of a child it started, the port variable and the
 * {@value #MARKER} variable as it received them. The report is written to a temporary file and moved into
 * place, so a test that sees the file reads all of it.</p>
 *
 * <p>Whatever the mode, the process halts itself after {@value #LIFETIME_SECONDS} seconds, so that a test
 * that fails to reap it leaves nothing behind for long.</p>
 */
public final class TrainingFixture {

    /** The environment variable that selects the modes. */
    static final String MODE = "TRAINING_FIXTURE_MODE";

    /** The environment variable that names the report file. */
    static final String REPORT = "TRAINING_FIXTURE_REPORT";

    /** An environment variable the report echoes, which a test sets through the training settings. */
    static final String MARKER = "TRAINING_FIXTURE_MARKER";

    /** What the fixture prints once it listens. */
    static final String LISTENING = "TRAINING FIXTURE listening";

    /** What it prints when it is not going to. */
    static final String NEVER_LISTENS = "TRAINING FIXTURE never listens";

    private static final int LIFETIME_SECONDS = 180;

    private TrainingFixture() {
    }

    /**
     * Runs the fixture.
     *
     * @param args the modes and the report file, each optional
     * @throws Exception if the fixture cannot do what its mode asks
     */
    public static void main(String[] args) throws Exception {
        Thread lifetime = new Thread(() -> {
            try {
                Thread.sleep(LIFETIME_SECONDS * 1000L);
            } catch (InterruptedException ignored) {
                // halt either way
            }
            Runtime.getRuntime().halt(99);
        }, "fixture-lifetime");
        lifetime.setDaemon(true);
        lifetime.start();

        String modeList = args.length > 0 ? args[0] : System.getenv().getOrDefault(MODE, "");
        Set<String> modes = new TreeSet<>(Arrays.asList(modeList.split(",")));
        String reportFile = args.length > 1 ? args[1] : System.getenv(REPORT);
        List<String> report = new ArrayList<>();
        report.add("pid=" + ProcessHandle.current().pid());
        report.add("port=" + System.getenv().getOrDefault("MICRONAUT_SERVER_PORT", ""));
        report.add("marker=" + System.getenv().getOrDefault(MARKER, ""));
        report.add("url=" + System.getenv().getOrDefault(TrainingDriver.URL_VARIABLE, ""));

        if (modes.contains("sleep")) {
            Thread.sleep(Long.MAX_VALUE);
        }
        if (modes.contains("grandchild")) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), TrainingFixture.class.getName(), "sleep")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            report.add("child=" + child.pid());
        }
        if (modes.contains("stubborn")) {
            // A JVM whose shutdown hook never returns does not end on SIGTERM.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    // the JVM is being killed
                }
            }));
        }
        if (modes.contains("flood")) {
            byte[] line = new byte[1024];
            Arrays.fill(line, (byte) 'x');
            line[line.length - 1] = '\n';
            for (int i = 0; i < 8 * 1024; i++) {
                System.out.write(line);
            }
            System.out.flush();
        }
        write(reportFile, report);

        if (modes.contains("exit-3")) {
            System.out.println("TRAINING FIXTURE exits with status 3");
            System.exit(3);
        }
        if (modes.contains("run-to-exit")) {
            System.out.println("TRAINING FIXTURE ran to its exit");
            return;
        }
        if (modes.contains("command")) {
            System.out.println("TRAINING FIXTURE command saw " + System.getenv(TrainingDriver.URL_VARIABLE));
            return;
        }
        if (modes.contains("never-listen") || modes.contains("command-hang")) {
            System.out.println(NEVER_LISTENS);
            Thread.sleep(Long.MAX_VALUE);
        }
        serve(modes.contains("work-500"));
    }

    private static void serve(boolean failWork) throws IOException {
        int port = Integer.parseInt(System.getenv("MICRONAUT_SERVER_PORT"));
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 0);
        AtomicInteger work = new AtomicInteger();
        server.createContext("/ready", exchange -> respond(exchange, 200, "ready"));
        server.createContext("/work", exchange -> {
            System.out.println("TRAINING FIXTURE work " + work.incrementAndGet());
            respond(exchange, failWork ? 500 : 200, "work");
        });
        server.createContext("/stop", exchange -> {
            boolean post = "POST".equals(exchange.getRequestMethod());
            respond(exchange, post ? 200 : 405, "stop");
            if (post) {
                System.out.println("TRAINING FIXTURE stopping");
                new Thread(() -> System.exit(0), "fixture-stop").start();
            }
        });
        server.createContext("/", exchange -> respond(exchange, 404, "not found"));
        server.start();
        System.out.println(LISTENING);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void write(String reportFile, List<String> report) throws IOException {
        if (reportFile == null || reportFile.isEmpty()) {
            return;
        }
        Path target = Path.of(reportFile);
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "report-", ".tmp");
        Files.write(temporary, report, StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
