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

package com.palantir.gradle.suppressibleerrorprone.modes;

import com.palantir.gradle.suppressibleerrorprone.modes.common.Flag;
import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode;
import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode.FlagOptionContext;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption;
import com.palantir.gradle.suppressibleerrorprone.modes.interferences.DisableModeInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.interferences.RemovingAndSuppressingInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.interferences.SuppressingAndApplyingInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.ApplyMode;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.DisableMode;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.RemoveRolloutMode;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.SuppressMode;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.TimingsMode;
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

public abstract class Modes {
    @Inject
    protected abstract ProviderFactory getProviderFactory();

    private final Map<Flag, Mode> flags = Map.of(
            Flag.APPLY, new ApplyMode(),
            Flag.DISABLE, new DisableMode(),
            Flag.REMOVE_ROLLOUT, new RemoveRolloutMode(),
            Flag.SUPPRESS, new SuppressMode(),
            Flag.TIMINGS, new TimingsMode());

    private final Set<ModeInterference> interferences = Set.of(
            new DisableModeInterference(),
            new RemovingAndSuppressingInterference(),
            new SuppressingAndApplyingInterference());

    public final ModifyCheckApiOption.FinalValue modifyCheckApi() {
        return ModifyCheckApiOption.combine(flagsEnabledAndValues().keySet().stream()
                .map(flags::get)
                .map(Mode::modifyCheckApi)
                .collect(Collectors.toSet()));
    }

    public final ModeOptions flagOptionsFor(JavaCompile javaCompile) {
        Map<Flag, Optional<String>> flagToFlagValue = flagsEnabledAndValues();

        Map<Set<Flag>, ModeInterference> interferingFlags = StreamEx.of(interferences)
                .mapToEntry(interference -> interference.interferesWith(flagToFlagValue.keySet()))
                .filterValues(Predicate.not(Set::isEmpty))
                .invert()
                .toMap();

        Set<Flag> allInterferingFlags =
                interferingFlags.keySet().stream().flatMap(Set::stream).collect(Collectors.toSet());

        Map<Flag, ModeOptions> flagOptions = EntryStream.of(flagToFlagValue)
                .mapToValue((flagName, flagValue) -> {
                    Mode mode = flags.get(flagName);
                    return mode.options(new FlagOptionContext(flagValue, javaCompile));
                })
                .toMap();

        Map<Set<Flag>, ModeOptions> interferingFlagOptions = EntryStream.of(interferingFlags)
                .mapToValue((interferingFlagNames, modeInterference) -> {
                    return modeInterference.interfere(StreamEx.of(interferingFlagNames)
                            .mapToEntry(flagOptions::get)
                            .toMap());
                })
                .toMap();

        Set<ModeOptions> nonInterferingModeOptions = EntryStream.of(flagOptions)
                .filterKeys(Predicate.not(allInterferingFlags::contains))
                .values()
                .toSet();

        Set<ModeOptions> allModeOptions = StreamEx.of(interferingFlagOptions.values())
                .append(nonInterferingModeOptions)
                .collect(Collectors.toSet());

        return ModeOptions.naivelyCombine(allModeOptions);
    }

    private Map<Flag, Optional<String>> flagsEnabledAndValues() {
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
