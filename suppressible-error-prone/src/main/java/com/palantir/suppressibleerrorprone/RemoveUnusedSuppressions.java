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
import com.google.errorprone.ErrorProneOptions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.scanner.ErrorProneScanner;
import com.google.errorprone.suppliers.Supplier;
import com.palantir.suppressibleerrorprone.UnusedSuppressionsTree.TreeWithUnusedSuppressions;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This error-prone check identifies and removes unused @SuppressWarnings annotations.
 * It works by checking each suppression warning against its associated BugChecker to determine
 * if the suppression is actually needed.
 */
@AutoService(BugChecker.class)
@BugPattern(
        link = "https://github.com/palantir/suppressible-error-prone",
        linkType = BugPattern.LinkType.CUSTOM,
        // This needs to be SUGGESTION so that error prone won't try to apply the check in normal operations
        // When requested, we will directly enable it in the command line arguments
        severity = BugPattern.SeverityLevel.SUGGESTION,
        summary = "Remove unused @SuppressWarnings annotations")
@SuppressWarnings("TreeToString")
public final class RemoveUnusedSuppressions extends BugChecker implements BugChecker.CompilationUnitTreeMatcher {
    private static final Supplier<BugCheckerRegistry> enabledBugCheckers =
            VisitorState.memoize(BugCheckerRegistry::constructFromEnabledCheckers);

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        UnusedSuppressionsTree unusedSuppressionsTree = UnusedSuppressionsTree.initializeWithSuppressions(tree);

        if (unusedSuppressionsTree.allSuppressionNames().isEmpty()) {
            return Description.NO_MATCH;
        }

        for (String suppression : unusedSuppressionsTree.allSuppressionNames()) {
            Optional<BugChecker> bugCheckerMaybe = enabledBugCheckers.get(state).get(suppression);
            if (bugCheckerMaybe.isEmpty()) {
                unusedSuppressionsTree.markAllSuppressionsAsUsed(suppression);
                continue;
            }

            // customState uses the same compilation context and severity map (which tells us which bugcheckers are
            // enabled) as the main compilation, but with two tweaks:
            // 1. The DescriptionListener usually takes your reported Descriptions and reports them to javac and makes
            // changes to source. We use a custom listener which does none of that, and just reports any Descriptions to
            // unusedSuppressionTree
            // 2. Turn on XepIgnoreSuppressionAnnotations in ErrorProneOptions.
            VisitorState customState = VisitorState.createConfiguredForCompilation(
                            state.context,
                            description -> {
                                if (description != Description.NO_MATCH) {
                                    unusedSuppressionsTree.flagFirstParentSuppressionAsUsed(
                                            description.position.getTree(), description.checkName);
                                }
                            },
                            state.severityMap(),
                            ignoreSuppressions(state.errorProneOptions()))
                    .withPath(state.getPath());
            new ErrorProneScanner(bugCheckerMaybe.get()).scan(tree, customState);
        }

        for (TreeWithUnusedSuppressions treeWithUnusedSuppressions : unusedSuppressionsTree.unused()) {
            Set<String> unusedSuppressions = treeWithUnusedSuppressions.unusedSuppressions();
            List<? extends AnnotationTree> suppressions =
                    getModifiersTree(treeWithUnusedSuppressions).getAnnotations().stream()
                            .filter(AnnotationUtils::isSuppressWarningsAnnotation)
                            .toList();
            for (AnnotationTree suppression : suppressions) {
                Fix fix = createFix(suppression, unusedSuppressions, state);
                state.reportMatch(buildDescription(tree)
                        .setMessage("Remove unused @SuppressWarnings: " + unusedSuppressions)
                        .addFix(fix)
                        .build());
            }
        }

        return Description.NO_MATCH;
    }

    private static ModifiersTree getModifiersTree(TreeWithUnusedSuppressions treeWithUnusedSuppressions) {
        Tree declarationTree = treeWithUnusedSuppressions.tree();
        if (declarationTree instanceof MethodTree methodTree) {
            return methodTree.getModifiers();
        } else if (declarationTree instanceof ClassTree classTree) {
            return classTree.getModifiers();
        } else if (declarationTree instanceof VariableTree variableTree) {
            return variableTree.getModifiers();
        } else {
            throw new IllegalStateException("Unexpected tree type: " + declarationTree.getClass());
        }
    }

    // Annoyingly, we have to construct a fresh ErrorProneOptions and copy the rest of the flags manually,
    // before turning on XepIgnoreSuppressionAnnotations. This is so fragile :|
    @SuppressWarnings("CyclomaticComplexity")
    private static ErrorProneOptions ignoreSuppressions(ErrorProneOptions originalOptions) {
        List<String> args = new ArrayList<>();
        args.add("-XepIgnoreSuppressionAnnotations");

        // Reconstruct severity mappings
        originalOptions.getSeverityMap().forEach((check, severity) -> {
            args.add("-Xep:" + check + ":" + severity);
        });

        // Reconstruct boolean flags
        if (originalOptions.ignoreUnknownChecks()) {
            args.add("-XepIgnoreUnknownCheckNames");
        }
        if (originalOptions.disableWarningsInGeneratedCode()) {
            args.add("-XepDisableWarningsInGeneratedCode");
        }
        if (originalOptions.isDisableAllWarnings()) {
            args.add("-XepDisableAllWarnings");
        }
        if (originalOptions.isDropErrorsToWarnings()) {
            args.add("-XepAllErrorsAsWarnings");
        }
        if (originalOptions.isSuggestionsAsWarnings()) {
            args.add("-XepAllSuggestionsAsWarnings");
        }
        if (originalOptions.isEnableAllChecksAsWarnings()) {
            args.add("-XepAllDisabledChecksAsWarnings");
        }
        if (originalOptions.isDisableAllChecks()) {
            args.add("-XepDisableAllChecks");
        }
        if (originalOptions.isTestOnlyTarget()) {
            args.add("-XepCompilingTestOnlyCode");
        }
        if (originalOptions.isPubliclyVisibleTarget()) {
            args.add("-XepCompilingPubliclyVisibleCode");
        }

        // Reconstruct excluded paths pattern
        if (originalOptions.getExcludedPattern() != null) {
            args.add("-XepExcludedPaths:" + originalOptions.getExcludedPattern().pattern());
        }

        return ErrorProneOptions.processArgs(args);
    }

    private static Fix createFix(AnnotationTree suppressWarnings, Set<String> unusedSuppressions, VisitorState state) {
        List<String> currentSuppressions =
                AnnotationUtils.annotationStringValues(suppressWarnings).toList();
        List<String> remainingSuppressions = currentSuppressions.stream()
                .filter(s -> !unusedSuppressions.contains(s))
                .collect(Collectors.toList());
        String newSuppressWarnings = SuppressWarningsUtils.suppressWarningsString(remainingSuppressions);
        return new LineRemovingReplacementFix(
                state.getSourceCode(), (DiagnosticPosition) suppressWarnings, newSuppressWarnings);
    }
}
