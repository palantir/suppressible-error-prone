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
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.scanner.BuiltInCheckerSuppliers;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import one.util.streamex.StreamEx;

public final class AllErrorprones {
    private static Set<String> cachedAllBugcheckerNames;

    public static Set<String> allBugcheckerNames() {
        if (cachedAllBugcheckerNames == null) {
            Stream<BugChecker> pluginChecks =
                    ServiceLoader.load(BugChecker.class).stream().map(ServiceLoader.Provider::get);
            Stream<String> pluginCheckNames =
                    StreamEx.of(pluginChecks).flatMap(bugchecker -> bugchecker.allNames().stream());

            Stream<BugCheckerInfo> builtInChecks = BuiltInCheckerSuppliers.allChecks().getAllChecks().values().stream();
            Stream<String> builtInCheckNames =
                    StreamEx.of(builtInChecks).flatMap(bugchecker -> bugchecker.allNames().stream());

            cachedAllBugcheckerNames =
                    Stream.concat(pluginCheckNames, builtInCheckNames).collect(Collectors.toSet());
        }

        return cachedAllBugcheckerNames;
    }

    private AllErrorprones() {}
}
