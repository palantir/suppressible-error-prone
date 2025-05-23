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
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import java.util.List;

final class DelegatingTreeCopier<P> extends TreeCopier<P> {
    private final List<TreeCopyHandler<P>> treeCopyHandlers;

    DelegatingTreeCopier(TreeMaker treeMaker, List<TreeCopyHandler<P>> treeCopyHandlers) {
        super(treeMaker);
        this.treeCopyHandlers = treeCopyHandlers;
    }

    @Override
    public <T extends JCTree> T copy(T tree, P value) {
        T copy = super.copy(tree, value);
        for (TreeCopyHandler<P> treeCopier : treeCopyHandlers) {
            treeCopier.handleCopy(tree, copy, value);
        }
        return copy;
    }

    interface TreeCopyHandler<P> {
        <T extends JCTree> void handleCopy(T originalTree, T copiedTree, P value);
    }
}
