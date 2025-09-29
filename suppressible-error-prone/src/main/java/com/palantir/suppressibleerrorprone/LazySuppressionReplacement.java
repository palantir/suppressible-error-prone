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

/**
 * A Replacement that handles @SuppressWarnings modifications with line-removing capabilities.
 * When the final replacement text is empty (no suppressions), it will remove preceding
 * whitespace up to and including the newline.
 */
final class LazySuppressionReplacement extends Replacement {
    private final Range<Integer> range;
    private final List<String> existingSuppressions;
    private final String suffix;
    private final Set<String> desiredSuppressions;
    private final Optional<CharSequence> sourceCode;
    private final Optional<? extends AnnotationTree> suppressWarnings;

    LazySuppressionReplacement(
            EndPosTable endPositions,
            Set<String> desiredSuppressions,
            Optional<CharSequence> sourceCode,
            Optional<? extends AnnotationTree> suppressWarnings,
            Tree declaration) {
        this.desiredSuppressions = desiredSuppressions;
        this.sourceCode = sourceCode;
        this.suppressWarnings = suppressWarnings;

        // Calculate range immediately to avoid tree representation changes
        this.range = calculateRange(endPositions, suppressWarnings, declaration);

        this.suffix = suppressWarnings
                // If we're replacing an existing @SuppressWarnings, there's no need to add an indent
                .map(_ignored -> "")
                // If we're adding a new @SuppressWarnings, we need to indent the next line correctly
                .orElseGet(() -> "\n" + SourceCodeUtils.indentForTree(sourceCode, declaration));

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
        String result = calculateReplacementText();
        return result;
    }

    private String calculateReplacementText() {
        if (desiredSuppressions.isEmpty()) {
            // No suppressions left, return empty string (line removal will be handled by range())
            return "";
        }

        // Generate the @SuppressWarnings annotation with remaining suppressions
        return SuppressWarningsUtils.suppressWarningsString(
                        SuppressWarningsUtils.sortHumanFirstThenAlphabetical(desiredSuppressions))
                + suffix;
    }

    private static Range<Integer> calculateRange(
            EndPosTable endPositions, Optional<? extends AnnotationTree> suppressWarnings, Tree tree) {
        return suppressWarnings
                .map(annotationTree -> {
                    // @SuppressWarnings already exists, we need to replace the whole expression
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
