/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.suppressibleerrorprone;

import com.google.errorprone.bugpatterns.BugChecker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.stream.Collectors;

public final class BugCheckerCollector {
    public static void main(String... args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Exactly one argument expected - the output location");
        }

        Path outputLocation = Path.of(args[0]);

        String output = ServiceLoader.load(BugChecker.class).stream()
                .map(Provider::get)
                .map(bugChecker -> bugChecker.getClass().getCanonicalName() + "," + bugChecker.canonicalName())
                .collect(Collectors.joining("\n"));

        try {
            Files.writeString(outputLocation, output);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write output to " + outputLocation, e);
        }
    }

    private BugCheckerCollector() {}
}
