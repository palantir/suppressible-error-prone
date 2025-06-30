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

import com.google.common.collect.Range;
import com.google.errorprone.fixes.Replacement;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class SuppressingReplacement extends Replacement {
    // We really do need to be this lazy for generating the Replacements, as error-prone immediately converts
    // the Fix to a Replacement when a Description is given to it, and we need to defer the computation of the
    // Replacement until a number of Descriptions have been produced, to handle multiple errors being suppressed
    // at the same level.

    private final Range<Integer> range;
    private final List<String> existingSuppressions;
    private final String suffix;
    private final Set<String> newSuppressions;
    private final boolean isRemovingUnusedSuppressions;

    SuppressingReplacement(
            EndPosTable endPositions,
            Set<String> newSuppressions,
            Optional<CharSequence> sourceCode,
            Optional<? extends AnnotationTree> suppressWarnings,
            Tree tree,
            boolean isRemovingUnusedSuppressions) {
        // Note this is a *mutable* set from SuppressingFix, we need to able to add a new suppression before this
        // instance is instantiated
        this.newSuppressions = newSuppressions;
        this.isRemovingUnusedSuppressions = isRemovingUnusedSuppressions;

        // There is an additional issue that by the time error-prone comes around to apply the replacements, the
        // compiler seems to change the representation of the tree for another phase - `App.Builder` becomes
        // `App$Builder` etc and the start position for the expression changes to be after `App` rather than at the
        // start of `App`. If we calculate the replacement range too late, we insert our @SuppressWarnings at the
        // wrong location, and the indentation is miscalculated. But we can't calculate the replacement string
        // straight away, as we might not have all the new suppressions added yet. So we have to immediately
        // calculate the replacement range and indentation/suffix, but hold off building the final replacement
        // string until we have all the new suppressions.
        this.range = calculateRange(endPositions, suppressWarnings, tree);

        this.suffix = suppressWarnings
                // If we're replacing an existing @SuppressWarnings, there's no need to add an indent
                .map(_ignored -> "")
                // If we're adding a new @SuppressWarnings, we need to indent the next line correctly
                .orElseGet(() -> "\n" + SourceCodeUtils.indentForTree(sourceCode, tree));

        this.existingSuppressions = suppressWarnings.stream()
                .flatMap(AnnotationUtils::annotationStringValues)
                .collect(Collectors.toList());
    }

    @Override
    public Range<Integer> range() {
        return range;
    }

    @Override
    public String replaceWith() {
        return SuppressWarningsUtils.suppressWarningsString(SuppressWarningsUtils.modifySuppressions(
                        existingSuppressions, newSuppressions, isRemovingUnusedSuppressions))
                + suffix;
    }

    private static Range<Integer> calculateRange(
            EndPosTable endPositions, Optional<? extends AnnotationTree> suppressWarnings, Tree tree) {
        return suppressWarnings
                .map(annotationTree -> {
                    // @SuppressWarnings already exists, we need to replace the whole expression with our own
                    DiagnosticPosition position = (DiagnosticPosition) annotationTree;
                    return Range.closedOpen(position.getStartPosition(), position.getEndPosition(endPositions));
                })
                .orElseGet(() -> {
                    // No @SuppressWarnings, we want to prefix a new one before the start of the tree
                    int startPosition = ((DiagnosticPosition) tree).getStartPosition();
                    return Range.closedOpen(startPosition, startPosition);
                });
    }
}
