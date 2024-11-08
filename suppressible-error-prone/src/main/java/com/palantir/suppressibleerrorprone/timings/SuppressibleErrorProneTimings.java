/*
 * (c) Copyright 2024 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.suppressibleerrorprone.timings;

import com.google.common.base.Stopwatch;
import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.Suppressible;
import com.sun.tools.javac.util.Context;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class SuppressibleErrorProneTimings {
    private static final Context.Key<SuppressibleErrorProneTimings> timingsKey = new Context.Key<>();

    public static SuppressibleErrorProneTimings instance(Context context) {
        SuppressibleErrorProneTimings instance = context.get(timingsKey);
        if (instance == null) {
            instance = new SuppressibleErrorProneTimings(context);
        }
        return instance;
    }

    public static void initOnVisitorStateSharedState(Object visitorStateSharedStateInstance, Context context) {
        try {
            // Preferring to use reflection to reduce bytecode editing (even though bytecode editing may result
            // in a "cleaner" result) as if the field name changes, the error should be much clearer here than if
            // the bytecode version goes wrong.
            Field timingsField = Class.forName(VisitorState.class.getCanonicalName() + "$SharedState")
                    .getDeclaredField("suppressibleErrorProneTimings");
            timingsField.setAccessible(true);
            timingsField.set(visitorStateSharedStateInstance, instance(context));
        } catch (NoSuchFieldException | IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(
                    "supressible-error-prone failed to modify the suppressibleErrorProneTimings "
                            + "field of a VisitorState$SharedState",
                    e);
        }
    }

    private SuppressibleErrorProneTimings(Context context) {
        context.put(timingsKey, this);
    }

    private final ConcurrentMap<URI, ConcurrentMap<String, Stopwatch>> timers = new ConcurrentHashMap<>();

    public AutoCloseable span(VisitorState visitorState, Suppressible suppressible) {
        URI uri = visitorState.getPath().getCompilationUnit().getSourceFile().toUri();

        ConcurrentMap<String, Stopwatch> fileTimers =
                timers.computeIfAbsent(uri, _ignored -> new ConcurrentHashMap<>());
        Stopwatch sw = fileTimers
                .computeIfAbsent(suppressible.canonicalName(), _ignored -> Stopwatch.createUnstarted())
                .start();
        return sw::stop;
    }

    public Map<URI, Map<String, Duration>> timings() {
        return timers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey, v -> v.getValue().elapsed()))));
    }
}
