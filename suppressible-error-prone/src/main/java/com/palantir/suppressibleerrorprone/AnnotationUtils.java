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

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewArrayTree;
import java.util.stream.Stream;

final class AnnotationUtils {
    static Stream<String> annotationStringValues(AnnotationTree annotation) {
        return annotation.getArguments().stream().flatMap(arg -> {
            if (!(arg instanceof AssignmentTree)) {
                return Stream.empty();
            }
            AssignmentTree assignment = (AssignmentTree) arg;

            ExpressionTree expression = assignment.getExpression();

            if (expression instanceof LiteralTree) {
                LiteralTree literalTree = (LiteralTree) expression;
                return Stream.of((String) literalTree.getValue());
            }

            if (expression instanceof NewArrayTree) {
                NewArrayTree newArray = (NewArrayTree) expression;
                return newArray.getInitializers().stream()
                        .map(LiteralTree.class::cast)
                        .map(LiteralTree::getValue)
                        .map(String.class::cast);
            }

            throw new UnsupportedOperationException("Unsupported assignment expression: "
                    + expression.getClass().getCanonicalName());
        });
    }

    private AnnotationUtils() {}
}
