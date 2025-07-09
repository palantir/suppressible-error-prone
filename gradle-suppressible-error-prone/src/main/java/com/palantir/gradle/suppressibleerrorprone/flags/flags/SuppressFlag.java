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

package com.palantir.gradle.suppressibleerrorprone.flags.flags;

import com.palantir.gradle.suppressibleerrorprone.SuppressibleErrorProneExtension;
import com.palantir.gradle.suppressibleerrorprone.flags.common.Flag;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagName;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagOptions;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.ltgt.gradle.errorprone.CheckSeverity;
import net.ltgt.gradle.errorprone.ErrorProneOptions;
import org.gradle.api.tasks.compile.JavaCompile;

public final class SuppressFlag implements Flag {
    @Override
    public FlagName name() {
        return FlagName.SUPPRESS;
    }

    @Override
    public FlagOptions options(Optional<String> flagValue) {
        return new FlagOptions() {
            @Override
            public boolean modifyErrorProneCheckApi() {
                return true;
            }
        };
    }

    private static List<String> checksToApplySuggestedPatchesFor(
            Optional<String> flagValue,
            SuppressibleErrorProneExtension extension,
            JavaCompile javaCompile,
            ErrorProneOptions errorProneOptions) {

        boolean hasSpecificPatchChecks =
                flagValue.isPresent() && !flagValue.get().isBlank();

        if (hasSpecificPatchChecks) {
            return Arrays.stream(flagValue.get().split(","))
                    .map(String::trim)
                    .filter(Predicate.not(String::isEmpty))
                    .toList();
        }

        return extension.patchChecksForCompilation(javaCompile).stream()
                // Do not patch checks that have been explicitly disabled
                .filter(check -> errorProneOptions.getChecks().getting(check).getOrNull() != CheckSeverity.OFF)
                // Sorted so that we maintain arg ordering and continue to get cache hits
                .sorted()
                .collect(Collectors.toList());
    }
}
