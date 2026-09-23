package com.example;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;

/** Normal-termination endpoint enabled only for the repository's CDS training lifecycle. */
@Requires(env = "cds-training")
@Controller("/cds-training")
public final class CdsTrainingController {

    private final ApplicationContext applicationContext;

    /**
     * Creates the training-only controller.
     *
     * @param applicationContext the context to stop after the response has been sent
     */
    public CdsTrainingController(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Returns successfully, then stops the application so cache generation runs on a normal JVM exit.
     *
     * @return the termination acknowledgement
     */
    @Post(uri = "/stop", produces = "text/plain")
    public String stop() {
        Thread.ofPlatform().daemon().name("cds-training-stop").start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            applicationContext.stop();
        });
        return "stopping";
    }
}