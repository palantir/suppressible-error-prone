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

import com.sun.source.util.TreePath;
import com.google.errorprone.fixes.SuggestedFix;

/**
 * Retains an existing suppression, preventing its removal.
 * This has the highest priority in the resolution algorithm.
 */
public final record LazySuppressionRetention(TreePath targetPath, String checkName) implements LazyRefactor {

    @Override
    public SuggestedFix generateFix() {
        // Retention doesn't generate a fix - it prevents removal
        return SuggestedFix.builder().build();
    }
}
