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
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterference.ModeInterferenceResult.Interference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterference.ModeInterferenceResult.NoInterference;
import java.util.Map;
import java.util.Set;

/**
 * {@link Mode}s can interfere with each other, for example, removing and suppressing are mutually exclusive.
 * Each of these provide a way to indicate which {@link ModeName}s interfere with each other and modify
 * the {@link CommonOptions} they produce in order to work properly together.
 */
public interface ModeInterference {
    /**
     * Identify which of the flags interfere with each other.
     *
     * @param modeNames The flags that are enabled in this run
     * @return Either the flags that interfere with each other, or throw an exception if they are incompatible
     */
    ModeInterferenceResult interferesWith(Set<ModeName> modeNames);

    sealed interface ModeInterferenceResult permits NoInterference, Interference {
        static ModeInterferenceResult noInterference() {
            return NoInterference.INSTANCE;
        }

        static ModeInterferenceResult interferenceBetween(Set<ModeName> modes) {
            return new Interference(modes);
        }

        enum NoInterference implements ModeInterferenceResult {
            INSTANCE
        }

        record Interference(Set<ModeName> interferingModes) implements ModeInterferenceResult {
            public Interference {
                Preconditions.checkArgument(
                        interferingModes.size() > 1, "interference must be between at least 2 modes");
            }
        }
    }

    /**
     * Modify the modeOptions if the flags interfere with each other.
     * @param modeOptions A map from the {@link ModeName}s that were indicated to be interfering in
     *                    {@link #interferesWith} to their corresponding {@link CommonOptions}.
     * @return The modified {@link CommonOptions} that will be used in place of the originals that the flags produced
     */
    default CommonOptions interfere(Map<ModeName, CommonOptions> modeOptions) {
        throw new UnsupportedOperationException("interfere is not implemented");
    }
}
