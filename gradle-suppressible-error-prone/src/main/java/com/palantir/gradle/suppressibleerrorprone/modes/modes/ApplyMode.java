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

import com.google.common.base.Splitter;
import com.palantir.gradle.suppressibleerrorprone.modes.common.CommonOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode;
import com.palantir.gradle.suppressibleerrorprone.modes.common.PatchChecksOption;
import java.util.Set;
import java.util.stream.Collectors;

public final class ApplyMode implements Mode {
    @Override
    public CommonOptions configureAndReturnCommonOptions(ModeOptionContext context) {
        return new CommonOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return PatchChecksOption.someChecks(() -> checksToApplySuggestedPatchesFor(context));
            }
        };
    }

    private static Set<String> checksToApplySuggestedPatchesFor(ModeOptionContext context) {
        boolean hasSpecificPatchChecks =
                context.flagValue().isPresent() && !context.flagValue().get().isBlank();

        if (hasSpecificPatchChecks) {
            return Splitter.on(',')
                    .omitEmptyStrings()
                    .splitToStream(context.flagValue().get())
                    .map(String::trim)
                    .collect(Collectors.toSet());
        }

        return context.extension().patchChecksForCompilation(context.javaCompile());
    }
}
