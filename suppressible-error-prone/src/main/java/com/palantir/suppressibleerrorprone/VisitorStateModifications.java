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

// CHECKSTYLE:OFF
import com.google.errorprone.VisitorState;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
// CHECKSTYLE:ON

public final class VisitorStateModifications {
    private static final Logger log = Logger.getLogger(VisitorStateModifications.class.getName());

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

        Optional<TreePath> firstSuppressible = Stream.iterate(
                        pathToActualError, treePath -> treePath.getParentPath() != null, TreePath::getParentPath)
                .dropWhile(path -> !suppressibleKind(path.getLeaf().getKind()))
                .findFirst();

        // If we can't find a suppressible parent, we can't add a suppression, so just give up.
        // This happens when there's a suppression on an import or compilation unit.
        // Imports should never be at error level as we can't suppress them. Or have an autofix that *always* works.
        if (firstSuppressible.isEmpty()) {
            log.warning("Couldn't find a suppressible parent for " + description.checkName + " at position "
                    + description.position.getStartPosition() + " in "
                    + visitorState.getPath().getCompilationUnit().getSourceFile() + "."
                    + " SuppressibleErrorProne will not be able to add a suppression for this error.");
            return Description.NO_MATCH;
        }

        Tree firstSuppressibleParent = firstSuppressible.get().getLeaf();

        // Guess the indent if we can't find it for some reason. Formatter will fix.
        CharSequence indent =
                indentForTree(visitorState, firstSuppressibleParent).orElse("    ");

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

    private static Optional<CharSequence> indentForTree(VisitorState visitorState, Tree firstSuppressibleParent) {
        return Optional.ofNullable(visitorState.getSourceCode())
                .map(sourceCode -> whitespaceIndentBefore(
                        sourceCode, ((DiagnosticPosition) firstSuppressibleParent).getStartPosition()));
    }

    static CharSequence whitespaceIndentBefore(CharSequence sourceCode, int sourceElementPosition) {
        int pos = sourceElementPosition - 1;

        for (; pos >= 0; pos--) {
            char character = sourceCode.charAt(pos);
            if (character == '\n' || !Character.isWhitespace(character)) {
                break;
            }
        }

        return sourceCode.subSequence(pos + 1, sourceElementPosition);
    }

    private static boolean suppressibleKind(Tree.Kind kind) {
        // This covers all type definitions eg class, interface, enum, record, annotation, future kinds
        // of class-like type definitions.
        if (kind.asInterface().equals(ClassTree.class)) {
            return true;
        }

        // VARIABLE includes fields
        switch (kind) {
            case METHOD:
            case VARIABLE:
                return true;
            default:
                return false;
        }
    }

    private VisitorStateModifications() {}
}
