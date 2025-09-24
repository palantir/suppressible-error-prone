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
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.IdentifierTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MemberReferenceTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MemberSelectTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodInvocationTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import java.util.Optional;
import javax.lang.model.element.Name;

@AutoService(BugChecker.class)
@BugPattern(
        link = "https://github.com/palantir/gradle-baseline#baseline-error-prone-checks",
        linkType = BugPattern.LinkType.CUSTOM,
        severity = BugPattern.SeverityLevel.ERROR,
        summary = "Deprecated APIs should not be relied upon as they may be removed in a future release.",
        // Use deprecation as the main name for the check, for familiarity with the javac flag.
        name = "deprecation",
        altNames = "DeprecatedApiUsage")
public class DeprecatedApiUsage extends BugChecker
        implements MethodInvocationTreeMatcher,
                MemberReferenceTreeMatcher,
                MemberSelectTreeMatcher,
                IdentifierTreeMatcher {

    private static final String MESSAGE_DETAILS =
            " - this may be removed in a future release and prevent library upgrades. Note: This error comes from "
                    + "the DeprecatedApiUsage error-prone check, replacing the java compiler flag '-Xlint:deprecation.'"
                    + " Use @SuppressWarnings(\"deprecation\") to suppress this error.";

    private static final Matcher<Tree> DEPRECATED_SYMBOL =
            Matchers.symbolMatcher((symbol, state) -> symbol.isDeprecated() && !symbol.isDeprecatedForRemoval());

    private boolean isDeprecationWarning(Tree tree, VisitorState state) {
        return DEPRECATED_SYMBOL.matches(tree, state);
    }

    private String getErrorDescription(Optional<String> qualifiedName) {
        return qualifiedName
                        .map(name -> String.format("%s is deprecated", name))
                        .orElse("Deprecated API usage")
                + MESSAGE_DETAILS;
    }

    @Override
    public final Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        return checkTree(tree, state);
    }

    @Override
    public final Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
        return checkTree(tree, state);
    }

    @Override
    public final Description matchIdentifier(IdentifierTree tree, VisitorState state) {
        return checkTree(tree, state);
    }

    @Override
    public final Description matchMemberSelect(MemberSelectTree tree, VisitorState state) {
        return checkTree(tree, state);
    }

    private Description checkTree(Tree tree, VisitorState state) {
        if (isImportStatement(state)) {
            // We don't want to flag import statements, as those cannot be suppressed.
            return Description.NO_MATCH;
        }

        if (!isDeprecationWarning(tree, state)) {
            return Description.NO_MATCH;
        }

        Optional<Symbol> symbol = Optional.ofNullable(ASTHelpers.getSymbol(tree));

        if (symbol.isPresent()) {
            Optional<Name> currentClass = getCurrentClass(state);
            if (currentClass.isPresent()
                    && currentClass.get().equals(symbol.get().owner.getQualifiedName())) {
                // Don't complain about deprecated APIs used within the same class
                return Description.NO_MATCH;
            }
        }

        Optional<String> qualifiedName = symbol.map(
                s -> s.owner.getQualifiedName() + "#" + s.getQualifiedName().toString());
        String description = getErrorDescription(qualifiedName);
        return buildDescription(tree).setMessage(description).build();
    }

    private boolean isImportStatement(VisitorState state) {
        return ASTHelpers.findEnclosingNode(state.getPath(), ImportTree.class) != null;
    }

    private Optional<Name> getCurrentClass(VisitorState state) {
        return Optional.ofNullable(ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class))
                .map(ASTHelpers::getSymbol)
                .map(Symbol::getQualifiedName);
    }
}
