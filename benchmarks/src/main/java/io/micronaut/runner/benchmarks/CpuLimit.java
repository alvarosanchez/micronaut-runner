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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * A Linux CPU-affinity limit for every child JVM the harness starts: timing runs, AOT-cache training and
 * verification, and the {@link CpuProbe}. The first {@code cpus} CPUs the harness may use are the children's; the
 * rest are the harness's own once it has pinned itself.
 *
 * <p>{@code taskset} sets affinity, not a CFS quota: the child's threads cannot leave its CPUs, but they are never
 * throttled either. That models a node or container given whole CPUs, not {@code docker --cpus}.</p>
 *
 * <p>Parsing and validation are pure functions of their inputs, so they run in unit tests on any OS.</p>
 *
 * @param cpus    how many CPUs the children get
 * @param machine every CPU the harness was allowed before pinning, ascending
 * @param child   the children's CPUs: the first {@code cpus} of {@code machine}
 * @param harness the harness's CPUs after pinning: the rest of {@code machine}
 */
record CpuLimit(int cpus, List<Integer> machine, List<Integer> child, List<Integer> harness) {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    CpuLimit {
        machine = List.copyOf(machine);
        child = List.copyOf(child);
        harness = List.copyOf(harness);
    }

    /**
     * Validates a requested limit against the machine the harness runs on.
     *
     * @param cpus            the requested number of child CPUs
     * @param osName          the {@code os.name} system property
     * @param cpusAllowedList the harness's {@code Cpus_allowed_list} from {@code /proc/self/status}, or
     *                        {@code null} when it could not be read
     * @param tasksetOnPath   whether {@code taskset} is on the {@code PATH}
     * @return the split between child and harness CPUs
     * @throws IllegalArgumentException if a limit cannot be applied here
     */
    static CpuLimit validate(int cpus, String osName, String cpusAllowedList, boolean tasksetOnPath) {
        if (!linux(osName)) {
            throw new IllegalArgumentException("--cpus needs Linux (taskset); this is " + osName);
        }
        if (!tasksetOnPath) {
            throw new IllegalArgumentException("--cpus needs taskset on the PATH (util-linux)");
        }
        if (cpusAllowedList == null || cpusAllowedList.isBlank()) {
            throw new IllegalArgumentException("--cpus could not read Cpus_allowed_list from /proc/self/status");
        }
        List<Integer> allowed = parseList(cpusAllowedList);
        if (cpus < 1 || cpus > allowed.size() - 1) {
            throw new IllegalArgumentException("--cpus must be between 1 and " + (allowed.size() - 1)
                    + ": the harness may use " + allowed.size() + " CPUs (" + format(allowed)
                    + ") and keeps at least one for itself; got " + cpus);
        }
        return new CpuLimit(cpus, allowed, allowed.subList(0, cpus), allowed.subList(cpus, allowed.size()));
    }

    /**
     * Validates a requested limit against this machine.
     *
     * @param cpus the requested number of child CPUs
     * @return the limit
     * @throws IllegalArgumentException if a limit cannot be applied here
     */
    static CpuLimit forThisMachine(int cpus) {
        String osName = System.getProperty("os.name", "");
        String allowed = linux(osName) ? allowedList(readProcSelfStatus()) : null;
        return validate(cpus, osName, allowed, onPath("taskset", System.getenv("PATH")));
    }

    /**
     * The value of {@code Cpus_allowed_list} in the text of {@code /proc/<pid>/status}.
     *
     * @param procStatus the file's text, or {@code null}
     * @return the list, for example {@code 0-3} or {@code 0,2,4-5}, or {@code null} when it is not there
     */
    static String allowedList(String procStatus) {
        if (procStatus == null) {
            return null;
        }
        for (String line : procStatus.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals("Cpus_allowed_list")) {
                String value = line.substring(colon + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Parses a kernel CPU list.
     *
     * @param list for example {@code 0-3} or {@code 0,2,4-5}
     * @return the CPUs, ascending and distinct
     * @throws IllegalArgumentException if the list is malformed
     */
    static List<Integer> parseList(String list) {
        TreeSet<Integer> cpus = new TreeSet<>();
        for (String part : list.trim().split(",")) {
            String range = part.trim();
            try {
                int dash = range.indexOf('-');
                if (dash < 0) {
                    cpus.add(nonNegative(Integer.parseInt(range)));
                    continue;
                }
                int from = nonNegative(Integer.parseInt(range.substring(0, dash).trim()));
                int to = nonNegative(Integer.parseInt(range.substring(dash + 1).trim()));
                if (to < from) {
                    throw new IllegalArgumentException("descending CPU range " + range);
                }
                for (int cpu = from; cpu <= to; cpu++) {
                    cpus.add(cpu);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed CPU list '" + list + "'", e);
            }
        }
        return List.copyOf(cpus);
    }

    /**
     * Formats CPUs as a kernel CPU list, collapsing runs into ranges.
     *
     * @param cpus ascending, distinct CPUs
     * @return for example {@code 0-3} or {@code 0,2,4-5}
     */
    static String format(List<Integer> cpus) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < cpus.size()) {
            int j = i;
            while (j + 1 < cpus.size() && cpus.get(j + 1) == cpus.get(j) + 1) {
                j++;
            }
            out.append(out.isEmpty() ? "" : ",").append(cpus.get(i));
            if (j > i) {
                out.append('-').append(cpus.get(j));
            }
            i = j + 1;
        }
        return out.toString();
    }

    /**
     * Whether an executable is on a {@code PATH}.
     *
     * @param tool the executable's name
     * @param path the {@code PATH} value, or {@code null}
     * @return whether some entry holds an executable of that name
     */
    static boolean onPath(String tool, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (!entry.isBlank() && Files.isExecutable(Path.of(entry, tool))) {
                return true;
            }
        }
        return false;
    }

    /**
     * What every limited child command starts with. The prefix is never part of {@link Variant#command()}: JVM
     * arguments go after that command's {@code java}, and the reports show it without the prefix.
     *
     * @return {@code taskset -c <child CPUs>}
     */
    List<String> commandPrefix() {
        return List.of("taskset", "-c", format(child));
    }

    /**
     * What a limit adds to an AOT cache's identity: a cache trained under one set of VM ergonomics is never reused
     * under another.
     *
     * @return {@code cpus=<n>}
     */
    List<String> relevantJvmFlags() {
        return List.of("cpus=" + cpus);
    }

    /**
     * The command that moves every thread of the harness onto its own CPUs.
     *
     * @param pid the harness's process id
     * @return {@code taskset -a -c -p <harness CPUs> <pid>}
     */
    List<String> pinCommand(long pid) {
        return List.of("taskset", "-a", "-c", "-p", format(harness), Long.toString(pid));
    }

    /**
     * Pins every thread of this JVM to the harness's CPUs, so the readiness poller, the output drains and the
     * readiness probe stay off the children's CPUs.
     *
     * @throws IOException if {@code taskset} fails
     */
    void pinHarness() throws IOException {
        List<String> command = pinCommand(ProcessHandle.current().pid());
        Commands.Result result = Commands.run(command, Map.of(), COMMAND_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new IOException("could not pin the harness: " + String.join(" ", command) + " exited with "
                    + result.exitCode() + ": " + result.output().trim());
        }
    }

    /**
     * The SMT siblings of every child CPU, best effort.
     *
     * @return CPU to its {@code thread_siblings_list}, {@code null} for a CPU whose list could not be read
     */
    Map<Integer, String> childSiblings() {
        Map<Integer, String> siblings = new LinkedHashMap<>();
        for (int cpu : child) {
            Path file = Path.of("/sys/devices/system/cpu", "cpu" + cpu, "topology", "thread_siblings_list");
            String value;
            try {
                value = Files.readString(file, StandardCharsets.UTF_8).trim();
            } catch (IOException | RuntimeException e) {
                value = null;
            }
            siblings.put(cpu, value == null || value.isEmpty() ? null : value);
        }
        return siblings;
    }

    /**
     * Runs {@link CpuProbe} under the limit and checks that the child JVM sees exactly {@code cpus} CPUs.
     *
     * @param java      the {@code java} every variant is started with
     * @param classPath the class path holding {@link CpuProbe}
     * @return what the child reported
     * @throws IOException if the probe fails or reports a different CPU count
     */
    Probe probe(Path java, String classPath) throws IOException {
        Probe probe = runProbe(commandPrefix(), java, classPath);
        if (probe.availableProcessors() != cpus) {
            throw new IOException("under " + String.join(" ", commandPrefix()) + " the JVM sees "
                    + probe.availableProcessors() + " CPUs, not " + cpus);
        }
        return probe;
    }

    /**
     * The probe's command: {@code <prefix> <java> -XX:+PrintCommandLineFlags -cp <class path> CpuProbe}.
     *
     * @param prefix    the command prefix, for example {@code taskset -c 0}
     * @param java      the {@code java} every variant is started with
     * @param classPath the class path holding {@link CpuProbe}
     * @return the command
     */
    static List<String> probeCommand(List<String> prefix, Path java, String classPath) {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(List.of(java.toString(), "-XX:+PrintCommandLineFlags", "-cp", classPath,
                CpuProbe.class.getName()));
        return List.copyOf(command);
    }

    /**
     * Runs {@link CpuProbe} under a prefix.
     *
     * @param prefix    the command prefix
     * @param java      the {@code java} to run it with
     * @param classPath the class path holding {@link CpuProbe}
     * @return the CPU count it printed and the JVM's ergonomic flags
     * @throws IOException if it fails or prints no count
     */
    static Probe runProbe(List<String> prefix, Path java, String classPath) throws IOException {
        List<String> command = probeCommand(prefix, java, classPath);
        Commands.Result result = Commands.run(command, Map.of(), COMMAND_TIMEOUT);
        Probe probe = result.exitCode() == 0 ? parseProbe(result.output()) : null;
        if (probe == null || probe.availableProcessors() < 1) {
            throw new IOException("the CPU probe " + String.join(" ", command) + " exited with "
                    + result.exitCode() + " and printed: " + result.output().trim());
        }
        return probe;
    }

    /**
     * Reads the probe's output: the {@code -XX:+PrintCommandLineFlags} line, then the CPU count.
     *
     * @param output the probe's output
     * @return the count ({@code -1} when none was printed) and the flags ({@code null} when none were)
     */
    static Probe parseProbe(String output) {
        int processors = -1;
        String flags = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-XX:") && flags == null) {
                flags = trimmed;
            } else if (trimmed.matches("[0-9]{1,9}")) {
                processors = Integer.parseInt(trimmed);
            }
        }
        return new Probe(processors, flags);
    }

    /**
     * The CPU model {@code lscpu} names, best effort.
     *
     * @return the model name, or {@code null} when {@code lscpu} is missing or names none
     */
    static String cpuModel() {
        Commands.Result result = Commands.run(List.of("lscpu"), Map.of("LC_ALL", "C"), COMMAND_TIMEOUT);
        return result.exitCode() == 0 ? modelName(result.output()) : null;
    }

    /**
     * The {@code Model name} of {@code lscpu} output.
     *
     * @param lscpu the output
     * @return the first model name, or {@code null}
     */
    static String modelName(String lscpu) {
        for (String line : lscpu.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals("Model name")) {
                String value = line.substring(colon + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * The CPUs this process may run on, best effort.
     *
     * @return the kernel CPU list, or {@code null} off Linux or when it cannot be read
     */
    static String machineList() {
        return linux(System.getProperty("os.name", "")) ? allowedList(readProcSelfStatus()) : null;
    }

    private static String readProcSelfStatus() {
        try {
            return Files.readString(Path.of("/proc/self/status"), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean linux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    private static int nonNegative(int cpu) {
        if (cpu < 0) {
            throw new NumberFormatException("negative CPU " + cpu);
        }
        return cpu;
    }

    /**
     * What {@link CpuProbe} reported under a limit.
     *
     * @param availableProcessors the CPUs the JVM sees
     * @param flags               the JVM's {@code -XX:+PrintCommandLineFlags} line, collector included
     */
    record Probe(int availableProcessors, String flags) {
    }
}
