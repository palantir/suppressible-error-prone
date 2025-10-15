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

public final class RemoveUnusedAndSuppressInterference extends SpecificModeInterference {
    @Override
    protected Set<ModeName> interferingModes() {
        return Set.of(ModeName.REMOVE_UNUSED, ModeName.SUPPRESS);
    }

    @Override
    public CommonOptions interfere(Map<ModeName, CommonOptions> modeCommonOptions) {
        CommonOptions removeUnused = modeCommonOptions.get(ModeName.REMOVE_UNUSED);
        CommonOptions suppress = modeCommonOptions.get(ModeName.SUPPRESS);
        return removeUnused.naivelyCombinedWith(suppress);
    }
}
