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

package com.palantir.gradle.suppressibleerrorprone.modes.common;

import com.google.common.base.Preconditions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterferenceResult.Interference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterferenceResult.NoInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterferenceResult.NotCompatible;
import java.util.Set;

public sealed interface ModeInterferenceResult permits NoInterference, Interference, NotCompatible {
    static ModeInterferenceResult noInterference() {
        return NoInterference.INSTANCE;
    }

    static ModeInterferenceResult interferenceBetween(Set<ModeName> modes) {
        return new Interference(modes);
    }

    static ModeInterferenceResult notCompatible(String message) {
        return new NotCompatible(message);
    }

    enum NoInterference implements ModeInterferenceResult {
        INSTANCE
    }

    record Interference(Set<ModeName> interferingModes) implements ModeInterferenceResult {
        public Interference {
            Preconditions.checkArgument(interferingModes.size() > 1, "interference must be between at least 2 modes");
        }
    }

    record NotCompatible(String message) implements ModeInterferenceResult {}
}
