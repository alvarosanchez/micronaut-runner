package com.example;

import io.micronaut.runtime.Micronaut;

/** Entry point of the representative benchmark application. */
public final class Application {
    private Application() {
    }

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
