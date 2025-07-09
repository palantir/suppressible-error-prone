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

package com.palantir.gradle.suppressibleerrorprone.flags.interferences;

import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagName;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagOptions;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagSetInterference;
import java.util.Map;
import java.util.Set;

public final class SuppressingAndApplyingInterference extends FlagSetInterference {
    @Override
    public Set<FlagName> interferingFlags() {
        return Set.of(FlagName.SUPPRESS, FlagName.APPLY);
    }

    @Override
    public FlagOptions interfere(Map<FlagName, FlagOptions> flagOptions) {
        FlagOptions suppress = flagOptions.get(FlagName.SUPPRESS);
        FlagOptions apply = flagOptions.get(FlagName.APPLY);

        // If we're applying suggested patches at the same time as suppressing, we still need to tell
        // errorprone to patch all checks, so we can make suggested fixes for suppressions in any check.
        // However, inside our changes to errorprone, we need to get the list of checks that we're going
        // to use the default suggested fixes for, so we can work out which ones to use the suggested
        // fixes for and which to suppress. So we add the PreferPatchChecks argument here, which we can
        // use inside error-prone/the compiler.

        return suppress.naivelyCombinedWith(apply)
                .withExtraFlag("PreferPatchChecks", apply.patchChecks().asCommaSeparated());
    }
}
