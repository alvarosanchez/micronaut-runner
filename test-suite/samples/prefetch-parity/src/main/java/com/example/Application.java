package com.example;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

/** Entry point of the sample application. */
public final class Application {

    private Application() {
    }

    /**
     * Starts the server, or, with {@code PROBE_ENTRY=context}, builds a context without one, prints its bean
     * definitions and exits: the second way through {@code ApplicationContextBuilder} an application may take.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        if ("context".equals(System.getenv("PROBE_ENTRY"))) {
            try (ApplicationContext context = ApplicationContext.run()) {
                System.out.println(Definitions.BEGIN);
                System.out.println(Definitions.names(context));
                System.out.println(Definitions.END);
            }
            return;
        }
        Micronaut.run(Application.class, args);
    }
}
