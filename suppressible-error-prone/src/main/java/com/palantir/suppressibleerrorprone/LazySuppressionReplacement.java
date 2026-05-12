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
import com.google.errorprone.fixes.ErrorProneEndPosTable;
import com.google.errorprone.fixes.Replacement;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Replacement that handles @SuppressWarnings modifications with line-removing capabilities.
 * When the final replacement text is empty (no suppressions), it will remove preceding
 * whitespace up to and including the newline.
 * <p>
 * Note that {@link Replacement} is a record, so we're extending it by removing the {@code final} tag via
 * bytecode manipulation. For more details, see {@code SetupPreCompilationBytecodeManipulationPlugin}
 */
final class LazySuppressionReplacement extends Replacement {
    // We really do need to be this lazy for generating the Replacements, as error-prone immediately converts
    // the Fix to a Replacement when a Description is given to it, and we need to defer the computation of the
    // Replacement until a number of Descriptions have been produced, to handle multiple errors being suppressed
    // at the same level.
        private final Range<Integer> range;

    private final List<String> existingSuppressions;
    private final String suffix;
    private final Set<String> desiredSuppressions;
    private final Optional<CharSequence> sourceCode;
    private final Optional<? extends AnnotationTree> suppressWarnings;

    LazySuppressionReplacement(
            ErrorProneEndPosTable endPositions,
            Set<String> desiredSuppressions,
            Optional<CharSequence> sourceCode,
            Optional<? extends AnnotationTree> suppressWarnings,
            Tree declaration) {
        // Call the record constructor with dummy values since we override range() and replaceWith()
        super(Range.closedOpen(0, 0), "");

        this.desiredSuppressions = desiredSuppressions;
        this.sourceCode = sourceCode;
        this.suppressWarnings = suppressWarnings;

        // There is an additional issue that by the time error-prone comes around to apply the replacements, the
        // compiler seems to change the representation of the tree for another phase - `App.Builder` becomes
        // `App$Builder` etc and the start position for the expression changes to be after `App` rather than at the
        // start of `App`. If we calculate the replacement range too late, we insert our @SuppressWarnings at the
        // wrong location, and the indentation is miscalculated. But we can't calculate the replacement string
        // straight away, as we might not have all the new suppressions added yet. So we have to immediately
        // calculate the replacement range and indentation/suffix, but hold off building the final replacement
        // string until we have all the new suppressions.
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
        if (desiredSuppressions.isEmpty() && sourceCode.isPresent()) {
            // If the next element is in a newline, remove the newline as well.
            // Ideally, we also remove whitespace before the next element, but that would overlap with any done on
            // the next element. We leave those spaces to the formatter.
            // Otherwise, the next element is a non-whitespace. Remove up until the first non-whitespace.
            int end = SourceCodeUtils.firstNonWhitespaceOrNextLineStart(sourceCode.get(), range.upperEndpoint());
            return Range.closedOpen(range.lowerEndpoint(), end);
        }
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
            ErrorProneEndPosTable endPositions, Optional<? extends AnnotationTree> suppressWarnings, Tree tree) {
        return suppressWarnings
                .map(annotationTree -> {
                    // @SuppressWarnings already exists, we need to replace the whole expression
                    DiagnosticPosition position = (DiagnosticPosition) annotationTree;
                    return Range.closedOpen(position.getStartPosition(), endPositions.getEndPosition(position));
                })
                .orElseGet(() -> {
                    // No @SuppressWarnings, we want to prefix a new one before the start of the tree
                    int startPosition = ((DiagnosticPosition) tree).getStartPosition();
                    return Range.closedOpen(startPosition, startPosition);
                });
    }

    // Since the parent is a record, whose equals and hashCode methods solely rely on the record's fields,
    // We have to re-override these methods to use the default implementation from Object
    // Or else, only one Replacement will be added to any given file:
    // https://github.com/google/error-prone/blob/a5a718974dd7d325025ea14c1492f113490d5cf8/check_api/src/main/java/com/google/errorprone/fixes/Replacements.java#L146
    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
