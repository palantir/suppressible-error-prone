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
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Pair;

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

    public SuppressWarningsAddingSymbolModifier(
            VisitorState visitorState, Tree treeToAddSuppressWarningsTo, String checkName) {
        this.visitorState = visitorState;
        this.treeToAddSuppressWarningsTo = treeToAddSuppressWarningsTo;
        this.checkName = checkName;
    }

    @Override
    public <T extends JCTree> void handleCopy(T originalTree, T copiedTree, P value) {
        if (originalTree == treeToAddSuppressWarningsTo) {
            Symbol cloneSymbol = clonedSymbolIfSuppressibleFor(copiedTree);

            Type suppressWarnings = SUPPRESS_WARNINGS.get(visitorState);

            Attribute.Compound newSuppressWarnings = new Compound(
                    suppressWarnings,
                    List.of(Pair.of(
                            SUPPRESS_WARNINGS_VALUE.get(visitorState),
                            new Attribute.Array(
                                    STRING_ARRAY_TYPE.get(visitorState),
                                    List.of(new Attribute.Constant(STRING_TYPE.get(visitorState), checkName))))));

            cloneSymbol.resetAnnotations();
            cloneSymbol.setDeclarationAttributes(List.of(newSuppressWarnings));
        }
    }

    private <T extends JCTree> Symbol clonedSymbolIfSuppressibleFor(T tree) {
        if (tree instanceof JCVariableDecl varDecl) {
            varDecl.sym = varDecl.sym.clone(varDecl.sym.owner);
            return varDecl.sym;
        }

        if (tree instanceof JCMethodDecl methodDecl) {
            methodDecl.sym = methodDecl.sym.clone(methodDecl.sym.owner);
            return methodDecl.sym;
        }

        if (tree instanceof JCClassDecl classDecl) {
            ClassSymbol originalSymbol = classDecl.sym;
            classDecl.sym = new ClassSymbol(
                    originalSymbol.flags(), originalSymbol.name, originalSymbol.type, originalSymbol.owner);
        }

        throw new IllegalStateException(
                "You can only give suppressible trees to this class. You gave a " + tree.getKind());
    }
}
