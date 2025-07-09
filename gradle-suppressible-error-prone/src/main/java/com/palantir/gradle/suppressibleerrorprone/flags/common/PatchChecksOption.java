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

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface PatchChecksOption {
    String asCommaSeparated();

    default PatchChecksOption combine(PatchChecksOption other) {
        if (this instanceof AllChecks || other instanceof AllChecks) {
            return AllChecks.INSTANCE;
        }

        if (this instanceof SomeChecks thisSome && other instanceof SomeChecks otherSome) {
            return new SomeChecks(Stream.concat(thisSome.patchChecks().stream(), otherSome.patchChecks().stream())
                    .collect(Collectors.toSet()));
        }

        throw new IllegalArgumentException("Must be an instance of AllChecks or SomeChecks");
    }

    static PatchChecksOption allChecks() {
        return AllChecks.INSTANCE;
    }

    static PatchChecksOption someChecks(String... patchChecks) {
        return new SomeChecks(Set.of(patchChecks));
    }

    enum AllChecks implements PatchChecksOption {
        INSTANCE;

        @Override
        public String asCommaSeparated() {
            return "";
        }
    }

    record SomeChecks(Set<String> patchChecks) implements PatchChecksOption {
        @Override
        public String asCommaSeparated() {
            return String.join(",", patchChecks);
        }
    }
}
