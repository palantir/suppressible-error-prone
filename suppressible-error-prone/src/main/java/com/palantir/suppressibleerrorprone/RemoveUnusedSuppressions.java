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
import com.google.errorprone.ErrorProneOptions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.Replacement;
import com.google.errorprone.fixes.Replacements.CoalescePolicy;
import com.google.errorprone.matchers.Description;
import com.palantir.suppressibleerrorprone.SuppressionUsageTree.TreeWithUnusedSuppressions;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // Thread-local flag to track when a match is reported during scanning
    private static final ThreadLocal<AtomicBoolean> MATCH_REPORTED =
            ThreadLocal.withInitial(() -> new AtomicBoolean(false));

    // Cache the registry per compilation to avoid repeated instantiation
    private volatile CheckerRegistry cachedRegistry;

    @Override
    public Description matchCompilationUnit(CompilationUnitTree tree, VisitorState state) {
        // build SuppressionUsageTree
        SuppressionUsageTree suppressionUsageTree = SuppressionUsageTree.constructSuppressions(tree, state);
        System.err.println("All suppressions found in compilation unit: " + suppressionUsageTree.allSuppressionNames());

        if (suppressionUsageTree.allSuppressionNames().isEmpty()) {
            return Description.NO_MATCH;
        }

        // for each suppressed bugchecker, scan the whole compilation unit, bypassing any suppressions.
        for (String suppression : suppressionUsageTree.allSuppressionNames()) {
            Optional<BugChecker> bugCheckerMaybe = getBugCheckerForSuppression(suppression, state);
            if (bugCheckerMaybe.isEmpty()) {
                System.err.println(suppression + ": not found in registry, so we won't remove them");
                suppressionUsageTree.markAllSuppressionsAsUsed(suppression);
                continue;
            }

            BugChecker bugChecker = bugCheckerMaybe.get();
            VisitorState customState = VisitorState.createConfiguredForCompilation(
                            state.context,
                            (description) -> {},
                            state.severityMap(),
                            ignoreSuppressions(state.errorProneOptions()))
                    .withPath(state.getPath());
            System.err.println(suppression + ": scanning to find usages");

            new SuppressionCheckingScanner(bugChecker, suppressionUsageTree).scan(tree, customState);
        }

        for (TreeWithUnusedSuppressions treeWithUnusedSuppressions : suppressionUsageTree.unusedSuppressions()) {
            Set<String> unusedSuppressions = treeWithUnusedSuppressions.unusedSuppressions();
            System.err.println("========================================");
            System.err.println("Unused suppressions found in tree : " + unusedSuppressions);
            System.err.println(treeWithUnusedSuppressions.tree());
            System.err.println("========================================\n");

            // Get modifiers tree based on tree type
            ModifiersTree modifiers;
            Tree declarationTree = treeWithUnusedSuppressions.tree();
            if (declarationTree instanceof MethodTree methodTree) {
                modifiers = methodTree.getModifiers();
            } else if (declarationTree instanceof ClassTree classTree) {
                modifiers = classTree.getModifiers();
            } else if (declarationTree instanceof VariableTree variableTree) {
                modifiers = variableTree.getModifiers();
            } else {
                throw new IllegalStateException("Unexpected tree type: " + declarationTree.getClass());
            }

            // Find @SuppressWarnings annotation
            for (AnnotationTree annotation : modifiers.getAnnotations()) {
                if (isSuppressWarningsAnnotation(annotation)) {
                    Fix fix = createSuppressionFix(annotation, unusedSuppressions, state);
                    return buildDescription(tree)
                            .setMessage("Remove unused @SuppressWarnings: " + unusedSuppressions)
                            .addFix(fix)
                            .build();
                }
            }
        }

        return Description.NO_MATCH;
    }

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

    /**
     * Gets the BugChecker associated with a suppression name using the registry.
     */
    private Optional<BugChecker> getBugCheckerForSuppression(String suppression, VisitorState state) {
        // Use cached registry or create new one
        if (cachedRegistry == null) {
            synchronized (this) {
                if (cachedRegistry == null) {
                    cachedRegistry = CheckerRegistry.createFromEnabledCheckers(state);
                }
            }
        }
        return cachedRegistry.getCheckerForName(suppression);
    }

    private class SuppressionCheckingScanner extends TreeScanner<Void, VisitorState> {
        private final SuppressionUsageTree suppressionUsageTree;
        private final BugChecker checker;

        public SuppressionCheckingScanner(BugChecker checker, SuppressionUsageTree suppressionUsageTree) {
            super();
            this.checker = checker;
            this.suppressionUsageTree = suppressionUsageTree;
        }

        @Override
        public Void scan(Tree tree, VisitorState state) {
            if (tree == null) {
                return null;
            }

            VisitorState newState = state.withPath(state.getPath());

            MATCH_REPORTED.get().set(false);
            Description description = checkTreeAgainstChecker(tree, newState);
            state.reportMatch(description);
            if (MATCH_REPORTED.get().get()) {
                suppressionUsageTree.flagImmediateSuppressionsAsUsed(state.getPath(), checker.canonicalName());
            }

            return super.scan(tree, newState);
        }

        /**
         * Checks a tree against all the matcher interfaces implemented by the BugChecker.
         */
        private Description checkTreeAgainstChecker(Tree tree, VisitorState state) {
            // Check each matcher interface that the checker implements
            if (checker instanceof BugChecker.AnnotationTreeMatcher && tree instanceof AnnotationTree) {
                return ((BugChecker.AnnotationTreeMatcher) checker).matchAnnotation((AnnotationTree) tree, state);
            }
            if (checker instanceof BugChecker.ArrayAccessTreeMatcher && tree instanceof ArrayAccessTree) {
                return ((BugChecker.ArrayAccessTreeMatcher) checker).matchArrayAccess((ArrayAccessTree) tree, state);
            }
            if (checker instanceof BugChecker.ArrayTypeTreeMatcher && tree instanceof ArrayTypeTree) {
                return ((BugChecker.ArrayTypeTreeMatcher) checker).matchArrayType((ArrayTypeTree) tree, state);
            }
            if (checker instanceof BugChecker.AssertTreeMatcher && tree instanceof AssertTree) {
                return ((BugChecker.AssertTreeMatcher) checker).matchAssert((AssertTree) tree, state);
            }
            if (checker instanceof BugChecker.AssignmentTreeMatcher && tree instanceof AssignmentTree) {
                return ((BugChecker.AssignmentTreeMatcher) checker).matchAssignment((AssignmentTree) tree, state);
            }
            if (checker instanceof BugChecker.BinaryTreeMatcher && tree instanceof BinaryTree) {
                return ((BugChecker.BinaryTreeMatcher) checker).matchBinary((BinaryTree) tree, state);
            }
            if (checker instanceof BugChecker.BlockTreeMatcher && tree instanceof BlockTree) {
                return ((BugChecker.BlockTreeMatcher) checker).matchBlock((BlockTree) tree, state);
            }
            if (checker instanceof BugChecker.BreakTreeMatcher && tree instanceof BreakTree) {
                return ((BugChecker.BreakTreeMatcher) checker).matchBreak((BreakTree) tree, state);
            }
            if (checker instanceof BugChecker.CaseTreeMatcher && tree instanceof CaseTree) {
                return ((BugChecker.CaseTreeMatcher) checker).matchCase((CaseTree) tree, state);
            }
            if (checker instanceof BugChecker.CatchTreeMatcher && tree instanceof CatchTree) {
                return ((BugChecker.CatchTreeMatcher) checker).matchCatch((CatchTree) tree, state);
            }
            if (checker instanceof BugChecker.ClassTreeMatcher && tree instanceof ClassTree) {
                return ((BugChecker.ClassTreeMatcher) checker).matchClass((ClassTree) tree, state);
            }
            if (checker instanceof BugChecker.CompilationUnitTreeMatcher && tree instanceof CompilationUnitTree) {
                return ((BugChecker.CompilationUnitTreeMatcher) checker)
                        .matchCompilationUnit((CompilationUnitTree) tree, state);
            }
            if (checker instanceof BugChecker.CompoundAssignmentTreeMatcher && tree instanceof CompoundAssignmentTree) {
                return ((BugChecker.CompoundAssignmentTreeMatcher) checker)
                        .matchCompoundAssignment((CompoundAssignmentTree) tree, state);
            }
            if (checker instanceof BugChecker.ConditionalExpressionTreeMatcher
                    && tree instanceof ConditionalExpressionTree) {
                return ((BugChecker.ConditionalExpressionTreeMatcher) checker)
                        .matchConditionalExpression((ConditionalExpressionTree) tree, state);
            }
            if (checker instanceof BugChecker.ContinueTreeMatcher && tree instanceof ContinueTree) {
                return ((BugChecker.ContinueTreeMatcher) checker).matchContinue((ContinueTree) tree, state);
            }
            if (checker instanceof BugChecker.DoWhileLoopTreeMatcher && tree instanceof DoWhileLoopTree) {
                return ((BugChecker.DoWhileLoopTreeMatcher) checker).matchDoWhileLoop((DoWhileLoopTree) tree, state);
            }
            if (checker instanceof BugChecker.EmptyStatementTreeMatcher && tree instanceof EmptyStatementTree) {
                return ((BugChecker.EmptyStatementTreeMatcher) checker)
                        .matchEmptyStatement((EmptyStatementTree) tree, state);
            }
            if (checker instanceof BugChecker.EnhancedForLoopTreeMatcher && tree instanceof EnhancedForLoopTree) {
                return ((BugChecker.EnhancedForLoopTreeMatcher) checker)
                        .matchEnhancedForLoop((EnhancedForLoopTree) tree, state);
            }
            if (checker instanceof BugChecker.ExpressionStatementTreeMatcher
                    && tree instanceof ExpressionStatementTree) {
                return ((BugChecker.ExpressionStatementTreeMatcher) checker)
                        .matchExpressionStatement((ExpressionStatementTree) tree, state);
            }
            if (checker instanceof BugChecker.ForLoopTreeMatcher && tree instanceof ForLoopTree) {
                return ((BugChecker.ForLoopTreeMatcher) checker).matchForLoop((ForLoopTree) tree, state);
            }
            if (checker instanceof BugChecker.IdentifierTreeMatcher && tree instanceof IdentifierTree) {
                return ((BugChecker.IdentifierTreeMatcher) checker).matchIdentifier((IdentifierTree) tree, state);
            }
            if (checker instanceof BugChecker.IfTreeMatcher && tree instanceof IfTree) {
                return ((BugChecker.IfTreeMatcher) checker).matchIf((IfTree) tree, state);
            }
            if (checker instanceof BugChecker.ImportTreeMatcher && tree instanceof ImportTree) {
                return ((BugChecker.ImportTreeMatcher) checker).matchImport((ImportTree) tree, state);
            }
            if (checker instanceof BugChecker.InstanceOfTreeMatcher && tree instanceof InstanceOfTree) {
                return ((BugChecker.InstanceOfTreeMatcher) checker).matchInstanceOf((InstanceOfTree) tree, state);
            }
            if (checker instanceof BugChecker.LabeledStatementTreeMatcher && tree instanceof LabeledStatementTree) {
                return ((BugChecker.LabeledStatementTreeMatcher) checker)
                        .matchLabeledStatement((LabeledStatementTree) tree, state);
            }
            if (checker instanceof BugChecker.LambdaExpressionTreeMatcher && tree instanceof LambdaExpressionTree) {
                return ((BugChecker.LambdaExpressionTreeMatcher) checker)
                        .matchLambdaExpression((LambdaExpressionTree) tree, state);
            }
            if (checker instanceof BugChecker.LiteralTreeMatcher && tree instanceof LiteralTree) {
                return ((BugChecker.LiteralTreeMatcher) checker).matchLiteral((LiteralTree) tree, state);
            }
            if (checker instanceof BugChecker.MemberReferenceTreeMatcher && tree instanceof MemberReferenceTree) {
                return ((BugChecker.MemberReferenceTreeMatcher) checker)
                        .matchMemberReference((MemberReferenceTree) tree, state);
            }
            if (checker instanceof BugChecker.MemberSelectTreeMatcher && tree instanceof MemberSelectTree) {
                return ((BugChecker.MemberSelectTreeMatcher) checker).matchMemberSelect((MemberSelectTree) tree, state);
            }
            if (checker instanceof BugChecker.MethodTreeMatcher && tree instanceof MethodTree) {
                return ((BugChecker.MethodTreeMatcher) checker).matchMethod((MethodTree) tree, state);
            }
            if (checker instanceof BugChecker.MethodInvocationTreeMatcher && tree instanceof MethodInvocationTree) {
                return ((BugChecker.MethodInvocationTreeMatcher) checker)
                        .matchMethodInvocation((MethodInvocationTree) tree, state);
            }
            if (checker instanceof BugChecker.ModifiersTreeMatcher && tree instanceof ModifiersTree) {
                return ((BugChecker.ModifiersTreeMatcher) checker).matchModifiers((ModifiersTree) tree, state);
            }
            if (checker instanceof BugChecker.NewArrayTreeMatcher && tree instanceof NewArrayTree) {
                return ((BugChecker.NewArrayTreeMatcher) checker).matchNewArray((NewArrayTree) tree, state);
            }
            if (checker instanceof BugChecker.NewClassTreeMatcher && tree instanceof NewClassTree) {
                return ((BugChecker.NewClassTreeMatcher) checker).matchNewClass((NewClassTree) tree, state);
            }
            if (checker instanceof BugChecker.ParenthesizedTreeMatcher && tree instanceof ParenthesizedTree) {
                return ((BugChecker.ParenthesizedTreeMatcher) checker)
                        .matchParenthesized((ParenthesizedTree) tree, state);
            }
            if (checker instanceof BugChecker.ReturnTreeMatcher && tree instanceof ReturnTree) {
                return ((BugChecker.ReturnTreeMatcher) checker).matchReturn((ReturnTree) tree, state);
            }
            if (checker instanceof BugChecker.SwitchTreeMatcher && tree instanceof SwitchTree) {
                return ((BugChecker.SwitchTreeMatcher) checker).matchSwitch((SwitchTree) tree, state);
            }
            if (checker instanceof BugChecker.SynchronizedTreeMatcher && tree instanceof SynchronizedTree) {
                return ((BugChecker.SynchronizedTreeMatcher) checker).matchSynchronized((SynchronizedTree) tree, state);
            }
            if (checker instanceof BugChecker.ThrowTreeMatcher && tree instanceof ThrowTree) {
                return ((BugChecker.ThrowTreeMatcher) checker).matchThrow((ThrowTree) tree, state);
            }
            if (checker instanceof BugChecker.TryTreeMatcher && tree instanceof TryTree) {
                return ((BugChecker.TryTreeMatcher) checker).matchTry((TryTree) tree, state);
            }
            if (checker instanceof BugChecker.TypeCastTreeMatcher && tree instanceof TypeCastTree) {
                return ((BugChecker.TypeCastTreeMatcher) checker).matchTypeCast((TypeCastTree) tree, state);
            }
            if (checker instanceof BugChecker.UnaryTreeMatcher && tree instanceof UnaryTree) {
                return ((BugChecker.UnaryTreeMatcher) checker).matchUnary((UnaryTree) tree, state);
            }
            if (checker instanceof BugChecker.VariableTreeMatcher && tree instanceof VariableTree) {
                return ((BugChecker.VariableTreeMatcher) checker).matchVariable((VariableTree) tree, state);
            }
            if (checker instanceof BugChecker.WhileLoopTreeMatcher && tree instanceof WhileLoopTree) {
                return ((BugChecker.WhileLoopTreeMatcher) checker).matchWhileLoop((WhileLoopTree) tree, state);
            }

            return Description.NO_MATCH;
        }
    }

    /**
     * Hook to be called from VisitorState.reportMatch to track when matches are reported.
     */
    public static void onMatchReported() {
        System.err.println("reportMatch called");
        MATCH_REPORTED.get().set(true);
    }

    private static boolean isSuppressWarningsAnnotation(AnnotationTree annotation) {
        return AnnotationUtils.annotationName(annotation.getAnnotationType()).contentEquals("SuppressWarnings");
    }

    private static Fix createSuppressionFix(
            AnnotationTree annotation, Set<String> unusedSuppressions, VisitorState state) {
        // Get current suppression values using existing utility
        List<String> currentSuppressions =
                AnnotationUtils.annotationStringValues(annotation).toList();

        // Remove unused suppressions
        List<String> remainingSuppressions = currentSuppressions.stream()
                .filter(s -> !unusedSuppressions.contains(s))
                .collect(Collectors.toList());

        if (remainingSuppressions.isEmpty()) {
            // Remove entire annotation
            return new LineRemovingReplacementFix(state.getSourceCode(), (DiagnosticPosition) annotation, "");
        } else {
            // Update annotation with remaining suppressions
            String newAnnotation = buildSuppressWarningsAnnotation(remainingSuppressions);
            return new LineRemovingReplacementFix(
                    state.getSourceCode(), (DiagnosticPosition) annotation, newAnnotation);
        }
    }

    private static String buildSuppressWarningsAnnotation(List<String> suppressions) {
        if (suppressions.size() == 1) {
            return "@SuppressWarnings(\"" + suppressions.get(0) + "\")";
        } else {
            String values = suppressions.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
            return "@SuppressWarnings({" + values + "})";
        }
    }

    /**
     * This class handles replacement with optional line removal for empty suppressions.
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
