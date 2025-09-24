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
package com.palantir.gradle.suppressibleerrorprone;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MemberSelectTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberSelectTree;
import com.sun.tools.javac.code.Symbol;

@AutoService(BugChecker.class)
@BugPattern(
        severity = BugPattern.SeverityLevel.ERROR,
        summary = "Deprecated APIs should not be relied upon as they may be removed in a future release.")
public class TestOnlyDeprecatedApiUsage extends BugChecker implements MemberSelectTreeMatcher {

    @Override
    public final Description matchMemberSelect(MemberSelectTree tree, VisitorState _state) {
        Symbol symbol = ASTHelpers.getSymbol(tree);
        if (symbol != null && symbol.isDeprecated()) {
            return buildDescription(tree).setMessage("Deprecated").build();
        }

        return Description.NO_MATCH;
    }
}
