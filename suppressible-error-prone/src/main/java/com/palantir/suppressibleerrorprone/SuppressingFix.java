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
import com.google.common.collect.Range;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.Replacement;
import com.google.errorprone.fixes.Replacements.CoalescePolicy;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SuppressingFix implements Fix {
    private final Optional<CharSequence> sourceCode;
    private final Optional<? extends AnnotationTree> suppressWarnings;
    private final Tree tree;
    private final Set<String> errors = new HashSet<>();

    private Optional<Range<Integer>> cachedRange = Optional.empty();

    SuppressingFix(Optional<CharSequence> sourceCode, Optional<? extends AnnotationTree> suppressWarnings, Tree tree) {
        this.sourceCode = sourceCode;
        this.suppressWarnings = suppressWarnings;
        this.tree = tree;
    }

    public void suppressError(String error) {
        errors.add(error);
    }

    @Override
    public ImmutableSet<Replacement> getReplacements(EndPosTable endPositions) {
        if (cachedRange.isEmpty()) {
            cachedRange = Optional.of(calculateRange(endPositions));
        }

        return ImmutableSet.of(new SuppressingReplacement(() -> cachedRange.get(), this::suppressWarningsString));
    }

    private Range<Integer> calculateRange(EndPosTable endPositions) {
        return suppressWarnings.map(annotationTree -> {
            // @SuppressWarnings already exists, we need to replace the whole expression with our own
            DiagnosticPosition position = (DiagnosticPosition) annotationTree;
            return Range.closedOpen(position.getStartPosition(), position.getEndPosition(endPositions));
        }).orElseGet(() -> {
            // No @SuppressWarnings, we want to prefix a new one before the start of the tree
            int startPosition = ((DiagnosticPosition) tree).getStartPosition();
            return Range.closedOpen(startPosition, startPosition);
        });
    }

    private String suppressWarningsString() {
        return suppressWarnings
                .map(this::suppressWarningsWithExistingSuppressWarnings)
                .orElseGet(this::suppressWarningsWithoutExistingSuppressWarnings);
    }

    private String suppressWarningsWithExistingSuppressWarnings(AnnotationTree suppressWarningsAnnotation) {
        List<String> existingSuppressions =
                annotationStringValues(suppressWarningsAnnotation).collect(Collectors.toList());

        List<String> warningsToSuppress = SuppressWarningsUtils.modifySuppressions(existingSuppressions, errors);

        return SuppressWarningsUtils.suppressWarningsString(warningsToSuppress);
    }

    private String suppressWarningsWithoutExistingSuppressWarnings() {
        String suppressWarningsString = SuppressWarningsUtils.suppressWarningsString(
                SuppressWarningsUtils.modifySuppressions(List.of(), errors));

        return suppressWarningsString + "\n" + indentForTree();
    }

    private static Stream<String> annotationStringValues(AnnotationTree annotation) {
        return annotation.getArguments().stream().flatMap(arg -> {
            if (!(arg instanceof AssignmentTree)) {
                return Stream.empty();
            }
            AssignmentTree assignment = (AssignmentTree) arg;

            ExpressionTree expression = assignment.getExpression();

            if (expression instanceof LiteralTree) {
                LiteralTree literalTree = (LiteralTree) expression;
                return Stream.of((String) literalTree.getValue());
            }

            if (expression instanceof NewArrayTree) {
                NewArrayTree newArray = (NewArrayTree) expression;
                return newArray.getInitializers().stream()
                        .map(LiteralTree.class::cast)
                        .map(LiteralTree::getValue)
                        .map(String.class::cast);
            }

            throw new UnsupportedOperationException("Unsupported assignment expression: "
                    + expression.getClass().getCanonicalName());
        });
    }

    static CharSequence whitespaceIndentBefore(CharSequence sourceCode, int sourceElementPosition) {
        int pos = sourceElementPosition - 1;

        for (; pos >= 0; pos--) {
            char character = sourceCode.charAt(pos);
            if (character == '\n' || !Character.isWhitespace(character)) {
                break;
            }
        }

        return sourceCode.subSequence(pos + 1, sourceElementPosition);
    }

    private CharSequence indentForTree() {
        return sourceCode
                .map(actualSourceCode ->
                        whitespaceIndentBefore(actualSourceCode, ((DiagnosticPosition) tree).getStartPosition()))
                .orElse("    ");
    }

    @Override
    public String toString(JCCompilationUnit compilationUnit) {
        return "SuppressingFix";
    }

    @Override
    public String getShortDescription() {
        return "Adding automatic suppressions";
    }

    @Override
    public CoalescePolicy getCoalescePolicy() {
        return CoalescePolicy.REJECT;
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

    private static final class SuppressingReplacement extends Replacement {
        // We really do need to be this lazy for generating the Replacements, as error-prone immediately converts
        // the Fix to a Replacement when a Description is given to it, and we need to defer the computation of the
        // Replacement until a number of Descriptions have been produced, to handle multiple errors being suppressed
        // at the same level.
        // We *cannot* make this a memoized supplier. The first thing error-prone does with the Fix is to evaluate it
        // to produce a nice error message, and we don't want to fix the number of suppression we make until we're
        // ready to produce the Replacement after *all* the error-prone checks have been run.
        private final Supplier<Range<Integer>> range;
        private final Supplier<String> replaceWith;

        public SuppressingReplacement(Supplier<Range<Integer>> range, Supplier<String> replaceWith) {
            this.range = range;
            this.replaceWith = replaceWith;
        }

        @Override
        public Range<Integer> range() {
            return range.get();
        }

        @Override
        public String replaceWith() {
            return replaceWith.get();
        }
    }
}
