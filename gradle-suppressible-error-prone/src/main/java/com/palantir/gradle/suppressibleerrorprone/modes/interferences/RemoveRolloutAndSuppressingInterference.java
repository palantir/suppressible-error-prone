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

import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterferenceResult;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeName;
import java.util.Set;

/**
 * Removing and suppressing cannot run at the same time, as removing just runs an errorprone to remove the for-rollout:
 * suppressions - if suppressing happened at the same time, this errorprone would just get suppressed.
 */
public final class RemoveRolloutAndSuppressingInterference implements ModeInterference {
    @Override
    public ModeInterferenceResult interferesWith(Set<ModeName> modeNames) {
        if (modeNames.containsAll(Set.of(ModeName.REMOVE_ROLLOUT, ModeName.SUPPRESS))) {
            return ModeInterferenceResult.notCompatible("%s cannot be used at the same time as %s"
                    .formatted(
                            ModeName.REMOVE_ROLLOUT.asGradlePropertyArgument(),
                            ModeName.SUPPRESS.asGradlePropertyArgument()));
        }

        return ModeInterferenceResult.noInterference();
    }
}
