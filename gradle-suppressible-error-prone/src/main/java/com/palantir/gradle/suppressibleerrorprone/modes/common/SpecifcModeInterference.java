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

import java.util.Set;

/**
 * This is a helper class when there are just a set of {@link Mode}s that always interfere with each other, to
 * reduce some Set logic overhead.
 */
public abstract class SpecifcModeInterference implements ModeInterference {
    protected abstract Set<ModeName> interferingModes();

    @Override
    public final Set<ModeName> interferesWith(Set<ModeName> modeNames) {
        if (modeNames.containsAll(interferingModes())) {
            return interferingModes();
        }

        return Set.of();
    }
}
