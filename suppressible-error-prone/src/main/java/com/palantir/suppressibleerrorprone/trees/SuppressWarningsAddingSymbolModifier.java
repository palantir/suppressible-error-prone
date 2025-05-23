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

import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.palantir.suppressibleerrorprone.trees.DelegatingTreeCopier.TreeCopyHandler;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Pair;
import java.util.Optional;

public final class SuppressWarningsAddingSymbolModifier<P> implements TreeCopyHandler<P> {
    private static final Supplier<Type> STRING_TYPE = Suppliers.typeFromString("java.lang.String");
    private static final Supplier<Type> STRING_ARRAY_TYPE = Suppliers.arrayOf(STRING_TYPE);
    private static final Supplier<Type> SUPPRESS_WARNINGS = Suppliers.typeFromString("java.lang.SuppressWarnings");
    private static final Supplier<MethodSymbol> SUPPRESS_WARNINGS_VALUE =
            VisitorState.memoize(state -> (MethodSymbol) SUPPRESS_WARNINGS
                    .get(state)
                    .tsym
                    .members()
                    .getSymbolsByName(state.getName("value"))
                    .iterator()
                    .next());

    private final VisitorState visitorState;
    private final Tree treeToAddSuppressWarningsTo;
    private final String checkName;
    private Symbol symbol;
    private List<Compound> originalAttributes;

    public SuppressWarningsAddingSymbolModifier(
            VisitorState visitorState, Tree treeToAddSuppressWarningsTo, String checkName) {
        this.visitorState = visitorState;
        this.treeToAddSuppressWarningsTo = treeToAddSuppressWarningsTo;
        this.checkName = checkName;
    }

    @Override
    public <T extends JCTree> void handleCopy(T originalTree, T copiedTree, P value) {
        if (originalTree == treeToAddSuppressWarningsTo) {
            Symbol suppressibleSymbol = symbolIfSuppressibleFor(copiedTree)
                    .orElseThrow(() -> new IllegalStateException("You can only give suppressible trees to this class"));

            if (symbol != null) {
                throw new IllegalStateException("The symbolMetadata of the original tree have already been set");
            }

            symbol = suppressibleSymbol;
            originalAttributes = symbol.getDeclarationAttributes();

            Type suppressWarnings = SUPPRESS_WARNINGS.get(visitorState);

            Attribute.Compound newSuppressWarnings = new Compound(
                    suppressWarnings,
                    List.of(Pair.of(
                            SUPPRESS_WARNINGS_VALUE.get(visitorState),
                            new Attribute.Array(
                                    STRING_ARRAY_TYPE.get(visitorState),
                                    List.of(new Attribute.Constant(STRING_TYPE.get(visitorState), checkName))))));

            symbol.resetAnnotations();
            symbol.setDeclarationAttributes(List.of(newSuppressWarnings));
        }
    }

    public void resetSymbolMetadataAttributes() {
        if (symbol == null || originalAttributes == null) {
            return;
        }

        // Will error out unless we reset the annotations before setting them again (annotations == attributes here)
        symbol.resetAnnotations();
        symbol.setDeclarationAttributes(originalAttributes);
    }

    private <T extends JCTree> Optional<Symbol> symbolIfSuppressibleFor(T tree) {
        if (tree instanceof JCClassDecl classDecl) {
            return Optional.ofNullable(classDecl.sym);
        }

        if (tree instanceof JCMethodDecl methodDecl) {
            return Optional.ofNullable(methodDecl.sym);
        }

        if (tree instanceof JCVariableDecl varDecl) {
            return Optional.ofNullable(varDecl.sym);
        }

        return Optional.empty();
    }
}
