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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.errorprone.VisitorState;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.Replacement;
import com.google.errorprone.fixes.Replacements.CoalescePolicy;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Name;
// CHECKSTYLE:ON

public final class VisitorStateModifications {
    private static final Logger log = Logger.getLogger(VisitorStateModifications.class.getName());

    private static final Map<Tree, OurFix> FIXES = new WeakHashMap<>();

    @SuppressWarnings("RestrictedApi")
    public static Description interceptDescription(VisitorState visitorState, Description description) {
        if (description == Description.NO_MATCH) {
            return description;
        }

        // We can't just use visitorState.getPath() because there are checks that do not emit Descriptions
        // at the level they have descended to using the visitor. For example, UnusedVariable implements
        // only CompilationUnitTreeMatcher then manually descends itself. So we need to look at the path
        // to the actual error description.
        TreePath pathToActualError =
                TreePath.getPath(visitorState.getPath().getCompilationUnit(), description.position.getTree());

        Optional<TreePath> firstSuppressible = Stream.iterate(
                        pathToActualError, treePath -> treePath.getParentPath() != null, TreePath::getParentPath)
                .dropWhile(path -> !suppressibleTree(path.getLeaf()))
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

        ModifiersTree modifiersTree = isModifiersTree(firstSuppressibleParent).get();

        Optional<? extends AnnotationTree> suppressWarnings = modifiersTree.getAnnotations().stream()
                .filter(annotation -> {
                    Name annotationName = annotationName(annotation.getAnnotationType());
                    return annotationName.contentEquals("SuppressWarnings");
                })
                .findFirst();

        boolean containedKey = FIXES.containsKey(firstSuppressibleParent);

        OurFix ourFix = FIXES.computeIfAbsent(
                firstSuppressibleParent,
                _ignored -> new OurFix(
                        Optional.ofNullable(visitorState.getSourceCode()), suppressWarnings, firstSuppressibleParent));

        ourFix.addError(description.checkName);

        if (containedKey) {
            return Description.NO_MATCH;
        }

        return Description.builder(
                        description.position,
                        description.checkName,
                        description.getLink(),
                        description.getMessageWithoutCheckName())
                .addFix(ourFix)
                .build();
    }

    private static final class OurReplacement extends Replacement {
        private final Supplier<Replacement> replacement;

        OurReplacement(Supplier<Replacement> replacement) {
            this.replacement = replacement;
        }

        @Override
        public Range<Integer> range() {
            return replacement.get().range();
        }

        @Override
        public String replaceWith() {
            return replacement.get().replaceWith();
        }
    }

    private static final class OurFix implements Fix {
        private final Optional<CharSequence> sourceCode;
        private final Optional<? extends AnnotationTree> suppressWarnings;
        private final Tree tree;
        private final Set<String> errors = new LinkedHashSet<>();

        OurFix(Optional<CharSequence> sourceCode, Optional<? extends AnnotationTree> suppressWarnings, Tree tree) {
            this.sourceCode = sourceCode;
            this.suppressWarnings = suppressWarnings;
            this.tree = tree;
        }

        private static String suppressWarningsString(List<String> warningsToSuppress) {
            String suppressWarningsString = '"' + String.join("\", \"", warningsToSuppress) + '"';

            if (warningsToSuppress.size() > 1) {
                suppressWarningsString = "{" + suppressWarningsString + "}";
            }
            return "@SuppressWarnings(" + suppressWarningsString + ")";
        }

        private CharSequence indentForTree() {
            return sourceCode
                    .map(actualSourceCode ->
                            whitespaceIndentBefore(actualSourceCode, ((DiagnosticPosition) tree).getStartPosition()))
                    .orElse("    ");
        }

        private Fix fix() {
            return suppressWarnings
                    .map(suppressWarningsAnnotation -> {
                        Set<String> existingValues = SuppressWarningsCoalesce.annotationStringValues(
                                        suppressWarningsAnnotation)
                                .collect(Collectors.toSet());
                        Set<String> toAdd = errors.stream()
                                .filter(Predicate.not(error -> existingValues.contains(error)
                                        || existingValues.contains(CommonConstants.AUTOMATICALLY_ADDED_PREFIX + error)))
                                .collect(Collectors.toSet());

                        List<String> warningsToSuppress = Stream.concat(
                                        existingValues.stream(),
                                        toAdd.stream()
                                                .sorted()
                                                .map(warning -> CommonConstants.AUTOMATICALLY_ADDED_PREFIX + warning))
                                .collect(Collectors.toList());

                        String suppressWarningsString = suppressWarningsString(warningsToSuppress);

                        return SuggestedFix.replace(suppressWarningsAnnotation, suppressWarningsString);
                    })
                    .orElseGet(() -> {
                        List<String> warningsToSuppress = errors.stream()
                                .sorted()
                                .map(warning -> CommonConstants.AUTOMATICALLY_ADDED_PREFIX + warning)
                                .collect(Collectors.toList());

                        String suppressWarningsString = suppressWarningsString(warningsToSuppress);

                        return SuggestedFix.prefixWith(tree, suppressWarningsString + "\n" + indentForTree());
                    });
        }

        public void addError(String error) {
            errors.add(error);
        }

        @Override
        public String toString(JCCompilationUnit compilationUnit) {
            return fix().toString(compilationUnit);
        }

        @Override
        public String getShortDescription() {
            return fix().getShortDescription();
        }

        @Override
        public CoalescePolicy getCoalescePolicy() {
            return CoalescePolicy.REJECT;
        }

        @Override
        public ImmutableSet<Replacement> getReplacements(EndPosTable endPositions) {
            return ImmutableSet.of(new OurReplacement(() -> fix().getReplacements(endPositions).iterator().next()));
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

    private static boolean suppressibleTree(Tree tree) {
        return isModifiersTree(tree).isPresent();
    }

    private static Optional<ModifiersTree> isModifiersTree(Tree tree) {
        // This covers all type definitions eg class, interface, enum, record, annotation, future kinds
        // of class-like type definitions.
        if (tree instanceof ClassTree) {
            return Optional.of(((ClassTree) tree).getModifiers());
        }

        if (tree instanceof MethodTree) {
            return Optional.of(((MethodTree) tree).getModifiers());
        }

        if (tree instanceof VariableTree) {
            return Optional.of(((VariableTree) tree).getModifiers());
        }

        return Optional.empty();
    }

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

    private VisitorStateModifications() {}
}
