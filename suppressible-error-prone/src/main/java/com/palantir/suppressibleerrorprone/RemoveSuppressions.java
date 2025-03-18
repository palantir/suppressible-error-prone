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
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Name;

@AutoService(BugChecker.class)
@BugPattern(
        // TODO(aldexis): add docs to readme and link to it
        link = "https://github.com/palantir/suppressible-error-prone",
        linkType = BugPattern.LinkType.CUSTOM,
        severity = BugPattern.SeverityLevel.ERROR,
        summary = "Remove specific suppression warnings")
public final class RemoveSuppressions extends BugChecker implements BugChecker.AnnotationTreeMatcher {

    @Override
    public Description matchAnnotation(AnnotationTree tree, VisitorState state) {
        Name annotationName = annotationName(tree.getAnnotationType());
        if (!annotationName.contentEquals("SuppressWarnings")) {
            return Description.NO_MATCH;
        }

        Set<String> suppressionsToRemove = state
                .errorProneOptions()
                .getFlags()
                .getSetOrEmpty("SuppressibleErrorProne:RemoveForRolloutWarnings")
                .stream()
                .map(s -> CommonConstants.AUTOMATICALLY_ADDED_PREFIX + s)
                .collect(Collectors.toSet());

        List<String> existingSuppressions =
                AnnotationUtils.annotationStringValues(tree).collect(Collectors.toList());

        List<String> updatedSuppressions = existingSuppressions.stream()
                .filter(suppression -> !suppressionsToRemove.contains(suppression))
                .collect(Collectors.toList());

        if (existingSuppressions.size() == updatedSuppressions.size()) {
            return Description.NO_MATCH;
        }

        String updatedText = SuppressWarningsUtils.suppressWarningsString(updatedSuppressions);

        return buildDescription(tree)
                .addFix(new SuppressionFix(state.getSourceCode(), (DiagnosticPosition) tree, updatedText))
                .build();
    }

    // TODO(aldexis): extract (see identical method in VisitorStateModifications)
    private static Name annotationName(Tree annotationType) {
        if (annotationType instanceof IdentifierTree) {
            return ((IdentifierTree) annotationType).getName();
        }

        if (annotationType instanceof MemberSelectTree) {
            return ((MemberSelectTree) annotationType).getIdentifier();
        }

        throw new UnsupportedOperationException(
                "Unsupported annotation type: " + annotationType.getClass().getCanonicalName());
    }

    private static final class SuppressionFix implements Fix {
        private final CharSequence sourceCode;
        private final DiagnosticPosition position;
        private final String replacementText;

        private SuppressionFix(CharSequence sourceCode, DiagnosticPosition position, String replacementText) {
            this.sourceCode = sourceCode;
            this.position = position;
            this.replacementText = replacementText;
        }

        @Override
        public String toString(JCCompilationUnit compilationUnit) {
            return "SuppressionFix";
        }

        @Override
        public String getShortDescription() {
            return "TODO";
        }

        @Override
        public CoalescePolicy getCoalescePolicy() {
            return CoalescePolicy.REJECT;
        }

        @Override
        public ImmutableSet<Replacement> getReplacements(EndPosTable endPositions) {
            if (replacementText.isEmpty() && sourceCode != null) {
                // TODO(aldexis): handle case of not a newline
                int start = SourceCodeUtils.startPositionWithWhitespace(sourceCode, position.getStartPosition()) - 1;
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
