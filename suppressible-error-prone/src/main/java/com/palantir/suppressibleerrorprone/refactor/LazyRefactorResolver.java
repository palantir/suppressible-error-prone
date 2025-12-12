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

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.Replacement;
import com.google.errorprone.fixes.Replacements.CoalescePolicy;
import com.google.errorprone.fixes.SuggestedFix;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Fix} implementation that triggers resolution of all accumulated {@link LazyRefactor}s
 * when Error Prone requests the replacements.
 *
 * <p>This is designed to work with {@link RefactorAccumulator}'s lazy resolution mechanism.
 * The first call to {@link #getReplacements(EndPosTable)} triggers resolution for the entire
 * compilation unit, and subsequent calls return cached results.
 */
public final class LazyRefactorResolver implements Fix {
    private final CompilationUnitTree compilationUnit;
    private final TreePath targetPath;

    public LazyRefactorResolver(CompilationUnitTree compilationUnit, TreePath targetPath) {
        this.compilationUnit = compilationUnit;
        this.targetPath = targetPath;
    }

    @Override
    public String toString(JCCompilationUnit compilationUnit) {
        return "LazyRefactorResolver[" + targetPath.getLeaf().getKind() + "]";
    }

    @Override
    public String getShortDescription() {
        return "Applying accumulated refactorings";
    }

    @Override
    public CoalescePolicy getCoalescePolicy() {
        return CoalescePolicy.EXISTING_FIRST;
    }

    @Override
    public ImmutableSet<String> getImportsToAdd() {
        // TODO: Aggregate imports from all resolved refactors
        return ImmutableSet.of();
    }

    @Override
    public ImmutableSet<String> getImportsToRemove() {
        // TODO: Aggregate imports from all resolved refactors
        return ImmutableSet.of();
    }

    @Override
    public boolean isEmpty() {
        // We don't know if it's empty until resolution
        return false;
    }

    @Override
    public ImmutableSet<Replacement> getReplacements(EndPosTable endPositions) {
        // Note: We don't have access to originalSuppressions here.
        // The accumulator must be created earlier with the suppressions.
        // For now, we'll pass an empty map, but this should be fixed in integration.
        RefactorAccumulator accumulator = RefactorAccumulator.forCompilationUnit(compilationUnit, Map.of());

        // Trigger resolution if not already done
        if (!accumulator.isResolved()) {
            accumulator.resolve();
        }

        // Collect all replacements from resolved refactors
        ImmutableSet.Builder<Replacement> allReplacements = ImmutableSet.builder();
        List<LazyRefactor> resolvedRefactors = accumulator.getResolvedRefactors();

        for (LazyRefactor refactor : resolvedRefactors) {
            SuggestedFix fix = refactor.generateFix();
            if (fix != null && !fix.isEmpty()) {
                allReplacements.addAll(fix.getReplacements(endPositions));
            }
        }

        return allReplacements.build();
    }

    /**
     * Creates a resolver for a specific target path within a compilation unit.
     */
    public static LazyRefactorResolver create(CompilationUnitTree compilationUnit, TreePath targetPath) {
        return new LazyRefactorResolver(compilationUnit, targetPath);
    }
}
