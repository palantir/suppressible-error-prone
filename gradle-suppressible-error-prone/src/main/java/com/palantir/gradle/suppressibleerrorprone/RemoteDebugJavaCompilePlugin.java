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

package com.palantir.gradle.suppressibleerrorprone;

import java.util.List;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.configuration.BuildFeatures;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * To debug suppressible-error-prone in a build (works with breakpoints in VisitorStateModifications):
 * 1. Run the "Debug errorprones" debug config which ships with this repository. Notably, it sets up a jdwp listener
 *    on port 5006
 * 2. Run the build-under-test with this plugin applied
 */
public abstract class RemoteDebugJavaCompilePlugin implements Plugin<Project> {
    @Inject
    protected abstract BuildFeatures getBuildFeatures();

    @Override
    public final void apply(Project project) {
        if (getBuildFeatures().getConfigurationCache().getActive().get()) {
            throw new IllegalArgumentException(
                    "The JDWP will throw a cryptic error when run with the configuration cache. Turn off configuration"
                            + " cache for the build-under-debug. Hint: you can conditionally apply "
                            + "`com.palantir.remote-debug-java-compile` only if "
                            + "`IntegrationTestKitBase#isJdwpLoaded()`");
        }

        project.getPluginManager().withPlugin("java", unused -> {
            project.getTasks().withType(JavaCompile.class).configureEach(javaCompile -> {
                javaCompile
                        .getOptions()
                        .getForkOptions()
                        .getJvmArgumentProviders()
                        .add(new CommandLineArgumentProvider() {
                            @Override
                            public Iterable<String> asArguments() {
                                return List.of("-agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5006");
                            }
                        });
            });
        });
    }
}
