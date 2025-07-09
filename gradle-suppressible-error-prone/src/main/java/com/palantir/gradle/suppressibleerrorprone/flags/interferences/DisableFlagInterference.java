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

package com.palantir.gradle.suppressibleerrorprone.flags.interferences;

import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagInterference;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagName;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagOptions;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class DisableFlagInterference implements FlagInterference {
    @Override
    public Set<FlagName> interferesWith(Set<FlagName> flags) {
        if (flags.contains(FlagName.DISABLE)) {
            return flags;
        }

        return Set.of();
    }

    @Override
    public FlagOptions interfere(Map<FlagName, FlagOptions> flagOptions) {
        throw new IllegalStateException("%s cannot be used at the same time as any of %s"
                .formatted(
                        FlagName.DISABLE.asGradlePropertyArgument(),
                        flagOptions.keySet().stream()
                                .filter(Predicate.not(Predicate.isEqual(FlagName.DISABLE)))
                                .map(FlagName::asGradlePropertyArgument)
                                .collect(Collectors.joining(", "))));
    }
}
