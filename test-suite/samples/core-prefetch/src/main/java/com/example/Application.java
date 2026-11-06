package com.example;

import io.micronaut.runtime.Micronaut;

/** Entry point of the sample application. */
public final class Application {

    private Application() {
    }

    /**
     * Starts the server, with micronaut-core's bean definition prefetch when the {@code PREFETCH} environment
     * variable is {@code true}.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        Micronaut.build(args)
                .mainClass(Application.class)
                .beanDefinitionPrefetchEnabled(Boolean.parseBoolean(System.getenv("PREFETCH")))
                .start();
    }
}
