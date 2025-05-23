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
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;

public final class SuppressWarningsAddingTreeTranslator extends TreeTranslator {
    private final VisitorState visitorState;
    private final JCModifiers originalModifiers;
    private final String checkName;

    private JCModifiers copiedJcModifiers;

    public SuppressWarningsAddingTreeTranslator(VisitorState visitorState, JCModifiers jcModifiers, String checkName) {
        this.originalModifiers = jcModifiers;
        this.visitorState = visitorState;
        this.checkName = checkName;
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
    public void visitMethodDef(JCMethodDecl tree) {
        super.visitMethodDef(tree);
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
