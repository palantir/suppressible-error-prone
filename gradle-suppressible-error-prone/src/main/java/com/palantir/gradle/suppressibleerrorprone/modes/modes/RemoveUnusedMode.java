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

import com.palantir.gradle.suppressibleerrorprone.modes.common.CommonOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption;
import com.palantir.gradle.suppressibleerrorprone.modes.common.PatchChecksOption;
import com.palantir.gradle.suppressibleerrorprone.modes.common.RemoveUnusedCheck;
import java.util.Map;

public class RemoveUnusedMode implements Mode {
    private static final String ALL_CHECKS = "";

    public ModifyCheckApiOption modifyCheckApi() {
        return ModifyCheckApiOption.mustModifyIncludingVisitorState();
    }

    @Override
    public CommonOptions configureAndReturnCommonOptions(ModeOptionContext context) {
        return new CommonOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return PatchChecksOption.someChecks("RemoveUnusedSuppressions");
            }

            @Override
            public Map<String, String> extraErrorProneCheckOptions() {
                // For the suppressions to remove, if no specific check is enabled, we need to just remove everything
                // We can't explicitly list all possible checks, because some might not exist anymore
                // The logic itself needs to consider an empty list as "remove all"

                return Map.of(
                        "SuppressibleErrorProne:RemoveUnusedSuppressions",
                        context.flagValue().orElse(ALL_CHECKS));
            }

            @Override
            public RemoveUnusedCheck removeUnusedCheck() {
                return RemoveUnusedCheck.ENABLED;
            }
        };
    }
}
