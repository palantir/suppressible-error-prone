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

package com.palantir.suppressibleerrorprone;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.Replacement;
import com.google.errorprone.fixes.Replacements.CoalescePolicy;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;

/**
 * This class has been introduced because the normal {@link com.google.errorprone.fixes.SuggestedFix} does not
 *   allow us to introduce a Replacement which has a different start position than the tree's normal start position.
 * Here we want to:
 *   - replace the element defined by the provided position
 *   - also replace the whitespace before the element, up to and including the newline
 * This way, if e.g. @SuppressWarnings("foo") must be removed entirely, we can remove the entire line, rather than
 *   just the annotation, leaving us with an empty line.
 *
 * Note that this will only delete the whitespace before the element if the entire element is removed
 *   (i.e. if the replacement text is null or empty).
 */
public final class LineRemovingReplacementFix implements Fix {
    private final CharSequence sourceCode;
    private final DiagnosticPosition position;
    private final String replacementText;

    LineRemovingReplacementFix(CharSequence sourceCode, DiagnosticPosition position, String replacementText) {
        this.sourceCode = sourceCode;
        this.position = position;
        this.replacementText = replacementText;
    }

    @Override
    public String toString(JCCompilationUnit compilationUnit) {
        return "LineRemovingReplacementFix";
    }

    @Override
    public String getShortDescription() {
        return "Replace text at the position with the provided text, "
                + "or remove the text and all preceding whitespace";
    }

    @Override
    public CoalescePolicy getCoalescePolicy() {
        return CoalescePolicy.REJECT;
    }

    @Override
    public ImmutableSet<Replacement> getReplacements(EndPosTable endPositions) {
        // If we are looking to delete the entire element, we should also remove whitespace before it,
        //   up to and including the newline
        if (replacementText.isEmpty() && sourceCode != null) {
            int start = SourceCodeUtils.startPositionWithWhitespaceIncludingNewLine(
                    sourceCode, position.getStartPosition());
            return ImmutableSet.of(Replacement.create(start, position.getEndPosition(endPositions), ""));
        }
        return ImmutableSet.of(Replacement.create(
                position.getStartPosition(), position.getEndPosition(endPositions), replacementText));
    }

    @Override
    public ImmutableSet<String> getImportsToAdd() {
        return ImmutableSet.of();
    }

    @Override
    public ImmutableSet<String> getImportsToRemove() {
        return ImmutableSet.of();
    }

    @Override
    public boolean isEmpty() {
        return false;
    }
}
