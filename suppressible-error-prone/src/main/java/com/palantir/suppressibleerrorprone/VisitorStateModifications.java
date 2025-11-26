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
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import one.util.streamex.MoreCollectors;

// CHECKSTYLE:ON

@SuppressWarnings("RestrictedApi")
public final class VisitorStateModifications {
    private static final Logger log = Logger.getLogger(VisitorStateModifications.class.getName());
    private static final ReportedFixCache FIXES = new ReportedFixCache();

    @SuppressWarnings("CyclomaticComplexity")
    public static Description interceptDescription(VisitorState visitorState, Description description) {
        // Prevent infinite recursion on reported fixes
        if (description == Description.NO_MATCH
                || description.checkName.equals(SuppressibleErrorProne.class.getSimpleName())) {
            return description;
        }

        CompilationUnitTree compilationUnit = visitorState.getPath().getCompilationUnit();
        Set<String> modes = getModes(visitorState);

        if (modes.contains("RemoveUnused")) {
            // Start by removing all suppressions on error-prones, including rollout and human-made.
            // Then, as we encounter Descriptions without fixes, we add back the closest suppression
            SuppressionRemover.removeAllSuppressionsOnErrorprones(FIXES, compilationUnit, visitorState);
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
        if (visitorState.severityMap().get(description.checkName) == SeverityLevel.SUGGESTION) {
            return Description.NO_MATCH;
        }

        // We can't just use visitorState.getPath() because there are checks that do not emit Descriptions
        // at the level they have descended to using the visitor. For example, UnusedVariable implements
        // only CompilationUnitTreeMatcher then manually descends itself. So we need to look at the path
        // to the actual error description.
        TreePath pathToActualError =
                TreePath.getPath(visitorState.getPath().getCompilationUnit(), description.position.getTree());

        Optional<TreePath> firstSuppressibleWhichSuppressesDescription = Stream.iterate(
                        pathToActualError, treePath -> treePath.getParentPath() != null, TreePath::getParentPath)
                .dropWhile(path -> !suppresses(path, description, visitorState))
                .findFirst();

        if (firstSuppressibleWhichSuppressesDescription.isPresent() && modes.contains("RemoveUnused")) {
            // In RemoveUnused mode, removeAllSuppressionsOnErrorprones guarantees that a fix must already exist on
            // this suppressible.
            TreePath declaration = firstSuppressibleWhichSuppressesDescription.get();
            Set<String> allNames =
                    AllErrorprones.allNames(visitorState, description.checkName).get();
            // Use the existing suppression, rather than changing it to the canonical suppression
            String existingSuppression = AnnotationUtils.annotationStringValues(
                            SuppressWarningsUtils.getSuppressWarnings(declaration)
                                    .get())
                    .filter(suppression -> allNames.contains(SuppressWarningsUtils.stripForRollout(suppression)))
                    .collect(MoreCollectors.first())
                    .get();
            FIXES.getExisting(declaration).addSuppression(existingSuppression);
            return Description.NO_MATCH;
        }

        if (!modes.contains("Suppress")) {
            log.warning("No autofix was found for " + description.checkName + " at position "
                    + description.position.getStartPosition() + " in "
                    + visitorState.getPath().getCompilationUnit().getSourceFile() + "."
                    + " -PerrorProneSuppress was not passed either. "
                    + " SuppressibleErrorProne will not be able to add a suppression for this error.");
            // Returning description here could trigger an unintended autofix
            return Description.NO_MATCH;
        }

        Optional<TreePath> firstSuppressible = Stream.iterate(
                        pathToActualError, treePath -> treePath.getParentPath() != null, TreePath::getParentPath)
                .dropWhile(path -> !SuppressWarningsUtils.suppressibleTreePath(path))
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

        // In order to be able to suppress multiple errors in one pass on the same element, we need to do a single
        // Fix/Replacement in error-prone. It's not possible to do this bit by bit with multiple Replacements. To do
        // this, we make sure we only make one fix per source element we put the suppression on by using a Map. This
        // way we have our own mutable Fix that we can add errors to, and only once the file has been visited by all
        // the error-prone checks it will then produce a replacement with all the checks suppressed.
        LazySuppressionFix suppressingFix = FIXES.getOrReportNew(firstSuppressible.get(), visitorState, bc -> true);
        suppressingFix.addSuppression(CommonConstants.AUTOMATICALLY_ADDED_PREFIX + description.checkName);
        return Description.NO_MATCH;
    }

    private static boolean suppresses(TreePath declaration, Description description, VisitorState state) {
        return !suppressionsOn(declaration, description, state).isEmpty();
    }

    private static List<String> suppressionsOn(TreePath declaration, Description description, VisitorState state) {
        if (!SuppressWarningsUtils.suppressibleTreePath(declaration)) {
            return List.of();
        }

        Optional<? extends AnnotationTree> suppressWarningsMaybe =
                SuppressWarningsUtils.getSuppressWarnings(declaration);
        if (suppressWarningsMaybe.isEmpty()) {
            return List.of();
        }

        return AnnotationUtils.annotationStringValues(suppressWarningsMaybe.get())
                .filter(suppression -> AllErrorprones.possibleCanonicalNames(
                                state, SuppressWarningsUtils.stripForRollout(suppression))
                        .contains(description.checkName))
                .toList();
    }

    private static Set<String> getModes(VisitorState state) {
        return state.errorProneOptions().getFlags().getSetOrEmpty("SuppressibleErrorProne:Mode");
    }

    private VisitorStateModifications() {}
}
