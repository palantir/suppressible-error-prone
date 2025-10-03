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
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.MethodTree;
import javax.lang.model.element.Modifier;

@AutoService(BugChecker.class)
@BugPattern(
        severity = BugPattern.SeverityLevel.ERROR,
        summary = "This check is meant only for testing suppressible-error-prone functionality")
public final class ShouldBePrivate extends BugChecker implements BugChecker.MethodTreeMatcher {

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        if (tree.getName().contentEquals("fixme")
                && !tree.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
            SuggestedFix fix =
                    SuggestedFixes.addModifiers(tree, state, Modifier.PRIVATE).orElseGet(SuggestedFix::emptyFix);
            return buildDescription(tree).addFix(fix).build();
        }
        return Description.NO_MATCH;
    }
}
