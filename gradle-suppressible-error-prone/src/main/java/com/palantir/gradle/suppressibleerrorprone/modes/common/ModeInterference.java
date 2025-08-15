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
     * @return Either no interference, the flags that interfere with each other, or why they are incompatible
     */
    ModeInterferenceResult interferesWith(Set<ModeName> modeNames);

    /**
     * Modify the modeCommonOptions if the flags interfere with each other.
     * @param modeCommonOptions A map from the {@link ModeName}s that were indicated to be interfering in
     *                    {@link #interferesWith} to their corresponding {@link CommonOptions}.
     * @return The modified {@link CommonOptions} that will be used in place of the originals that the flags produced
     */
    default CommonOptions interfere(Map<ModeName, CommonOptions> modeCommonOptions) {
        throw new IllegalStateException(("The interference for this class '%s' has not been implemented. "
                        + "This is a logic error by the class author as either `interferesWith` should return"
                        + "not compatible or this method should be implemented.")
                .formatted(getClass().getCanonicalName()));
    }
}
