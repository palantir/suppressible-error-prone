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

import com.google.common.collect.Sets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SuppressWarningsUtils {
    private enum SuppressionsType {
        AUTOMATICALLY_ADDED,
        HUMAN_AUTHORED;

        public static SuppressionsType fromName(String name) {
            return name.startsWith(CommonConstants.AUTOMATICALLY_ADDED_PREFIX) ? AUTOMATICALLY_ADDED : HUMAN_AUTHORED;
        }
    }

    public static List<String> modifySuppressions(
            List<String> existingSuppressions, Set<String> newAutomatedSuppressions) {
        return modifySuppressions(existingSuppressions, newAutomatedSuppressions, false);
    }

    public static List<String> modifySuppressions(
            List<String> existingSuppressions,
            Set<String> encounteredErrors,
            boolean isRemovingUnusedRolloutSuppressions) {
        Map<SuppressionsType, List<String>> automaticallyAddedOrNotSuppressions = existingSuppressions.stream()
                .collect(Collectors.groupingBy(SuppressionsType::fromName, Collectors.toList()));

        List<String> existingAutomaticallyAddedSuppressions =
                automaticallyAddedOrNotSuppressions.getOrDefault(SuppressionsType.AUTOMATICALLY_ADDED, List.of());

        List<String> humanAuthoredSuppressions =
                automaticallyAddedOrNotSuppressions.getOrDefault(SuppressionsType.HUMAN_AUTHORED, List.of());

        Set<String> existingAutomaticallyAddedSuppressionsWithoutPrefix =
                existingAutomaticallyAddedSuppressions.stream()
                        .map(error -> error.replace(CommonConstants.AUTOMATICALLY_ADDED_PREFIX, ""))
                        .collect(Collectors.toSet());

        Set<String> newAutomatedSuppressions = isRemovingUnusedRolloutSuppressions
                ? encounteredErrors
                : Sets.union(existingAutomaticallyAddedSuppressionsWithoutPrefix, encounteredErrors);

        List<String> modifiedAutomaticallyAddedSuppressions = newAutomatedSuppressions.stream()
                .filter(Predicate.not(humanAuthoredSuppressions::contains))
                .distinct()
                .sorted()
                .map(warning -> CommonConstants.AUTOMATICALLY_ADDED_PREFIX + warning)
                .collect(Collectors.toList());

        return Stream.concat(humanAuthoredSuppressions.stream(), modifiedAutomaticallyAddedSuppressions.stream())
                .collect(Collectors.toList());
    }

    public static String suppressWarningsString(List<String> warningsToSuppress) {
        if (warningsToSuppress.isEmpty()) {
            return "";
        }

        String suppressWarningsString = '"' + String.join("\", \"", warningsToSuppress) + '"';

        if (warningsToSuppress.size() > 1) {
            suppressWarningsString = "{" + suppressWarningsString + "}";
        }
        return "@" + CommonConstants.SUPPRESS_WARNINGS_ANNOTATION + "(" + suppressWarningsString + ")";
    }

    private SuppressWarningsUtils() {}
}
