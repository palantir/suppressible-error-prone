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

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.BugCheckerInfo;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.ErrorPronePlugins;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.scanner.BuiltInCheckerSuppliers;
import com.google.errorprone.scanner.ErrorProneInjector;
import com.google.errorprone.scanner.ScannerSupplier;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry that maps checker names to their BugChecker instances.
 * This includes both built-in Error Prone checkers and custom plugin checkers.
 */
public final class BugCheckerRegistry {
    private final Map<String, BugChecker> checkersByName;

    private BugCheckerRegistry(Map<String, BugChecker> checkersByName) {
        this.checkersByName = ImmutableMap.copyOf(checkersByName);
    }

    /**
     * Creates a BugCheckerRegistry from enabled checkers only.
     */
    public static BugCheckerRegistry constructFromEnabledCheckers(VisitorState state) {
        ScannerSupplier defaultBugCheckers = BuiltInCheckerSuppliers.defaultChecks();
        ScannerSupplier defaultAndPluginBugCheckers = ErrorPronePlugins.loadPlugins(defaultBugCheckers, state.context);

        // Use a injector with empty flags, similar to ScannerSupplierImpl
        ErrorProneInjector injector =
                ErrorProneInjector.create().addBinding(ErrorProneFlags.class, ErrorProneFlags.empty());
        Map<String, SeverityLevel> severityMap = state.severityMap();

        Map<String, BugChecker> enabledBugCheckers = defaultAndPluginBugCheckers.getAllChecks().values().stream()
                .filter(info -> info.severity(severityMap) != SeverityLevel.SUGGESTION)
                .collect(Collectors.toMap(
                        BugCheckerInfo::canonicalName, info -> injector.getInstance(info.checkerClass())));

        return new BugCheckerRegistry(enabledBugCheckers);
    }

    /**
     * Gets the BugChecker instance for the given checker name.
     *
     * @param checkerName the name of the checker (canonical name or alternative name)
     * @return Optional containing the BugChecker if found, empty otherwise
     */
    public Optional<BugChecker> get(String checkerName) {
        return Optional.ofNullable(checkersByName.get(checkerName));
    }
}
