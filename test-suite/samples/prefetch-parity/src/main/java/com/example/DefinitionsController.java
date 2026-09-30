package com.example;

import io.micronaut.context.BeanContext;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/** Answers with the bean definitions of the running application. */
@Controller("/definitions")
public class DefinitionsController {

    private final BeanContext context;

    /**
     * Creates the controller.
     *
     * @param context the context the application runs in
     */
    public DefinitionsController(BeanContext context) {
        this.context = context;
    }

    /**
     * Lists the bean definitions.
     *
     * @return the class name of every bean definition, sorted, one per line
     */
    @Get(produces = "text/plain")
    public String definitions() {
        return Definitions.names(context);
    }
}
