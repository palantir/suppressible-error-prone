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

import com.google.errorprone.fixes.Replacement;
import com.google.errorprone.fixes.SuggestedFix;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Accumulates {@link LazyRefactor}s from all modes during compilation and resolves them
 * using a 4-phase algorithm to determine which refactorings should actually be applied.
 *
 * <p>Uses a WeakHashMap to cache accumulators per CompilationUnitTree, allowing different
 * modes and checks to contribute refactorings to the same accumulator.
 */
public final class RefactorAccumulator {
    private static final Map<CompilationUnitTree, WeakReference<RefactorAccumulator>> ACCUMULATORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final CompilationUnitTree compilationUnit;
    private final List<LazyRefactor> pendingRefactors;
    private final Map<TreePath, Set<String>> originalSuppressions;

    private boolean resolved = false;
    private List<LazyRefactor> resolvedRefactors;

    private RefactorAccumulator(CompilationUnitTree compilationUnit, Map<TreePath, Set<String>> originalSuppressions) {
        this.compilationUnit = compilationUnit;
        this.pendingRefactors = Collections.synchronizedList(new ArrayList<>());
        this.originalSuppressions = originalSuppressions;
        this.resolvedRefactors = null;
    }

    /**
     * Gets or creates an accumulator for the given compilation unit.
     */
    public static RefactorAccumulator forCompilationUnit(
            CompilationUnitTree compilationUnit, Map<TreePath, Set<String>> originalSuppressions) {
        synchronized (ACCUMULATORS) {
            WeakReference<RefactorAccumulator> ref = ACCUMULATORS.get(compilationUnit);
            RefactorAccumulator accumulator = ref != null ? ref.get() : null;

            if (accumulator == null) {
                accumulator = new RefactorAccumulator(compilationUnit, originalSuppressions);
                ACCUMULATORS.put(compilationUnit, new WeakReference<>(accumulator));
            }

            return accumulator;
        }
    }

    /**
     * Adds a refactor to be resolved later.
     * Must be called before resolution.
     */
    public synchronized void addRefactor(LazyRefactor refactor) {
        if (resolved) {
            throw new IllegalStateException("Cannot add refactors after resolution");
        }
        pendingRefactors.add(refactor);
    }

    /**
     * Resolves all pending refactors using a 4-phase algorithm:
     * <ol>
     *   <li>Phase 1: Apply all {@link LazySuppressionRetention}s (highest priority)
     *   <li>Phase 2: Apply all {@link LazySuppressionRemoval}s (respecting retentions)
     *   <li>Phase 3: Apply all {@link LazyFix}es (checking effective suppressions)
     *   <li>Phase 4: Apply all {@link LazySuppressionAddition}s (only if sources not handled)
     * </ol>
     *
     * Can be called multiple times; subsequent calls return cached results.
     */
    public synchronized void resolve() {
        if (resolved) {
            return;
        }

        EffectiveState state = new EffectiveState(originalSuppressions);
        List<LazyRefactor> toApply = new ArrayList<>();

        // Phase 1: Apply all retentions (highest priority)
        for (LazyRefactor refactor : pendingRefactors) {
            if (refactor instanceof LazySuppressionRetention retention) {
                state.applyRetention(retention);
                toApply.add(retention);
            }
        }

        // Phase 2: Apply all removals (respecting retentions)
        for (LazyRefactor refactor : pendingRefactors) {
            if (refactor instanceof LazySuppressionRemoval removal) {
                state.applyRemoval(removal);
                toApply.add(removal);
            }
        }

        // Phase 3: Apply fixes (checking effective suppressions)
        for (LazyRefactor refactor : pendingRefactors) {
            if (refactor instanceof LazyFix fix) {
                boolean isSuppressed = state.isSuppressed(fix.targetPath(), fix.description().checkName);

                // Apply fix if not suppressed, or if we're ignoring existing suppressions
                if (!isSuppressed || !fix.keepExistingSuppressions()) {
                    state.applyFix(fix);
                    toApply.add(fix);
                }
            }
        }

        // Phase 4: Apply suppressions (only if sources weren't handled)
        for (LazyRefactor refactor : pendingRefactors) {
            if (refactor instanceof LazySuppressionAddition addition) {
                if (!state.wereSourcesHandled(addition)) {
                    state.applySuppression(addition);
                    toApply.add(addition);
                }
            }
        }

        resolvedRefactors = Collections.unmodifiableList(toApply);
        resolved = true;
    }

    /**
     * Gets all resolved refactors that should be applied.
     * Must be called after {@link #resolve()}.
     */
    public synchronized List<LazyRefactor> getResolvedRefactors() {
        if (!resolved) {
            throw new IllegalStateException("Must call resolve() before getting resolved refactors");
        }
        return resolvedRefactors;
    }

    /**
     * Gets the compilation unit this accumulator is for.
     */
    public CompilationUnitTree getCompilationUnit() {
        return compilationUnit;
    }

    /**
     * Checks if this accumulator has been resolved.
     */
    public synchronized boolean isResolved() {
        return resolved;
    }

    /**
     * Clears the static cache. Useful for testing.
     */
    public static void clearCache() {
        synchronized (ACCUMULATORS) {
            ACCUMULATORS.clear();
        }
    }
}
