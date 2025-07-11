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

import com.palantir.gradle.suppressibleerrorprone.flags.common.ModifyCheckApiOption.DoNotModify;
import com.palantir.gradle.suppressibleerrorprone.flags.common.ModifyCheckApiOption.DontCare;
import com.palantir.gradle.suppressibleerrorprone.flags.common.ModifyCheckApiOption.MustModify;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import one.util.streamex.StreamEx;

public sealed interface ModifyCheckApiOption permits DoNotModify, DontCare, MustModify {
    enum DoNotModify implements ModifyCheckApiOption, FinalValue {
        INSTANCE
    }

    enum DontCare implements ModifyCheckApiOption {
        INSTANCE
    }

    record MustModify(Set<ClassesToModify> classesToModify) implements ModifyCheckApiOption, FinalValue {
        public MustModify combine(MustModify other) {
            return new MustModify(
                    StreamEx.of(classesToModify).append(other.classesToModify).toSet());
        }
    }

    enum ClassesToModify {
        BUG_CHECKER_INFO,
        VISITOR_STATE
    }

    sealed interface FinalValue permits DoNotModify, MustModify {}

    static FinalValue combine(Collection<ModifyCheckApiOption> options) {
        Set<ModifyCheckApiOption> withoutDontCares = options.stream()
                .filter(Predicate.not(Predicate.isEqual(DontCare.INSTANCE)))
                .collect(Collectors.toSet());

        if (withoutDontCares.isEmpty()) {
            return DoNotModify.INSTANCE;
        }

        boolean doNotModify = withoutDontCares.contains(DoNotModify.INSTANCE);
        boolean mustModify = withoutDontCares.stream().anyMatch(option -> option instanceof MustModify);

        if (doNotModify && mustModify) {
            throw new IllegalStateException("Cannot have both do not modify and must modify");
        }

        if (doNotModify) {
            return DoNotModify.INSTANCE;
        }

        return withoutDontCares.stream()
                .map(MustModify.class::cast)
                .reduce(MustModify::combine)
                .get();
    }
}
