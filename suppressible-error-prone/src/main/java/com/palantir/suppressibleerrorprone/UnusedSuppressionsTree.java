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

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SuppressWarnings("BadAssert")
public class UnusedSuppressionsTree {
    private final Map<Tree, Set<String>> treeToSuppressions;
    private final Map<Tree, Set<String>> usedSuppressions;
    private final Map<Tree, TreePath> treeToPath;

    private UnusedSuppressionsTree(Map<Tree, Set<String>> treeToSuppressions, Map<Tree, TreePath> treeToPath) {
        this.treeToSuppressions = Map.copyOf(treeToSuppressions);
        this.treeToPath = Map.copyOf(treeToPath);
        this.usedSuppressions = new HashMap<>();
        treeToSuppressions.keySet().forEach(tree -> usedSuppressions.put(tree, ConcurrentHashMap.newKeySet()));
    }

    public static UnusedSuppressionsTree initializeWithSuppressions(CompilationUnitTree tree) {
        Map<Tree, Set<String>> treeToSuppressions = new HashMap<>();
        Map<Tree, TreePath> treeToPath = new HashMap<>();

        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void p) {
                if (tree != null) {
                    treeToPath.put(tree, new TreePath(getCurrentPath(), tree));
                }
                return super.scan(tree, p);
            }

            @Override
            public Void visitMethod(MethodTree node, Void p) {
                collectSuppressions(node, node.getModifiers());
                return super.visitMethod(node, p);
            }

            @Override
            public Void visitClass(ClassTree node, Void p) {
                collectSuppressions(node, node.getModifiers());
                return super.visitClass(node, p);
            }

            @Override
            public Void visitVariable(VariableTree node, Void p) {
                collectSuppressions(node, node.getModifiers());
                return super.visitVariable(node, p);
            }

            private void collectSuppressions(Tree tree, ModifiersTree modifiers) {
                Set<String> suppressions = new HashSet<>();
                for (AnnotationTree annotation : modifiers.getAnnotations()) {
                    if (isSuppressWarningsAnnotation(annotation)) {
                        AnnotationUtils.annotationStringValues(annotation).forEach(suppressions::add);
                    }
                }
                if (!suppressions.isEmpty()) {
                    treeToSuppressions.put(tree, suppressions);
                }
            }

            private boolean isSuppressWarningsAnnotation(AnnotationTree annotation) {
                return AnnotationUtils.annotationName(annotation.getAnnotationType())
                        .contentEquals("SuppressWarnings");
            }
        }.scan(new TreePath(tree), null);

        return new UnusedSuppressionsTree(treeToSuppressions, treeToPath);
    }

    public Set<String> allSuppressionNames() {
        return treeToSuppressions.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
    }

    /**
     * Starting from {@code tree}, look for the first tree along the path which has a suppression on
     * {@code suppressionName}, and mark that suppression as used.
     *
     * This method is forced to take in a {@code Tree} rather than a {@code TreePath}, because it is called from
     * {@code description.position.getTree()}. To avoid doing a tree walk, we cache the tree->path mapping during
     * construction.
     */
    public void flagFirstParentSuppressionAsUsed(Tree tree, String suppressionName) {
        System.err.println("Flagging suppressions as used: " + suppressionName);
        System.err.println("tree: " + tree);
        TreePath treePath = treeToPath.get(tree);
        if (treePath == null) {
            System.err.println("No TreePath found for tree - tree not in our suppression map");
            System.err.println("========================================\n");
            return; // Tree not found in our map
        }
        System.err.println("leaf of path: " + treePath.getLeaf());

        for (TreePath path = treePath; path != null; path = path.getParentPath()) {
            Tree curr = path.getLeaf();
            Set<String> suppressions = treeToSuppressions.get(curr);
            if (suppressions != null && suppressions.contains(suppressionName)) {
                usedSuppressions.get(curr).add(suppressionName);
                System.err.println("Flagged suppression '" + suppressionName + "' as used: " + curr);
                System.err.println("========================================\n");

                return;
            }
        }

        System.err.println("No parent suppression found for '" + suppressionName + "' - walked entire parent chain");
        System.err.println("========================================\n");
    }

    public void markAllSuppressionsAsUsed(String suppressionName) {
        treeToSuppressions.entrySet().stream()
                .filter(entry -> entry.getValue().contains(suppressionName))
                .forEach(entry -> usedSuppressions.get(entry.getKey()).add(suppressionName));
    }

    public Set<TreeWithUnusedSuppressions> unused() {
        return treeToSuppressions.entrySet().stream()
                .map(entry -> {
                    Tree tree = entry.getKey();
                    Set<String> allSuppressions = entry.getValue();
                    Set<String> used = usedSuppressions.get(tree);
                    Set<String> unused = allSuppressions.stream()
                            .filter(s -> !used.contains(s))
                            .collect(Collectors.toSet());
                    return unused.isEmpty() ? null : new TreeWithUnusedSuppressions(tree, unused);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public record TreeWithUnusedSuppressions(Tree tree, Set<String> unusedSuppressions) {
        public TreeWithUnusedSuppressions {
            if (!(tree instanceof MethodTree || tree instanceof ClassTree || tree instanceof VariableTree)) {
                throw new IllegalArgumentException("Tree must be MethodTree, ClassTree, or VariableTree");
            }
            unusedSuppressions = Set.copyOf(unusedSuppressions);
        }
    }
}
