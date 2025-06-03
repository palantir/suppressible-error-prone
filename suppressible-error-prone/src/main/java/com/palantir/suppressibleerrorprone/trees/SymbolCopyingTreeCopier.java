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
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotatedType;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMemberReference;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCPackageDecl;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;

public final class SymbolCopyingTreeCopier<P> implements TreeCopyHandler<P> {
    @Override
    public <T extends JCTree> void handleCopy(T originalTree, T copiedTree, P value) {
        copySymbols(originalTree, copiedTree);
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private <T extends JCTree> void copySymbols(T from, T to) {
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
            if (fromInvoke.meth instanceof JCFieldAccess fromMeth && toInvoke.meth instanceof JCFieldAccess toMeth) {
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
