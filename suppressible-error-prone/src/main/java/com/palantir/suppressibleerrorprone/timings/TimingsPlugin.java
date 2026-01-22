/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.suppressibleerrorprone.timings;

import com.google.auto.service.AutoService;
import com.google.errorprone.ErrorProneTimings;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@AutoService(Plugin.class)
public final class TimingsPlugin implements Plugin {
    @Override
    public String getName() {
        return "SuppressibleErrorProneTimings";
    }

    @Override
    public void init(JavacTask task, String... args) {
        // Join all args back together since javac splits by spaces and paths may contain spaces.
        // e.g., "/path/with spaces/file" becomes args = ["/path/with", "spaces/file"]
        String path = String.join(" ", args);
        task.addTaskListener(new TimingsTaskListener(
                ErrorProneTimings.instance(((BasicJavacTask) task).getContext()), Path.of(path)));
    }

    private static class TimingsTaskListener implements TaskListener {
        private final ErrorProneTimings errorProneTimings;
        private final Path output;
        private final Set<URI> files = new HashSet<>();

        TimingsTaskListener(ErrorProneTimings errorProneTimings, Path output) {
            this.errorProneTimings = errorProneTimings;
            this.output = output;
        }

        @Override
        public void finished(TaskEvent event) {
            if (event.getKind().equals(Kind.ENTER)) {
                Optional.ofNullable(event.getSourceFile()).ifPresent(sourceFile -> {
                    files.add(sourceFile.toUri());
                });
            }

            if (!event.getKind().equals(Kind.COMPILATION)) {
                return;
            }

            Map<String, Duration> timings = errorProneTimings.timings();

            if (timings.isEmpty()) {
                writeOutput("No errorprones ran");
                return;
            }

            int maxNameLength =
                    timings.keySet().stream().mapToInt(String::length).max().getAsInt();

            int maxTimeLength = timings.values().stream()
                    .map(TimingsTaskListener::humanReadable)
                    .mapToInt(String::length)
                    .max()
                    .getAsInt();

            Duration totalTime = timings.values().stream().reduce(Duration.ZERO, Duration::plus);
            String totalTimeString = "Total errorprone time: " + humanReadable(totalTime);

            String perCheckOutput = timings.entrySet().stream()
                    .sorted(Entry.<String, Duration>comparingByValue().reversed())
                    .map(entry -> String.format(
                            "%-" + maxNameLength + "s: %-" + maxTimeLength + "s (%.2f%%)",
                            entry.getKey(),
                            humanReadable(entry.getValue()),
                            entry.getValue().toNanos() / ((double) totalTime.toNanos()) * 100))
                    .collect(Collectors.joining("\n"));

            writeOutput(totalTimeString + "\n\n"
                    + "ErrorProneName: <time taken> (percentage of all errorprone time)\n\n" + perCheckOutput);
        }

        private void writeOutput(String string) {
            try {
                Files.writeString(output, string);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String humanReadable(Duration duration) {
            if (duration.toMillis() == 0) {
                return duration.toNanos() / 1_000 + " micros";
            }
            if (duration.toSeconds() == 0) {
                return duration.toMillis() + " millis";
            }

            return String.format("%-2f seconds", duration.toMillis() / 1_000d);
        }
    }
}
