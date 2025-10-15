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
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
     * Modify these files in the error-prone API.
     */
    static MustModify mustModify(ModifiedFile... modifiedFile) {
        return new MustModify(Arrays.stream(modifiedFile).collect(Collectors.toSet()));
    }

    default Set<ModifiedFile> getModifiedFiles() {
        return Set.of();
    }
    ;

    enum DoNotModify implements ModifyCheckApiOption, CombinedValue {
        INSTANCE
    }

    enum NoEffect implements ModifyCheckApiOption {
        INSTANCE
    }

    record MustModify(Set<ModifiedFile> modifiedFiles) implements ModifyCheckApiOption, CombinedValue {
        public MustModify combine(MustModify other) {
            Set<ModifiedFile> union = Stream.concat(modifiedFiles.stream(), other.modifiedFiles.stream())
                    .collect(Collectors.toSet());
            return new MustModify(union);
        }
    }

    sealed interface CombinedValue permits DoNotModify, MustModify {}

    static CombinedValue combine(Collection<ModifyCheckApiOption> options) {
        Set<ModifyCheckApiOption> withoutNoEffects = StreamEx.of(options)
                .remove(Predicate.isEqual(NoEffect.INSTANCE))
                .toSet();

        if (withoutNoEffects.isEmpty()) {
            // By default, we need to modify the check API to support for-rollout suppressions
            return mustModify(ModifiedFile.BUG_CHECKER_INFO);
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

    enum ModifiedFile {
        BUG_CHECKER_INFO("BugCheckerInfo"),
        VISITOR_STATE("VisitorState"),
        SUPPRESSIBLE_TREE_PATH_SCANNER("SuppressibleTreePathScanner");

        private final String className;

        ModifiedFile(String className) {
            this.className = className;
        }

        public String getClassName() {
            return className;
        }
    }
}
