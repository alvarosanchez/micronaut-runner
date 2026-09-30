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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CPU and storage conditions a run measured under, captured before the harness pins itself, so the machine's
 * CPU count is the unpinned one. Recording is best effort: a missing tool or {@code /sys} file gives {@code null}
 * and never fails the run.
 *
 * @param cpuLimit         the child CPU limit, or {@code null} when the children may use every CPU
 * @param machineCpuCount  the CPUs the harness JVM saw before pinning, which the report's Machine line shows
 * @param machineCpus      the CPU list the harness was allowed before pinning (Linux), or {@code null}
 * @param childCpus        the children's CPU list, or {@code null} when unlimited
 * @param harnessCpus      the harness's CPU list after pinning, or {@code null} when unlimited
 * @param childCpuSiblings the SMT siblings of each child CPU, {@code null} when unlimited; a value is {@code null}
 *                         when its {@code thread_siblings_list} could not be read
 * @param cpuModel         the CPU model {@code lscpu} names, or {@code null}
 * @param probeCpus        the CPU count {@link CpuProbe} printed under the limit, or {@code null} when unlimited
 * @param probeFlags       the probe's {@code -XX:+PrintCommandLineFlags} line, collector included, or {@code null}
 * @param pageCache        the page-cache mode
 * @param evictionMethod   how that mode evicts, or {@code null} for {@code uncontrolled}
 * @param kernel           the {@code os.version} system property
 * @param storageMount     {@code findmnt -no SOURCE,FSTYPE -T <work dir>}, or {@code null}
 * @param storageDevices   {@code lsblk -sno NAME,ROTA,MODEL <source>}: the device and its parent disks, or
 *                         {@code null}
 */
record RunConditions(Integer cpuLimit,
                     int machineCpuCount,
                     String machineCpus,
                     String childCpus,
                     String harnessCpus,
                     Map<Integer, String> childCpuSiblings,
                     String cpuModel,
                     Integer probeCpus,
                     String probeFlags,
                     PageCacheMode pageCache,
                     String evictionMethod,
                     String kernel,
                     String storageMount,
                     String storageDevices) {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    RunConditions {
        childCpuSiblings = childCpuSiblings == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(childCpuSiblings));
    }

    /**
     * No CPU limit and an uncontrolled page cache, with nothing else recorded: what a context that states no
     * conditions ran under.
     *
     * @return the default conditions
     */
    static RunConditions defaults() {
        return new RunConditions(null, Runtime.getRuntime().availableProcessors(), null, null, null, null, null,
                null, null, PageCacheMode.UNCONTROLLED, null, System.getProperty("os.version"), null, null);
    }

    /**
     * Captures the conditions of this run. Call it before the harness pins itself.
     *
     * @param cpuLimit      the CPU limit, or {@code null}
     * @param probe         what the CPU probe reported under the limit, or {@code null} when unlimited
     * @param pageCache     the page-cache mode
     * @param workDirectory where the variants were built, whose storage is recorded
     * @return the conditions
     */
    static RunConditions capture(CpuLimit cpuLimit, CpuLimit.Probe probe, PageCacheMode pageCache,
                                 Path workDirectory) {
        String osName = System.getProperty("os.name", "");
        String mount = PageCacheMode.linux(osName) ? storageMount(workDirectory) : null;
        String source = mount == null ? null : mount.split("\\s+")[0];
        String devices = source == null ? null : Commands.run(List.of("lsblk", "-sno", "NAME,ROTA,MODEL", source),
                Map.of(), COMMAND_TIMEOUT).outputIfSuccessful();
        Map<Integer, String> siblings = cpuLimit == null ? null : cpuLimit.childSiblings();
        return new RunConditions(cpuLimit == null ? null : cpuLimit.cpus(),
                Runtime.getRuntime().availableProcessors(),
                cpuLimit == null ? CpuLimit.machineList() : CpuLimit.format(cpuLimit.machine()),
                cpuLimit == null ? null : CpuLimit.format(cpuLimit.child()),
                cpuLimit == null ? null : CpuLimit.format(cpuLimit.harness()),
                siblings,
                PageCacheMode.linux(osName) ? CpuLimit.cpuModel() : null,
                probe == null ? null : probe.availableProcessors(),
                probe == null ? null : probe.flags(),
                pageCache,
                pageCache.evictionMethod(osName),
                System.getProperty("os.version"),
                mount,
                devices);
    }

    private static String storageMount(Path workDirectory) {
        return Commands.run(List.of("findmnt", "-no", "SOURCE,FSTYPE", "-T",
                workDirectory.toAbsolutePath().normalize().toString()), Map.of(), COMMAND_TIMEOUT)
                .outputIfSuccessful();
    }
}
