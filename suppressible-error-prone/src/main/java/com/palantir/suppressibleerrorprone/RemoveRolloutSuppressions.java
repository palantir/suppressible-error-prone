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

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.Tree;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Name;

/**
 * This error-prone check is meant to flag and remove specific for-rollout: suppression warnings that the
 *   main gradle plugin would have introduced, as a way to help open pull requests flagging where they might still
 *   need to be fixed.
 */
@AutoService(BugChecker.class)
@BugPattern(
        link = "https://github.com/palantir/suppressible-error-prone",
        linkType = BugPattern.LinkType.CUSTOM,
        // This needs to be SUGGESTION so that error prone won't try to apply the check in normal operations
        // When requested, we will directly enable it in the command line arguments
        severity = BugPattern.SeverityLevel.SUGGESTION,
        summary = "Remove for-rollout suppression warnings",
        // Make it unsuppressible so that it can actually remove itself
        suppressionAnnotations = {})
public final class RemoveRolloutSuppressions extends BugChecker implements BugChecker.AnnotationTreeMatcher {

    public static final String ARGUMENT = "SuppressibleErrorProne:RemoveRolloutSuppressions";

    @Override
    public Description matchAnnotation(AnnotationTree tree, VisitorState state) {
        Name annotationName = AnnotationUtils.annotationName(tree.getAnnotationType());
        if (!annotationName.contentEquals(CommonConstants.SUPPRESS_WARNINGS_ANNOTATION)) {
            return Description.NO_MATCH;
        }

        Set<String> suppressionsToRemove = state.errorProneOptions().getFlags().getSetOrEmpty(ARGUMENT).stream()
                // If no check is specified in the command line argument, the error prone option will look like
                //   "-XepOpt:SuppressibleErrorProne:RemoveRolloutSuppressions=" which will match to just an empty
                // string. In this case, we actually want to remove all the suppressions
                .filter(s -> !s.isEmpty())
                .map(s -> CommonConstants.AUTOMATICALLY_ADDED_PREFIX + s)
                .collect(Collectors.toSet());

        List<String> existingSuppressions =
                AnnotationUtils.annotationStringValues(tree).toList();

        final List<String> updatedSuppressions;
        if (suppressionsToRemove.isEmpty()) {
            // We want to remove all automated suppressions if no specific argument is passed
            updatedSuppressions = existingSuppressions.stream()
                    .filter(suppression -> !suppression.startsWith(CommonConstants.AUTOMATICALLY_ADDED_PREFIX))
                    .collect(Collectors.toList());
        } else {
            updatedSuppressions = existingSuppressions.stream()
                    .filter(suppression -> !suppressionsToRemove.contains(suppression))
                    .collect(Collectors.toList());
        }

        if (existingSuppressions.size() == updatedSuppressions.size()) {
            return Description.NO_MATCH;
        }

        Tree declaration = state.getPath().getParentPath().getParentPath().getLeaf();
        return buildDescription(tree)
                .addFix(new LazySuppressionFix(
                        Optional.ofNullable(state.getSourceCode()),
                        Optional.of(tree),
                        declaration,
                        new HashSet<>(updatedSuppressions)))
                .build();
    }
}
