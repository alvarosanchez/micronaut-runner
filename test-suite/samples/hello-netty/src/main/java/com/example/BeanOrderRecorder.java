/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example;

import io.micronaut.context.BeanContext;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the order in which the bean definitions were registered, when the end-to-end suite asks for it
 * with {@code -Drunner.test.bean-order=<file>}. The suite compares the file of a launch that reads the
 * static service table with the file of a launch that scans. Without the property this bean does nothing.
 */
@Singleton
public class BeanOrderRecorder implements ApplicationEventListener<StartupEvent> {

    private final BeanContext context;

    /**
     * Creates the recorder.
     *
     * @param context the context whose definitions are listed
     */
    public BeanOrderRecorder(BeanContext context) {
        this.context = context;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        String file = System.getProperty("runner.test.bean-order");
        if (file == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (BeanDefinition<?> definition : context.getAllBeanDefinitions()) {
            names.add(definition.getName());
        }
        try {
            Files.write(Path.of(file), names, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
