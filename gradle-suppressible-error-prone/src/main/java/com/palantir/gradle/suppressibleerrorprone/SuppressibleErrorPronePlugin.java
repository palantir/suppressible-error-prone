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

import com.palantir.gradle.suppressibleerrorprone.modes.Modes;
import com.palantir.gradle.suppressibleerrorprone.modes.common.CommonOptions;
import com.palantir.gradle.suppressibleerrorprone.modes.common.ModifyCheckApiOption;
import com.palantir.gradle.suppressibleerrorprone.transform.ModifyErrorProneCheckApi;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.ltgt.gradle.errorprone.ErrorProneOptions;
import net.ltgt.gradle.errorprone.ErrorPronePlugin;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.process.CommandLineArgumentProvider;

public abstract class SuppressibleErrorPronePlugin implements Plugin<Project> {
    @Inject
    protected abstract ProviderFactory getProviderFactory();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Nested
    protected abstract Modes getModes();

    @Override
    public final void apply(Project project) {
        project.getPluginManager().withPlugin("java", unused -> {
            applyToJavaProject(project);
        });
    }

    private void applyToJavaProject(Project project) {
        project.getPluginManager().apply(ErrorPronePlugin.class);

        project.getExtensions().create("suppressibleErrorProne", SuppressibleErrorProneExtension.class);

        String version = Optional.ofNullable((String) project.findProperty("suppressibleErrorProneVersion"))
                .or(() -> Optional.ofNullable(
                        SuppressibleErrorPronePlugin.class.getPackage().getImplementationVersion()))
                .orElseThrow(
                        () -> new RuntimeException("SuppressibleErrorPronePlugin implementation version not found"));

        if (getModes().modifyCheckApi() instanceof ModifyCheckApiOption.MustModify mustModify) {
            // When auto-suppressing, the logic will run a bytecode patched version of errorprone
            // (via an artifact transform) that intercepts every error from every check and adds a custom fix
            setupErrorProneArtifactTransform(project, mustModify.modifyVisitorState());
        }

        project.getConfigurations().named(ErrorPronePlugin.CONFIGURATION_NAME).configure(errorProneConfiguration -> {
            // Required so that we can run the runtime parts of the errorprone patching in suppressing stage 1 and
            // also the SuppressWarningsCoalesce errorprone in suppressing stage 2.
            errorProneConfiguration
                    .getDependencies()
                    .add(project.getDependencies()
                            .create("com.palantir.suppressible-error-prone:suppressible-error-prone:" + version));
        });

        // Some JavaCompile configuration needs to happen in an afterEvaluate block - however, you can't call
        // afterEvaluate inside a getTasks().configureEach(), so we have to configure all the tasks in afterEvaluate
        project.afterEvaluate(_ignored -> {
            project.getTasks().withType(JavaCompile.class).configureEach(javaCompile -> {
                CommonOptions commonOptions = getModes().commonOptionsFor(javaCompile);
                configureJavaCompile(commonOptions, javaCompile);

                configureErrorProneOptions(javaCompile, errorProneOptions -> {
                    setupErrorProneOptions(commonOptions, errorProneOptions);
                });
            });
        });

        project.getTasks().register("compileAllErrorProne", Task.class, compileAll -> {
            compileAll.dependsOn(project.provider(
                    () -> project.getTasks().withType(JavaCompile.class).matching(javaCompile -> {
                        return errorProneOptionsFor(javaCompile).getEnabled().get();
                    })));
        });
    }

    private static void setupErrorProneArtifactTransform(Project project, boolean modifyVisitorState) {
        Attribute<Boolean> suppressible =
                Attribute.of("com.palantir.suppressible-error-prone.suppressible", Boolean.class);
        project.getDependencies().getAttributesSchema().attribute(suppressible);

        project.getDependencies()
                .getArtifactTypes()
                .getByName("jar")
                .getAttributes()
                .attribute(suppressible, false);

        project.getDependencies().registerTransform(ModifyErrorProneCheckApi.class, spec -> {
            spec.getParameters().getModifyVisitorState().set(modifyVisitorState);

            Attribute<String> artifactType = Attribute.of("artifactType", String.class);
            spec.getFrom().attribute(suppressible, false).attribute(artifactType, "jar");
            spec.getTo().attribute(suppressible, true).attribute(artifactType, "jar");
        });

        // We need to configure the transform in each source set as they each have their own compile task
        project.getExtensions().getByType(SourceSetContainer.class).configureEach(sourceSet -> {
            // It's the annotationProcessor configuration, not the errorprone that, is actually used by the compiler
            // and so where we must put our transform. annotationProcessor extendsFrom errorprone.
            project.getConfigurations()
                    .named(sourceSet.getAnnotationProcessorConfigurationName())
                    .configure(annotationProcessor -> {
                        annotationProcessor
                                .getDependencies()
                                .add(project.getDependencies().create("com.google.errorprone:error_prone_check_api"));
                        annotationProcessor.getAttributes().attribute(suppressible, true);
                    });

            project.getDependencies().getComponents().all(ConsistentErrorPronePlatformRule.class);
        });
    }

    /**
     * Stolen wholesale from GCV:
     *      https://github.com/palantir/gradle-consistent-versions/blob/8318ac29e81b6a77ed9ec223b2024cb7a61c7175/
     *      src/main/java/com/palantir/gradle/versions/VersionsPropsPlugin.java#L294-L305
     * This sets up a "virtual platform" that all errorprone dependencies are bound to. It means they will all have
     * the same version. It's very similar to adding `com.google.errorprone:* = ...` to the `versions.props` file
     * (in fact it's the same thing), except we are doing this from a gradle plugin.
     */
    static final class ConsistentErrorPronePlatformRule implements ComponentMetadataRule {
        private static final String ERRORPRONE_GROUP = "com.google.errorprone";

        @Override
        public void execute(ComponentMetadataContext context) {
            ModuleVersionIdentifier id = context.getDetails().getId();
            if (!id.getGroup().equals(ERRORPRONE_GROUP)) {
                return;
            }

            context.getDetails().belongsTo("%s:_:%s".formatted(ERRORPRONE_GROUP, id.getVersion()));
        }
    }

    private static void configureJavaCompile(CommonOptions commonOptions, JavaCompile javaCompile) {
        // Don't attempt to cache or be up-to-date since it won't capture the source files that might be modified
        javaCompile.getOutputs().cacheIf(t -> !commonOptions.patchChecks().isPatching());
        javaCompile.getOutputs().upToDateWhen(t -> !commonOptions.patchChecks().isPatching());

        if (commonOptions.patchChecks().isPatching()) {
            // To allow refactoring near usages of deprecated methods, even when -Xlint:deprecation is specified,
            // we need to remove these compiler flags after all configuration has happened.
            javaCompile.getOptions().setWarnings(false);
            javaCompile.getOptions().setDeprecation(false);
            // This needs to be done in afterEvaluate because we're reading the existing values, which may
            // not be fully set when the plugin is applied.
            javaCompile
                    .getOptions()
                    .setCompilerArgs(javaCompile.getOptions().getCompilerArgs().stream()
                            .filter(arg -> !arg.equals("-Werror"))
                            .filter(arg -> !arg.equals("-deprecation"))
                            .filter(arg -> !arg.equals("-Xlint:deprecation"))
                            .collect(Collectors.toList()));
        }
    }

    private void setupErrorProneOptions(CommonOptions commonOptions, ErrorProneOptions errorProneOptions) {
        // This doesn't seem to do what you'd expect: disabling the checks in the generated code. But it was enabled
        // when this code lived in baseline, so we'll keep it enabled.
        errorProneOptions.getDisableWarningsInGeneratedCode().set(true);

        errorProneOptions.getExcludedPaths().set(excludedPathsRegex());

        PatchChecksCommandLineArgumentProvider patchChecksCommandLineArgumentProvider =
                getObjectFactory().newInstance(PatchChecksCommandLineArgumentProvider.class);
        patchChecksCommandLineArgumentProvider
                .getPatchChecksArgument()
                .set(getProviderFactory()
                        .provider(() ->
                                commonOptions.patchChecks().asCommaSeparated().orElse(null)));

        errorProneOptions.getErrorproneArgumentProviders().add(patchChecksCommandLineArgumentProvider);

        errorProneOptions
                .getCheckOptions()
                .putAll(getProviderFactory().provider(commonOptions::extraErrorProneCheckOptions));

        errorProneOptions
                .getChecks()
                .put("RemoveUnusedSuppressions", getProviderFactory().provider(() -> commonOptions
                        .removeUnusedCheck()
                        .toCheckSeverity()));

        // We disable this to avoid having `Note: [RemoveRolloutSuppressions]` in
        // unrelated error messages as it's a suggestion level check. If the remove rollout mode is enabled,
        // this check will be explicitly patched, which will enable it by default.
        errorProneOptions
                .getChecks()
                .put("RemoveRolloutSuppressions", getProviderFactory().provider(() -> commonOptions
                        .removeRolloutCheck()
                        .toCheckSeverity()));
    }

    private static ErrorProneOptions errorProneOptionsFor(JavaCompile javaCompile) {
        return ((ExtensionAware) javaCompile.getOptions()).getExtensions().getByType(ErrorProneOptions.class);
    }

    static void configureErrorProneOptions(JavaCompile javaCompile, Action<ErrorProneOptions> action) {
        ((ExtensionAware) javaCompile.getOptions()).getExtensions().configure(ErrorProneOptions.class, action);
    }

    static String excludedPathsRegex() {
        // Error-prone normalizes filenames to use '/' path separator:
        // https://github.com/google/error-prone/blob/c601758e81723a8efc4671726b8363be7a306dce
        // /check_api/src/main/java/com/google/errorprone/util/ASTHelpers.java#L1277-L1285

        // language=RegExp
        return ".*/(build|generated_.*[sS]rc|src/generated.*)/.*";
    }

    public abstract static class PatchChecksCommandLineArgumentProvider implements CommandLineArgumentProvider {
        @Input
        @org.gradle.api.tasks.Optional
        protected abstract Property<String> getPatchChecksArgument();

        @Override
        public final Iterable<String> asArguments() {
            return Optional.ofNullable(getPatchChecksArgument().getOrNull())
                    .map(commaSeparated -> List.of("-XepPatchLocation:IN_PLACE", "-XepPatchChecks:" + commaSeparated))
                    .orElseGet(List::of);
        }
    }
}
