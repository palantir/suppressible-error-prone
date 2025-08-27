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

import com.palantir.gradle.suppressibleerrorprone.modes.common.CommonOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeName;
import com.palantir.gradle.suppressibleerrorprone.modes.common.SpecificModeInterference;
import java.util.Map;
import java.util.Set;

public final class RemoveUnnecessarySuppressionsInterference extends SpecificModeInterference {
    @Override
    protected Set<ModeName> interferingModes() {
        return Set.of(ModeName.REMOVE_UNNECESSARY_SUPPRESSIONS, ModeName.SUPPRESS);
    }

    @Override
    public CommonOptions interfere(Map<ModeName, CommonOptions> modeCommonOptions) {
        CommonOptions removeUnnecessary = modeCommonOptions.get(ModeName.REMOVE_UNNECESSARY_SUPPRESSIONS);
        CommonOptions suppress = modeCommonOptions.get(ModeName.SUPPRESS);

        // When removing unnecessary suppressions and suppressing at the same time, we need to tell
        // errorprone to track which checks are actually encountered so we can remove unnecessary
        // suppressions while adding new ones. We add a flag to enable this behavior.

        return removeUnnecessary.naivelyCombinedWith(suppress)
                .withExtraErrorProneCheckFlag("SuppressibleErrorProne:RemoveUnnecessarySuppressions", "true");
    }
}