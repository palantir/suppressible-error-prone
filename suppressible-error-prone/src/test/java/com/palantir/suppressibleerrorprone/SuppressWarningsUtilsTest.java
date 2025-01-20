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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SuppressWarningsUtilsTest {
    @Nested
    class ModifySuppressions {
        @Test
        void no_human_authored_suppressions() {
            assertThat(SuppressWarningsUtils.modifySuppressions(List.of(), Set.of("New")))
                    .containsExactly("for-rollout:New");
        }

        @Test
        void automatic_suppresions_go_after_human_authored_suppressions() {
            assertThat(SuppressWarningsUtils.modifySuppressions(List.of("Something"), Set.of("New")))
                    .containsExactly("Something", "for-rollout:New");
        }

        @Test
        void maintains_human_authored_ordering() {
            assertThat(SuppressWarningsUtils.modifySuppressions(List.of("C", "A", "B"), Set.of("ArrayToString")))
                    .containsExactly("C", "A", "B", "for-rollout:ArrayToString");
        }

        @Test
        void auto_suppressions_are_initially_alphabetically_ordered() {
            assertThat(SuppressWarningsUtils.modifySuppressions(List.of("A"), Set.of("ArrayEquals", "ArrayToString")))
                    .containsExactly("A", "for-rollout:ArrayEquals", "for-rollout:ArrayToString");
        }

        @Test
        void maintains_alphabetical_order_for_automated_suppressions() {
            assertThat(SuppressWarningsUtils.modifySuppressions(
                            List.of("Blah", "for-rollout:A", "for-rollout:Something"), Set.of("ArrayToString")))
                    .containsExactly("Blah", "for-rollout:A", "for-rollout:ArrayToString", "for-rollout:Something");
        }

        @Test
        void reorders_automated_suppresions_to_the_end() {
            assertThat(SuppressWarningsUtils.modifySuppressions(
                            List.of("for-rollout:Something", "Derp"), Set.of("ArrayToString")))
                    .containsExactly("Derp", "for-rollout:ArrayToString", "for-rollout:Something");
        }

        @Test
        void tidies_up_same_authored_and_human_suppression() {
            assertThat(SuppressWarningsUtils.modifySuppressions(List.of("A", "for-rollout:A"), Set.of("ArrayToString")))
                    .containsExactly("A", "for-rollout:ArrayToString");
        }

        @Test
        void puts_human_authored_suppression_that_has_been_placed_after_automated_suppression_back_in_order() {
            assertThat(SuppressWarningsUtils.modifySuppressions(
                            List.of("for-rollout:Something", "HumanAuthored"), Set.of("ArrayToString")))
                    .containsExactly("HumanAuthored", "for-rollout:ArrayToString", "for-rollout:Something");
        }
    }

    @Nested
    class SuppressWarningsString {
        @Test
        void zero_warnings_deletes_the_entire_suppresswarnings() {
            assertThat(SuppressWarningsUtils.suppressWarningsString(List.of())).isEqualTo("");
        }

        @Test
        void single_warning() {
            assertThat(SuppressWarningsUtils.suppressWarningsString(List.of("Something")))
                    .isEqualTo("@SuppressWarnings(\"Something\")");
        }

        @Test
        void multiple_warnings() {
            assertThat(SuppressWarningsUtils.suppressWarningsString(List.of("Something", "Another")))
                    .isEqualTo("@SuppressWarnings({\"Something\", \"Another\"})");
        }
    }
}
