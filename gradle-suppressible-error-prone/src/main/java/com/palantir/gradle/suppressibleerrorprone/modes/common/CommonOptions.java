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

package com.palantir.gradle.suppressibleerrorprone.modes.common;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import one.util.streamex.EntryStream;

/**
 * Represents options for configuration that multiple mode can influence, rather than something a single mode can
 * configure. This allows all the options from multiple modes to be combined, interfered between and
 * configured in a single place.
 */
public interface CommonOptions {
    default PatchChecksOption patchChecks() {
        return PatchChecksOption.noChecks();
    }

    default Map<String, String> extraErrorProneCheckOptions() {
        return Map.of();
    }

    default RemoveRolloutCheck removeRolloutCheck() {
        return RemoveRolloutCheck.DISABLE;
    }

    default boolean ignoreSuppressionAnnotations() {
        return false;
    }

    default CommonOptions naivelyCombinedWith(CommonOptions other) {
        return new CommonOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return CommonOptions.this.patchChecks().combine(other.patchChecks());
            }

            @Override
            public Map<String, String> extraErrorProneCheckOptions() {
                return EntryStream.of(CommonOptions.this.extraErrorProneCheckOptions())
                        .append(other.extraErrorProneCheckOptions())
                        .toMap();
            }

            @Override
            public RemoveRolloutCheck removeRolloutCheck() {
                return CommonOptions.this.removeRolloutCheck().or(other.removeRolloutCheck());
            }

            @Override
            public boolean ignoreSuppressionAnnotations() {
                return CommonOptions.this.ignoreSuppressionAnnotations() || other.ignoreSuppressionAnnotations();
            }
        };
    }

    static CommonOptions naivelyCombine(Collection<CommonOptions> commonOptions) {
        return commonOptions.stream().reduce(CommonOptions.empty(), CommonOptions::naivelyCombinedWith);
    }

    default CommonOptions withExtraErrorProneCheckFlag(String key, Supplier<String> value) {
        return new CommonOptions() {
            @Override
            public Map<String, String> extraErrorProneCheckOptions() {
                Map<String, String> map = new HashMap<>(CommonOptions.this.extraErrorProneCheckOptions());
                map.put(key, value.get());
                return Collections.unmodifiableMap(map);
            }

            @Override
            public PatchChecksOption patchChecks() {
                return CommonOptions.this.patchChecks();
            }

            @Override
            public RemoveRolloutCheck removeRolloutCheck() {
                return CommonOptions.this.removeRolloutCheck();
            }

            @Override
            public boolean ignoreSuppressionAnnotations() {
                return CommonOptions.this.ignoreSuppressionAnnotations();
            }
        };
    }

    /**
     * These options are the default empty values and have no effect when combined with other options.
     */
    static CommonOptions empty() {
        return Empty.INSTANCE;
    }

    enum Empty implements CommonOptions {
        INSTANCE
    }
}
