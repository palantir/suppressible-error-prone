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

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotatedType;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMemberReference;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCPackageDecl;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import java.util.function.Consumer;

final class SuppressWarningsAddingTreeTranslator extends TreeTranslator {
    private final VisitorState visitorState;
    private final JCModifiers originalModifiers;
    private final String checkName;

    private JCModifiers copiedJcModifiers;

    SuppressWarningsAddingTreeTranslator(VisitorState visitorState, JCModifiers jcModifiers, String checkName) {
        this.originalModifiers = jcModifiers;
        this.visitorState = visitorState;
        this.checkName = checkName;
    }

    private abstract static class SymbolTreeCopier<T> extends TreeCopier<T> {
        SymbolTreeCopier(TreeMaker treeMaker) {
            super(treeMaker);
        }

        @Override
        public <P extends JCTree> P copy(P tree, T value) {
            P copy = super.copy(tree, value);
            if (tree != null && copy != null) {
                copySymbols(tree, copy);
            }
            return copy;
        }

        @SuppressWarnings("checkstyle:CyclomaticComplexity")
        private <P extends JCTree> void copySymbols(P from, P to) {
            if (from instanceof JCClassDecl fromClass && to instanceof JCClassDecl toClass) {
                toClass.sym = fromClass.sym;
            } else if (from instanceof JCMethodDecl fromMethod && to instanceof JCMethodDecl toMethod) {
                toMethod.sym = fromMethod.sym;
            } else if (from instanceof JCVariableDecl fromVar && to instanceof JCVariableDecl toVar) {
                toVar.sym = fromVar.sym;
            } else if (from instanceof JCPackageDecl fromPkg && to instanceof JCPackageDecl toPkg) {
                toPkg.packge = fromPkg.packge;
            } else if (from instanceof JCTypeParameter fromType && to instanceof JCTypeParameter toType) {
                toType.type = fromType.type;
            } else if (from instanceof JCIdent fromIdent && to instanceof JCIdent toIdent) {
                toIdent.sym = fromIdent.sym;
            } else if (from instanceof JCFieldAccess fromField && to instanceof JCFieldAccess toField) {
                toField.sym = fromField.sym;
            } else if (from instanceof JCMethodInvocation fromInvoke && to instanceof JCMethodInvocation toInvoke) {
                if (fromInvoke.meth instanceof JCFieldAccess fromMeth
                        && toInvoke.meth instanceof JCFieldAccess toMeth) {
                    toMeth.sym = fromMeth.sym;
                }
            } else if (from instanceof JCNewClass fromNew && to instanceof JCNewClass toNew) {
                toNew.constructor = fromNew.constructor;
            } else if (from instanceof JCMemberReference fromRef && to instanceof JCMemberReference toRef) {
                toRef.sym = fromRef.sym;
            } else if (from instanceof JCAnnotatedType fromAnnot && to instanceof JCAnnotatedType toAnnot) {
                copySymbols(fromAnnot.underlyingType, toAnnot.underlyingType);
            }
        }
    }

    private static final class TrackingCopier<T extends JCTree> extends SymbolTreeCopier<Void> {
        private final T originalTree;
        private final Consumer<T> copiedTreeConsumer;

        TrackingCopier(TreeMaker treeMaker, T originalTree, Consumer<T> copiedTreeConsumer) {
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

    public JCTree translateTree() {
        JCTree original = (JCTree) visitorState.getPath().getLeaf();

        JCTree copy = new TrackingCopier<>(
                        visitorState.getTreeMaker(), originalModifiers, mods -> copiedJcModifiers = mods)
                .copy(original);

        return translate(copy);
    }

    @Override
    public void visitModifiers(JCModifiers tree) {
        if (copiedJcModifiers != tree) {
            super.visitModifiers(tree);
            return;
        }

        TreeMaker trees = visitorState.getTreeMaker();

        // Create a SuppressWarnings annotation with the current check name
        JCAnnotation suppressWarningsAnnotation = trees.Annotation(
                trees.Type(visitorState.getTypeFromString("java.lang.SuppressWarnings")),
                List.of(trees.Assign(trees.Ident(visitorState.getName("value")), trees.Literal(checkName))));

        // Add the annotation to the existing modifiers
        JCModifiers newModifiers = trees.Modifiers(
                copiedJcModifiers.flags, List.from(copiedJcModifiers.annotations.append(suppressWarningsAnnotation)));

        // Copy position information from the original modifiers
        newModifiers.pos = tree.pos;

        result = newModifiers;
    }

    @Override
    public <T extends JCTree> T translate(T tree) {
        T result = super.translate(tree);
        if (result != tree && result != null) {
            // Preserve position information
            result.pos = tree.pos;
        }
        return result;
    }
}
