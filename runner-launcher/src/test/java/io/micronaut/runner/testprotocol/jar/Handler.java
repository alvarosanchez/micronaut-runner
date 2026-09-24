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
package io.micronaut.runner.testprotocol.jar;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * A {@code jar:} handler that a package listed in {@code java.protocol.handler.pkgs} ahead of Runner's
 * contributes, and that never opens anything. The forked handler test starts a JVM with this package in the
 * property to check that registration still verifies the string-parse path in that case and warns.
 */
public class Handler extends URLStreamHandler {

    /** The JDK instantiates this reflectively, so it needs a public no-argument constructor. */
    public Handler() {
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        throw new IOException("The test protocol package never opens " + url);
    }
}
