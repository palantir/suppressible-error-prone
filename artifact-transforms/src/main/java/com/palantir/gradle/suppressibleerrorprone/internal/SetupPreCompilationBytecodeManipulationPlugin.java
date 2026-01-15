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

package com.palantir.gradle.suppressibleerrorprone.internal;

import com.palantir.suppressibleerrorprone.transform.ModifiedFile;
import com.palantir.suppressibleerrorprone.transform.ModifyErrorProneCheckApi;
import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;

public final class SetupPreCompilationBytecodeManipulationPlugin implements Plugin<Project> {
    private static final Attribute<Boolean> ARTIFACT_TRANSFORM_APPLIED =
            Attribute.of("com.palantir.suppressible-error-prone.transform-applied", Boolean.class);

    @Override
    public void apply(Project project) {
        project.getDependencies()
                .getArtifactTypes()
                .getByName("jar")
                .getAttributes()
                .attribute(ARTIFACT_TRANSFORM_APPLIED, false);

        project.getDependencies().registerTransform(ModifyErrorProneCheckApi.class, transform -> {
            transform
                    .getFrom()
                    .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
                    .attribute(ARTIFACT_TRANSFORM_APPLIED, false);
            transform
                    .getTo()
                    .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
                    .attribute(ARTIFACT_TRANSFORM_APPLIED, true);
            transform.parameters(params -> {
                params.getFilesToModify().set(Set.of(ModifiedFile.REPLACEMENT));
            });
        });

        project.getConfigurations().configureEach(configuration -> {
            String name = configuration.getName();
            if (name.equals("compileClasspath")
                    || name.equals("testCompileClasspath")
                    || name.equals("testRuntimeClasspath")) {
                configuration.getAttributes().attribute(ARTIFACT_TRANSFORM_APPLIED, true);
            }
        });
    }
}
