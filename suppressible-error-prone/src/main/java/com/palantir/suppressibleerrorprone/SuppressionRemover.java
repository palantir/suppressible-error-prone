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

import com.google.errorprone.VisitorState;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class SuppressionRemover {
    // Weak set so that we don't leak memory by keeping hold of references to CompilationUnitTrees after error-prone
    // has finished processing.
    private static final Set<CompilationUnitTree> attachedFixes = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<String> DO_NOT_REMOVE = Set.of(
            // NullAway does not respect -XepIgnoreSuppressionAnnotations, and it will take some work to make it so.
            // In the meantime, let's not touch any NullAway suppressions
            "NullAway", "CheckNullabilityTypes",
            // rawtypes is used by both the compiler and error-prone, let's not remove these
            "rawtypes", "RawTypes");

    public static void removeAllKnownBugcheckerSuppressions(
            ReportedFixCache reportedFixes, CompilationUnitTree unit, VisitorState state) {
        if (attachedFixes.add(unit)) {
            new TreePathScanner<Void, Void>() {
                @SuppressWarnings("for-rollout:VoidUsed")
                @Override
                public Void visitAnnotation(AnnotationTree node, Void unused) {
                    if (AnnotationUtils.isSuppressWarningsAnnotation(node)) {
                        TreePath declaration = getCurrentPath().getParentPath().getParentPath();

                        reportedFixes.getOrReportNew(
                                declaration,
                                state,
                                suppression -> !AllErrorprones.allBugcheckerNames(state)
                                                .contains(SuppressWarningsUtils.stripForRollout(suppression))
                                        || DO_NOT_REMOVE.contains(SuppressWarningsUtils.stripForRollout(suppression)));
                    }

                    return super.visitAnnotation(node, unused);
                }
            }.scan(unit, null);
        }
    }

    private SuppressionRemover() {}
}
