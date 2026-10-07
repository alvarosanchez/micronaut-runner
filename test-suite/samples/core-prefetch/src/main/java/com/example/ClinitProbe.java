package com.example;

/**
 * A class registered as a bean definition reference, by an entry of its own under
 * {@code META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/}, whose static initialiser always fails.
 *
 * <p>How it fails is chosen by the {@code PROBE_CLINIT} environment variable, and Micronaut treats the three ways
 * differently: {@code runtime} and {@code linkage} stop the application, and anything else is a missing class,
 * which Micronaut skips. It never gets as far as being used as a bean definition reference.</p>
 */
public class ClinitProbe {

    static {
        fail(System.getenv("PROBE_CLINIT"));
    }

    /** Micronaut instantiates a reference through its public no-argument constructor. */
    public ClinitProbe() {
    }

    private static void fail(String mode) {
        if ("runtime".equals(mode)) {
            throw new IllegalStateException("ClinitProbe failed at runtime");
        }
        if ("linkage".equals(mode)) {
            throw new NoSuchFieldError("ClinitProbe.missing");
        }
        throw new NoClassDefFoundError("com/example/Missing");
    }
}
