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
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A Fix that can lazily add/modify/remove @SuppressWarnings annotations with proper line handling.
 * When removing suppressions entirely, it will also remove preceding whitespace up to and including
 * the newline to avoid leaving empty lines.
 * Sorts suppressions by human-authored first, then alphabetically.
 */
final class LazySuppressionFix implements Fix {
    private final Set<String> desiredSuppressions = new HashSet<>();
    private final Function<EndPosTable, ImmutableSet<Replacement>> replacement;

    private LazySuppressionFix(
            Optional<CharSequence> sourceCode, Optional<? extends AnnotationTree> suppression, Tree declaration) {
        // Defer replacement creation until we have EndPosTable and all suppressions have been added
        this.replacement = new FirstTimeMemoizingFunction<>(
                (EndPosTable endPositions) -> ImmutableSet.of(new LazySuppressionReplacement(
                        endPositions, desiredSuppressions, sourceCode, suppression, declaration)));
    }

    LazySuppressionFix(
            Optional<CharSequence> sourceCode,
            Optional<? extends AnnotationTree> suppressWarnings,
            Tree tree,
            Set<String> desiredSuppressions) {
        this(sourceCode, suppressWarnings, tree);
        this.desiredSuppressions.addAll(desiredSuppressions);
    }

    public static LazySuppressionFix empty(
            Optional<CharSequence> sourceCode, Optional<? extends AnnotationTree> suppression, Tree tree) {
        return new LazySuppressionFix(sourceCode, suppression, tree);
    }

    public void addSuppression(String suppression) {
        desiredSuppressions.add(suppression);
    }

    @Override
    public ImmutableSet<Replacement> getReplacements(EndPosTable endPositions) {
        return replacement.apply(endPositions);
    }

    @Override
    public String toString(JCCompilationUnit compilationUnit) {
        return "LazySuppressionFix";
    }

    @Override
    public String getShortDescription() {
        return "Adding/modifying/removing @SuppressWarnings with proper line handling";
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
}
