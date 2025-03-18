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

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import org.junit.jupiter.api.Test;

class RemoveRolloutSuppressionsTest {

    @Test
    void testRemoveAllForRollout() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        public final class App {}
                        """)
                .doTest();
    }

    @Test
    void testRemoveAllForRolloutWithEmptyArgument() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=")
                .doTest();
    }

    @Test
    void testRemoveSpecificForRollout() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void testRemoveSingleArrayAnnotation() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings({"for-rollout:Test"})
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void testDoNotRemoveUnspecifiedSuppression() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Other")
                .doTest();
    }

    @Test
    void testDoNotRemoveManualSuppression() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings("Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings("Test")
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void testOnlyRemoveTargetedSuppressions() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings({"Test", "for-rollout:Test", "for-rollout:Other"})
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings({"Test", "for-rollout:Other"})
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void testRemovePartialLine() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @Deprecated @SuppressWarnings("for-rollout:Test") // comment
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @Deprecated // comment
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void testDoNotReorder() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings({"for-rollout:3", "for-rollout:2", "1"})
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        package app;

                        @SuppressWarnings({"for-rollout:3", "1"})
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=2")
                .doTest();
    }

    private BugCheckerRefactoringTestHelper fix() {
        return BugCheckerRefactoringTestHelper.newInstance(RemoveRolloutSuppressions.class, getClass());
    }
}
