package com.example;

import com.example.metadata.MainManifestMetadata;
import io.micronaut.context.ApplicationContext;

/**
 * Entry point of the sample. It starts a real application context, resolves a bean from it and prints one
 * line the end-to-end suite matches on, then exits.
 *
 * <p>There is no HTTP server here on purpose: the Gradle sample already covers a server, and this one is
 * about the Maven plugin producing an archive that actually runs. Printing and exiting keeps it fast and
 * removes the port and the readiness poll from the picture.</p>
 */
public final class Application {

    /** The prefix the end-to-end suite looks for on standard output. */
    public static final String MARKER = "RUNNER OK: ";

    private Application() {
    }

    /**
     * Starts the application.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try (ApplicationContext context = ApplicationContext.run()) {
            System.out.println(MARKER + context.getBean(Greeter.class).greet());
            System.out.println("RUNNER MANIFEST: main="
                    + MainManifestMetadata.class.getPackage().getImplementationVersion()
                    + ", package=" + Application.class.getPackage().getImplementationVersion());
        }
    }
}
