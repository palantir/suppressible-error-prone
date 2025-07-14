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

import com.palantir.gradle.suppressibleerrorprone.flags.common.Flag;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagOptions;
import com.palantir.gradle.suppressibleerrorprone.flags.common.PatchChecksOption;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.ltgt.gradle.errorprone.CheckSeverity;

public final class ApplyFlag implements Flag {
    @Override
    public FlagOptions options(FlagOptionContext context) {
        return new FlagOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return PatchChecksOption.someChecks(checksToApplySuggestedPatchesFor(context));
            }
        };
    }

    private static Set<String> checksToApplySuggestedPatchesFor(FlagOptionContext context) {
        boolean hasSpecificPatchChecks =
                context.flagValue().isPresent() && !context.flagValue().get().isBlank();

        if (hasSpecificPatchChecks) {
            return Arrays.stream(context.flagValue().get().split(","))
                    .map(String::trim)
                    .filter(Predicate.not(String::isEmpty))
                    .collect(Collectors.toSet());
        }

        return context.extension().patchChecksForCompilation(context.javaCompile()).stream()
                // Do not patch checks that have been explicitly disabled
                .filter(check ->
                        context.errorProneOptions().getChecks().getting(check).getOrNull() != CheckSeverity.OFF)
                .collect(Collectors.toSet());
    }
}
