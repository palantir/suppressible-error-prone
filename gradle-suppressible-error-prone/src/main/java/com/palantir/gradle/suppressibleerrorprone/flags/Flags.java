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

package com.palantir.gradle.suppressibleerrorprone.flags;

import com.palantir.gradle.suppressibleerrorprone.flags.common.Flag;
import com.palantir.gradle.suppressibleerrorprone.flags.common.Flag.FlagOptionContext;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagInterference;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagName;
import com.palantir.gradle.suppressibleerrorprone.flags.common.FlagOptions;
import com.palantir.gradle.suppressibleerrorprone.flags.common.ModifyCheckApiOption;
import com.palantir.gradle.suppressibleerrorprone.flags.flags.ApplyFlag;
import com.palantir.gradle.suppressibleerrorprone.flags.flags.DisableFlag;
import com.palantir.gradle.suppressibleerrorprone.flags.flags.RemoveRolloutFlag;
import com.palantir.gradle.suppressibleerrorprone.flags.flags.SuppressFlag;
import com.palantir.gradle.suppressibleerrorprone.flags.flags.TimingsFlag;
import com.palantir.gradle.suppressibleerrorprone.flags.interferences.DisableFlagInterference;
import com.palantir.gradle.suppressibleerrorprone.flags.interferences.RemovingAndSuppressingInterference;
import com.palantir.gradle.suppressibleerrorprone.flags.interferences.SuppressingAndApplyingInterference;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.compile.JavaCompile;

public abstract class Flags {
    @Inject
    protected abstract ProviderFactory getProviderFactory();

    private final Map<FlagName, Flag> flags = Map.of(
            FlagName.APPLY, new ApplyFlag(),
            FlagName.DISABLE, new DisableFlag(),
            FlagName.REMOVE_ROLLOUT, new RemoveRolloutFlag(),
            FlagName.SUPPRESS, new SuppressFlag(),
            FlagName.TIMINGS, new TimingsFlag());

    private final Set<FlagInterference> interferences = Set.of(
            new DisableFlagInterference(),
            new RemovingAndSuppressingInterference(),
            new SuppressingAndApplyingInterference());

    public final ModifyCheckApiOption.FinalValue modifyCheckApi() {
        return ModifyCheckApiOption.combine(flagsEnabledAndValues().keySet().stream()
                .map(flags::get)
                .map(Flag::modifyCheckApi)
                .collect(Collectors.toSet()));
    }

    public final FlagOptions flagOptionsFor(JavaCompile javaCompile) {
        Map<FlagName, Optional<String>> flagToFlagValue = flagsEnabledAndValues();

        Map<Set<FlagName>, FlagInterference> interferingFlags = StreamEx.of(interferences)
                .mapToEntry(interference -> interference.interferesWith(flagToFlagValue.keySet()))
                .filterValues(Predicate.not(Set::isEmpty))
                .invert()
                .toMap();

        Set<FlagName> allInterferingFlagNames =
                interferingFlags.keySet().stream().flatMap(Set::stream).collect(Collectors.toSet());

        Map<FlagName, FlagOptions> flagOptions = EntryStream.of(flagToFlagValue)
                .mapToValue((flagName, flagValue) -> {
                    Flag flag = flags.get(flagName);
                    return flag.options(new FlagOptionContext(flagValue, javaCompile));
                })
                .toMap();

        Map<Set<FlagName>, FlagOptions> interferingFlagOptions = EntryStream.of(interferingFlags)
                .mapToValue((interferingFlagNames, flagInterference) -> {
                    return flagInterference.interfere(StreamEx.of(interferingFlagNames)
                            .mapToEntry(flagOptions::get)
                            .toMap());
                })
                .toMap();

        Set<FlagOptions> nonInterferingFlagOptions = EntryStream.of(flagOptions)
                .filterKeys(Predicate.not(allInterferingFlagNames::contains))
                .values()
                .toSet();

        Set<FlagOptions> allFlagOptions = StreamEx.of(interferingFlagOptions.values())
                .append(nonInterferingFlagOptions)
                .collect(Collectors.toSet());

        return FlagOptions.naivelyCombine(allFlagOptions);
    }

    private Map<FlagName, Optional<String>> flagsEnabledAndValues() {
        return StreamEx.of(flags.keySet())
                .flatMapToEntry(flagName -> {
                    Map<String, List<String>> valuesToNames = StreamEx.of(flagName.allNames())
                            .mapToEntry(getProviderFactory()::gradleProperty)
                            .filterValues(Provider::isPresent)
                            .mapValues(Provider::get)
                            .invert()
                            .grouping();

                    if (valuesToNames.isEmpty()) {
                        return Map.of();
                    }

                    if (valuesToNames.size() > 1) {
                        throw new IllegalArgumentException(
                                "Multiple instances of the same flag were supplied with different values: "
                                        + valuesToNames);
                    }

                    return Map.of(flagName, valuesToNames.keySet().iterator().next());
                })
                .mapValues(value -> Optional.of(value).filter(Predicate.not(String::isBlank)))
                .toMap();
    }
}
