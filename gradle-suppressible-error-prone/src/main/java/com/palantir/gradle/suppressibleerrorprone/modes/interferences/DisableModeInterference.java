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

package com.palantir.gradle.suppressibleerrorprone.modes.interferences;

import com.palantir.gradle.suppressibleerrorprone.modes.common.Flag;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeOptions;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class DisableModeInterference implements ModeInterference {
    @Override
    public Set<Flag> interferesWith(Set<Flag> flags) {
        if (flags.contains(Flag.DISABLE) && flags.size() > 1) {
            return flags;
        }

        return Set.of();
    }

    @Override
    public ModeOptions interfere(Map<Flag, ModeOptions> flagOptions) {
        throw new IllegalStateException("%s cannot be used at the same time as any of %s"
                .formatted(
                        Flag.DISABLE.asGradlePropertyArgument(),
                        flagOptions.keySet().stream()
                                .filter(Predicate.not(Predicate.isEqual(Flag.DISABLE)))
                                .map(Flag::asGradlePropertyArgument)
                                .collect(Collectors.joining(", "))));
    }
}
