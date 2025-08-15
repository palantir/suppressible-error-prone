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

import com.google.common.base.Suppliers;
import com.palantir.gradle.suppressibleerrorprone.modes.common.PatchChecksOption.AllChecks;
import com.palantir.gradle.suppressibleerrorprone.modes.common.PatchChecksOption.PossiblySomeChecks;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import one.util.streamex.StreamEx;

/**
 * Error Prone has two different ways to patch checks, either you can patch *all* the checks or just patch specific
 * checks. This class represents these two options, and allows combining them sensibly. ie if one mode patches all
 * checks and another mode patches specific checks, then the combined option will patch all checks.
 */
public sealed interface PatchChecksOption permits AllChecks, PossiblySomeChecks {
    boolean hasChecks();

    Optional<String> asCommaSeparated();

    default PatchChecksOption combine(PatchChecksOption other) {
        if (this instanceof AllChecks || other instanceof AllChecks) {
            return AllChecks.INSTANCE;
        }

        if (this instanceof PossiblySomeChecks thisSome && other instanceof PossiblySomeChecks otherSome) {
            return new PossiblySomeChecks(() -> StreamEx.of(thisSome.patchChecks())
                    .append(otherSome.patchChecks())
                    .toSet());
        }

        throw new IllegalArgumentException("Must be an instance of AllChecks or SomeChecks");
    }

    static PatchChecksOption allChecks() {
        return AllChecks.INSTANCE;
    }

    static PatchChecksOption someChecks(String... patchChecks) {
        return new PossiblySomeChecks(() -> Set.of(patchChecks));
    }

    static PatchChecksOption someChecks(Supplier<Set<String>> patchChecks) {
        return new PossiblySomeChecks(patchChecks);
    }

    static PatchChecksOption noChecks() {
        return new PossiblySomeChecks(Set::of);
    }

    enum AllChecks implements PatchChecksOption {
        INSTANCE;

        @Override
        public boolean hasChecks() {
            return true;
        }

        @Override
        public Optional<String> asCommaSeparated() {
            // Empty string means "all checks" to Error Prone - ie the option `-XepPatchChecks:`
            return Optional.of("");
        }
    }

    final class PossiblySomeChecks implements PatchChecksOption {
        private final Supplier<Set<String>> patchChecks;

        public PossiblySomeChecks(Supplier<Set<String>> patchChecks) {
            // Memoize as if this is called at different times in the build, different values could be returned.
            // At least try to keep it consistent.
            this.patchChecks = Suppliers.memoize(() -> Set.copyOf(patchChecks.get()));
        }

        public Set<String> patchChecks() {
            return patchChecks.get();
        }

        @Override
        public boolean hasChecks() {
            return !patchChecks.get().isEmpty();
        }

        @Override
        public Optional<String> asCommaSeparated() {
            Set<String> checks = patchChecks.get();

            if (checks.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(checks.stream().sorted().collect(Collectors.joining(",")));
        }
    }
}
