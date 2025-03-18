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
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.Replacement;
import com.google.errorprone.fixes.Replacements.CoalescePolicy;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AnnotationTree;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.List;
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
        summary = "Remove for-rollout suppression warnings")
public final class RemoveRolloutSuppressions extends BugChecker implements BugChecker.AnnotationTreeMatcher {

    public static final String ARGUMENT = "SuppressibleErrorProne:RemoveForRolloutWarnings";

    @Override
    public Description matchAnnotation(AnnotationTree tree, VisitorState state) {
        Name annotationName = AnnotationUtils.annotationName(tree.getAnnotationType());
        if (!annotationName.contentEquals(CommonConstants.SUPPRESS_WARNINGS_ANNOTATION)) {
            return Description.NO_MATCH;
        }

        Set<String> suppressionsToRemove = state.errorProneOptions().getFlags().getSetOrEmpty(ARGUMENT).stream()
                // If no check is specified in the command line argument, the error prone option will look like
                //   "-XepOpt:SuppressibleErrorProne:RemoveForRolloutWarnings=" which will match to just an empty string
                // In this case, we actually want to remove all the suppressions
                .filter(s -> !s.isEmpty())
                .map(s -> CommonConstants.AUTOMATICALLY_ADDED_PREFIX + s)
                .collect(Collectors.toSet());

        List<String> existingSuppressions =
                AnnotationUtils.annotationStringValues(tree).collect(Collectors.toList());

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

        String updatedText = SuppressWarningsUtils.suppressWarningsString(updatedSuppressions);

        return buildDescription(tree)
                .addFix(new LineRemovingReplacementFix(state.getSourceCode(), (DiagnosticPosition) tree, updatedText))
                .build();
    }

    /**
     * This class has been introduced because the normal {@link com.google.errorprone.fixes.SuggestedFix} does not
     *   allow us to introduce a Replacement which has a different start position than the tree's normal start position.
     * Here we want to:
     *   - replace the element defined by the provided position
     *   - also replace the whitespace before the element, up to and including the newline
     * This way, if e.g. @SuppressWarnings("foo") must be removed entirely, we can remove the entire line, rather than
     *   just the annotation, leaving us with an empty line.
     *
     * Note that this will only delete the whitespace before the element if the entire element is removed
     *   (i.e. if the replacement text is null or empty).
     */
    private static final class LineRemovingReplacementFix implements Fix {
        private final CharSequence sourceCode;
        private final DiagnosticPosition position;
        private final String replacementText;

        private LineRemovingReplacementFix(
                CharSequence sourceCode, DiagnosticPosition position, String replacementText) {
            this.sourceCode = sourceCode;
            this.position = position;
            // Guarantee replacementText isn't empty to simplify the checks below
            this.replacementText = replacementText == null ? "" : replacementText;
        }

        @Override
        public String toString(JCCompilationUnit compilationUnit) {
            return "LineRemovingReplacementFix";
        }

        @Override
        public String getShortDescription() {
            return "Replace text at the position with the provided text, "
                    + "or remove the text and all preceding whitespace";
        }

        @Override
        public CoalescePolicy getCoalescePolicy() {
            return CoalescePolicy.REJECT;
        }

        @Override
        public ImmutableSet<Replacement> getReplacements(EndPosTable endPositions) {
            if (replacementText.isEmpty() && sourceCode != null) {
                int start = SourceCodeUtils.startPositionWithWhitespaceIncludingNewLine(
                        sourceCode, position.getStartPosition());
                return ImmutableSet.of(Replacement.create(start, position.getEndPosition(endPositions), ""));
            }
            return ImmutableSet.of(Replacement.create(
                    position.getStartPosition(), position.getEndPosition(endPositions), replacementText));
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
}
