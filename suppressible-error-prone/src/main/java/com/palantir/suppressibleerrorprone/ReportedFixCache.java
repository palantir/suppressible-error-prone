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

import com.google.common.base.Predicates;
import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Name;

final class ReportedFixCache {
    private final Map<Tree, LazySuppressionFix> cache = new WeakHashMap<>();
    private final Predicate<String> initializer;

    private ReportedFixCache(Predicate<String> initializer) {
        this.initializer = initializer;
    }

    public static ReportedFixCache startWithUnmodified() {
        return new ReportedFixCache(suppression -> true);
    }

    public static ReportedFixCache startWithRemovingAll() {
        return new ReportedFixCache(suppression -> false);
    }

    public static ReportedFixCache startWithRemoving(Set<String> suppressionsToRemove) {
        System.err.println(suppressionsToRemove);
        return new ReportedFixCache(Predicates.not(suppressionsToRemove::contains));
    }

    /**
     * Gets an existing fix on this declaration if it exists. Otherwise, initialize the fix with
     * declaration is a TreePath because we need information about it's parent to check if it is suppressible
     */
    public LazySuppressionFix getOrReportNew(TreePath declaration, VisitorState visitorState) {
        return cache.computeIfAbsent(declaration.getLeaf(), _ignored -> createAndReportFix(declaration, visitorState));
    }

    @SuppressWarnings("RestrictedApi")
    private LazySuppressionFix createAndReportFix(TreePath declaration, VisitorState state) {
        if (!SuppressWarningsUtils.suppressibleTreePath(declaration)) {
            throw new IllegalArgumentException("Not suppressible: " + declaration);
        }
        ModifiersTree modifiersTree = AnnotationUtils.getModifiers(declaration.getLeaf());
        Optional<? extends AnnotationTree> suppressWarnings = modifiersTree.getAnnotations().stream()
                .filter(annotation -> {
                    Name annotationName = AnnotationUtils.annotationName(annotation.getAnnotationType());
                    return annotationName.contentEquals(CommonConstants.SUPPRESS_WARNINGS_ANNOTATION);
                })
                .findFirst();

        Stream<String> allExistingSuppressions =
                suppressWarnings.map(AnnotationUtils::annotationStringValues).orElseGet(Stream::of);
        Set<String> filteredExistingSuppressions =
                allExistingSuppressions.filter(initializer).collect(Collectors.toSet());

        LazySuppressionFix fix = new LazySuppressionFix(
                Optional.ofNullable(state.getSourceCode()),
                suppressWarnings,
                declaration.getLeaf(),
                filteredExistingSuppressions);
        Description description = Description.builder(
                        (DiagnosticPosition) declaration.getLeaf(), "SuppressibleErrorProne", "", "")
                .addFix(fix)
                .build();
        state.reportMatch(description);
        return fix;
    }
}
