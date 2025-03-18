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

import org.junit.jupiter.api.Test;

class WhitespaceIndentBeforeTest {
    // In these tests, the '|' character represents the start position of the search.

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
        assertThat(whitespaceIndentBefore("\n  |public static void main() {")).isEqualTo("  ");
    }

    @Test
    void something_else_is_before_on_the_same_line() {
        assertThat(whitespaceIndentBefore("class Foo {} |class Bar {}")).isEqualTo(" ");
    }

    @Test
    void startPositionWithNewline() {
        assertThat(startPositionIncludingNewLine("\n  |public static void main() {"))
                .isEqualTo(0);
    }

    @Test
    void startPositionWithPrecedingLine() {
        assertThat(startPositionIncludingNewLine("1234\n  |public static void main() {"))
                .isEqualTo(4);
    }

    @Test
    void startPositionWithoutNewline() {
        assertThat(startPositionIncludingNewLine("class Foo {} |class Bar {}")).isEqualTo(12);
    }

    private int startPositionIncludingNewLine(String testCase) {
        return SourceCodeUtils.startPositionWithWhitespaceIncludingNewLine(
                testCase.replace("|", ""), testCase.indexOf('|'));
    }

    private CharSequence whitespaceIndentBefore(String testCase) {
        return SourceCodeUtils.whitespaceIndentBefore(testCase.replace("|", ""), testCase.indexOf('|'));
    }
}
