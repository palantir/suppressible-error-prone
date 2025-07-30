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

package com.palantir.gradle.suppressibleerrorprone.modes.modes;

import com.palantir.gradle.suppressibleerrorprone.modes.common.CommonModeOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.CommonModeOptions.DontCare;
import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode;
import java.nio.file.Path;
import java.util.List;
import org.gradle.process.CommandLineArgumentProvider;

public final class TimingsMode implements Mode {

    @Override
    public CommonModeOptions commonOptions(ModeOptionContext context) {
        // We can't control the working directory of the java compile task, as it actually runs inside some gradle
        // worker. So we can't pass a relative path to the javac plugin; it has to be absolute. When we pass
        // an absolute path, build caching no longer works between machines as the java compiler option args
        // are different on each machine. So we can't have this on all the time, otherwise local/CI build would
        // not cache from (other) CI builds. It's ok when hidden behind a flag, as then you don't generally don't
        // even want build caching if you're measuring timings. But unfortunately we can't print out timings
        // all the time.
        Path outputAbsolute = context.projectLayout()
                .getBuildDirectory()
                .file("errorprone-timings/" + context.javaCompile().getName())
                .get()
                .getAsFile()
                .toPath();

        context.javaCompile().getOutputs().file(outputAbsolute.toFile());

        context.javaCompile().getOptions().getCompilerArgumentProviders().add(new CommandLineArgumentProvider() {
            @Override
            public Iterable<String> asArguments() {
                return List.of("-Xplugin:SuppressibleErrorProneTimings " + outputAbsolute);
            }
        });

        return DontCare.INSTANCE;
    }
}
