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
 * Represents options for configuration that multiple flags can produce, rather than something a single flag can
 * configure. This allows all the options to be combined, interfered between and configured in a single place.
 */
public interface ModeOptions {
    default PatchChecksOption patchChecks() {
        return PatchChecksOption.noChecks();
    }

    default Map<String, String> extraFlags() {
        return Map.of();
    }

    default ModeOptions naivelyCombinedWith(ModeOptions other) {
        return new ModeOptions() {
            @Override
            public PatchChecksOption patchChecks() {
                return ModeOptions.this.patchChecks().combine(other.patchChecks());
            }

            @Override
            public Map<String, String> extraFlags() {
                return EntryStream.of(ModeOptions.this.extraFlags())
                        .append(other.extraFlags())
                        .toMap();
            }
        };
    }

    static ModeOptions naivelyCombine(Collection<ModeOptions> modeOptions) {
        return modeOptions.stream().reduce(ModeOptions.none(), ModeOptions::naivelyCombinedWith);
    }

    default ModeOptions withExtraFlag(String key, String value) {
        return new DefaultModeOptions(this) {
            @Override
            public Map<String, String> extraFlags() {
                Map<String, String> map = new HashMap<>(super.extraFlags());
                map.put(key, value);
                return map;
            }
        };
    }

    static ModeOptions none() {
        return None.INSTANCE;
    }

    final class None implements ModeOptions {
        public static final None INSTANCE = new None();

        private None() {}
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
        public Map<String, String> extraFlags() {
            return originalOptions.extraFlags();
        }
    }
}
