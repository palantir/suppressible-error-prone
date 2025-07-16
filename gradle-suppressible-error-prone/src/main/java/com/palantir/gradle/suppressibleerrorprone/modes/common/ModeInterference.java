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
 * Flags can interfere with each other, for example, removing and suppressing are mutually exclusive.
 * Each of these provide a way to indicate which flags interfere with each other and modify the {@link ModeOptions}
 * they produce.
 */
public interface ModeInterference {
    /**
     * Identify which of the flags interfere with each other.
     * @param modeNames The flags that are enabled in this run
     * @return Either the flags that interfere with each other, or throw an exception if they are incompatible
     */
    Set<ModeName> interferesWith(Set<ModeName> modeNames);

    /**
     * Modify the FlagOptions if the flags interfere with each other.
     * @param flagOptions A map from the {@link ModeName}s that were indicated to be interfering in
     *                    {@link #interferesWith} to their corresponding {@link ModeOptions}.
     * @return The modified {@link ModeOptions} that will be used in place of the originals that the flags produced
     */
    default ModeOptions interfere(Map<ModeName, ModeOptions> flagOptions) {
        throw new UnsupportedOperationException("interfere is not implemented");
    }
}
