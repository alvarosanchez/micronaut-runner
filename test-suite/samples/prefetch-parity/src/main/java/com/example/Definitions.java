package com.example;

import io.micronaut.context.BeanContext;

import java.util.stream.Collectors;

/** Names the bean definitions a context ended up with, so that two starts can be compared. */
public final class Definitions {

    /** Printed before the names when they go to standard output. */
    public static final String BEGIN = "DEFINITIONS-BEGIN";

    /** Printed after the names when they go to standard output. */
    public static final String END = "DEFINITIONS-END";

    private Definitions() {
    }

    /**
     * Lists the bean definitions of a context.
     *
     * @param context the context
     * @return the class name of every bean definition, sorted, one per line
     */
    public static String names(BeanContext context) {
        return context.getAllBeanDefinitions().stream()
                .map(definition -> definition.getClass().getName())
                .sorted()
                .collect(Collectors.joining("\n"));
    }
}
