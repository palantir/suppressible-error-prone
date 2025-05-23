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

package com.palantir.suppressibleerrorprone.trees;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.TreeMaker;

public final class Blah extends SymbolTreeCopier<Void> {

    Blah(TreeMaker treeMaker) {
        super(treeMaker);
    }

    @Override
    public <P extends JCTree> P copy(P from, Void value) {
        P to = super.copy(from, value);

        if (from != null && to != null) {
            return to;
        }

        if (from instanceof JCClassDecl fromClass && to instanceof JCClassDecl toClass) {}

        return to;
    }
}
