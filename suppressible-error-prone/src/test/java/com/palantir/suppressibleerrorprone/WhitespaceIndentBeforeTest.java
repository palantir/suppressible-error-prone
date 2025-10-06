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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WhitespaceIndentBeforeTest {
    // In these tests, the '|' character represents the start position of the search.

    @Nested
    class WhitespaceIndent {
        @Test
        void empty_string() {
            assertThat(whitespaceIndentBefore("|")).isEmpty();
        }

        @Test
        void start_of_string() {
            assertThat(whitespaceIndentBefore("    |class Foo {")).isEqualTo("    ");
        }

        @Test
        void newline() {
            assertThat(whitespaceIndentBefore("\n  |public static void main() {"))
                    .isEqualTo("  ");
        }

        @Test
        void something_else_is_before_on_the_same_line() {
            assertThat(whitespaceIndentBefore("class Foo {} |class Bar {}")).isEqualTo(" ");
        }

        private static CharSequence whitespaceIndentBefore(String testCase) {
            return SourceCodeUtils.whitespaceIndentBefore(testCase.replace("|", ""), testCase.indexOf('|'));
        }
    }

    @Nested
    class StartPosition {
        @Test
        void returns_start_of_file_when_checking_second_line_and_first_line_is_empty() {
            assertThat(startPositionIncludingNewLine("\n  |public static void main() {"))
                    .isEqualTo(0);
        }

        @Test
        void returns_start_of_file_when_checking_first_line() {
            assertThat(startPositionIncludingNewLine("  |public static void main() {"))
                    .isEqualTo(0);
        }

        @Test
        void returns_index_before_newline_when_only_whitespace() {
            assertThat(startPositionIncludingNewLine("1234\n  |public static void main() {"))
                    .isEqualTo(4);
        }

        @Test
        void returns_index_after_element_when_there_is_a_preceding_element() {
            assertThat(startPositionIncludingNewLine("class Foo {} |class Bar {}"))
                    .isEqualTo(12);
        }

        private static int startPositionIncludingNewLine(String testCase) {
            return SourceCodeUtils.startPositionWithWhitespaceIncludingNewLine(
                    testCase.replace("|", ""), testCase.indexOf('|'));
        }
    }

    @Nested
    class RemoveUntil {
        @Test
        void next_element_on_same_line() {
            assertThat(removeUntil("class| Foo")).isEqualTo(6);
        }

        @Test
        void next_element_in_newline() {
            assertThat(removeUntil("class| \n   Foo")).isEqualTo(7);
        }

        private static int removeUntil(String testCase) {
            return SourceCodeUtils.removeUntil(testCase.replace("|", ""), testCase.indexOf('|'));
        }
    }
}
