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

class RemoveUnnecessarySuppressionsTest {

    @Test
    void removes_unnecessary_human_authored_suppressions_when_enabled() {
        fix().addInputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings("UnnecessaryCheck")
                        public final class Test {}
                        """)
                .addOutputLines(
                        "Test.java",
                        // language=Java
                        """
                        public final class Test {}
                        """)
                .setArgs("-XepOpt:" + RemoveUnnecessarySuppressions.ARGUMENT + "=true")
                .doTest();
    }

    @Test
    void removes_all_human_authored_suppressions_when_no_errors_encountered() {
        // Unit tests don't have the full compilation context, so all human-authored suppressions are removed
        fix().addInputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings({"UnnecessaryCheck", "ArrayToString"})
                        public final class Test {}
                        """)
                .addOutputLines(
                        "Test.java",
                        // language=Java
                        """
                        public final class Test {}
                        """)
                .setArgs("-XepOpt:" + RemoveUnnecessarySuppressions.ARGUMENT + "=true")
                .doTest();
    }

    @Test
    void keeps_automatic_suppressions_unchanged() {
        fix().addInputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings({"UnnecessaryCheck", "for-rollout:SomeCheck"})
                        public final class Test {}
                        """)
                .addOutputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings("for-rollout:SomeCheck")
                        public final class Test {}
                        """)
                .setArgs("-XepOpt:" + RemoveUnnecessarySuppressions.ARGUMENT + "=true")
                .doTest();
    }

    @Test
    void does_nothing_when_disabled() {
        fix().addInputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings("UnnecessaryCheck")
                        public final class Test {}
                        """)
                .addOutputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings("UnnecessaryCheck")
                        public final class Test {}
                        """)
                .doTest();
    }

    @Test
    void removes_annotations_on_various_elements() {
        fix().addInputLines(
                        "Test.java",
                        // language=Java
                        """
                        public final class Test {
                            @SuppressWarnings("UnnecessaryCheck")
                            public final String field = "test";

                            @SuppressWarnings("UnnecessaryCheck")
                            public Test() {}

                            @SuppressWarnings("UnnecessaryCheck")
                            public void method() {}

                            public void variables() {
                                @SuppressWarnings("UnnecessaryCheck")
                                String variable = "test";
                            }

                            @SuppressWarnings("UnnecessaryCheck")
                            public static class SomeClass {}
                        }
                        """)
                .addOutputLines(
                        "Test.java",
                        // language=Java
                        """
                        public final class Test {
                            public final String field = "test";

                            public Test() {}

                            public void method() {}

                            public void variables() {
                                String variable = "test";
                            }

                            public static class SomeClass {}
                        }
                        """)
                .setArgs("-XepOpt:" + RemoveUnnecessarySuppressions.ARGUMENT + "=true")
                .doTest();
    }

    @Test
    void only_removes_human_authored_suppressions_when_there_are_multiple() {
        // Unit tests remove all human-authored suppressions but keep automatic ones
        fix().addInputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings({"NecessaryCheck", "for-rollout:AutoCheck", "UnnecessaryCheck"})
                        public final class Test {}
                        """)
                .addOutputLines(
                        "Test.java",
                        // language=Java
                        """
                        @SuppressWarnings("for-rollout:AutoCheck")
                        public final class Test {}
                        """)
                .setArgs("-XepOpt:" + RemoveUnnecessarySuppressions.ARGUMENT + "=true")
                .doTest();
    }

    private BugCheckerRefactoringTestHelper fix() {
        return BugCheckerRefactoringTestHelper.newInstance(RemoveUnnecessarySuppressions.class, getClass());
    }
}
