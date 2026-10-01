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

/**
 * Test-only access, for runner-build's tests, to the launcher members that are package-private because no main
 * code outside {@code io.micronaut.runner} calls them.
 *
 * <p>It lives in the launcher's package under runner-build's test sources: the test class path has one class
 * loader and the launcher seals no package, so package-private access works. It is test code, never packaged,
 * and it keeps those members off the launcher's public API.</p>
 */
public final class RunnerBuildTestAccess {

    /** See {@link IndexFormat#H_RESERVED}. */
    public static final int H_RESERVED = IndexFormat.H_RESERVED;

    private RunnerBuildTestAccess() {
    }

    /**
     * See {@link ArchiveSource#indexOnly()}.
     *
     * @param source the archive
     * @return whether only the index is mapped
     */
    public static boolean indexOnly(ArchiveSource source) {
        return source.indexOnly();
    }

    /**
     * See {@link Index#nestedStored()}.
     *
     * @param index the index
     * @return the header flag
     */
    public static boolean nestedStored(Index index) {
        return index.nestedStored();
    }

    /**
     * See {@link Index#largestStoredClass()}.
     *
     * @param index the index
     * @return the recorded size
     */
    public static long largestStoredClass(Index index) {
        return index.largestStoredClass();
    }

    /**
     * See {@link Index#outerFileLength()}.
     *
     * @param index the index
     * @return the recorded length
     */
    public static long outerFileLength(Index index) {
        return index.outerFileLength();
    }

    /**
     * See {@link Index#jdkPreloadName(int)}.
     *
     * @param index    the index
     * @param position the position in the list
     * @return the class name
     */
    public static String jdkPreloadName(Index index, int position) {
        return index.jdkPreloadName(position);
    }

    /**
     * See {@link Index#jarCoordinates(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the coordinates, or {@code null}
     */
    public static String jarCoordinates(Index index, int jarId) {
        return index.jarCoordinates(jarId);
    }

    /**
     * See {@link Index#jarLocalHeaderOffset(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the offset
     */
    public static long jarLocalHeaderOffset(Index index, int jarId) {
        return index.jarLocalHeaderOffset(jarId);
    }

    /**
     * See {@link Index#jarFlags(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the flags
     */
    public static int jarFlags(Index index, int jarId) {
        return index.jarFlags(jarId);
    }

    /**
     * See {@link Index#jarSealedByDefault(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the flag
     */
    public static boolean jarSealedByDefault(Index index, int jarId) {
        return index.jarSealedByDefault(jarId);
    }

    /**
     * See {@link Index#jarFirstEntry(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the first record
     */
    public static int jarFirstEntry(Index index, int jarId) {
        return index.jarFirstEntry(jarId);
    }

    /**
     * See {@link Index#jarEntryCount(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the number of records
     */
    public static int jarEntryCount(Index index, int jarId) {
        return index.jarEntryCount(jarId);
    }

    /**
     * See {@link Index#jarSpecTitle(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the attribute, or {@code null}
     */
    public static String jarSpecTitle(Index index, int jarId) {
        return index.jarSpecTitle(jarId);
    }

    /**
     * See {@link Index#jarSpecVersion(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the attribute, or {@code null}
     */
    public static String jarSpecVersion(Index index, int jarId) {
        return index.jarSpecVersion(jarId);
    }

    /**
     * See {@link Index#jarImplTitle(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the attribute, or {@code null}
     */
    public static String jarImplTitle(Index index, int jarId) {
        return index.jarImplTitle(jarId);
    }

    /**
     * See {@link Index#jarImplVersion(int)}.
     *
     * @param index the index
     * @param jarId the jar
     * @return the attribute, or {@code null}
     */
    public static String jarImplVersion(Index index, int jarId) {
        return index.jarImplVersion(jarId);
    }

    /**
     * See {@link Index#packageSpecTitle(int)}.
     *
     * @param index  the index
     * @param record the package record
     * @return the attribute, or {@code null}
     */
    public static String packageSpecTitle(Index index, int record) {
        return index.packageSpecTitle(record);
    }

    /**
     * See {@link Index#packageSpecVendor(int)}.
     *
     * @param index  the index
     * @param record the package record
     * @return the attribute, or {@code null}
     */
    public static String packageSpecVendor(Index index, int record) {
        return index.packageSpecVendor(record);
    }

    /**
     * See {@link Index#packageImplVersion(int)}.
     *
     * @param index  the index
     * @param record the package record
     * @return the attribute, or {@code null}
     */
    public static String packageImplVersion(Index index, int record) {
        return index.packageImplVersion(record);
    }

    /**
     * See {@link Index#packageSealedSpecified(int)}.
     *
     * @param index  the index
     * @param record the package record
     * @return the flag
     */
    public static boolean packageSealedSpecified(Index index, int record) {
        return index.packageSealedSpecified(record);
    }

    /**
     * See {@link Index#packageSealedValue(int)}.
     *
     * @param index  the index
     * @param record the package record
     * @return the flag
     */
    public static boolean packageSealedValue(Index index, int record) {
        return index.packageSealedValue(record);
    }

    /**
     * See {@link Index#entryJarId(int)}.
     *
     * @param index  the index
     * @param record the entry record
     * @return the jar
     */
    public static int entryJarId(Index index, int record) {
        return index.entryJarId(record);
    }

    /**
     * See {@link Index#entryMrVersion(int)}.
     *
     * @param index  the index
     * @param record the entry record
     * @return the multi-release version
     */
    public static int entryMrVersion(Index index, int record) {
        return index.entryMrVersion(record);
    }

    /**
     * See {@link Index#entryNextSameName(int)}.
     *
     * @param index  the index
     * @param record the entry record
     * @return the next record of the chain
     */
    public static int entryNextSameName(Index index, int record) {
        return index.entryNextSameName(record);
    }

    /**
     * See {@link Index#entrySyntheticDirectory(int)}.
     *
     * @param index  the index
     * @param record the entry record
     * @return the flag
     */
    public static boolean entrySyntheticDirectory(Index index, int record) {
        return index.entrySyntheticDirectory(record);
    }

    /**
     * See {@link Index#entryVersionedAlias(int)}.
     *
     * @param index  the index
     * @param record the entry record
     * @return the flag
     */
    public static boolean entryVersionedAlias(Index index, int record) {
        return index.entryVersionedAlias(record);
    }

    /**
     * See {@link Index#next(int)}.
     *
     * @param index  the index
     * @param record the entry record
     * @return the next record of the chain
     */
    public static int next(Index index, int record) {
        return index.next(record);
    }

    /**
     * See {@link Index#resolve(int, int)}.
     *
     * @param index            the index
     * @param chainHead        a record
     * @param effectiveVersion the runtime feature version
     * @return the first applicable record
     */
    public static int resolve(Index index, int chainHead, int effectiveVersion) {
        return index.resolve(chainHead, effectiveVersion);
    }
}
