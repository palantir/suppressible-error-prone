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

package com.palantir.suppressibleerrorprone;

import java.util.Optional;
import java.util.function.Function;
import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
final class FirstTimeMemoizingFunction<T, R> implements Function<T, R> {
    private final Function<T, R> function;
    private Optional<R> cachedValue = Optional.empty();

    FirstTimeMemoizingFunction(Function<T, R> function) {
        this.function = function;
    }

    @Override
    public R apply(T input) {
        return cachedValue.orElseGet(() -> {
            R result = function.apply(input);
            cachedValue = Optional.of(result);
            return result;
        });
    }
}
