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

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.AttributeMapper;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.BufWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassReader;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CustomAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles and packages the class fixtures of the class transform tests: real {@code javac} output, with
 * local-variable tables when compiled with {@code -g}, which none of the older fixture compilers of this
 * module emit.
 */
final class ClassFixtures {

    /** A fixed timestamp for fixture jars, so a rebuilt fixture changes nothing. */
    private static final long FIXTURE_TIME = 1_000_000_000_000L;

    private ClassFixtures() {
    }

    /**
     * Compiles sources into a directory.
     *
     * @param sources   where the sources are written
     * @param classes   where the classes go
     * @param options   the compiler options, such as {@code -g} and {@code --release 17}
     * @param files     the sources, keyed by relative path
     * @return {@code classes}
     * @throws IOException if the fixture does not compile
     */
    static Path compile(Path sources, Path classes, List<String> options, Map<String, String> files)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(options);
        // javac warns that release 8 is deprecated; the fixture wants exactly that class file version.
        arguments.addAll(List.of("-nowarn", "-Xlint:-options", "-d", classes.toString()));
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture " + files.keySet());
        }
        return classes;
    }

    /**
     * Every class file below a directory, keyed by entry name, in name order.
     *
     * @param classes the directory
     * @return the classes
     * @throws IOException if one cannot be read
     */
    static Map<String, byte[]> classes(Path classes) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return entries;
    }

    /**
     * Writes a jar with a plain manifest and deflated entries, in the given order.
     *
     * @param file    the jar
     * @param entries its entries
     * @return {@code file}
     * @throws IOException if it cannot be written
     */
    static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return jar(file, manifest, entries);
    }

    /**
     * Writes a jar with a manifest and deflated entries, in the given order.
     *
     * @param file     the jar
     * @param manifest its manifest
     * @param entries  its entries
     * @return {@code file}
     * @throws IOException if it cannot be written
     */
    static Path jar(Path file, Manifest manifest, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(FIXTURE_TIME);
                jar.putNextEntry(record);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }

    /**
     * Adds a class-level attribute the JDK does not know, whose two-byte payload is a constant pool index.
     *
     * @param bytes the class
     * @return the class with the attribute
     */
    static byte[] withUnknownAttribute(byte[] bytes) {
        ClassFile context = ClassFile.of();
        return context.transformClass(context.parse(bytes), ClassTransform.endHandler(builder ->
                builder.with(new VendorAttribute())));
    }

    /** The name of the attribute {@link #withUnknownAttribute(byte[])} adds. */
    static final String UNKNOWN_ATTRIBUTE = "ExampleVendorAttribute";

    /** The constant its payload points at. */
    static final String UNKNOWN_ATTRIBUTE_TARGET = "a constant only the vendor attribute names";

    /**
     * An attribute that the ClassFile API writes and, lacking a mapper when it reads it back, reports as
     * unknown.
     */
    private static final class VendorAttribute extends CustomAttribute<VendorAttribute> {

        private static final AttributeMapper<VendorAttribute> MAPPER = new AttributeMapper<>() {
            @Override
            public String name() {
                return UNKNOWN_ATTRIBUTE;
            }

            @Override
            public VendorAttribute readAttribute(AttributedElement enclosing, ClassReader reader, int position) {
                throw new UnsupportedOperationException("the fixture only writes it");
            }

            @Override
            public void writeAttribute(BufWriter buffer, VendorAttribute attribute) {
                buffer.writeIndex(buffer.constantPool().utf8Entry(UNKNOWN_ATTRIBUTE));
                buffer.writeInt(2);
                buffer.writeIndex(buffer.constantPool().utf8Entry(UNKNOWN_ATTRIBUTE_TARGET));
            }

            @Override
            public AttributeStability stability() {
                return AttributeStability.CP_REFS;
            }
        };

        private VendorAttribute() {
            super(MAPPER);
        }
    }

    /**
     * The sources, keyed by relative path, of one small class.
     *
     * @param name   the class's binary name
     * @param source its source
     * @return the map
     */
    static Map<String, String> source(String name, String source) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name.replace('.', '/') + ".java", source);
        return files;
    }

    /**
     * UTF-8 bytes.
     *
     * @param text the text
     * @return its bytes
     */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
