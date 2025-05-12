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

import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.ClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.Name;
// CHECKSTYLE:ON

public final class VisitorStateModifications {
    private static final Logger log = Logger.getLogger(VisitorStateModifications.class.getName());

    private static final ThreadLocal<Boolean> IS_TRYING_UP_TREES = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<ArrayList<Description>> TRYING_UP_TREES_ERRORS =
            ThreadLocal.withInitial(ArrayList::new);

    // Weak map so that we don't leak memory by keeping hold of references to the source element tree keys and our
    // mutable fixes values around forever, once error-prone has finished with the source element tree used as a key
    // here (once the file has been visited by all the error-prone checks), our SuppressingFixes can be safely
    // garbage collected.
    private static final Map<Tree, SuppressingFix> FIXES = new WeakHashMap<>();

    @SuppressWarnings("RestrictedApi")
    public static Description interceptDescription(VisitorState visitorState, Description description) {
        if (description == Description.NO_MATCH) {
            return description;
        }

        // TODO(callumr): Extend VisitorState instead?
        if (IS_TRYING_UP_TREES.get()) {
            TRYING_UP_TREES_ERRORS.get().add(description);
            // Don't let errorprone do anything else with it
            return Description.NO_MATCH;
        }

        // If both -PerrorProneSuppress and -PerrorProneApply are used at the same time, for the checks configured as
        // "patchChecks" in the extension we need to use their suggested fixes instead of suppressing, so we can do
        // both in one pass as if you'd run -PerrorProneApply and -PerrorProneSuppress separately. We pass the checks
        // we prefer patching in this flag, as we still need errorprone to allow patching every check, so we can add
        // our suppressions.
        Set<String> patchChecks =
                visitorState.errorProneOptions().getFlags().getSetOrEmpty("SuppressibleErrorProne:PreferPatchChecks");

        boolean shouldPreferDefaultSuggestedFixesForThisCheck = patchChecks.contains(description.checkName);
        boolean checkHasSuggestedFixes = !description.fixes.isEmpty();

        if (shouldPreferDefaultSuggestedFixesForThisCheck && checkHasSuggestedFixes) {
            return description;
        }

        // If the check is a suggestion, we don't want to auto-suppress it, so we return no match (such that it also
        //    doesn't auto-fix it if not requested, which is caught in the above)
        if (visitorState.severityMap().get(description.checkName).equals(SeverityLevel.SUGGESTION)) {
            return Description.NO_MATCH;
        }

        // We can't just use visitorState.getPath() because there are checks that do not emit Descriptions
        // at the level they have descended to using the visitor. For example, UnusedVariable implements
        // only CompilationUnitTreeMatcher then manually descends itself. So we need to look at the path
        // to the actual error description.
        TreePath pathToActualError =
                TreePath.getPath(visitorState.getPath().getCompilationUnit(), description.position.getTree());

        Optional<TreePath> firstTreeWhereSuppressingWillActuallyWork =
                firstTreeWhereSuppressingWillActuallyWork(visitorState, pathToActualError, description.checkName);

        // If we can't find a suppressible parent, we can't add a suppression, so just give up.
        // This happens when there's a suppression on an import or compilation unit.
        // Imports should never be at error level as we can't suppress them. Or have an autofix that *always* works.
        if (firstTreeWhereSuppressingWillActuallyWork.isEmpty()) {
            log.warning("Couldn't find a suppressible parent for " + description.checkName + " at position "
                    + description.position.getStartPosition() + " in "
                    + visitorState.getPath().getCompilationUnit().getSourceFile() + "."
                    + " SuppressibleErrorProne will not be able to add a suppression for this error.");
            return Description.NO_MATCH;
        }

        Tree firstSuppressibleParent =
                firstTreeWhereSuppressingWillActuallyWork.get().getLeaf();

        ModifiersTree modifiersTree = modifiersTree(firstSuppressibleParent).get();

        Optional<? extends AnnotationTree> suppressWarnings = modifiersTree.getAnnotations().stream()
                .filter(annotation -> {
                    Name annotationName = AnnotationUtils.annotationName(annotation.getAnnotationType());
                    return annotationName.contentEquals(CommonConstants.SUPPRESS_WARNINGS_ANNOTATION);
                })
                .findFirst();

        // In order to be able to suppress multiple errors in one pass on the same element, we need to do a single
        // Fix/Replacement in error-prone. It's not possible to do this bit by bit with multiple Replacements. To do
        // this, we make sure we only make one fix per source element we put the suppression on by using a Map. This
        // way we have our own mutable Fix that we can add errors to, and only once the file has been visited by all
        // the error-prone checks it will then produce a replacement with all the checks suppressed.
        boolean alreadyReportedFix = FIXES.containsKey(firstSuppressibleParent);

        SuppressingFix suppressingFix = FIXES.computeIfAbsent(
                firstSuppressibleParent,
                _ignored -> new SuppressingFix(
                        Optional.ofNullable(visitorState.getSourceCode()), suppressWarnings, firstSuppressibleParent));

        suppressingFix.addSuppression(description.checkName);

        // If we already submitted our mutable fix, we don't need to do so again, just need to add the error to the fix.
        if (alreadyReportedFix) {
            return Description.NO_MATCH;
        }

        return Description.builder(
                        description.position,
                        description.checkName,
                        description.getLink(),
                        description.getMessageWithoutCheckName())
                .addFix(suppressingFix)
                .build();
    }

    private static Optional<TreePath> firstTreeWhereSuppressingWillActuallyWork(
            VisitorState visitorState, TreePath path, String checkName) {
        return suppressibleTreesInPath(path)
                .filter(treePath ->
                        treePath.getLeaf().equals(visitorState.getPath().getLeaf())
                                || suppressibleTreeActuallySuppressesCheck(visitorState, checkName, treePath.getLeaf()))
                .findFirst();
    }

    private static boolean suppressibleTreeActuallySuppressesCheck(
            VisitorState visitorState, String checkName, Tree someTree) {
        JavacProcessingEnvironment processingEnvironment = JavacProcessingEnvironment.instance(visitorState.context);
        ClassLoader loader = processingEnvironment.getProcessorClassLoader();
        BugChecker bugChecker = ServiceLoader.load(BugChecker.class, loader).stream()
                .map(Provider::get)
                .filter(checker -> checker.canonicalName().equals(checkName))
                .findFirst()
                .get();

        ModifiersTree modifiersTree = modifiersTree(someTree)
                .orElseThrow(() -> new IllegalStateException("Could not get ModifiersTree for a suppressible element. "
                        + "This is a bug in suppressible-error-prone. Tree: \n\n"
                        + someTree));

        JCModifiers jcModifiers = (JCModifiers) modifiersTree;

        JCTree newTree = new SuppressWarningsAddingTreeTranslator(visitorState, jcModifiers, checkName).translateTree();

        IS_TRYING_UP_TREES.set(true);
        TRYING_UP_TREES_ERRORS.set(new ArrayList<>());

        Description returnedDescription;

        try {
            returnedDescription = ((ClassTreeMatcher) bugChecker)
                    .matchClass(
                            (JCClassDecl) newTree,
                            visitorState.withPath(
                                    new TreePath(visitorState.getPath().getParentPath(), newTree)));
        } finally {
            IS_TRYING_UP_TREES.set(false);
        }
        ArrayList<Description> reportMatchDescriptions = TRYING_UP_TREES_ERRORS.get();
        reportMatchDescriptions.add(returnedDescription);

        // TODO(callumr): Handle multiple descriptions

        return reportMatchDescriptions.size() == 1 && reportMatchDescriptions.get(0) == Description.NO_MATCH;
    }

    private static Stream<TreePath> suppressibleTreesInPath(TreePath initialPath) {
        return Stream.iterate(initialPath, treePath -> treePath.getParentPath() != null, TreePath::getParentPath)
                .filter(path -> suppressibleTree(path.getLeaf()));
    }

    private static boolean suppressibleTree(Tree tree) {
        return modifiersTree(tree).isPresent();
    }

    private static Optional<ModifiersTree> modifiersTree(Tree tree) {
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

    private VisitorStateModifications() {}
}
