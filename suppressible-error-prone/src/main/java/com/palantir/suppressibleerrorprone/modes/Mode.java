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

package com.palantir.suppressibleerrorprone.modes;

import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Description;
import com.palantir.suppressibleerrorprone.refactor.RefactorAccumulator;

/**
 * A mode that can handle error-prone violations by registering {@link com.palantir.suppressibleerrorprone.refactor.LazyRefactor}s
 * with a {@link RefactorAccumulator}.
 *
 * <p>Each mode is self-contained and encapsulates all logic for how it handles violations.
 * Modes register their desired refactorings, and the accumulator's resolution algorithm
 * determines which refactorings to actually apply based on their interactions.
 */
public interface Mode {

    /**
     * Handles a description by registering appropriate refactorings with the accumulator.
     *
     * @param description the violation description from Error Prone
     * @param state the visitor state containing context about the current location
     * @param accumulator the accumulator to register refactorings with
     */
    void handleDescription(Description description, VisitorState state, RefactorAccumulator accumulator);

    /**
     * Called when a compilation unit is first visited, before any checks run.
     * This allows modes to perform initialization like scanning for existing suppressions.
     *
     * @param state the visitor state for the compilation unit
     * @param accumulator the accumulator to register refactorings with
     */
    default void onFirstVisit(VisitorState state, RefactorAccumulator accumulator) {
        // Default: no initialization needed
    }

    /**
     * Returns the name of this mode for debugging and logging.
     */
    String getName();
}
