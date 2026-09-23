package com.example;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;

/** Normal-termination endpoint used only while training CDS/AOT archives. */
@Requires(env = "cds-training")
@Controller("/cds-training")
public final class CdsTrainingController {
    private final ApplicationContext applicationContext;

    public CdsTrainingController(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

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
