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
 * This error-prone check removes human-authored @SuppressWarnings annotations that are no longer necessary
 * because the corresponding error-prone checks are not triggered in the current compilation.
 * 
 * This works in conjunction with the automatic suppression feature to allow cleaning up unnecessary 
 * suppressions in the same compilation pass as adding new ones.
 */
@AutoService(BugChecker.class)
@BugPattern(
        link = "https://github.com/palantir/suppressible-error-prone",
        linkType = BugPattern.LinkType.CUSTOM,
        // This needs to be SUGGESTION so that error prone won't try to apply the check in normal operations
        // When requested, we will directly enable it in the command line arguments
        severity = BugPattern.SeverityLevel.SUGGESTION,
        summary = "Remove unnecessary human-authored suppression warnings",
        // Make it unsuppressible so that it can actually remove itself
        suppressionAnnotations = {})
public final class RemoveUnnecessarySuppressions extends BugChecker implements BugChecker.AnnotationTreeMatcher {

    public static final String ARGUMENT = "SuppressibleErrorProne:RemoveUnnecessarySuppressions";

    @Override
    public Description matchAnnotation(AnnotationTree tree, VisitorState state) {
        Name annotationName = AnnotationUtils.annotationName(tree.getAnnotationType());
        if (!annotationName.contentEquals(CommonConstants.SUPPRESS_WARNINGS_ANNOTATION)) {
            return Description.NO_MATCH;
        }

        // Only proceed if the feature is enabled
        boolean isEnabled = state.errorProneOptions().getFlags().getBoolean(ARGUMENT).orElse(false);
        if (!isEnabled) {
            return Description.NO_MATCH;
        }

        List<String> existingSuppressions =
                AnnotationUtils.annotationStringValues(tree).toList();

        // Get the set of encountered errors from the global tracker
        Set<String> encounteredErrors = VisitorStateModifications.getGlobalEncounteredErrors();

        // Filter out human-authored suppressions that don't correspond to encountered errors
        // Keep automatic suppressions (those with the "for-rollout:" prefix) as they are handled separately
        List<String> updatedSuppressions = existingSuppressions.stream()
                .filter(suppression -> {
                    // Keep automatic suppressions
                    if (suppression.startsWith(CommonConstants.AUTOMATICALLY_ADDED_PREFIX)) {
                        return true;
                    }
                    // Keep human-authored suppressions only if the corresponding error was encountered
                    return encounteredErrors.contains(suppression);
                })
                .collect(Collectors.toList());

        // If no suppressions were removed, no fix is needed
        if (existingSuppressions.size() == updatedSuppressions.size()) {
            return Description.NO_MATCH;
        }

        String updatedText = SuppressWarningsUtils.suppressWarningsString(updatedSuppressions);

        return buildDescription(tree)
                .addFix(new LineRemovingReplacementFix(state.getSourceCode(), (DiagnosticPosition) tree, updatedText))
                .build();
    }

    /**
     * Reuse the same LineRemovingReplacementFix from RemoveRolloutSuppressions to handle
     * removing entire lines when annotations are completely removed.
     */
    private static final class LineRemovingReplacementFix implements Fix {
        private final CharSequence sourceCode;
        private final DiagnosticPosition position;
        private final String replacementText;

        private LineRemovingReplacementFix(
                CharSequence sourceCode, DiagnosticPosition position, String replacementText) {
            this.sourceCode = sourceCode;
            this.position = position;
            this.replacementText = replacementText;
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
            // If we are looking to delete the entire element, we should also remove whitespace before it,
            //   up to and including the newline
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
