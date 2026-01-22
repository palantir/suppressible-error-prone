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

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.junit.DisabledConfigurationCache;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.javaformat.java.Formatter;
import com.palantir.javaformat.java.FormatterException;
import com.palantir.javaformat.java.JavaFormatterOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@GradlePluginTests
@DisabledConfigurationCache("Tests manipulate files directly which is not compatible with configuration cache")
final class SuppressibleErrorPronePluginIntegrationTest {
    private static final Formatter FORMATTER = Formatter.createFormatter(JavaFormatterOptions.builder()
            .style(JavaFormatterOptions.Style.PALANTIR)
            .build());

    // This makes debugging the errorprone check code running inside the compiler (including the bytecode
    // edited modifications we have made) "just work" from inside these tests.
    // Change the variable below to true to enable it, after setting up the standalone debugger:
    //   1. Make a new run configuration in IntelliJ of type JVM Debug
    //   2. Change it to "Listen" rather than "Attach"
    //   3. Select Auto-restart.
    //   4. Run the debugger
    //   5. Run the tests as well
    // If the variable below is true the tests will fail as the compilation process will try to
    // attach to a non-existent debugger. Set it to false before you push any code.
    private static final boolean DEBUGGING_ERROR_PRONES = false;

    private String projectVersion;

    @BeforeEach
    @SuppressWarnings("GradleTestPluginsBlock") // buildscript and apply plugin need special handling
    void setup(RootProject rootProject) {
        projectVersion = Optional.ofNullable(System.getProperty("projectVersion"))
                .orElseThrow(() -> new IllegalStateException("projectVersion system property must be set"));

        // Note: We use append() with buildscript block and apply plugin because these need special handling
        // that can't use the plugins() API
        rootProject.buildGradle().append("""
            buildscript {
                repositories {
                    mavenCentral()
                }
                dependencies {
                    classpath 'com.palantir.gradle.consistentversions:gradle-consistent-versions:3.1.0'
                }
            }
            // Consistent versions checks we don't resolve configurations at configuration time and
            // also interacts in many ways with dependencies
            apply plugin: 'com.palantir.consistent-versions'
            """);

        rootProject.buildGradle().plugins().add("com.palantir.suppressible-error-prone");
        rootProject.buildGradle().plugins().add("java");

        rootProject.buildGradle().append("""
            repositories {
                mavenCentral()
                // Needed so that suppressible-error-prone and suppressible-error-prone-annotations can be added
                // as jars to the various configurations. We make sure to publish these to maven local before the
                // test task runs.
                mavenLocal()
            }

            sourceSets {
                other
            }

            dependencies {
                errorprone 'com.google.errorprone:error_prone_core:2.31.0'
                // Mimick the way SuppressibleErrorPronePlugin adds the dependency on suppressible-error-prone
                // This should guarantee that we're using the same version, both of which should be in maven local
                //   and be the current version
                errorprone 'com.palantir.suppressible-error-prone:test-error-prone-checks:' + project.findProperty("suppressibleErrorProneVersion")
            }

            suppressibleErrorProne {
                configureEachErrorProneOptions {
                    // These interfere with some tests, so disable them
                    // TODO(callumr): Rewrite the tests to use custom testing error-prones rather than built in checks
                    //                to make upgrading error-prone easier.
                    disable('Varifier', 'ReturnValueIgnored', 'UnusedVariable', 'IdentifierName', 'UnusedMethod')
                    ignoreUnknownCheckNames = true
                }
            }
            """);

        if (DEBUGGING_ERROR_PRONES) {
            rootProject.buildGradle().append("""
                tasks.withType(JavaCompile).configureEach {
                    it.options.forkOptions.jvmArgumentProviders.add(new CommandLineArgumentProvider() {
                        @Override
                        public Iterable<String> asArguments() {
                            return List.of("-agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5005")
                        }
                    })
                }
                """);
        }

        rootProject
                .gradlePropertiesFile()
                .appendProperty("__TESTING", "true")
                .appendProperty("__TESTING_CACHE_BUST_ERRORPRONE_TRANSFORM", "true");

        rootProject.file("versions.lock").createEmpty();
    }

    @Test
    void reports_a_failing_error_prone(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        InvocationResult result = runTasksWithFailure(gradle, "compileAllErrorProne");

        assertThat(result.output()).contains("[ArrayToString]");
    }

    @Test
    void can_suppress_an_error_prone_with_for_rollout_prefix(GradleInvoker gradle, RootProject rootProject) {
        // This test is explicitly checking we suppress the for-rollout prefix as that is what exists
        // in people's codebases

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void ensure_error_prone_checks_are_disabled_in_generated_code(GradleInvoker gradle, RootProject rootProject) {
        String erroringCode = """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """;

        Path sourceDir1 = rootProject.path().resolve("src/generated");
        Path sourceDir2 = rootProject.path().resolve("build/generated");

        writeJavaSourceFile(rootProject, erroringCode, sourceDir1);
        writeJavaSourceFile(rootProject, erroringCode.replace("App", "App2"), sourceDir2);

        rootProject
                .buildGradle()
                .append(
                        "sourceSets.main.java.srcDirs('%s', '%s')",
                        rootProject.path().relativize(sourceDir1),
                        rootProject.path().relativize(sourceDir2));

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void can_apply_patches_for_a_check_if_added_to_the_patchChecks_list(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply");
        runTasksSuccessfully(gradle, "compileAllErrorProne");

        javaSourceContains(rootProject, "Arrays.toString(new int[3])");
    }

    @Test
    void does_not_apply_patches_for_a_check_if_not_added_to_the_patchChecks_list(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                // To make sure set is not empty
                patchChecks = ['SomeCheck']
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply");

        javaSourceContains(rootProject, "new int[3].toString()");
    }

    @Test
    void does_not_apply_patches_if_there_is_nothing_in_patchChecks_set(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                patchChecks.empty()
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        // Doesn't actually do any patching as the set is empty. It just does a normal compile that fails.
        InvocationResult result = runTasksWithFailure(gradle, "compileAllErrorProne", "-PerrorProneApply");

        assertThat(result.output()).contains("[ArrayToString]");
        javaSourceContains(rootProject, "new int[3].toString()");
    }

    @Test
    void does_not_apply_patches_for_check_that_was_explicitly_disabled(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }

            tasks.withType(JavaCompile).configureEach {
                options.errorprone.disable 'ArrayToString'
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply");

        javaSourceContains(rootProject, "new int[3].toString()");
    }

    @Test
    void can_patch_specific_checks_using_errorProneApply(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[2].equals(new int[1]);
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply=ArrayToString,ArrayEquals");

        javaSourceContains(rootProject, "Arrays.toString(new int[3])");
        javaSourceContains(rootProject, "Arrays.equals(new int[2], new int[1])");
    }

    @Test
    void can_suppress_a_failing_check_even_if_not_in_patchChecks_set(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        javaSourceContains(rootProject, "@SuppressWarnings(\"for-rollout:ArrayToString\")");

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void does_not_apply_suppress_warnings_to_implicit_lambda_parameters(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            import java.util.stream.Stream;

            public class App {
                void test() {
                    Stream.of(new Object()).forEach(o -> o.toString());
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        // Suppression should be on the method, not the lambda parameter
        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.stream.Stream;

            public class App {
                @SuppressWarnings("for-rollout:TestCheckNoSingleLetterVariable")
                void test() {
                    Stream.of(new Object()).forEach(o -> o.toString());
                }
            }
            """);

        // Verify the code still compiles after the suppression has been applied, as previous versions
        //   were adding the annotation to the lambda implicit parameter which is not valid java
        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void does_not_apply_suppress_warnings_to_explicit_lambda_parameters(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            import java.util.stream.Stream;

            public class App {
                void test() {
                    Stream.of(new Object()).forEach((Object o) -> o.toString());
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        // Suppression should be on the method, not the lambda parameter
        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.stream.Stream;

            public class App {
                @SuppressWarnings("for-rollout:TestCheckNoSingleLetterVariable")
                void test() {
                    Stream.of(new Object()).forEach((Object o) -> o.toString());
                }
            }
            """);

        // Verify the code still compiles after the suppression has been applied, as previous versions
        //   were adding the annotation to the lambda implicit parameter which is not valid java
        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void does_not_apply_suppress_warnings_to_anonymous_classes(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            import java.util.stream.Stream;

            public class App {
                void test() {
                    new Object() {
                        {
                            Stream.of(new Object()).forEach(o -> o.toString());
                        }
                    };
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        // Suppression should be on the method, not the anonymous class
        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.stream.Stream;

            public class App {
                @SuppressWarnings("for-rollout:TestCheckNoSingleLetterVariable")
                void test() {
                    new Object() {
                        {
                            Stream.of(new Object()).forEach(o -> o.toString());
                        }
                    };
                }
            }
            """);

        // Verify the code still compiles after the suppression has been applied, as previous versions
        //   were adding the annotation to the anonymous class which is not valid java
        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void demonstrate_suppressions_on_different_source_elements(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public final String field = new int[3].toString();

                public App() {
                    new int[3].toString();
                }

                public void method() {
                    new int[3].toString();
                }

                public void variables() {
                    String variable = new int[3].toString();
                }

                public static class SomeClass {
                    static {
                        new int[3].toString();
                    }
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public final String field = new int[3].toString();

                @SuppressWarnings("for-rollout:ArrayToString")
                public App() {
                    new int[3].toString();
                }

                @SuppressWarnings("for-rollout:ArrayToString")
                public void method() {
                    new int[3].toString();
                }

                public void variables() {
                    @SuppressWarnings("for-rollout:ArrayToString")
                    String variable = new int[3].toString();
                }

                @SuppressWarnings("for-rollout:ArrayToString")
                public static class SomeClass {
                    static {
                        new int[3].toString();
                    }
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void supports_errorprone_checks_that_match_on_a_larger_element_than_they_report_errors_on(
            GradleInvoker gradle, RootProject rootProject) {
        // The UnusedVariable check implements CompilationUnitTreeMatcher, so will start with a whole
        // CompilationUnitTree and then narrows down to the specific variable declaration that is unused.
        // This trips up the "naive" suppression logic, which looks at where the visitor has got to rather
        // than where the diagnostic description was produced.

        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                configureEachErrorProneOptions {
                    enable('UnusedVariable')
                }
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public void variables() {
                    String variable;
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                public void variables() {
                    @SuppressWarnings("for-rollout:UnusedVariable")
                    String variable;
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void supports_suppressing_errorprone_checks_on_classes_interfaces_records_enums_etc(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                static class exports {}
                interface opens {}
                record provides(int cat) {}
                enum to {;}
                @interface module {}
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                static class exports {}

                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                interface opens {}

                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                record provides(int cat) {}

                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                enum to {
                    ;
                }

                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                @interface module {}
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void does_not_place_suppress_warnings_annotation_in_the_middle_of_a_type_builder_variables_reference(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("UnusedVariable")
                public static void main(String[] args) {
                    App.Builder builder = new App.Builder(new int[3].toString());
                }

                static class Builder {
                    Builder(Object object) {}
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("UnusedVariable")
                public static void main(String[] args) {
                    @SuppressWarnings("for-rollout:ArrayToString")
                    App.Builder builder = new App.Builder(new int[3].toString());
                }

                static class Builder {
                    Builder(Object object) {}
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void can_run_apply_and_suppress_at_the_same_time_it_uses_the_suggested_fix_if_a_patch_check_suppresses_otherwise(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[3].equals(new int[3]);
                }

                // Does not remove existing suppressions
                @SuppressWarnings("checkstyle:LineLength")
                public static void helper() {
                    new int[3].equals(new int[3]);
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayEquals")
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                    new int[3].equals(new int[3]);
                }

                // Does not remove existing suppressions
                @SuppressWarnings({"checkstyle:LineLength", "for-rollout:ArrayEquals"})
                public static void helper() {
                    new int[3].equals(new int[3]);
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void can_run_apply_and_suppress_at_the_same_time_with_if_module_is_used_without_exploding(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            import com.palantir.gradle.suppressibleerrorprone.ConditionalPatchCheck
            import com.palantir.gradle.suppressibleerrorprone.IfModuleIsUsed

            suppressibleErrorProne {
                conditionalPatchChecks.add(new ConditionalPatchCheck(
                        new IfModuleIsUsed('com.fasterxml.jackson.core', 'jackson-core'), 'ArrayToString'))
            }

            dependencies {
                implementation 'com.fasterxml.jackson.core:jackson-core:2.17.1'
                otherImplementation 'com.fasterxml.jackson.core:jackson-core:2.17.1'
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[3].equals(new int[3]);
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayEquals")
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                    new int[3].equals(new int[3]);
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne");
    }

    @Test
    void can_disable_errorprone_using_property(GradleInvoker gradle, RootProject rootProject) {
        // when: 'there is java code some that will fail an errorprone during compilation'
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        // then: 'compilation succeeds when errorprone is disabled'
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneDisable");
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-Pcom.palantir.baseline-error-prone.disable");
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-Pcom.palantir.baseline-error-prone.disable=true");

        // then: 'compilation fails the legacy baseline errorprone disable property is set to false'
        runTasksWithFailure(gradle, "compileAllErrorProne", "-Pcom.palantir.baseline-error-prone.disable=false");
    }

    @Test
    void should_be_able_to_refactor_near_usages_of_deprecated_methods(GradleInvoker gradle, RootProject rootProject) {
        // If a deprecated method usage appears in a compilation unit that is being refactored, the compiler will
        // raise a warning about the deprecated method usage. If -Werror is also enabled, compilation will fail
        // rather than succeed, even when patching checks. The code should make sure to disable the -Werror
        // behaviour so patching always succeeds.

        rootProject.buildGradle().append("""
            tasks.withType(JavaCompile) {
                options.compilerArgs += ['-Werror', '-Xlint:deprecation']
                doFirst {
                    println "COMPILER ARGS: ${options.compilerArgs}"
                }
            }

            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    Character.isJavaLetter('c'); // deprecated method
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply");

        javaSourceContains(rootProject, "Arrays.toString(new int[3])");
    }

    @Test
    void can_conditionally_add_patch_checks(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            import com.palantir.gradle.suppressibleerrorprone.ConditionalPatchCheck

            suppressibleErrorProne {
                patchChecks.add('Something')
                conditionalPatchChecks.add(new ConditionalPatchCheck({ true }, 'ArrayToString'))
                conditionalPatchChecks.add(new ConditionalPatchCheck({ false }, Set.of('ArrayEquals')))
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[2].equals(new int[1]);
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply");

        javaSourceContains(rootProject, "Arrays.toString(new int[3])");
        javaSourceContains(rootProject, "new int[2].equals(new int[1])");
    }

    @Test
    void if_module_is_used_works_properly(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            import com.palantir.gradle.suppressibleerrorprone.ConditionalPatchCheck
            import com.palantir.gradle.suppressibleerrorprone.IfModuleIsUsed

            suppressibleErrorProne {
                conditionalPatchChecks.add(new ConditionalPatchCheck(new IfModuleIsUsed('com.fasterxml.jackson.core', 'jackson-core'), 'ArrayToString'))
                conditionalPatchChecks.add(new ConditionalPatchCheck(new IfModuleIsUsed('doesnt', 'exist'), 'ArrayEquals'))
            }

            dependencies {
                // Depends on jackson-core
                implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.1'
                otherImplementation 'com.fasterxml.jackson.core:jackson-databind:2.17.1'
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[2].equals(new int[1]);
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneApply");

        javaSourceContains(rootProject, "Arrays.toString(new int[3])");
        javaSourceContains(rootProject, "new int[2].equals(new int[1])");
    }

    @Test
    void compileAllErrorProne_only_depends_on_compile_tasks_with_errorprone_enabled(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            tasks.named('compileTestJava').configure {
                options.errorprone.enabled = false
            }
            """);

        InvocationResult result = runTasksSuccessfully(gradle, "compileAllErrorProne", "--dry-run");

        assertThat(result.output()).contains(":compileJava SKIPPED");
        assertThat(result.output()).doesNotContain(":compileTestJava SKIPPED");
        assertThat(result.output()).contains(":compileOtherJava SKIPPED");
    }

    @Test
    void suggestion_level_checks_are_not_suppressed(GradleInvoker gradle, RootProject rootProject) {
        // The code below should hit the FieldCanBeFinal suggestion level check
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                private int field;
                public App() {
                    this.field = 3;
                }
                public int getField() {
                    return field;
                }
            }
            """);

        // when: 'a suggestion check is made error level'
        rootProject.buildGradle().append("""
            tasks.withType(JavaCompile).configureEach {
                options.errorprone.error('FieldCanBeFinal')
            }
            """);

        // then: 'it causes the test code to fail compilation, confirming the check is being run on the code'
        InvocationResult result = runTasksWithFailure(gradle, "compileAllErrorProne");
        assertThat(result.output()).contains("[FieldCanBeFinal]");

        // Now reset the build file and test at SUGGESTION level
        // We need a fresh project for this, so we'll just verify the initial error behavior
    }

    @Test
    void suggestion_level_checks_are_not_suppressed_at_default_level(GradleInvoker gradle, RootProject rootProject) {
        // The code below should hit the FieldCanBeFinal suggestion level check
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                private int field;
                public App() {
                    this.field = 3;
                }
                public int getField() {
                    return field;
                }
            }
            """);

        // when: 'the check is run at the default SUGGESTION level'
        rootProject.buildGradle().append("""
            tasks.withType(JavaCompile).configureEach {
                // This is disabled by default in error-prone, so enable it
                //   https://github.com/google/error-prone/blob/04f05c24882152d3c84f4caf9345efd15859b928/core/src/main/java/com/google/errorprone/scanner/BuiltInCheckerSuppliers.java#L1191
                options.errorprone.enable('FieldCanBeFinal')
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        // then: 'it is not suppressed'
        javaSourceDoesNotContain(rootProject, "SuppressWarnings");
    }

    @Test
    void warning_level_checks_are_suppressed(GradleInvoker gradle, RootProject rootProject) {
        // when: 'the check is at warning level'
        rootProject.buildGradle().append("""
            tasks.withType(JavaCompile).configureEach {
                options.errorprone.warn('ArrayToString')
            }
            """);

        // The code below should hit the LongDoubleConversion warning level check
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String... args) {
                    new int[3].toString();
                }
            }
            """);

        // then: 'compilation does not fail'
        runTasksSuccessfully(gradle, "compileAllErrorProne");

        // when: 'the check is run at the default WARNING level, and then automated suppressions are applied'
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        // then: 'it is suppressed'
        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String... args) {
                    new int[3].toString();
                }
            }
            """);
    }

    @Test
    void makes_no_changes_when_there_is_an_error_on_an_import(GradleInvoker gradle, RootProject rootProject) {
        // when: 'theres an illegal import'
        writeJavaSourceFile(rootProject, """
            package app;
            public class A {
                public static class Inner {}
            }
            """, rootProject.path().resolve("src/main/java"));

        writeJavaSourceFile(rootProject, """
            package app;
            public class B extends A {}
            """, rootProject.path().resolve("src/main/java"));

        // This below hits the NonCanonicalStaticImport as it should refer to app.A.Inner, not app.B.Inner
        writeJavaSourceFile(rootProject, """
            package app;
            import static app.B.Inner;
            public final class App {}
            """, rootProject.path().resolve("src/main/java"));

        // Copy to other source set
        writeJavaSourceFile(rootProject, """
            package app;
            public class A {
                public static class Inner {}
            }
            """, rootProject.path().resolve("src/other/java"));

        writeJavaSourceFile(rootProject, """
            package app;
            public class B extends A {}
            """, rootProject.path().resolve("src/other/java"));

        writeJavaSourceFile(rootProject, """
            package app;
            import static app.B.Inner;
            public final class App {}
            """, rootProject.path().resolve("src/other/java"));

        // then: 'compilation fails'
        InvocationResult result = runTasksWithFailure(gradle, "compileAllErrorProne");
        assertThat(result.output()).contains("[NonCanonicalStaticImport]");

        // when: 'we try to suppress it'
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        // then: 'nothing has changed as we cant put SuppressWarnings on an import'
        InvocationResult result2 = runTasksWithFailure(gradle, "compileAllErrorProne");
        assertThat(result2.output()).contains("[NonCanonicalStaticImport]");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import static app.B.Inner;

            public final class App {}
            """);
    }

    @Test
    void timings_are_outputted(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String... args) {}
            }
            """);

        // when: 'a compilation happens but -PerrorProneTimings is not applied'
        runTasksSuccessfully(gradle, "compileAllErrorProne");

        // then: 'timings are not outputted'
        assertThat(rootProject.path().resolve("build/errorprone-timings/compileJava"))
                .doesNotExist();
        assertThat(rootProject.path().resolve("build/errorprone-timings/compileOtherJava"))
                .doesNotExist();

        // when: 'a compilation happens and -PerrorProneTimings is applied'
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneTimings");

        // then: 'timings are outputted'
        assertThat(rootProject.path().resolve("build/errorprone-timings/compileJava"))
                .exists();
        assertThat(rootProject.path().resolve("build/errorprone-timings/compileOtherJava"))
                .exists();
    }

    static Stream<Arguments> compile_tasks_are_never_up_to_date_modes() {
        return Stream.of(
                Arguments.of((Object) new String[] {"-PerrorProneApply"}),
                Arguments.of((Object) new String[] {"-PerrorProneSuppress"}),
                Arguments.of((Object) new String[] {"-PerrorProneApply", "-PerrorProneSuppress"}));
    }

    @ParameterizedTest
    @MethodSource("compile_tasks_are_never_up_to_date_modes")
    void compile_tasks_are_never_up_to_date_when_applying_changes(
            String[] mode, GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
            """);

        String originalSource = """
            package app;

            public final class App {
                public static void main(String... args) {
                    new int[3].toString();
                }
            }
            """;

        writeJavaSourceFileToSourceSets(rootProject, originalSource);

        // when: 'a compilation with code changes happens'
        runTasksSuccessfully(gradle, "compileAllErrorProne", mode);

        // then: 'the source code is reset back to the original state'
        writeJavaSourceFileToSourceSets(rootProject, originalSource);

        // when: 'compilation with changes runs again'
        runTasksSuccessfully(gradle, "compileAllErrorProne", mode);

        // then: 'changes are actually made, it was not up-to-date'
        javaSourceIsSyntacticallyNotEqualTo(rootProject, originalSource);
    }

    @Test
    void throws_exception_when_errorProneDisable_is_combined_with_errorProneApply_or_errorProneSuppress(
            GradleInvoker gradle, RootProject rootProject) {
        InvocationResult applyResult =
                runTasksWithFailure(gradle, "compileAllErrorProne", "-PerrorProneDisable", "-PerrorProneApply");

        assertThat(applyResult.output()).contains("-PerrorProneDisable cannot be used");

        InvocationResult suppressResult =
                runTasksWithFailure(gradle, "compileAllErrorProne", "-PerrorProneDisable", "-PerrorProneSuppress");

        assertThat(suppressResult.output()).contains("-PerrorProneDisable cannot be used");
    }

    @Test
    void supports_removing_specific_error_prone_suppressions(GradleInvoker gradle, RootProject rootProject) {
        // This test also verifies we're properly passing the arguments to the errorprone plugin
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings("for-rollout:Test")
            public final class App {}
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout=Test");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {}
            """);
    }

    @Test
    void supports_removing_all_error_prone_suppressions(GradleInvoker gradle, RootProject rootProject) {
        // This test also verifies we're properly passing the arguments to the errorprone plugin
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            // We can remove entire lines
            @SuppressWarnings("for-rollout:Test")
            public final class App {
                // We keep non-rollout suppressions untouched
                @SuppressWarnings({"for-rollout:Test", "Test"})
                void nested() {}
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            // We can remove entire lines
            public final class App {
                // We keep non-rollout suppressions untouched
                @SuppressWarnings("Test")
                void nested() {}
            }
            """);
    }

    @Test
    void does_not_remove_suppressions_other_than_requested(GradleInvoker gradle, RootProject rootProject) {
        // This test also verifies we're properly passing the arguments to the errorprone plugin
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings("for-rollout:Test")
            public final class App {}
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout=Other");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            @SuppressWarnings("for-rollout:Test")
            public final class App {}
            """);
    }

    @Test
    void does_not_suppress_remove_rollout_suppressions(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings("for-rollout:Test")
            public final class App {}
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            @SuppressWarnings("for-rollout:Test")
            public final class App {}
            """);
    }

    @Test
    void remove_rollout_suppressions_can_remove_itself(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings("for-rollout:RemoveRolloutSuppressions")
            public final class App {}
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout=RemoveRolloutSuppressions");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {}
            """);
    }

    @Test
    void can_patch_checks_while_using_errorProneRemoveRollout_even_if_suppressed_for_rollout(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(
                gradle,
                "compileAllErrorProne",
                "-PerrorProneRemoveRollout=ArrayToString",
                "-PerrorProneApply=ArrayToString");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
            """);
    }

    @Test
    void can_patch_checks_while_using_errorProneRemoveRollout_which_also_add_annotations_as_fixes(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ShouldBeNullable")
                private Object fixme() {
                    return null;
                }

                @SuppressWarnings("for-rollout:ShouldBeNullable")
                private Object fixme(Object andMySuppressionHasWhiteSpaceAfterIt) {
                    return null;
                }

                @SuppressWarnings({"for-rollout:ShouldBePrivate", "for-rollout:ShouldBeNullable"})
                Integer fixme(Integer i) {
                    return null;
                }
            }
            """);

        runTasksSuccessfully(
                gradle,
                "compileAllErrorProne",
                "-PerrorProneRemoveRollout=ShouldBeNullable,ShouldBePrivate",
                "-PerrorProneApply=ShouldBeNullable,ShouldBePrivate");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import javax.annotation.Nullable;

            public final class App {
                @Nullable
                private Object fixme() {
                    return null;
                }

                @Nullable
                private Object fixme(Object andMySuppressionHasWhiteSpaceAfterIt) {
                    return null;
                }

                @Nullable
                private Integer fixme(Integer i) {
                    return null;
                }
            }
            """);
    }

    @Test
    void errorProneSuppress_then_errorProneRemoveRollout_does_not_add_newlines(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                private Object fixme() {
                    return null;
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneSuppress=ShouldBeNullable");
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout=ShouldBeNullable");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                private Object fixme() {
                    return null;
                }
            }
            """);
    }

    @Test
    void does_not_patch_checks_while_using_errorProneRemoveRollout_if_suppressed_normally(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(
                gradle,
                "compileAllErrorProne",
                "-PerrorProneRemoveRollout=ArrayToString",
                "-PerrorProneApply=ArrayToString");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);
    }

    @Test
    void can_patch_checks_while_using_errorProneRemoveRollout_if_not_suppressed(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(
                gradle,
                "compileAllErrorProne",
                "-PerrorProneRemoveRollout=ArrayToString",
                "-PerrorProneApply=ArrayToString");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
            """);
    }

    @Test
    void errorProneRemoveRollout_does_not_patch_unrelated_checks(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(
                gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout=NullAway", "-PerrorProneApply=NullAway");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);
    }

    @Test
    void errorProneRemoveRollout_does_not_patch_by_itself(GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout=ArrayToString");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);
    }

    @Test
    void errorProneRemoveRollout_does_not_patch_if_specific_check_is_not_selected(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;
            @SuppressWarnings("for-rollout:Test")
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(
                gradle,
                "compileAllErrorProne",
                "-PerrorProneRemoveRollout=ArrayToString,Test",
                "-PerrorProneApply=Test");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);
    }

    @Test
    void can_patch_specific_checks_even_if_errorProneRemoveRollout_argument_is_empty(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(
                gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout", "-PerrorProneApply=ArrayToString");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
            """);
    }

    @Test
    void can_patch_configured_checks_even_if_errorProneRemoveRollout_argument_is_empty(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
            """);

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveRollout", "-PerrorProneApply");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
            """);
    }

    @Test
    void remove_rollout_suppressions_does_not_appear_as_a_note_in_unrelated_errors(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("for-rollout:NullAway")
                public static void method() {
                    new int[3].toString();
                }
            }
            """);

        InvocationResult result = runTasksWithFailure(gradle, "compileAllErrorProne");

        assertThat(result.output()).doesNotContain("[RemoveRolloutSuppressions]");
    }

    @Test
    void errorProneRemoveUnused_removes_only_unused_error_prone_suppressions_and_leaves_unknown_suppressions_untouched(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings({"ArrayToString", "UnnecessaryFinal", "InlineTrivialConstant", "NotAnErrorProne", "checkstyle:Bla",})
            public final class App {
                private static final String EMPTY_STRING = "";

                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused=ArrayToString");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            @SuppressWarnings({"ArrayToString", "InlineTrivialConstant", "NotAnErrorProne", "checkstyle:Bla"})
            public final class App {
                private static final String EMPTY_STRING = "";

                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
            """);
    }

    @Test
    void errorProneRemoveUnused_does_not_apply_fixes(GradleInvoker gradle, RootProject rootProject) {
        String initialSource = """
            package app;

            public final class App {
                class Inner {
                    class InnerInner {}
                }
            }
            """;

        writeJavaSourceFileToSourceSets(rootProject, initialSource);

        // when: 'errorProneRemoveUnused is run by itself'
        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused");

        // then: 'No checks are applied'
        javaSourceIsSyntacticallyEqualTo(rootProject, initialSource);

        // when: 'ClassCanBeStatic is applied alongside errorProneRemoveUnused'
        runTasksSuccessfully(
                gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused", "-PerrorProneApply=ClassCanBeStatic");

        // then: 'ClassCanBeStatic is applied'
        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                static class Inner {
                    static class InnerInner {}
                }
            }
            """);
    }

    @Test
    void errorProneRemoveUnused_only_keeps_the_closest_suppression_to_a_violation(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings("InlineTrivialConstant")
            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                @SuppressWarnings("InlineTrivialConstant")
                class Inner {
                    @SuppressWarnings("InlineTrivialConstant")
                    class InnerInner {
                        @SuppressWarnings("InlineTrivialConstant")
                        class InnerInnerInner {
                            private static final String EMPTY = "";
                        }
                    }
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                class Inner {
                    class InnerInner {
                        @SuppressWarnings("InlineTrivialConstant")
                        class InnerInnerInner {
                            private static final String EMPTY = "";
                        }
                    }
                }
            }
            """);
    }

    @Test
    void errorProneRemoveUnused_handles_multiple_suppressions_on_different_tree_types_gracefully(
            GradleInvoker gradle, RootProject rootProject) {
        // Here we test the three types of trees you can suppress — ClassTree, MethodTree, VariableTree

        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                // Doesn't move an already existing suppression, even if it could be closer to the violation
                @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
                static class Inner {
                    @SuppressWarnings("InlineTrivialConstant")
                    private static final String EMPTY = "";
                    boolean truism = new int[3].equals(new int[3]);

                    @SuppressWarnings("InlineTrivialConstant")
                    static class InnerInner {
                        @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
                        void method() {
                            new int[3].equals(new int[3]);
                        }
                    }
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                // Doesn't move an already existing suppression, even if it could be closer to the violation
                @SuppressWarnings("ArrayEquals")
                static class Inner {
                    @SuppressWarnings("InlineTrivialConstant")
                    private static final String EMPTY = "";

                    boolean truism = new int[3].equals(new int[3]);

                    static class InnerInner {
                        @SuppressWarnings("ArrayEquals")
                        void method() {
                            new int[3].equals(new int[3]);
                        }
                    }
                }
            }
            """);
    }

    @Test
    void errorProneRemoveUnused_removes_entire_suppress_warnings_annotation_when_all_suppressions_are_unused(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings({"UnusedVariable", "ArrayToString"})
            public final class App {
                public static void main(String[] args) {
                    System.out.println("No violations here");
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                public static void main(String[] args) {
                    System.out.println("No violations here");
                }
            }
            """);
    }

    @Test
    void errorProneRemoveUnused_and_errorProneSuppress_uses_existing_suppressions_if_possible(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings("InlineTrivialConstant")
            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                @SuppressWarnings("InlineTrivialConstant")
                static class Inner {
                    @SuppressWarnings("InlineTrivialConstant")
                    private static final String EMPTY = "";
                    boolean truism = new int[3].equals(new int[3]);

                    @SuppressWarnings("InlineTrivialConstant")
                    static class InnerInner {
                        @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
                        void method() {
                            new int[3].equals(new int[3]);
                        }
                    }
                }
            }
            """);

        runTasksSuccessfully(gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused", "-PerrorProneSuppress");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                static class Inner {
                    @SuppressWarnings("InlineTrivialConstant")
                    private static final String EMPTY = "";

                    @SuppressWarnings("for-rollout:ArrayEquals")
                    boolean truism = new int[3].equals(new int[3]);

                    static class InnerInner {
                        @SuppressWarnings("ArrayEquals")
                        void method() {
                            new int[3].equals(new int[3]);
                        }
                    }
                }
            }
            """);
    }

    @Test
    void errorProneRemoveUnused_and_errorProneApply_applies_fixes_on_previously_suppressed_elements(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;
            @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
                static class Inner {
                    @SuppressWarnings("InlineTrivialConstant")
                    private static final String EMPTY = "";
                    boolean truism = new int[3].equals(new int[3]);

                    @SuppressWarnings("InlineTrivialConstant")
                    static class InnerInner {
                        @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
                        void method() {
                            new int[3].equals(new int[3]);
                        }
                    }
                }
            }
            """);

        runTasksSuccessfully(
                gradle, "compileAllErrorProne", "-PerrorProneRemoveUnused", "-PerrorProneApply=ArrayEquals");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                @SuppressWarnings("InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                static class Inner {
                    @SuppressWarnings("InlineTrivialConstant")
                    private static final String EMPTY = "";

                    boolean truism = Arrays.equals(new int[3], new int[3]);

                    static class InnerInner {
                        void method() {
                            Arrays.equals(new int[3], new int[3]);
                        }
                    }
                }
            }
            """);
    }

    @Test
    void errorProneRemoveUnused_apply_suppress_fixes_and_suppressions_on_previously_suppressed_elements(
            GradleInvoker gradle, RootProject rootProject) {
        writeJavaSourceFileToSourceSets(rootProject, """
            package app;

            @SuppressWarnings("ArrayEquals")
            public final class App {
                private static final String EMPTY_STRING = "";

                // Although InlineTrivialConstant can be placed lower in the AST hierarchy,
                // we preserve existing suppressions whenever possible rather than move suppressions around.
                // Also, note that we don't add for-rollout here.
                @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
                static class Inner {
                    private static final String EMPTY = "";
                    boolean truism = new int[3].equals(new int[3]);

                    @SuppressWarnings("InlineTrivialConstant")
                    static class InnerInner {
                        @SuppressWarnings({"ArrayEquals", "InlineTrivialConstant"})
                        void method() {
                            new int[3].equals(new int[3]);
                        }
                    }
                }
            }
            """);

        runTasksSuccessfully(
                gradle,
                "compileAllErrorProne",
                "-PerrorProneRemoveUnused",
                "-PerrorProneSuppress",
                "-PerrorProneApply=ArrayEquals");

        javaSourceIsSyntacticallyEqualTo(rootProject, """
            package app;

            import java.util.Arrays;

            public final class App {
                @SuppressWarnings("for-rollout:InlineTrivialConstant")
                private static final String EMPTY_STRING = "";

                // Although InlineTrivialConstant can be placed lower in the AST hierarchy,
                // we preserve existing suppressions whenever possible rather than move suppressions around.
                // Also, note that we don't add for-rollout here.
                @SuppressWarnings("InlineTrivialConstant")
                static class Inner {
                    private static final String EMPTY = "";
                    boolean truism = Arrays.equals(new int[3], new int[3]);

                    static class InnerInner {
                        void method() {
                            Arrays.equals(new int[3], new int[3]);
                        }
                    }
                }
            }
            """);
    }

    @Test
    void error_prone_dependencies_have_versions_bound_together_by_a_virtual_platform(
            GradleInvoker gradle, RootProject rootProject) {
        // setup: 'when an error-prone dependency is forced to certain version'
        rootProject.buildGradle().append("""
            configurations.named('annotationProcessor') {
                resolutionStrategy {
                   force 'com.google.errorprone:error_prone_annotation:2.3.4'
                }
            }

            tasks.register('printErrorProneVersions') {
                inputs.files(configurations.named('annotationProcessor'))
                doLast {
                    inputs.files.files.each {
                        println("ERROR-PRONE: ${it.name}")
                    }
                }
            }
            """);

        InvocationResult result = runTasksSuccessfully(gradle, "printErrorProneVersions");

        // then: 'every single error-prone dependency has the same version'
        assertThat(result.output()).contains("ERROR-PRONE: error_prone_annotation-2.3.4.jar");
        assertThat(result.output()).contains("ERROR-PRONE: error_prone_core-2.3.4.jar");
    }

    // Helper methods

    private InvocationResult runTasksSuccessfully(GradleInvoker gradle, String... tasks) {
        String[] allTasks = new String[tasks.length + 1];
        System.arraycopy(tasks, 0, allTasks, 0, tasks.length);
        allTasks[tasks.length] = "-PsuppressibleErrorProneVersion=" + projectVersion;

        // Note: Configuration cache is disabled at class level due to file manipulation
        return gradle.withArgs(allTasks).buildsSuccessfully();
    }

    private InvocationResult runTasksSuccessfully(GradleInvoker gradle, String task, String[] additionalArgs) {
        String[] allTasks = new String[additionalArgs.length + 2];
        allTasks[0] = task;
        System.arraycopy(additionalArgs, 0, allTasks, 1, additionalArgs.length);
        allTasks[allTasks.length - 1] = "-PsuppressibleErrorProneVersion=" + projectVersion;

        // Note: Configuration cache is disabled at class level due to file manipulation
        return gradle.withArgs(allTasks).buildsSuccessfully();
    }

    private InvocationResult runTasksWithFailure(GradleInvoker gradle, String... tasks) {
        String[] allTasks = new String[tasks.length + 1];
        System.arraycopy(tasks, 0, allTasks, 0, tasks.length);
        allTasks[tasks.length] = "-PsuppressibleErrorProneVersion=" + projectVersion;

        // Note: Configuration cache is disabled at class level due to file manipulation
        return gradle.withArgs(allTasks).buildsWithFailure();
    }

    private void writeJavaSourceFileToSourceSets(RootProject rootProject, String source) {
        rootProject.sourceSet("main").java().writeClass(source.stripIndent());
        rootProject.sourceSet("other").java().writeClass(source.stripIndent());
    }

    private void writeJavaSourceFile(RootProject rootProject, String source, Path sourceDir) {
        try {
            // Extract package and class name to create proper file structure
            String className = extractClassName(source);

            Path packageDir = sourceDir.resolve("app");
            Files.createDirectories(packageDir);
            Path javaFile = packageDir.resolve(className + ".java");
            Files.writeString(javaFile, source.stripIndent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write Java source file", e);
        }
    }

    private static String extractClassName(String source) {
        // Simple extraction - looks for "class X" or "interface X" etc.
        String[] patterns = {"public final class ", "public class ", "class ", "interface ", "record ", "enum "};
        for (String pattern : patterns) {
            int idx = source.indexOf(pattern);
            if (idx != -1) {
                int start = idx + pattern.length();
                int end = start;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                return source.substring(start, end);
            }
        }
        return "App";
    }

    private void javaSourceContains(RootProject rootProject, String substring) {
        Path mainJava = rootProject.path().resolve("src/main/java/app/App.java");
        Path otherJava = rootProject.path().resolve("src/other/java/app/App.java");

        try {
            String mainContent = Files.readString(mainJava);
            String otherContent = Files.readString(otherJava);
            assertThat(mainContent).contains(substring);
            assertThat(otherContent).contains(substring);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Java source files", e);
        }
    }

    private void javaSourceDoesNotContain(RootProject rootProject, String substring) {
        Path mainJava = rootProject.path().resolve("src/main/java/app/App.java");
        Path otherJava = rootProject.path().resolve("src/other/java/app/App.java");

        try {
            String mainContent = Files.readString(mainJava);
            String otherContent = Files.readString(otherJava);
            assertThat(mainContent).doesNotContain(substring);
            assertThat(otherContent).doesNotContain(substring);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Java source files", e);
        }
    }

    // Normalizes Java source by trimming whitespace and applying consistent formatting.
    // Preserves newlines since the formatter allows them within methods, and we need
    // to test that error-prone doesn't introduce unwanted line breaks.
    @SuppressWarnings("StringSplitter")
    private static String normalizeSource(String content) {
        String[] lines = content.split("\n");
        StringBuilder stripped = new StringBuilder();
        for (String line : lines) {
            stripped.append(line.trim()).append('\n');
        }

        try {
            return FORMATTER.formatSource(stripped.toString());
        } catch (FormatterException e) {
            throw new RuntimeException("Failed to format source", e);
        }
    }

    private void javaSourceIsSyntacticallyEqualTo(RootProject rootProject, String source) {
        Path mainJava = rootProject.path().resolve("src/main/java/app/App.java");
        Path otherJava = rootProject.path().resolve("src/other/java/app/App.java");

        try {
            String mainContent = Files.readString(mainJava);
            String otherContent = Files.readString(otherJava);

            String output = normalizeSource(mainContent);
            String expected = normalizeSource(source);

            assertThat(output).isEqualTo(expected);

            String outputOther = normalizeSource(otherContent);
            assertThat(outputOther).isEqualTo(expected);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Java source files", e);
        }
    }

    private void javaSourceIsSyntacticallyNotEqualTo(RootProject rootProject, String source) {
        Path mainJava = rootProject.path().resolve("src/main/java/app/App.java");
        Path otherJava = rootProject.path().resolve("src/other/java/app/App.java");

        try {
            String mainContent = Files.readString(mainJava);
            String otherContent = Files.readString(otherJava);

            String output = normalizeSource(mainContent);
            String expected = normalizeSource(source);

            assertThat(output).isNotEqualTo(expected);

            String outputOther = normalizeSource(otherContent);
            assertThat(outputOther).isNotEqualTo(expected);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Java source files", e);
        }
    }
}
