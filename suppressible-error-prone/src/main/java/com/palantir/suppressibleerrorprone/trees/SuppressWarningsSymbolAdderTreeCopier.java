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

import com.palantir.suppressibleerrorprone.trees.DelegatingTreeCopier.TreeCopyHandler;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;

final class SuppressWarningsSymbolAdderTreeCopier<P> implements TreeCopyHandler<P> {
    private final SuppressWarningsSymbolAdder suppressWarningsSymbolAdder;
    private final Tree treeToAddSuppressWarningsTo;
    private final String checkName;

    SuppressWarningsSymbolAdderTreeCopier(
            SuppressWarningsSymbolAdder suppressWarningsSymbolAdder,
            Tree treeToAddSuppressWarningsTo,
            String checkName) {
        this.suppressWarningsSymbolAdder = suppressWarningsSymbolAdder;
        this.treeToAddSuppressWarningsTo = treeToAddSuppressWarningsTo;
        this.checkName = checkName;
    }

    @Override
    public <T extends JCTree> void handleCopy(T originalTree, T copiedTree, P value) {
        if (originalTree != treeToAddSuppressWarningsTo) {
            return;
        }

        SymbolCloneResult symbolCloneResult = cloneSymbolAndReplaceInTree(copiedTree);

        List<Compound> newAttributes = suppressWarningsSymbolAdder.addToSuppressWarnings(
                symbolCloneResult.original.getDeclarationAttributes(), checkName);

        symbolCloneResult.clonedSymbol.resetAnnotations();
        symbolCloneResult.clonedSymbol.setDeclarationAttributes(newAttributes);
    }

    private <T extends JCTree> SymbolCloneResult cloneSymbolAndReplaceInTree(T tree) {
        if (tree instanceof JCVariableDecl varDecl) {
            VarSymbol originalSymbol = varDecl.sym;
            varDecl.sym = originalSymbol.clone(originalSymbol.owner);
            return new SymbolCloneResult(originalSymbol, varDecl.sym);
        }

        if (tree instanceof JCMethodDecl methodDecl) {
            MethodSymbol originalSymbol = methodDecl.sym;
            methodDecl.sym = originalSymbol.clone(originalSymbol.owner);
            return new SymbolCloneResult(originalSymbol, methodDecl.sym);
        }

        if (tree instanceof JCClassDecl classDecl) {
            ClassSymbol originalSymbol = classDecl.sym;
            classDecl.sym = new ClassSymbol(
                    originalSymbol.flags(), originalSymbol.name, originalSymbol.type, originalSymbol.owner);
            return new SymbolCloneResult(originalSymbol, classDecl.sym);
        }

        throw new IllegalStateException(
                "You can only give suppressible trees to this class. You gave a " + tree.getKind());
    }

    private record SymbolCloneResult(Symbol original, Symbol clonedSymbol) {}
}
