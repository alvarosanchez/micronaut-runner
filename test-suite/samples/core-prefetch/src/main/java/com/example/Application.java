package com.example;

import io.micronaut.runtime.Micronaut;

/** Entry point of the sample application. */
public final class Application {

    private Application() {
    }

    /**
     * Starts the server through {@code Micronaut.run}, which micronaut-core's bean definition prefetch serves.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
