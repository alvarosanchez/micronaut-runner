package com.example;

import jakarta.inject.Singleton;

/** A bean, so that the sample exercises the bean discovery path rather than just the class loader. */
@Singleton
public class Greeter {

    /**
     * Builds the greeting, reading a classpath resource so resource loading is exercised too.
     *
     * @return the greeting
     */
    public String greet() {
        return "hello from " + getClass().getClassLoader().getClass().getSimpleName();
    }
}
