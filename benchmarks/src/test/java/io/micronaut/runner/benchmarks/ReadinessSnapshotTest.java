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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The readiness snapshot's parsers, fed captured text; nothing here forks a process. */
class ReadinessSnapshotTest {

    /** {@code /proc/<pid>/status} of an idle JDK 25 JVM (eclipse-temurin:25-jdk, Linux). */
    private static final String PROC_STATUS = """
            Name:\tjava
            Umask:\t0022
            State:\tS (sleeping)
            Tgid:\t7
            Ngid:\t0
            Pid:\t7
            PPid:\t1
            TracerPid:\t0
            Uid:\t0\t0\t0\t0
            Gid:\t0\t0\t0\t0
            FDSize:\t256
            Groups:\t0 
            NStgid:\t7
            NSpid:\t7
            NSpgid:\t1
            NSsid:\t1
            Kthread:\t0
            VmPeak:\t 5996632 kB
            VmSize:\t 5931096 kB
            VmLck:\t       0 kB
            VmPin:\t       0 kB
            VmHWM:\t  127044 kB
            VmRSS:\t  127044 kB
            RssAnon:\t   93680 kB
            RssFile:\t   33364 kB
            RssShmem:\t       0 kB
            VmData:\t  362388 kB
            VmStk:\t     132 kB
            VmExe:\t       4 kB
            VmLib:\t   23796 kB
            VmPTE:\t     504 kB
            VmSwap:\t       0 kB
            HugetlbPages:\t       0 kB
            CoreDumping:\t0
            THP_enabled:\t1
            untag_mask:\t0xffffffffffffff
            Threads:\t19
            SigQ:\t2/47559
            SigPnd:\t0000000000000000
            ShdPnd:\t0000000000000000
            SigBlk:\t0000000000000000
            SigIgn:\t0000000000000002
            SigCgt:\t2000000101005ccd
            CapInh:\t0000000000000000
            CapPrm:\t00000000a80425fb
            CapEff:\t00000000a80425fb
            CapBnd:\t00000000a80425fb
            CapAmb:\t0000000000000000
            NoNewPrivs:\t0
            Seccomp:\t2
            Seccomp_filters:\t1
            Speculation_Store_Bypass:\tvulnerable
            SpeculationIndirectBranch:\tunknown
            Cpus_allowed:\t3f
            Cpus_allowed_list:\t0-5
            Mems_allowed:\t00000000,00000001
            Mems_allowed_list:\t0
            voluntary_ctxt_switches:\t3
            nonvoluntary_ctxt_switches:\t1
            """;

    /** The first lines of {@code jstat -J-Djstat.showUnsupported=true -snap <pid>} against the same JVM. */
    private static final String JSTAT_SNAP = """
            java.ci.totalTime=186597888
            java.cls.loadedClasses=1469
            java.cls.sharedLoadedClasses=1050
            java.cls.sharedUnloadedClasses=0
            java.cls.unloadedClasses=0
            java.property.java.class.path="."
            java.property.java.home="/opt/java/openjdk"
            java.property.java.library.path="/usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib"
            java.property.java.version="25.0.4"
            java.property.java.vm.info="mixed mode, sharing"
            java.property.java.vm.name="OpenJDK 64-Bit Server VM"
            java.property.java.vm.specification.name="Java Virtual Machine Specification"
            java.property.java.vm.specification.vendor="Oracle Corporation"
            java.property.java.vm.specification.version="25"
            java.property.java.vm.vendor="Eclipse Adoptium"
            java.property.java.vm.version="25.0.4+7-LTS"
            java.property.jdk.debug="release"
            java.rt.vmArgs="--add-modules=ALL-DEFAULT"
            java.rt.vmFlags=""
            java.threads.daemon=5
            java.threads.live=6
            java.threads.livePeak=6
            java.threads.started=6
            sun.ci.findWitnessAnywhere=650
            sun.ci.findWitnessAnywhereSteps=5951
            sun.ci.findWitnessIn=81
            """;

    /**
     * {@code /proc/<pid>/stat} of a JVM whose command name contains {@code ") "}: field 12, {@code majflt}, is the
     * tenth token after the last {@code )}, here 789.
     */
    private static final String PROC_STAT = "4242 (a) b) java) S 1 4242 4242 0 -1 4194304 23456 17 789 3 120 30 0 0"
            + " 20 0 25 0 1234567 6073389056 31761 18446744073709551615 1 1 0 0 0 0 0 2 16800975 0 0 0 17 5 0 0 0"
            + " 0 0 0 0 0 0 0 0 0 0\n";

    /** {@code /proc/<pid>/io} of the same JVM. */
    private static final String PROC_IO = """
            rchar: 323934931
            wchar: 1024
            syscr: 632687
            syscw: 12
            read_bytes: 34164736
            write_bytes: 4096
            cancelled_write_bytes: 0
            """;

    @Test
    void majorFaultsAreTheTenthTokenAfterTheLastParenthesis() {
        assertEquals(789, ReadinessSnapshot.parseMajorFaults(PROC_STAT));
        assertEquals(789, ReadinessSnapshot.parseMajorFaults(PROC_STAT.replace("(a) b) java)", "(java)")));
    }

    @Test
    void readBytesAreTheReadBytesLineOfProcIo() {
        assertEquals(34_164_736, ReadinessSnapshot.parseReadBytes(PROC_IO));
    }

    @Test
    void missingOrGarbledCountersAreUnavailable() {
        for (String garbage : new String[] {null, "", "4242 (java S 1", "4242 (java) S 1 4242",
                "4242 (java) S 1 4242 4242 0 -1 4194304 23456 17 many 3", "\0\1)"}) {
            assertEquals(-1, ReadinessSnapshot.parseMajorFaults(garbage), String.valueOf(garbage));
        }
        for (String garbage : new String[] {null, "", "rchar: 1\nwchar: 2\n", "read_bytes: lots\n",
                "read_bytes:\n", "read_bytes: -5\n"}) {
            assertEquals(-1, ReadinessSnapshot.parseReadBytes(garbage), String.valueOf(garbage));
        }
    }

    @Test
    void procStatusGivesResidentAnonymousAndFileBytes() {
        ReadinessSnapshot snapshot = ReadinessSnapshot.parseProcStatus(PROC_STATUS);

        assertEquals(127_044L * 1024, snapshot.rssBytes());
        assertEquals(127_044L * 1024, snapshot.peakRssBytes());
        assertEquals(93_680L * 1024, snapshot.anonBytes());
        assertEquals(33_364L * 1024, snapshot.fileBytes());
        assertEquals(-1, snapshot.footprintBytes());
        assertEquals(-1, snapshot.peakFootprintBytes());
        assertEquals(-1, snapshot.loadedClasses());
        assertEquals(-1, snapshot.sharedClasses());
        assertEquals(-1, snapshot.probeMillis());
    }

    @Test
    void jstatLoadedClassesIncludeTheSharedOnes() {
        ReadinessSnapshot snapshot = ReadinessSnapshot.parseJstatSnap(JSTAT_SNAP);

        assertEquals(1469 + 1050, snapshot.loadedClasses());
        assertEquals(1050, snapshot.sharedClasses());
        assertEquals(-1, snapshot.rssBytes());
        assertEquals(-1, snapshot.anonBytes());
    }

    @Test
    void aMissingKeyIsUnavailable() {
        ReadinessSnapshot status = ReadinessSnapshot.parseProcStatus(PROC_STATUS.replaceAll("(?m)^RssAnon:.*\n", ""));
        assertEquals(-1, status.anonBytes());
        assertEquals(127_044L * 1024, status.rssBytes());

        ReadinessSnapshot noShared = ReadinessSnapshot.parseJstatSnap(
                JSTAT_SNAP.replaceAll("(?m)^java\\.cls\\.sharedLoadedClasses=.*\n", ""));
        assertEquals(-1, noShared.loadedClasses());
        assertEquals(-1, noShared.sharedClasses());

        ReadinessSnapshot noLoaded = ReadinessSnapshot.parseJstatSnap(
                JSTAT_SNAP.replaceAll("(?m)^java\\.cls\\.loadedClasses=.*\n", ""));
        assertEquals(-1, noLoaded.loadedClasses());
        assertEquals(1050, noLoaded.sharedClasses());
    }

    @Test
    void garbageIsUnavailableRatherThanAnException() {
        for (String garbage : new String[] {"", "\0\1", "VmRSS: lots kB\nRssAnon:\t-5 kB\nRssFile:\t12 MB\n",
                "VmHWM:\n:::\n", "Could not attach to 61919\n"}) {
            assertEquals(ReadinessSnapshot.UNAVAILABLE, ReadinessSnapshot.parseProcStatus(garbage), garbage);
        }
        for (String garbage : new String[] {"", "java.cls.loadedClasses=14180,2\njava.cls.sharedLoadedClasses=x\n",
                "java.cls.loadedClasses=\n=\n==", "Could not attach to 61919\n"}) {
            assertEquals(ReadinessSnapshot.UNAVAILABLE, ReadinessSnapshot.parseJstatSnap(garbage), garbage);
        }
    }

    @Test
    void privateAndPeakMemoryAreEachPlatformsOwnCounters() {
        ReadinessSnapshot linux = ReadinessSnapshot.parseProcStatus(PROC_STATUS);
        assertEquals(linux.anonBytes(), linux.privateBytes());
        assertEquals(linux.peakRssBytes(), linux.peakBytes());

        ReadinessSnapshot macOs = new ReadinessSnapshot(-1, 168_312_832, -1, -1, -1, 118_522_960, 127_452_216, -1, -1,
                -1, -1);
        assertEquals(118_522_960, macOs.privateBytes());
        assertEquals(127_452_216, macOs.peakBytes());

        assertEquals(-1, ReadinessSnapshot.UNAVAILABLE.privateBytes());
        assertEquals(-1, ReadinessSnapshot.UNAVAILABLE.peakBytes());
    }

    @Test
    void withProbeMillisKeepsEveryOtherField() {
        ReadinessSnapshot snapshot = new ReadinessSnapshot(-1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10).withProbeMillis(12.5);

        assertEquals(new ReadinessSnapshot(12.5, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), snapshot);
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void theSharedRssHelperReadsThisProcessWithoutASubprocess() {
        assertTrue(ReadinessSnapshot.rssBytes(ProcessHandle.current().pid()) > 0);
    }
}
