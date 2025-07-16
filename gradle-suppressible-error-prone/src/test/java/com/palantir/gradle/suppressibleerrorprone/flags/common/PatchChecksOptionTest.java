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

package com.palantir.gradle.suppressibleerrorprone.flags.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatchChecksOptionTest {
    @Test
    void some_checks_sorts_values_alphabetically() {
        PatchChecksOption someChecks = PatchChecksOption.someChecks("zebra", "apple", "banana");

        assertThat(someChecks.asCommaSeparated()).isEqualTo(Optional.of("apple,banana,zebra"));
    }

    @Test
    void combining_all_checks_with_all_checks_returns_all_checks() {
        PatchChecksOption allChecks1 = PatchChecksOption.allChecks();
        PatchChecksOption allChecks2 = PatchChecksOption.allChecks();

        assertThat(allChecks1.combine(allChecks2)).isEqualTo(PatchChecksOption.allChecks());
    }

    @Test
    void combining_all_checks_with_some_checks_returns_all_checks() {
        PatchChecksOption allChecks = PatchChecksOption.allChecks();
        PatchChecksOption someChecks = PatchChecksOption.someChecks("check1");

        assertThat(allChecks.combine(someChecks)).isEqualTo(PatchChecksOption.allChecks());
        assertThat(someChecks.combine(allChecks)).isEqualTo(PatchChecksOption.allChecks());
    }

    @Test
    void combining_some_checks_with_some_checks_merges_and_sorts_values() {
        PatchChecksOption someChecks1 = PatchChecksOption.someChecks("check1", "check2");
        PatchChecksOption someChecks2 = PatchChecksOption.someChecks("check2", "check3");

        assertThat(someChecks1.combine(someChecks2).asCommaSeparated()).isEqualTo(Optional.of("check1,check2,check3"));
    }

    @Test
    void some_checks_memoizes_supplier_result() {
        Set<String> mutableSet = new HashSet<>();
        mutableSet.add("check1");

        PatchChecksOption someChecks = PatchChecksOption.someChecks(() -> mutableSet);
        assertThat(someChecks.asCommaSeparated()).isEqualTo(Optional.of("check1"));

        // Adding to the original set should not affect the result due to memoization
        mutableSet.add("check2");
        assertThat(someChecks.asCommaSeparated()).isEqualTo(Optional.of("check1"));
    }
}
