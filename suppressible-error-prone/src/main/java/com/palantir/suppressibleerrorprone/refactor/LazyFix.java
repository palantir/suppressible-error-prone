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

import com.google.errorprone.matchers.Description;
import com.google.errorprone.fixes.SuggestedFix;
import com.sun.source.util.TreePath;

/**
 * Applies an automatic fix from Error Prone.
 * Applied if the target is not suppressed (unless keepExistingSuppressions is false).
 */
public final record LazyFix(
        TreePath targetPath,
        Description description,
        boolean keepExistingSuppressions
) implements LazyRefactor {

    @Override
    public SuggestedFix generateFix() {
        // Return the suggested fix from the description
        // Note: description.fixes contains Fix objects, which may include SuggestedFix
        if (description.fixes.isEmpty()) {
            return SuggestedFix.builder().build();
        }

        // If the fix is already a SuggestedFix, return it
        if (description.fixes.get(0) instanceof SuggestedFix suggestedFix) {
            return suggestedFix;
        }

        // Otherwise, we can't convert it directly
        // TODO: Handle other Fix implementations
        return SuggestedFix.builder().build();
    }
}
