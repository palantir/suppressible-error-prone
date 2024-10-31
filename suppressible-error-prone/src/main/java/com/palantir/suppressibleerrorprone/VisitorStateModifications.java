/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
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
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class VisitorStateModifications {

    private static final Pattern LAST_INDENT = Pattern.compile("(?:.|\\n)*\\n(?<indent>\\s*?)$", Pattern.MULTILINE);

    @SuppressWarnings("RestrictedApi")
    public static Description interceptDescription(VisitorState visitorState, Description description) {
        if (description == Description.NO_MATCH) {
            return Description.NO_MATCH;
        }

        // We can't just use visitorState.getPath() because there are checks that do not emit Descriptions
        // at the level they have descended to using the visitor. For example, UnusedVariable implements
        // only CompilationUnitTreeMatcher then manually descends itself. So we need to look at the path
        // to the actual error description.
        TreePath pathToActualError =
                TreePath.getPath(visitorState.getPath().getCompilationUnit(), description.position.getTree());

        Tree firstSuppressibleParent = Stream.iterate(pathToActualError, TreePath::getParentPath)
                .dropWhile(path -> !suppressibleKind(path.getLeaf().getKind()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Can't find anything we can suppress"))
                .getLeaf();

        // Guess the indent if we can't find it for some reason. Formatter will fix.
        String indent = indentForTree(visitorState, firstSuppressibleParent).orElse("    ");

        return Description.builder(
                        description.position,
                        description.checkName,
                        description.getLink(),
                        description.getMessageWithoutCheckName())
                .addFix(SuggestedFix.builder()
                        .prefixWith(
                                firstSuppressibleParent,
                                "@com.palantir.suppressibleerrorprone.RepeatableSuppressWarnings(\""
                                        + CommonConstants.AUTOMATICALLY_ADDED_PREFIX + description.checkName
                                        + "\")\n"
                                        + indent)
                        .build())
                .build();
    }

    private static Optional<String> indentForTree(VisitorState visitorState, Tree firstSuppressibleParent) {
        return Optional.ofNullable(visitorState.getSourceCode())
                .map(sourceCode -> LAST_INDENT.matcher(
                        sourceCode.subSequence(0, ((DiagnosticPosition) firstSuppressibleParent).getStartPosition())))
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1));
    }

    private VisitorStateModifications() {}

    private static boolean suppressibleKind(Tree.Kind kind) {
        switch (kind) {
            case CLASS:
            case METHOD:
            case VARIABLE:
                // VARIABLE includes fields
                return true;
            default:
                return false;
        }
    }
}
