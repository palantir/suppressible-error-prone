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

public final class DisableMode implements Mode {

    @Override
    public ModeOptions options(FlagOptionContext context) {
        // Options:
        //   -PerrorProneDisable
        //   -Pcom.palantir.baseline-error-prone.disable
        //   -Pcom.palantir.baseline-error-prone.disable=true
        //   -Pcom.palantir.baseline-error-prone.disable=false
        // So if the value is true, we actually set errorProneOptions.enabled to false

        context.errorProneOptions()
                .getEnabled()
                .set(context.flagValue()
                        .map(value -> !Boolean.parseBoolean(value))
                        .orElse(false));

        return ModeOptions.none();
    }
}
