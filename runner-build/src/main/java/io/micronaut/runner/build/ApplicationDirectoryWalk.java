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

import io.micronaut.core.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The walk of {@link RunnerJarBuilder} through its application directories: it hands every regular file below
 * one of them to a {@link FileSink}, under the logical name a class path gives it. The "Path safety" section of
 * {@link RunnerJarBuilder} says which links the walk follows and which it rejects. A walk belongs to the
 * builder's calling thread.
 */
final class ApplicationDirectoryWalk {

    /** The real path of the directory that holds the output and the work directory. */
    private final Path outputDirectory;
    /** The output, which only messages name. */
    private final Path output;
    private final FileSink files;

    /**
     * @param outputDirectory the real path of the directory that holds the output and the work directory
     * @param output          the output, which only messages name
     * @param files           receives every regular file the walk finds
     */
    ApplicationDirectoryWalk(Path outputDirectory, Path output, FileSink files) {
        this.outputDirectory = outputDirectory;
        this.output = output;
        this.files = files;
    }

    /**
     * Walks one application directory, in the order of its sorted names.
     *
     * @param root the application directory, which entry names are relative to
     * @param real its real path
     * @throws IOException if an entry cannot be read or added, a link or junction does not resolve, leads into a
     *                     directory the walk is already inside, or reaches the directory that holds the output
     */
    void walk(Path root, Path real) throws IOException {
        Map<Path, Path> walking = new HashMap<>();
        walking.put(real, root);
        collectDirectory(root, root, real, walking, null);
    }

    /**
     * Adds the files below one directory of an application directory, following symbolic links and Windows
     * directory junctions as a class path does: an entry reached through a link or a junction keeps the link's
     * or the junction's own name.
     *
     * @param root      the application directory, which entry names are relative to
     * @param directory the directory to list, named through any links that led to it
     * @param real      the real path of {@code directory}
     * @param walking   the real paths of {@code directory} and of every directory above it up to {@code root},
     *                  each mapped to that directory as the walk names it
     * @param junction  the directory junction nearest to {@code directory} on the walk from {@code root},
     *                  {@code directory} itself included, or {@code null} if there is none; only the message of
     *                  a cycle names it
     * @throws IOException if an entry cannot be read or added, a link or junction does not resolve, leads into a
     *                     directory the walk is already inside, or reaches the directory that holds the output
     */
    private void collectDirectory(Path root, Path directory, Path real, Map<Path, Path> walking,
            @Nullable Path junction) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        // Sorted, so that the archive does not depend on the order the file system happens to report.
        children.sort(Comparator.comparing(RunnerJarBuilder::fileName));
        for (Path child : children) {
            BasicFileAttributes attributes =
                    Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean link = attributes.isSymbolicLink();
            if (link) {
                attributes = followLink(root, child);
            }
            if (attributes.isDirectory()) {
                // Java reports a Windows directory junction as a directory that is "other" (a reparse point that
                // is not a symbolic link), never as a link, and only Windows reports a directory as "other". Any
                // other such directory, such as a cloud-files placeholder, costs one toRealPath(), which resolves
                // it to its own path, and passes both checks.
                boolean isJunction = !link && attributes.isOther();
                // A plain subdirectory's real path follows from its parent's without a system call.
                Path childReal = link || isJunction
                        ? linkedDirectory(root, child, isJunction)
                        : real.resolve(child.getFileName());
                Path nearestJunction = isJunction ? child : junction;
                Path reentered = walking.putIfAbsent(childReal, child);
                if (reentered != null) {
                    throw cycle(root, child, childReal, reentered, nearestJunction);
                }
                collectDirectory(root, child, childReal, walking, nearestJunction);
                walking.remove(childReal);
            } else if (attributes.isRegularFile()) {
                String name = root.relativize(child).toString().replace(File.separatorChar, '/');
                files.accept(root, name, child, attributes.size());
            }
        }
    }

    /**
     * The failure for a cycle: the walk reached {@code child}, whose real path is that of {@code reentered}, a
     * directory the walk is already inside. The message names the nearest directory junction when the cycle runs
     * through it, that is, when it lies below {@code reentered}. Both name directories on the walk's way to
     * {@code child}, through the links that led there, so the one with more name elements is the deeper. A cycle
     * that runs through no junction keeps the symbolic-link message.
     */
    private static IOException cycle(Path root, Path child, Path childReal, Path reentered,
            @Nullable Path junction) {
        String message = "The application output " + root + " reaches " + child + ", which leads back to "
                + childReal;
        if (junction != null && junction.getNameCount() > reentered.getNameCount()) {
            return new IOException(message + " through the directory junction " + junction
                    + "; cycles through directory junctions or symbolic links are not supported");
        }
        return new IOException(message + "; symbolic-link cycles are not supported");
    }

    /**
     * Resolves a symbolic link or a Windows directory junction to a directory inside an application directory,
     * refusing one that leads to the directory that holds the output or to one of its ancestors. Validation has
     * already refused an output directory below an application directory, so every other directory the walk
     * enters is below the root or below a link or junction checked here.
     *
     * @param root     the application directory
     * @param link     the symbolic link or junction
     * @param junction whether {@code link} is a directory junction, whose target nothing has read yet
     * @throws IOException if a junction does not resolve, or the link or junction reaches the directory where
     *                     the work directory and the output go
     */
    private Path linkedDirectory(Path root, Path link, boolean junction) throws IOException {
        String kind = junction ? "directory junction" : "symbolic link";
        Path real;
        try {
            real = link.toRealPath();
        } catch (IOException e) {
            if (!junction) {
                throw e;
            }
            // Not through followLink: Files.readSymbolicLink refuses a junction with a NotLinkException.
            throw new IOException("The application output " + root + " contains the " + kind + " " + link
                    + ", which does not resolve", e);
        }
        if (outputDirectory.startsWith(real)) {
            throw new IOException("The application output " + root + " contains the " + kind + " " + link
                    + " to " + real + ", which is or contains the directory of the output " + output
                    + "; packaging it would read what it is writing");
        }
        return real;
    }

    /**
     * Reads the attributes of what a symbolic link inside an application directory leads to.
     *
     * @throws IOException if the link does not resolve, including a link that leads back to itself
     */
    private static BasicFileAttributes followLink(Path root, Path link) throws IOException {
        try {
            return Files.readAttributes(link, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new IOException("The application output " + root + " contains the symbolic link " + link
                    + " to " + Files.readSymbolicLink(link) + ", which does not resolve"
                    + "; maven-resources-plugin 3.3.1 copies symbolic links into target/classes verbatim, so a"
                    + " relative link that works from src/main/resources can dangle there. Use an absolute"
                    + " link, a copy, or maven-resources-plugin 3.4.0 or later", e);
        }
    }

    /** Receives every regular file of an application directory, in the order of the walk. */
    @FunctionalInterface
    interface FileSink {

        /**
         * Takes one regular file.
         *
         * @param root the application directory the file was found in
         * @param name the file's logical name: its path below {@code root}, with {@code /} separators
         * @param file the file, named through any links that led to it
         * @param size the file's size, as the walk read it
         * @throws IOException if the file cannot be added
         */
        void accept(Path root, String name, Path file, long size) throws IOException;
    }
}
