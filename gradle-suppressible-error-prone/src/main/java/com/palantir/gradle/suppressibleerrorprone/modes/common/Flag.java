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
import one.util.streamex.StreamEx;

public enum Flag {
    APPLY("errorProneApply"),
    SUPPRESS("errorProneSuppress"),
    REMOVE_ROLLOUT("errorProneRemoveRollout"),
    TIMINGS("errorProneTimings"),
    // Historically, the logic of this plugin lived in baseline, so we need to support the old disable flag.
    DISABLE("errorProneDisable", "com.palantir.baseline-error-prone.disable"),
    ;

    private final String canonicalName;
    private final Set<String> allNames;

    Flag(String canonicalName, String... otherNames) {
        this.canonicalName = canonicalName;
        this.allNames = StreamEx.of(canonicalName).append(otherNames).toSet();
    }

    public String canonicalName() {
        return canonicalName;
    }

    public Set<String> allNames() {
        return allNames;
    }

    public String asGradlePropertyArgument() {
        return "-P" + canonicalName;
    }
}
