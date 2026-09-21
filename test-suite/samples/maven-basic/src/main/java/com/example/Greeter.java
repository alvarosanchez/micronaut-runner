package com.example;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * A bean, so the sample exercises bean discovery - the annotation processor's generated definitions and
 * the {@code META-INF/micronaut} service metadata inside a nested jar - rather than just class loading.
 */
@Singleton
public class Greeter {

    /**
     * Builds the greeting from a classpath resource, so resource loading out of the application layer is
     * exercised too, and names the class loader that is actually installed.
     *
     * @return the greeting
     */
    public String greet() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("greeting.txt")) {
            if (in == null) {
                throw new IllegalStateException("greeting.txt was not found on the class path");
            }
            String greeting = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return greeting + " from " + getClass().getClassLoader().getClass().getSimpleName();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
