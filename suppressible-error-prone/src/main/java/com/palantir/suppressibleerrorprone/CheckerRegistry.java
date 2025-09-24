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
import com.sun.tools.javac.util.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry that maps checker names to their BugChecker instances.
 * This includes both built-in Error Prone checkers and custom plugin checkers.
 */
public final class CheckerRegistry {

    private static final Logger logger = Logger.getLogger(CheckerRegistry.class.getName());

    // Cache of checker name to BugChecker instance
    private final Map<String, BugChecker> checkersByName;

    private CheckerRegistry(Map<String, BugChecker> checkersByName) {
        this.checkersByName = ImmutableMap.copyOf(checkersByName);
    }

    /**
     * Creates a CheckerRegistry from enabled checkers only.
     * This is more efficient if you only need to check suppressions for active checkers.
     */
    public static CheckerRegistry createFromEnabledCheckers(VisitorState state) {
        Context context = state.context;

        // Get only enabled checkers (errors and warnings)
        ScannerSupplier enabledSupplier = BuiltInCheckerSuppliers.defaultChecks();

        // Load plugin checkers and filter to enabled ones
        ScannerSupplier allSuppliers = ErrorPronePlugins.loadPlugins(enabledSupplier, context);

        // Build the registry with only enabled checkers
        Map<String, BugChecker> checkersByName = new HashMap<>();

        // Get the severity map to check if checkers are enabled
        Map<String, com.google.errorprone.BugPattern.SeverityLevel> severityMap = state.severityMap();

        for (BugCheckerInfo info : allSuppliers.getAllChecks().values()) {
            // Check if this checker is enabled (not OFF)
            com.google.errorprone.BugPattern.SeverityLevel severity = info.severity(severityMap);

            if (severity != SeverityLevel.SUGGESTION) {
                try {
                    BugChecker checker = instantiateChecker(info.checkerClass());

                    // Register by canonical name
                    checkersByName.put(info.canonicalName(), checker);

                    // Register by all alternative names
                    for (String name : info.allNames()) {
                        checkersByName.put(name, checker);
                    }

                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to instantiate checker: " + info.canonicalName(), e);
                }
            }
        }

        System.err.println("Initialized checkers: " + checkersByName.keySet());

        return new CheckerRegistry(checkersByName);
    }

    /**
     * Gets the BugChecker instance for the given checker name.
     *
     * @param checkerName the name of the checker (canonical name or alternative name)
     * @return Optional containing the BugChecker if found, empty otherwise
     */
    public Optional<BugChecker> getCheckerForName(String checkerName) {
        return Optional.ofNullable(checkersByName.get(checkerName));
    }

    /**
     * Convenience method for the RemoveUnusedSuppressions class.
     */
    public static BugChecker getCheckerForSuppression(String suppression, VisitorState state) {
        // Create registry lazily - you might want to cache this per compilation unit
        CheckerRegistry registry = createFromEnabledCheckers(state);
        return registry.getCheckerForName(suppression).orElse(null);
    }

    private static BugChecker instantiateChecker(Class<? extends BugChecker> checkerClass) {
        // Create an injector with empty flags, similar to ScannerSupplierImpl
        ErrorProneInjector injector =
                ErrorProneInjector.create().addBinding(ErrorProneFlags.class, ErrorProneFlags.empty());

        return injector.getInstance(checkerClass);
    }

    /**
     * Returns the number of registered checkers.
     */
    public int size() {
        return checkersByName.size();
    }
}
