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

package com.palantir.gradle.suppressibleerrorprone.flags.common;

import java.util.Map;
import java.util.Set;

/**
 * Flags can interfere with each other, for example, removing and suppressing are mutually exclusive.
 * Each of these provide a way to indicate which flags interfere with each other and modify the {@link FlagOptions}
 * they produce.
 */
public interface FlagInterference {
    /**
     * Identify which of the flags interfere with each other.
     * @param flags The flags that are enabled in this run
     * @return Either the flags that interfere with each other, or throw an exception if they are incompatible
     */
    Set<FlagName> interferesWith(Set<FlagName> flags);

    /**
     * Modify the FlagOptions if the flags interfere with each other.
     * @param flagOptions A map from the {@link FlagName}s that were indicated to be interfering in
     *                    {@link #interferesWith} to their corresponding {@link FlagOptions}.
     * @return The modified {@link FlagOptions} that will be used in place of the originals that the flags produced
     */
    FlagOptions interfere(Map<FlagName, FlagOptions> flagOptions);
}
