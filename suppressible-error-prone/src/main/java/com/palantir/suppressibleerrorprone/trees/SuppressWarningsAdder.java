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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.VisitorState;
import com.palantir.suppressibleerrorprone.VisitorStateModifications;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class SuppressWarningsAdder {
    private final VisitorState visitorState;

    public SuppressWarningsAdder(VisitorState visitorState) {
        this.visitorState = visitorState;
    }

    public <T> T withTreeWithSuppressionAdded(
            Tree treeToAddSuppressWarningsTo, String checkName, Function<Tree, T> treeConsumer) {
        Tree rootTree = visitorState.getPath().getLeaf();

        JCModifiers originalModifiers = (JCModifiers) VisitorStateModifications.modifiersTree(
                        treeToAddSuppressWarningsTo)
                .orElseThrow(() -> new IllegalStateException("Could not get ModifiersTree for a suppressible element. "
                        + "This is a bug in suppressible-error-prone. Tree: \n\n"
                        + treeToAddSuppressWarningsTo));

        AtomicReference<JCModifiers> copiedJcModifiers = new AtomicReference<>();

        SuppressWarningsAddingSymbolModifier<Void> symbolModifier =
                new SuppressWarningsAddingSymbolModifier<>(visitorState, treeToAddSuppressWarningsTo, checkName);

        JCTree copiedRootTree = new DelegatingTreeCopier<>(
                        visitorState.getTreeMaker(),
                        ImmutableList.of(
                                new SymbolTreeCopier<>(),
                                symbolModifier,
                                new TrackingCopier<>(originalModifiers, copiedJcModifiers::set)))
                .copy((JCTree) rootTree);

        JCTree translatedTree = new SuppressWarningsAddingTreeTranslator(
                        visitorState, copiedJcModifiers.get(), checkName)
                .translate(copiedRootTree);

        return treeConsumer.apply(translatedTree);
    }
}
