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

import com.google.errorprone.BugCheckerInfo;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.scanner.BuiltInCheckerSuppliers;
import com.google.errorprone.suppliers.Supplier;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;

public final class AllErrorprones {
    private static Supplier<Map<String, Set<String>>> canonicalToAllNames = VisitorState.memoize(state -> {
        // Use the same classloader that Error Prone was loaded from to avoid classloader skew
        // when using Error Prone plugins together with the Error Prone javac plugin.
        JavacProcessingEnvironment processingEnvironment = JavacProcessingEnvironment.instance(state.context);
        ClassLoader loader = processingEnvironment.getProcessorClassLoader();
        List<BugChecker> pluginChecks = ServiceLoader.load(BugChecker.class, loader).stream()
                .map(Provider::get)
                .collect(Collectors.toList());
        EntryStream<String, Set<String>> pluginCheckNames =
                StreamEx.of(pluginChecks).mapToEntry(BugChecker::canonicalName, BugChecker::allNames);

        Stream<BugCheckerInfo> builtInChecks = BuiltInCheckerSuppliers.allChecks().getAllChecks().values().stream();
        EntryStream<String, Set<String>> builtInCheckNames =
                StreamEx.of(builtInChecks).mapToEntry(BugCheckerInfo::canonicalName, BugCheckerInfo::allNames);

        return pluginCheckNames.append(builtInCheckNames).toMap();
    });

    private static Supplier<Set<String>> allNames =
            VisitorState.memoize(state -> EntryStream.of(canonicalToAllNames.get(state))
                    .flatMap(entry -> entry.getValue().stream())
                    .collect(Collectors.toSet()));

    private static Supplier<Map<String, Set<String>>> nameToPossibleCanonicalNames =
            VisitorState.memoize(state -> EntryStream.of(canonicalToAllNames.get(state))
                    .flatMapValues(Set::stream)
                    .invert()
                    .grouping(Collectors.toSet()));

    public static Set<String> allBugcheckerNames(VisitorState state) {
        return allNames.get(state);
    }

    public static Optional<Set<String>> allNames(VisitorState state, String canonicalName) {
        return Optional.ofNullable(canonicalToAllNames.get(state).get(canonicalName));
    }

    public static Set<String> possibleCanonicalNames(VisitorState state, String name) {
        return nameToPossibleCanonicalNames.get(state).getOrDefault(name, Set.of());
    }

    private AllErrorprones() {}
}
