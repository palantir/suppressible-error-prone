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

package com.palantir.suppressibleerrorprone.refactor;

import com.google.errorprone.fixes.SuggestedFix;
import com.sun.source.util.TreePath;

/**
 * A lazy refactoring operation that is resolved after all checks have run.
 * Implementations declare what kind of refactoring they represent, and the resolution
 * algorithm determines which refactorings to apply based on their interactions.
 */
public sealed interface LazyRefactor
        permits LazySuppressionRetention, LazySuppressionRemoval, LazyFix, LazySuppressionAddition {

    /**
     * The tree path where this refactoring should be applied.
     */
    TreePath targetPath();

    /**
     * Generates the actual SuggestedFix to apply.
     * Called only after resolution determines this refactor should be applied.
     */
    SuggestedFix generateFix();
}
