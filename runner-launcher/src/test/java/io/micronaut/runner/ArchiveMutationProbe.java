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
package io.micronaut.runner;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Child-JVM side of {@link ArchiveMutationTest}. It is deliberately a separate main class: accessing a
 * mapping after its file is truncated can terminate the VM on some platforms and must never happen in the
 * Gradle test worker.
 */
final class ArchiveMutationProbe {

    static final String APPLICATION_ENTRY = "ready.txt";
    static final String TARGET_ENTRY = "payload.bin";
    static final int PAYLOAD_SIZE = 16 * 1024;

    private ArchiveMutationProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("archive, mapped and prevalidated arguments are required");
        }
        File archive = new File(arguments[0]);
        boolean mapped = Boolean.parseBoolean(arguments[1]);
        boolean prevalidated = Boolean.parseBoolean(arguments[2]);
        System.setProperty(ArchiveSource.MMAP_PROPERTY, Boolean.toString(mapped));

        try (ArchiveSource source = ArchiveSource.open(archive)) {
            Index index = Index.open(source);
            byte[] application = read(index, index.find(APPLICATION_ENTRY));
            if (!Arrays.equals(applicationPayload(), application)) {
                throw new IllegalStateException("application readiness entry did not match its index");
            }
            if (prevalidated) {
                byte[] initial = read(index, index.find(TARGET_ENTRY));
                if (!Arrays.equals(originalPayload(), initial)) {
                    throw new IllegalStateException("target entry changed before the readiness handshake");
                }
            }

            System.out.println("JAVA_RUNTIME=" + clean(System.getProperty("java.runtime.name")));
            System.out.println("JAVA_VERSION=" + clean(System.getProperty("java.runtime.version")));
            System.out.println("OS=" + clean(System.getProperty("os.name")) + "/"
                    + clean(System.getProperty("os.version")) + "/" + clean(System.getProperty("os.arch")));
            System.out.println("READY mapped=" + source.mapped() + " prevalidated=" + prevalidated);
            System.out.flush();

            if (System.in.read() < 0) {
                throw new IllegalStateException("coordinator closed the handshake before mutation");
            }
            try {
                byte[] observed = read(index, index.find(TARGET_ENTRY));
                if (Arrays.equals(originalPayload(), observed)) {
                    System.out.println("RESULT=ORIGINAL_BYTES");
                } else if (Arrays.equals(replacementPayload(), observed)) {
                    System.out.println("RESULT=REPLACEMENT_BYTES");
                } else {
                    System.out.println("RESULT=OTHER_BYTES length=" + observed.length);
                }
            } catch (Throwable failure) {
                System.out.println("RESULT=JAVA_EXCEPTION type=" + failure.getClass().getName()
                        + " message=" + clean(failure.getMessage()));
            }
            System.out.flush();
        }
    }

    static byte[] applicationPayload() {
        return "ready".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static byte[] originalPayload() {
        return payload(0x31);
    }

    static byte[] replacementPayload() {
        return payload(0x67);
    }

    private static byte[] read(Index index, int record) throws Exception {
        if (record == IndexFormat.NO_INDEX) {
            throw new IllegalStateException("fixture entry is absent from the index");
        }
        try (InputStream input = index.openEntryStream(record, false)) {
            return input.readAllBytes();
        }
    }

    private static byte[] payload(int seed) {
        byte[] result = new byte[PAYLOAD_SIZE];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i * 17 + (i >>> 4));
        }
        return result;
    }

    private static String clean(String value) {
        if (value == null) {
            return "(null)";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
