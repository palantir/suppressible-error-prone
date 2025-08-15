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

import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption.DoNotModify;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption.MustModify;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption.NoEffect;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import one.util.streamex.StreamEx;

/**
 * suppressible-error-prone is powered by an artifact transform that modifies the error_prone_check_api jar.
 * Some modes must not run with this jar modified, some must run with it modified (and then some may or may not need
 * the VisitorState class modified), and some don't care. This class represents these states and allows combining them
 * sensibly.
 */
public sealed interface ModifyCheckApiOption permits DoNotModify, NoEffect, MustModify {
    /**
     * The {@code error_prone_check_api} jar must not be modified.
     */
    static ModifyCheckApiOption doNotModify() {
        return DoNotModify.INSTANCE;
    }

    /**
     * It doesn't matter whether the {@code error_prone_check_api} jar is modified or not. This will have no effect
     * on the decision made.
     */
    static ModifyCheckApiOption noEffect() {
        return NoEffect.INSTANCE;
    }

    /**
     * The {@code error_prone_check_api} jar must be modified to allow {@code `for-rollout:`} suppressions to work.
     */
    static ModifyCheckApiOption mustModify() {
        return new MustModify(false);
    }

    /**
     * The {@code error_prone_check_api} jar must be modified to allow {@code `for-rollout:`} suppressions to work,
     * and {@code VisitorState} must be modified to intercept reportMatch.
     */
    static ModifyCheckApiOption mustModifyIncludingVisitorState() {
        return new MustModify(true);
    }

    enum DoNotModify implements ModifyCheckApiOption, CombinedValue {
        INSTANCE
    }

    enum NoEffect implements ModifyCheckApiOption {
        INSTANCE
    }

    record MustModify(boolean modifyVisitorState) implements ModifyCheckApiOption, CombinedValue {
        public MustModify combine(MustModify other) {
            return new MustModify(modifyVisitorState || other.modifyVisitorState);
        }
    }

    sealed interface CombinedValue permits DoNotModify, MustModify {}

    static CombinedValue combine(Collection<ModifyCheckApiOption> options) {
        Set<ModifyCheckApiOption> withoutNoEffects = StreamEx.of(options)
                .remove(Predicate.isEqual(NoEffect.INSTANCE))
                .toSet();

        if (withoutNoEffects.isEmpty()) {
            // By default, we need to modify the check API to support for-rollout suppressions
            return new MustModify(false);
        }

        boolean doNotModify = withoutNoEffects.contains(DoNotModify.INSTANCE);
        boolean mustModify = withoutNoEffects.stream().anyMatch(option -> option instanceof MustModify);

        if (doNotModify && mustModify) {
            throw new IllegalStateException("Cannot have both do not modify and must modify");
        }

        if (doNotModify) {
            return DoNotModify.INSTANCE;
        }

        return withoutNoEffects.stream()
                .map(MustModify.class::cast)
                .reduce(MustModify::combine)
                .get();
    }
}
