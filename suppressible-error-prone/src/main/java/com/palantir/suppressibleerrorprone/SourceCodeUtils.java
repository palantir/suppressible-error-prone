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

import com.sun.source.tree.Tree;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.Optional;

final class SourceCodeUtils {
    static CharSequence indentForTree(Optional<CharSequence> sourceCode, Tree tree) {
        return sourceCode
                .map(actualSourceCode ->
                        whitespaceIndentBefore(actualSourceCode, ((DiagnosticPosition) tree).getStartPosition()))
                .orElse("    ");
    }

    static CharSequence whitespaceIndentBefore(CharSequence sourceCode, int sourceElementPosition) {
        int pos = startPositionWithWhitespace(sourceCode, sourceElementPosition);

        return sourceCode.subSequence(pos, sourceElementPosition);
    }

    static int startPositionWithWhitespaceIncludingNewLine(CharSequence sourceCode, int sourceElementPosition) {
        int pos = startPositionWithWhitespace(sourceCode, sourceElementPosition);

        // If the character just before the position is a new line, we return the position of the new line, so it can
        //   be replaced as well.
        if (pos > 0 && sourceCode.charAt(pos - 1) == '\n') {
            return pos - 1;
        }

        return pos;
    }

    /**
     * Returns the position of either the start of the line or wherever non-whitespace starts before the given
     *  source element's position.
     */
    private static int startPositionWithWhitespace(CharSequence sourceCode, int sourceElementPosition) {
        int pos = sourceElementPosition - 1;

        for (; pos >= 0; pos--) {
            char character = sourceCode.charAt(pos);
            if (character == '\n' || !Character.isWhitespace(character)) {
                break;
            }
        }

        return pos + 1;
    }

    private SourceCodeUtils() {}
}
