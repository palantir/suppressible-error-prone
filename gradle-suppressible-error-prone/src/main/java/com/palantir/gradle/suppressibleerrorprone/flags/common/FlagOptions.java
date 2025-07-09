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

import com.palantir.gradle.suppressibleerrorprone.flags.common.Flag.PatchSomeChecksOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public interface FlagOptions {
    default boolean modifyErrorProneCheckApi() {
        return false;
    }

    default PatchChecksOption patchChecks() {
        return new PatchSomeChecksOption(Set.of());
    }

    default Map<String, String> extraFlags() {
        return Map.of();
    }

    default FlagOptions naivelyCombinedWith(FlagOptions other) {
        return new FlagOptions() {
            @Override
            public boolean modifyErrorProneCheckApi() {
                return FlagOptions.this.modifyErrorProneCheckApi() || other.modifyErrorProneCheckApi();
            }

            @Override
            public PatchChecksOption patchChecks() {
                throw new UnsupportedOperationException("not implemented");
            }

            @Override
            public Map<String, String> extraFlags() {
                throw new UnsupportedOperationException("not implemented");
            }
        };
    }

    default FlagOptions withExtraFlag(String key, String value) {
        return new DefaultFlagOptions(this) {
            @Override
            public Map<String, String> extraFlags() {
                Map<String, String> map = new HashMap<>(super.extraFlags());
                map.put(key, value);
                return map;
            }
        };
    }

    final class Empty implements FlagOptions {
        public static final Empty INSTANCE = new Empty();

        private Empty() {}
    }

    @SuppressWarnings("checkstyle:DesignForExtension")
    class DefaultFlagOptions implements FlagOptions {
        private final FlagOptions originalOptions;

        protected DefaultFlagOptions(FlagOptions originalOptions) {
            this.originalOptions = originalOptions;
        }

        @Override
        public boolean modifyErrorProneCheckApi() {
            return originalOptions.modifyErrorProneCheckApi();
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
