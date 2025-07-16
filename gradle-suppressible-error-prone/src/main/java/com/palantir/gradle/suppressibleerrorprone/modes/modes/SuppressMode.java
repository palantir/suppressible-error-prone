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

import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption;
import com.palantir.gradle.suppressibleerrorprone.modes.common.PatchChecksOption;

public final class SuppressMode implements Mode {
    @Override
    public ModifyCheckApiOption modifyCheckApi() {
        return ModifyCheckApiOption.mustModifyIncludingVisitorState();
    }

    @Override
    public ModeOptions options(ModeOptionContext context) {
        return new ModeOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return PatchChecksOption.allChecks();
            }
        };
    }
}
