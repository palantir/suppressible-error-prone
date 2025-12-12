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

import com.sun.source.util.TreePath;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the effective state of suppressions and fixes during the resolution process.
 * This state represents what the code will look like after all refactorings are applied.
 */
public final class EffectiveState {
    private final Map<TreePath, Set<String>> originalSuppressions;
    private final Map<TreePath, Set<String>> effectiveSuppressions;
    private final Map<TreePath, Set<String>> appliedFixes;
    private final Set<LazyRefactor> retainedSuppressions;
    private final Set<LazyRefactor> removedSuppressions;

    public EffectiveState(Map<TreePath, Set<String>> originalSuppressions) {
        this.originalSuppressions = Map.copyOf(originalSuppressions);
        this.effectiveSuppressions = new HashMap<>();
        // Deep copy the original suppressions
        originalSuppressions.forEach((path, checks) ->
                this.effectiveSuppressions.put(path, new HashSet<>(checks)));
        this.appliedFixes = new HashMap<>();
        this.retainedSuppressions = new HashSet<>();
        this.removedSuppressions = new HashSet<>();
    }

    /**
     * Phase 1: Apply a suppression retention.
     * Ensures this suppression stays in the effective state.
     */
    public void applyRetention(LazySuppressionRetention retention) {
        retainedSuppressions.add(retention);
        effectiveSuppressions
                .computeIfAbsent(retention.targetPath(), _k -> new HashSet<>())
                .add(retention.checkName());
    }

    /**
     * Phase 2: Apply a suppression removal, unless it's been retained.
     */
    public void applyRemoval(LazySuppressionRemoval removal) {
        // Check if there's a retention for this exact suppression
        boolean isRetained = retainedSuppressions.stream()
                .anyMatch(r -> r instanceof LazySuppressionRetention ret
                        && ret.targetPath().equals(removal.targetPath())
                        && ret.checkName().equals(removal.checkName()));

        if (!isRetained) {
            removedSuppressions.add(removal);
            Set<String> suppressions = effectiveSuppressions.get(removal.targetPath());
            if (suppressions != null) {
                suppressions.remove(removal.checkName());
            }
        }
    }

    /**
     * Phase 3: Query whether a location is effectively suppressed.
     * Walks up the tree to check ancestors for suppressions.
     */
    public boolean isSuppressed(TreePath path, String checkName) {
        TreePath current = path;
        while (current != null) {
            Set<String> suppressions = effectiveSuppressions.get(current);
            if (suppressions != null && suppressions.contains(checkName)) {
                return true;
            }
            current = current.getParentPath();
        }
        return false;
    }

    /**
     * Phase 3: Record that a fix is being applied.
     */
    public void applyFix(LazyFix fix) {
        appliedFixes
                .computeIfAbsent(fix.targetPath(), _k -> new HashSet<>())
                .add(fix.description().checkName);
    }

    /**
     * Phase 4: Query whether a location has a fix applied.
     */
    public boolean isFixed(TreePath path, String checkName) {
        Set<String> fixes = appliedFixes.get(path);
        return fixes != null && fixes.contains(checkName);
    }

    /**
     * Phase 4: Check if all sources for a suppression addition were handled.
     * Returns true if all sources were either fixed or retained.
     */
    public boolean wereSourcesHandled(LazySuppressionAddition addition) {
        return addition.sources().stream().allMatch(source -> {
            if (source instanceof LazyFix fix) {
                return appliedFixes
                        .getOrDefault(fix.targetPath(), Collections.emptySet())
                        .contains(fix.description().checkName);
            }
            if (source instanceof LazySuppressionRetention ret) {
                return retainedSuppressions.contains(ret);
            }
            return false;
        });
    }

    /**
     * Phase 4: Record that a suppression is being added.
     */
    public void applySuppression(LazySuppressionAddition addition) {
        effectiveSuppressions
                .computeIfAbsent(addition.targetPath(), _k -> new HashSet<>())
                .add(addition.description().checkName);
    }

    public Map<TreePath, Set<String>> getOriginalSuppressions() {
        return originalSuppressions;
    }

    public Map<TreePath, Set<String>> getEffectiveSuppressions() {
        return Collections.unmodifiableMap(effectiveSuppressions);
    }

    public Map<TreePath, Set<String>> getAppliedFixes() {
        return Collections.unmodifiableMap(appliedFixes);
    }
}
