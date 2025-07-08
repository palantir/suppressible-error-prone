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

import com.palantir.gradle.suppressibleerrorprone.flags.Flag.FlagOptions.Empty;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface Flag {
    FlagName name();

    FlagOptions options(Optional<String> flagValue);

    interface PatchChecksOption {
        String asCommaSeparated();
    }

    enum PatchAllChecksOption implements PatchChecksOption {
        PATCH_ALL_CHECKS;

        @Override
        public String asCommaSeparated() {
            return "";
        }
    }

    record PatchSomeChecksOption(Set<String> patchChecks) implements PatchChecksOption {
        @Override
        public String asCommaSeparated() {
            return String.join(",", patchChecks);
        }
    }

    interface FlagOptions {
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
            public boolean timingsEnabled() {
                return originalOptions.timingsEnabled();
            }

            @Override
            public boolean errorProneDisabled() {
                return originalOptions.errorProneDisabled();
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

        default boolean modifyErrorProneCheckApi() {
            return false;
        }

        default boolean timingsEnabled() {
            return false;
        }

        default boolean errorProneDisabled() {
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
                public boolean timingsEnabled() {
                    return FlagOptions.super.timingsEnabled() || other.timingsEnabled();
                }

                @Override
                public boolean errorProneDisabled() {
                    return FlagOptions.super.errorProneDisabled() || other.errorProneDisabled();
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
    }

    interface FlagInterference {
        Set<FlagName> interferesWith(Set<FlagName> flags);

        FlagOptions interfere(Map<FlagName, FlagOptions> flagOptions);
    }

    abstract class FlagSetInteference implements FlagInterference {
        abstract Set<FlagName> interferingFlags();

        @Override
        public final Set<FlagName> interferesWith(Set<FlagName> flags) {
            return interferingFlags().containsAll(flags) ? interferingFlags() : Set.of();
        }
    }

    enum FlagName {
        APPLY,
        SUPPRESS,
        REMOVE_ROLLOUT,
        TIMINGS,
        DISABLE
    }

    final class ApplyFlag implements Flag {
        @Override
        public FlagName name() {
            return FlagName.APPLY;
        }

        @Override
        public FlagOptions options(Optional<String> flagValue) {
            return new FlagOptions() {
                @Override
                public PatchChecksOption patchChecks() {
                    return PatchAllChecksOption.PATCH_ALL_CHECKS;
                }
            };
        }
    }

    final class SuppressFlag implements Flag {
        @Override
        public FlagName name() {
            return FlagName.SUPPRESS;
        }

        @Override
        public FlagOptions options(Optional<String> flagValue) {
            return new FlagOptions() {
                @Override
                public boolean modifyErrorProneCheckApi() {
                    return true;
                }
            };
        }
    }

    final class RemoveFlag implements Flag {

        @Override
        public FlagName name() {
            return FlagName.REMOVE_ROLLOUT;
        }

        @Override
        public FlagOptions options(Optional<String> flagValue) {
            return null;
        }
    }

    final class TimingsFlag implements Flag {
        @Override
        public FlagName name() {
            return FlagName.TIMINGS;
        }

        @Override
        public FlagOptions options(Optional<String> flagValue) {
            return Empty.INSTANCE;
        }
    }

    final class DisableFlag implements Flag {
        @Override
        public FlagName name() {
            return FlagName.DISABLE;
        }

        @Override
        public FlagOptions options(Optional<String> flagValue) {
            return null;
        }
    }

    final class DisableFlagInterference implements FlagInterference {
        @Override
        public Set<FlagName> interferesWith(Set<FlagName> flags) {
            if (flags.contains(FlagName.DISABLE)) {
                return flags;
            }
        }

        @Override
        public FlagOptions interfere(Map<FlagName, FlagOptions> flagOptions) {
            return null;
        }
    }

    final class RemoveSuppressionsAndApplyInterference extends FlagSetInteference {
        @Override
        Set<FlagName> interferingFlags() {
            return Set.of(FlagName.REMOVE_ROLLOUT, FlagName.APPLY);
        }

        @Override
        public FlagOptions interfere(Map<FlagName, FlagOptions> flagOptions) {
            FlagOptions remove = flagOptions.get(FlagName.REMOVE_ROLLOUT);
            FlagOptions apply = flagOptions.get(FlagName.APPLY);

            return remove.naivelyCombinedWith(apply);
        }
    }

    final class SuppressingAndApplyingInterference extends FlagSetInteference {
        @Override
        public Set<FlagName> interferingFlags() {
            return Set.of(FlagName.SUPPRESS, FlagName.APPLY);
        }

        @Override
        public FlagOptions interfere(Map<FlagName, FlagOptions> flagOptions) {
            FlagOptions suppress = flagOptions.get(FlagName.SUPPRESS);
            FlagOptions apply = flagOptions.get(FlagName.APPLY);
            return suppress.naivelyCombinedWith(apply)
                    .withExtraFlag("PreferPatchChecks", apply.patchChecks().asCommaSeparated());
        }
    }
}
