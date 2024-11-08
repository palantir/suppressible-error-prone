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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.auto.service.AutoService;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
                SuppressibleErrorProneTimings.instance(((BasicJavacTask) task).getContext()), Path.of(path)));
    }

    private class TimingsTaskListener implements TaskListener {
        private final SuppressibleErrorProneTimings suppressibleErrorProneTimings;
        private final Path output;

        TimingsTaskListener(SuppressibleErrorProneTimings suppressibleErrorProneTimings, Path output) {
            this.suppressibleErrorProneTimings = suppressibleErrorProneTimings;
            this.output = output;
        }

        @Override
        public void finished(TaskEvent event) {
            if (!event.getKind().equals(Kind.COMPILATION)) {
                return;
            }

            Map<URI, Map<String, Duration>> timings = suppressibleErrorProneTimings.timings();

            try {
                Files.writeString(
                        output,
                        new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(timings));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
