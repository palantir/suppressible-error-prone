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

import com.palantir.gradle.suppressibleerrorprone.modes.common.Flag;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeSetInterference;
import java.util.Map;
import java.util.Set;

public final class RemovingAndSuppressingInterference extends ModeSetInterference {
    @Override
    public Set<Flag> interferingFlags() {
        return Set.of(Flag.REMOVE_ROLLOUT, Flag.SUPPRESS);
    }

    @Override
    public ModeOptions interfere(Map<Flag, ModeOptions> flagOptions) {
        throw new IllegalStateException("%s cannot be used at the same time as %s");
    }
}
