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
import java.util.HashMap;
import java.util.Map;
import one.util.streamex.EntryStream;

/**
 * Represents options for configuration that multiple mode can influence, rather than something a single mode can
 * configure. This allows all the options from multiple modes to be combined, interfered between and
 * configured in a single place.
 */
public interface ModeOptions {
    default PatchChecksOption patchChecks() {
        return PatchChecksOption.noChecks();
    }

    default Map<String, String> extraErrorProneCheckFlags() {
        return Map.of();
    }

    default ModeOptions naivelyCombinedWith(ModeOptions other) {
        return new ModeOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return ModeOptions.this.patchChecks().combine(other.patchChecks());
            }

            @Override
            public Map<String, String> extraErrorProneCheckFlags() {
                return EntryStream.of(ModeOptions.this.extraErrorProneCheckFlags())
                        .append(other.extraErrorProneCheckFlags())
                        .toMap();
            }
        };
    }

    static ModeOptions naivelyCombine(Collection<ModeOptions> modeOptions) {
        return modeOptions.stream().reduce(ModeOptions.dontCare(), ModeOptions::naivelyCombinedWith);
    }

    default ModeOptions withExtraErrorProneCheckFlag(String key, String value) {
        return new DefaultModeOptions(this) {
            @Override
            public Map<String, String> extraErrorProneCheckFlags() {
                Map<String, String> map = new HashMap<>(super.extraErrorProneCheckFlags());
                map.put(key, value);
                return map;
            }
        };
    }

    static ModeOptions dontCare() {
        return DontCare.INSTANCE;
    }

    final class DontCare implements ModeOptions {
        public static final DontCare INSTANCE = new DontCare();

        private DontCare() {}
    }

    @SuppressWarnings("checkstyle:DesignForExtension")
    class DefaultModeOptions implements ModeOptions {
        private final ModeOptions originalOptions;

        protected DefaultModeOptions(ModeOptions originalOptions) {
            this.originalOptions = originalOptions;
        }

        @Override
        public PatchChecksOption patchChecks() {
            return originalOptions.patchChecks();
        }

        @Override
        public Map<String, String> extraErrorProneCheckFlags() {
            return originalOptions.extraErrorProneCheckFlags();
        }
    }
}
