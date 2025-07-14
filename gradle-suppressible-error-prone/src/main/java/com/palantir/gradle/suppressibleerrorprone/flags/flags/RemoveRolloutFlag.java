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
import com.palantir.gradle.suppressibleerrorprone.flags.common.ModifyCheckApiOption;
import com.palantir.gradle.suppressibleerrorprone.flags.common.PatchChecksOption;
import java.util.Map;

public final class RemoveRolloutFlag implements Flag {
    @Override
    public ModifyCheckApiOption modifyCheckApi() {
        return ModifyCheckApiOption.doNotModify();
    }

    @Override
    public FlagOptions options(FlagOptionContext context) {
        return new FlagOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return PatchChecksOption.someChecks("RemoveRolloutSuppressions");
            }

            @Override
            public Map<String, String> extraFlags() {
                // For the suppressions to remove, if no specific check is enabled, we need to just remove everything
                // We can't explicitly list all possible checks, because some might not exist anymore
                // The logic itself needs to consider an empty list as "remove all"

                return Map.of("RemoveRolloutSuppressions", context.flagValue().orElse(""));
            }
        };
    }
}
