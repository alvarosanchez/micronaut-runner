package com.example;

import io.micronaut.context.BeanContext;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.stream.Collectors;

/** Answers with the bean definitions of the running application, so that two starts can be compared. */
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
        return context.getAllBeanDefinitions().stream()
                .map(definition -> definition.getClass().getName())
                .sorted()
                .collect(Collectors.joining("\n"));
    }
}
