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

import com.google.common.collect.Iterables;
import com.palantir.gradle.suppressibleerrorprone.modes.common.CommonOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode;
import com.palantir.gradle.suppressibleerrorprone.modes.common.Mode.ModeOptionContext;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterferenceResult;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterferenceResult.Interference;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeInterferenceResult.NotCompatible;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModeName;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption.CombinedValue;
import com.palantir.gradle.suppressibleerrorprone.modes.interferences.DisableModeInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.interferences.RemovingAndSuppressingInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.interferences.SuppressingAndApplyingInterference;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.ApplyMode;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.DisableMode;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.RemoveRolloutMode;
import com.palantir.gradle.suppressibleerrorprone.modes.modes.RemoveUnnecessarySuppressionsMode;
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

/**
 * Use this class to determine the final combination of {@link Mode}s and {@link CommonOptions} for the various
 * flags given to suppressible-error-prone.
 */
public abstract class Modes {
    @Inject
    protected abstract ProviderFactory getProviderFactory();

    private final Map<ModeName, Mode> modes = Map.of(
            ModeName.APPLY, new ApplyMode(),
            ModeName.DISABLE, new DisableMode(),
            ModeName.REMOVE_ROLLOUT, new RemoveRolloutMode(),
            ModeName.REMOVE_UNNECESSARY_SUPPRESSIONS, new RemoveUnnecessarySuppressionsMode(),
            ModeName.SUPPRESS, new SuppressMode(),
            ModeName.TIMINGS, new TimingsMode());

    private final Set<ModeInterference> interferences = Set.of(
            new DisableModeInterference(),
            new RemovingAndSuppressingInterference(),
            new SuppressingAndApplyingInterference());

    public final CombinedValue modifyCheckApi() {
        return ModifyCheckApiOption.combine(
                StreamEx.of(modesEnabledAndFlagValues().keySet())
                        .map(modes::get)
                        .map(Mode::modifyCheckApi)
                        .toSet());
    }

    public final CommonOptions commonOptionsFor(JavaCompile javaCompile) {
        Map<ModeName, Optional<String>> modeNameToFlagValue = modesEnabledAndFlagValues();

        Map<ModeInterference, ModeInterferenceResult> interferenceResults = StreamEx.of(interferences)
                .mapToEntry(interference -> interference.interferesWith(modeNameToFlagValue.keySet()))
                .toMap();

        Set<NotCompatible> notCompatibles = StreamEx.of(interferenceResults.values())
                .select(NotCompatible.class)
                .toSet();

        if (!notCompatibles.isEmpty()) {
            throw new IllegalStateException("Flags were used which are incompatible:\n"
                    + notCompatibles.stream()
                            .map(notCompatible -> "* " + notCompatible.message())
                            .collect(Collectors.joining("\n")));
        }

        Map<Interference, ModeInterference> interferingModes = EntryStream.of(interferenceResults)
                .selectValues(Interference.class)
                .invert()
                .toMap();

        Map<ModeName, CommonOptions> commonOptionsPerMode = EntryStream.of(modeNameToFlagValue)
                .mapToValue((flagName, flagValue) -> {
                    Mode mode = modes.get(flagName);
                    return mode.configureAndReturnCommonOptions(new ModeOptionContext(flagValue, javaCompile));
                })
                .toMap();

        Map<Interference, CommonOptions> commonOptionsAfterInterfering = EntryStream.of(interferingModes)
                .mapToValue((interference, modeInterference) -> {
                    return modeInterference.interfere(StreamEx.of(interference.interferingModes())
                            .mapToEntry(commonOptionsPerMode::get)
                            .toMap());
                })
                .toMap();

        Set<ModeName> allInterferingModes = StreamEx.of(interferingModes.keySet())
                .flatMap(interference -> interference.interferingModes().stream())
                .toSet();

        Set<CommonOptions> nonInterferingCommonOptions = EntryStream.of(commonOptionsPerMode)
                .removeKeys(allInterferingModes::contains)
                .values()
                .toSet();

        Set<CommonOptions> allCommonOptions = StreamEx.of(commonOptionsAfterInterfering.values())
                .append(nonInterferingCommonOptions)
                .toSet();

        return CommonOptions.naivelyCombine(allCommonOptions);
    }

    private Map<ModeName, Optional<String>> modesEnabledAndFlagValues() {
        return StreamEx.of(modes.keySet())
                .flatMapToEntry(modeName -> {
                    Map<String, List<String>> flagValuesToNames = StreamEx.of(modeName.allFlags())
                            .mapToEntry(getProviderFactory()::gradleProperty)
                            .filterValues(Provider::isPresent)
                            .mapValues(Provider::get)
                            .invert()
                            .grouping();

                    if (flagValuesToNames.isEmpty()) {
                        return Map.of();
                    }

                    if (flagValuesToNames.size() > 1) {
                        throw new IllegalArgumentException(
                                "Multiple instances of flags for the same mode were supplied with different values: "
                                        + flagValuesToNames);
                    }

                    return Map.of(modeName, Iterables.getOnlyElement(flagValuesToNames.keySet()));
                })
                .mapValues(value -> Optional.of(value).filter(Predicate.not(String::isBlank)))
                .toMap();
    }
}
