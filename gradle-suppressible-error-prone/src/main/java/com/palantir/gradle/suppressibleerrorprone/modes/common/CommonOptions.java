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

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import one.util.streamex.EntryStream;

/**
 * Represents options for configuration that multiple mode can influence, rather than something a single mode can
 * configure. This allows all the options from multiple modes to be combined, interfered between and
 * configured in a single place.
 */
public interface CommonOptions extends Serializable {
    default PatchChecksOption patchChecks() {
        return PatchChecksOption.noChecks();
    }

    default Map<String, String> extraErrorProneCheckOptions() {
        return Map.of();
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
        };
    }

    static CommonOptions naivelyCombine(Collection<CommonOptions> commonOptions) {
        return commonOptions.stream().reduce(CommonOptions.empty(), CommonOptions::naivelyCombinedWith);
    }

    default CommonOptions withExtraErrorProneCheckFlag(String key, String value) {
        Map<String, String> map = new HashMap<>(extraErrorProneCheckOptions());
        map.put(key, value);
        return new DefaultCommonOptions(patchChecks(), map);
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

    @SuppressWarnings("checkstyle:DesignForExtension")
    record DefaultCommonOptions(PatchChecksOption patchChecks, Map<String, String> extraErrorProneCheckOptions)
            implements CommonOptions {}
}
