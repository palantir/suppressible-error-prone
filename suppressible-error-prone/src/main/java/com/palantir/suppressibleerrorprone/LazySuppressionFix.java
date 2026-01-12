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
import com.google.errorprone.fixes.ErrorProneEndPosTable;
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

/**
 * A Fix which contains the desired end state of the {@code @SuppressWarnings} annotation on a suppressible. Supports
 * lazily adding/removing suppressions. If no suppressions are present at render time, it will remove the
 * {@code @SuppressWarnings} annotation, along with the newline. Suppressions in the rendered fix are sorted by
 * human-authored first, then alphabetically.
 *
 * <p>
 *
 * We have to handle line removal ourselves because the normal {@link com.google.errorprone.fixes.SuggestedFix} does not
 * allow us to introduce a Replacement which has a different start position than the tree's normal start position.
 */
final class LazySuppressionFix implements Fix {
    // This set describes the desired end state of the fix. Suppressions can be added and removed from this set until
    // the LazySuppressingReplacement is rendered
    private final Set<String> desiredSuppressions = new HashSet<>();
    private final FirstTimeMemoizingFunction<EndPosTable, ImmutableSet<Replacement>> replacement;

    private LazySuppressionFix(
            Optional<CharSequence> sourceCode, Optional<? extends AnnotationTree> suppression, Tree declaration) {
        // See note in LazySuppressionReplacement about when we have to calculate stuff
        // We *cannot* simply make this a memoized supplier. The first thing error-prone does with the Fix is to
        // evaluate it to produce a nice error message, and we don't want to fix the number of suppression we make
        // until we're ready to produce the Replacement after *all* the error-prone checks have been run.
        // In order for SuppressingReplacement to calculate source code positions elements when it's constructed, it
        // needs an EndPosTable. However, we don't get the EndPosTable until getReplacements is called. So we have
        // to use this FirstTimeMemoizingFunction thing, that will allow use to defer creating the Replacement until
        // we have access to the EndPosTable, then keep hold of the created SuppressingReplacement. We only need a
        // single instance of EndPosTable to evaluate the source positions exactly once, so this works out.
        this.replacement = new FirstTimeMemoizingFunction<>(
                (ErrorProneEndPosTable endPositions) -> ImmutableSet.of(new LazySuppressionReplacement(
                        endPositions, desiredSuppressions, sourceCode, suppression, declaration)));
    }

    /**
     * Initialize a {@code LazySuppressingFix} on {@code suppressWarnings} with the initial suppressions
     * {@code desiredSuppressions}.
     */
    LazySuppressionFix(
            Optional<CharSequence> sourceCode,
            Optional<? extends AnnotationTree> suppressWarnings,
            Tree declaration,
            Set<String> desiredSuppressions) {
        this(sourceCode, suppressWarnings, declaration);
        this.desiredSuppressions.addAll(desiredSuppressions);
    }

    /**
     * Initialize a {@code LazySuppressingFix} on {@code suppressWarnings} with no suppressions initially.
     */
    public static LazySuppressionFix empty(
            Optional<CharSequence> sourceCode, Optional<? extends AnnotationTree> suppression, Tree tree) {
        return new LazySuppressionFix(sourceCode, suppression, tree);
    }

    public void addSuppression(String suppression) {
        desiredSuppressions.add(suppression);
    }

    @Override
    public ImmutableSet<Replacement> getReplacements(ErrorProneEndPosTable endPositions) {
        return replacement.apply();
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
