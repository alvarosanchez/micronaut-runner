package com.example;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/** A trivial endpoint, used to measure the time from process start to the first successful response. */
@Controller("/")
public class HelloController {

    private final Greeter greeter;

    /**
     * Creates the controller.
     *
     * @param greeter the injected greeter, which proves bean discovery worked
     */
    public HelloController(Greeter greeter) {
        this.greeter = greeter;
    }

    /**
     * Answers a readiness probe.
     *
     * @return the greeting
     */
    @Get(uri = "/hello", produces = "text/plain")
    public String hello() {
        return greeter.greet();
    }
}
