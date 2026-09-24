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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Reads the first {@code size} bytes of a file, positionally, and fails unless they are the bytes a
 * recorded CRC-32 describes.
 *
 * <p>{@link RunnerJarBuilder} checksums a file when it collects it and copies it into the archive later, so
 * the file can change in between. Reading it through this stream makes that change fail the build instead of
 * reaching the archive: a file that changed fails at its last byte, and one that became shorter fails where
 * it now ends. It reads the application's own files and, in PRESERVE, the dependencies nested as they are.</p>
 *
 * @since 1.0
 */
final class VerifiedFileInputStream extends InputStream {

    private static final int SKIP_BUFFER_SIZE = 64 * 1024;

    private final Path file;
    private final FileChannel channel;
    private final CRC32 crc = new CRC32();
    private final long expectedCrc;
    private final byte[] one = new byte[1];
    private long position;
    private long remaining;
    private boolean verified;

    /**
     * Opens a file.
     *
     * @param file        the file
     * @param size        how many bytes to read, from its first byte
     * @param expectedCrc the CRC-32 of those bytes
     * @throws IOException if the file cannot be opened, or {@code size} is zero and {@code expectedCrc} is not
     *                     the CRC-32 of nothing
     */
    VerifiedFileInputStream(Path file, long size, long expectedCrc) throws IOException {
        this.file = file;
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.remaining = size;
        this.expectedCrc = expectedCrc;
        if (size == 0) {
            verify();
        }
    }

    @Override
    public int read() throws IOException {
        int read = read(one, 0, 1);
        return read < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] destination, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, destination.length);
        if (count == 0) {
            return 0;
        }
        if (remaining == 0) {
            return -1;
        }
        int wanted = (int) Math.min(count, remaining);
        ByteBuffer buffer = ByteBuffer.wrap(destination, offset, wanted);
        int read;
        do {
            read = channel.read(buffer, position);
        } while (read == 0);
        if (read < 0) {
            throw new IOException("The source " + file + " ended with " + remaining + " bytes remaining");
        }
        crc.update(destination, offset, read);
        position += read;
        remaining -= read;
        if (remaining == 0) {
            verify();
        }
        return read;
    }

    @Override
    public long skip(long count) throws IOException {
        long wanted = Math.min(Math.max(count, 0), remaining);
        if (wanted == 0) {
            return 0;
        }
        byte[] buffer = new byte[(int) Math.min(SKIP_BUFFER_SIZE, wanted)];
        long skipped = 0;
        while (skipped < wanted) {
            int read = read(buffer, 0, (int) Math.min(buffer.length, wanted - skipped));
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void verify() throws IOException {
        if (!verified && crc.getValue() != expectedCrc) {
            throw new IOException("The source " + file + " does not match its recorded CRC-32: expected "
                    + Long.toHexString(expectedCrc) + ", computed " + Long.toHexString(crc.getValue()));
        }
        verified = true;
    }
}
