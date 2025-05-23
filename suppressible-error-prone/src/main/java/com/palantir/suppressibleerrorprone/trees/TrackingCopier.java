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
import com.sun.tools.javac.tree.TreeMaker;
import java.util.function.Consumer;

public final class TrackingCopier<T extends JCTree> extends SymbolTreeCopier<Void> {
    private final T originalTree;
    private final Consumer<T> copiedTreeConsumer;

    public TrackingCopier(TreeMaker treeMaker, T originalTree, Consumer<T> copiedTreeConsumer) {
        super(treeMaker);
        this.originalTree = originalTree;
        this.copiedTreeConsumer = copiedTreeConsumer;
    }

    @Override
    public <TreeT extends JCTree> TreeT copy(TreeT tree, Void unused) {
        TreeT copy = super.copy(tree, unused);

        if (tree == originalTree) {
            copiedTreeConsumer.accept((T) copy);
        }

        return copy;
    }
}
