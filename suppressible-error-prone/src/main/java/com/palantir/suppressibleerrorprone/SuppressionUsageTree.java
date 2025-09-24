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
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SuppressionUsageTree {
    private final Map<Tree, Set<String>> treeToSuppressions;
    private final Map<Tree, Set<String>> usedSuppressions;

    private SuppressionUsageTree(Map<Tree, Set<String>> treeToSuppressions) {
        this.treeToSuppressions = Map.copyOf(treeToSuppressions);
        this.usedSuppressions = new ConcurrentHashMap<>();
        // Initialize used suppressions map
        treeToSuppressions.keySet().forEach(tree -> usedSuppressions.put(tree, ConcurrentHashMap.newKeySet()));
    }

    public static SuppressionUsageTree constructSuppressions(CompilationUnitTree tree, VisitorState state) {
        Map<Tree, Set<String>> treeToSuppressions = new HashMap<>();

        new TreeScanner<Void, Void>() {
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
        }.scan(tree, null);

        return new SuppressionUsageTree(treeToSuppressions);
    }

    public Set<String> allSuppressionNames() {
        return treeToSuppressions.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
    }

    public void flagImmediateSuppressionsAsUsed(TreePath treePath, String suppressionName) {
        System.err.println("========================================");
        System.err.println("Flagging suppressions as used: " + suppressionName);
        System.err.println(treePath.getLeaf());
        System.err.println("========================================\n");

        // First, flag the first parent suppression as used
        for (TreePath path = treePath; path != null; path = path.getParentPath()) {
            Tree tree = path.getLeaf();
            Set<String> suppressions = treeToSuppressions.get(tree);
            if (suppressions != null && suppressions.contains(suppressionName)) {
                usedSuppressions.get(tree).add(suppressionName);
                break; // Only flag the first parent
            }
        }

        // Then, flag all reachable child suppressions as used
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void p) {
                if (tree == null) {
                    return null;
                }

                // Check if this tree has the suppression we're looking for
                Set<String> suppressions = treeToSuppressions.get(tree);
                if (suppressions != null && suppressions.contains(suppressionName)) {
                    // Flag this suppression as used
                    usedSuppressions.get(tree).add(suppressionName);

                    // Don't continue scanning children - this suppression blocks further propagation
                    return null;
                }

                // Continue scanning children if no blocking suppression found
                return super.scan(tree, p);
            }
        }.scan(treePath.getLeaf(), null);
    }

    public void markAllSuppressionsAsUsed(String suppressionName) {
        treeToSuppressions.entrySet().stream()
                .filter(entry -> entry.getValue().contains(suppressionName))
                .forEach(entry -> usedSuppressions.get(entry.getKey()).add(suppressionName));
    }

    public Set<TreeWithUnusedSuppressions> unusedSuppressions() {
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
