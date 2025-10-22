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
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Name;

final class ReportedFixCache {
    // Weak map so that we don't leak memory by keeping hold of references to the source element tree keys and our
    // mutable fixes values around forever, once error-prone has finished with the source element tree used as a key
    // here (once the file has been visited by all the error-prone checks), our SuppressingFixes can be safely
    // garbage collected.
    private final WeakHashMap<Tree, LazySuppressionFix> cache = new WeakHashMap<>();

    public static final Predicate<String> REMOVE_EVERYTHING = bc -> false;
    public static final Predicate<String> KEEP_EVERYTHING = bc -> true;
    public static final Predicate<String> NOT_AN_ERRORPRONE = suppression -> {
        String checkerName = suppression.startsWith(CommonConstants.AUTOMATICALLY_ADDED_PREFIX)
                ? suppression.substring(CommonConstants.AUTOMATICALLY_ADDED_PREFIX.length())
                : suppression;
        return !AllErrorprones.allBugcheckerNames().contains(checkerName);
    };

    ReportedFixCache() {}

    /**
     * When first called on a {@code declaration}, initialize a {@code LazySuppressionFix} on it, choosing which
     * suppressions to keep with {@code filterExisting}. Subsequent calls on this {@code declaration} will return the
     * initialized {@code LazySuppressionFix}.
     */
    public LazySuppressionFix getOrReportNew(
            TreePath declaration, VisitorState visitorState, Predicate<String> filterExisting) {
        return cache.computeIfAbsent(
                declaration.getLeaf(), _ignored -> createAndReportFix(declaration, visitorState, filterExisting));
    }

    /**
     * Initialize a {@code LazySuppressionFix} on {@code declaration}, choosing which suppressions to keep with
     * {@code filterExisting}.
     */
    @SuppressWarnings("PreferSafeLoggableExceptions") // It doesn't matter in our internal codebases
    public LazySuppressionFix reportNew(
            TreePath declaration, VisitorState visitorState, Predicate<String> filterExisting) {
        if (cache.containsKey(declaration.getLeaf())) {
            throw new IllegalArgumentException("A fix on this declaration already exists");
        }
        return cache.put(declaration.getLeaf(), createAndReportFix(declaration, visitorState, filterExisting));
    }

    /**
     * Gets an existing {@code LazySuppressionFix} on {@code declaration}
     */
    public LazySuppressionFix getExisting(TreePath declaration) {
        if (!cache.containsKey(declaration.getLeaf())) {
            throw new IllegalArgumentException("A fix on this declaration must already exists");
        }
        return cache.get(declaration.getLeaf());
    }

    private LazySuppressionFix createAndReportFix(
            TreePath declaration, VisitorState state, Predicate<String> filterExisting) {
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
                allExistingSuppressions.filter(filterExisting).collect(Collectors.toSet());

        LazySuppressionFix fix = new LazySuppressionFix(
                Optional.ofNullable(state.getSourceCode()),
                suppressWarnings,
                declaration.getLeaf(),
                filteredExistingSuppressions);
        @SuppressWarnings("RestrictedApi")
        Description description = Description.builder(
                        (DiagnosticPosition) declaration.getLeaf(),
                        SuppressibleErrorProne.class.getSimpleName(),
                        "https://github.com/palantir/suppressible-error-prone",
                        "A fix on a suppressible by suppressible-error-prone")
                .addFix(fix)
                .build();
        state.reportMatch(description);
        return fix;
    }
}
