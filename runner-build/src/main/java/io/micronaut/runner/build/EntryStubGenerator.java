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

import io.micronaut.runner.Entry;
import io.micronaut.runner.IndexFormat;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Generates the class that lets the launcher start the application through an interface call.
 *
 * <p>The class it writes is this, give or take the constant pool:</p>
 * <pre>{@code
 * package io.micronaut.runner.generated;
 *
 * public final class AppEntry implements io.micronaut.runner.Entry {
 *     static {
 *         io.micronaut.runner.Launcher.register(new AppEntry());
 *     }
 *
 *     public void run(String[] args) throws Throwable {
 *         com.example.Application.main(args);
 *     }
 * }
 * }</pre>
 *
 * <p>It is packaged as an ordinary application class, so the launcher's {@code Class.forName} on it runs
 * the static initialiser, which hands the launcher an instance, on which the launcher calls
 * {@link Entry#run(String[])}. Entering the application is then an {@code invokeinterface} followed by an
 * {@code invokestatic}: neither {@code java.lang.reflect} nor {@code java.lang.invoke} is pulled onto the
 * startup path, and no method handle bootstrap is paid for. {@code run} declares {@code Throwable} so that
 * whatever the application throws travels out of the launcher unchanged.</p>
 *
 * <h2>Class file version</h2>
 * <p>The version is set explicitly to {@value #CLASS_FILE_MAJOR_VERSION}.{@value #CLASS_FILE_MINOR_VERSION}
 * (Java 25, the baseline of this project). {@link ClassFile} otherwise defaults to the version of the JDK
 * that happens to run the packager, so packaging on a newer JDK would produce a class that the JDK 25
 * running the application cannot load - a failure at startup, in the artifact, that the build would never
 * see.</p>
 *
 * <h2>Eligibility</h2>
 * <p>A stub that cannot work is worse than no stub at all: it fails when the application starts rather than
 * when it is packaged, and the launcher's reflective fallback would have started the same application
 * without complaint. {@link #ineligibilityReason(String, byte[])} is therefore deliberately narrow, and it
 * answers by <em>parsing</em> the main class rather than loading it - a packager must never run application
 * code or initialise application classes.</p>
 *
 * @since 1.0
 */
public final class EntryStubGenerator {

    /** Binary name of the generated class, which the index header records for the launcher. */
    public static final String STUB_CLASS = IndexFormat.GENERATED_PACKAGE + ".AppEntry";

    /** Major class file version of the generated class: {@code 69} is Java 25. */
    public static final int CLASS_FILE_MAJOR_VERSION = 69;

    /** Minor class file version of the generated class. */
    public static final int CLASS_FILE_MINOR_VERSION = 0;

    /**
     * Logical name of the generated class inside the application layer, which is
     * {@code MICRONAUT-INF/classes/io/micronaut/runner/generated/AppEntry.class} in the outer archive.
     */
    public static final String STUB_RESOURCE_NAME = STUB_CLASS.replace('.', '/') + ".class";

    /** The method the application has to declare, and the method the stub calls. */
    private static final String MAIN_METHOD = "main";

    /** Descriptor of {@code void main(String[])}, compared against the parsed class as it is stored. */
    private static final String MAIN_DESCRIPTOR = "([Ljava/lang/String;)V";

    /** The method of {@link Entry} the generated class implements. */
    private static final String RUN_METHOD = "run";

    /** The method of the launcher the static initialiser calls. */
    private static final String REGISTER_METHOD = "register";

    private static final ClassDesc STUB_TYPE = ClassDesc.of(STUB_CLASS);
    private static final ClassDesc ENTRY_TYPE = ClassDesc.of(Entry.class.getName());
    private static final ClassDesc LAUNCHER_TYPE = ClassDesc.of(IndexFormat.LAUNCHER_CLASS);
    private static final MethodTypeDesc MAIN_TYPE =
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType());
    private static final MethodTypeDesc REGISTER_TYPE =
            MethodTypeDesc.of(ConstantDescs.CD_void, ENTRY_TYPE);

    private static final Consumer<CodeBuilder> CONSTRUCTOR_BODY = new ConstructorBody();
    private static final Consumer<CodeBuilder> STATIC_INITIALISER_BODY = new StaticInitialiserBody();

    private EntryStubGenerator() {
    }

    /**
     * Generates the entry stub for an application.
     *
     * <p>The caller is expected to have found {@link #ineligibilityReason(String, byte[])} to be
     * {@code null} first; this method assumes the main class is one the generated code may call.</p>
     *
     * @param mainClass the binary name of the application main class, for example
     *                  {@code com.example.Application}
     * @return the class file of {@value #STUB_CLASS}
     * @throws IllegalArgumentException if the name is not a legal binary class name
     * @throws NullPointerException     if the name is {@code null}
     */
    public static byte[] generate(String mainClass) {
        Objects.requireNonNull(mainClass, "mainClass");
        return ClassFile.of().build(STUB_TYPE, new StubClass(ClassDesc.of(mainClass)));
    }

    /**
     * Decides whether an application can be entered through a generated stub, by parsing its main class.
     *
     * <p>Eligible means: the class file really is the class it was looked up as, it is a public class (not
     * an interface, not abstract), it is not in the default package, and it <em>declares itself</em> - not
     * inherits - a {@code public static void main(String[])}. That is exactly the shape for which an
     * {@code invokestatic} behaves like the main method selection of the {@code java} launcher, which the
     * runner's reflective fallback reproduces: the fallback prefers the {@code String[]} form over the
     * no-argument form throughout the hierarchy and accepts instance methods, so any other shape has to
     * keep using it.</p>
     *
     * @param mainClass the binary name of the application main class
     * @param classFile the bytes of that class, as they are packaged; they are parsed, never loaded
     * @return {@code null} when a stub can be generated, otherwise why it cannot, phrased to be read after
     *         "no entry stub was generated because ..."
     * @throws NullPointerException if an argument is {@code null}
     */
    public static String ineligibilityReason(String mainClass, byte[] classFile) {
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(classFile, "classFile");
        if (mainClass.indexOf('.') < 0) {
            return mainClass + " is in the default package, which a class in "
                    + IndexFormat.GENERATED_PACKAGE + " cannot refer to";
        }
        try {
            // The generator has to be able to name the class in a constant pool. A name it cannot express
            // reaches here as a mistyped plugin setting, and has to come back as a reason like any other
            // rather than as an exception out of the middle of packaging.
            ClassDesc.of(mainClass);
        } catch (IllegalArgumentException e) {
            return mainClass + " is not a name the generated code can refer to: " + e.getMessage();
        }
        ClassModel model;
        try {
            model = ClassFile.of().parse(classFile);
        } catch (IllegalArgumentException e) {
            return "the class file of " + mainClass + " cannot be parsed: " + e.getMessage();
        }
        String declared = model.thisClass().asInternalName().replace('/', '.');
        if (!mainClass.equals(declared)) {
            return "the class file packaged as " + mainClass + " declares itself to be " + declared;
        }
        if (model.flags().has(AccessFlag.INTERFACE)) {
            return mainClass + " is an interface";
        }
        if (model.flags().has(AccessFlag.ABSTRACT)) {
            return mainClass + " is abstract";
        }
        if (!model.flags().has(AccessFlag.PUBLIC)) {
            return mainClass + " is not public";
        }
        for (MethodModel method : model.methods()) {
            if (!MAIN_METHOD.equals(method.methodName().stringValue())
                    || !MAIN_DESCRIPTOR.equals(method.methodType().stringValue())) {
                continue;
            }
            if (!method.flags().has(AccessFlag.PUBLIC)) {
                return mainClass + " declares main(String[]) but it is not public";
            }
            if (!method.flags().has(AccessFlag.STATIC)) {
                return mainClass + " declares main(String[]) but it is not static";
            }
            return null;
        }
        return mainClass + " does not declare its own public static void main(String[])";
    }

    /**
     * The generated class: its version, its flags, its interface and its three methods.
     */
    private static final class StubClass implements Consumer<ClassBuilder> {

        private final ClassDesc application;

        private StubClass(ClassDesc application) {
            this.application = application;
        }

        @Override
        public void accept(ClassBuilder builder) {
            // Explicitly, and not ClassFile.latestMajorVersion(): see the class documentation.
            builder.withVersion(CLASS_FILE_MAJOR_VERSION, CLASS_FILE_MINOR_VERSION);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withInterfaceSymbols(ENTRY_TYPE);
            builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void,
                    ClassFile.ACC_PUBLIC, CONSTRUCTOR_BODY);
            builder.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void,
                    ClassFile.ACC_STATIC, STATIC_INITIALISER_BODY);
            builder.withMethod(RUN_METHOD, MAIN_TYPE, ClassFile.ACC_PUBLIC, new RunMethod(application));
        }
    }

    /**
     * {@code public AppEntry() { super(); }}, which the static initialiser needs and nothing else calls.
     */
    private static final class ConstructorBody implements Consumer<CodeBuilder> {

        @Override
        public void accept(CodeBuilder code) {
            code.aload(0)
                    .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                    .return_();
        }
    }

    /**
     * {@code static { Launcher.register(new AppEntry()); }}, which runs when the launcher initialises the
     * class and saves it looking a constructor up.
     */
    private static final class StaticInitialiserBody implements Consumer<CodeBuilder> {

        @Override
        public void accept(CodeBuilder code) {
            code.new_(STUB_TYPE)
                    .dup()
                    .invokespecial(STUB_TYPE, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                    .invokestatic(LAUNCHER_TYPE, REGISTER_METHOD, REGISTER_TYPE)
                    .return_();
        }
    }

    /**
     * {@code public void run(String[] args) throws Throwable}, declaring {@code Throwable} so that the
     * application's own exceptions reach the launcher unchanged.
     */
    private static final class RunMethod implements Consumer<MethodBuilder> {

        private final ClassDesc application;

        private RunMethod(ClassDesc application) {
            this.application = application;
        }

        @Override
        public void accept(MethodBuilder builder) {
            builder.with(ExceptionsAttribute.ofSymbols(ConstantDescs.CD_Throwable));
            builder.withCode(new RunBody(application));
        }
    }

    /**
     * {@code Application.main(args);}, the one call the whole stub exists for.
     */
    private static final class RunBody implements Consumer<CodeBuilder> {

        private final ClassDesc application;

        private RunBody(ClassDesc application) {
            this.application = application;
        }

        @Override
        public void accept(CodeBuilder code) {
            code.aload(1)
                    .invokestatic(application, MAIN_METHOD, MAIN_TYPE)
                    .return_();
        }
    }
}
