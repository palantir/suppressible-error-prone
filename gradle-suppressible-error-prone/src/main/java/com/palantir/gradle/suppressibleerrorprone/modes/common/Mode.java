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

package com.palantir.gradle.suppressibleerrorprone.modes.common;

import com.palantir.gradle.suppressibleerrorprone.SuppressibleErrorProneExtension;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeOptions.None;
import java.util.Optional;
import net.ltgt.gradle.errorprone.ErrorProneOptions;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * A Mode represents a mode of operation in suppressible-error-prone that set by a feature {@link ModeName}.
 */
public interface Mode {
    default ModifyCheckApiOption modifyCheckApi() {
        return ModifyCheckApiOption.dontCare();
    }

    default ModeOptions options(ModeOptionContext context) {
        return None.INSTANCE;
    }

    record ModeOptionContext(Optional<String> flagValue, JavaCompile javaCompile) {
        public ProjectLayout projectLayout() {
            return javaCompile.getProject().getLayout();
        }

        public SuppressibleErrorProneExtension extension() {
            return javaCompile.getProject().getExtensions().getByType(SuppressibleErrorProneExtension.class);
        }

        public ErrorProneOptions errorProneOptions() {
            return ((ExtensionAware) javaCompile.getOptions()).getExtensions().getByType(ErrorProneOptions.class);
        }
    }
}
