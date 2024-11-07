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

package com.palantir.suppressibleerrorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.ErrorProneTimings;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.stream.Collectors;

@AutoService(Plugin.class)
public final class TimingsPlugin implements Plugin {
    @Override
    public String getName() {
        return "SuppressibleErrorProneTimings";
    }

    @Override
    public void init(JavacTask task, String... args) {
        String path = args[0];
        task.addTaskListener(new TimingsTaskListener(
                ErrorProneTimings.instance(((BasicJavacTask) task).getContext()), Path.of(path)));
    }

    private class TimingsTaskListener implements TaskListener {
        private final ErrorProneTimings errorProneTimings;
        private final Path output;

        TimingsTaskListener(ErrorProneTimings errorProneTimings, Path output) {
            this.errorProneTimings = errorProneTimings;
            this.output = output;
        }

        @Override
        public void finished(TaskEvent event) {
            if (!event.getKind().equals(Kind.COMPILATION)) {
                return;
            }

            String perCheckOutput = errorProneTimings.timings().entrySet().stream()
                    .sorted(Entry.<String, Duration>comparingByValue().reversed())
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining("\n"));

            try {
                Files.writeString(output, perCheckOutput);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
