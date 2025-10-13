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
    void no_check_argument_means_all_rollout_suppressions_are_removed() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        public final class App {}
                        """)
                .doTest();
    }

    @Test
    void no_specific_checks_means_all_rollout_suppressions_are_removed() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=")
                .doTest();
    }

    @Test
    void specific_checks_are_being_properly_removed() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void annotations_with_single_array_argument_can_be_removed() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings({"for-rollout:Test"})
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void do_not_remove_unspecified_suppression() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings("for-rollout:Test")
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Other")
                .doTest();
    }

    @Test
    void do_not_remove_manual_suppression() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings("Test")
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings("Test")
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void only_remove_targeted_suppression_when_there_are_multiple() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings({"Test", "for-rollout:Test", "for-rollout:Other"})
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings({"Test", "for-rollout:Other"})
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @SuppressWarnings("for-rollout:MisformattedTestData")
    @Test
    void remove_only_annotation_when_there_is_more_on_the_same_line() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @Deprecated @SuppressWarnings("for-rollout:Test") // comment
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        @Deprecated // comment
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    @Test
    void sorts_by_human_authored_first_then_by_alnum() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings({"for-rollout:3", "for-rollout:2", "for-rollout:1", "b", "a"})
                        public final class App {}
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        @SuppressWarnings({"a", "b", "for-rollout:1", "for-rollout:3"})
                        public final class App {}
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=2")
                .doTest();
    }

    @SuppressWarnings("for-rollout:MisformattedTestData")
    @Test
    void removes_annotations_on_vaious_elements() {
        fix().addInputLines(
                        "App.java",
                        // language=Java
                        """
                        public final class App {
                            @SuppressWarnings("for-rollout:Test")
                            public final String field = new int[3].toString();

                            @SuppressWarnings("for-rollout:Test")
                            public App() {
                                System.out.println(new int[3].toString());
                            }

                            @SuppressWarnings("for-rollout:Test")
                            public void method() {
                                System.out.println(new int[3].toString());
                            }

                            public void variables() {
                                @SuppressWarnings("for-rollout:Test")
                                String variable = new int[3].toString();
                                System.out.println(variable);
                            }

                            @SuppressWarnings("for-rollout:Test")
                            public static class SomeClass {
                                static {
                                    System.out.println(new int[3].toString());
                                }
                            }
                        }
                        """)
                .addOutputLines(
                        "App.java",
                        // language=Java
                        """
                        public final class App {
                            public final String field = new int[3].toString();

                            public App() {
                                System.out.println(new int[3].toString());
                            }

                            public void method() {
                                System.out.println(new int[3].toString());
                            }

                            public void variables() {
                                String variable = new int[3].toString();
                                System.out.println(variable);
                            }

                            public static class SomeClass {
                                static {
                                    System.out.println(new int[3].toString());
                                }
                            }
                        }
                        """)
                .setArgs("-XepOpt:" + RemoveRolloutSuppressions.ARGUMENT + "=Test")
                .doTest();
    }

    private BugCheckerRefactoringTestHelper fix() {
        return BugCheckerRefactoringTestHelper.newInstance(RemoveRolloutSuppressions.class, getClass());
    }
}
