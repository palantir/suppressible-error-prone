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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

            apply plugin: 'com.palantir.suppressible-error-prone'
            """);

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
    void ensure_error_prone_checks_are_disabled_in_generated_code(GradleInvoker gradle, RootProject rootProject)
            throws IOException {
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

        Files.createDirectories(sourceDir1);
        Files.createDirectories(sourceDir2);

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

    // Helper methods

    private InvocationResult runTasksSuccessfully(GradleInvoker gradle, String... tasks) {
        String[] allTasks = new String[tasks.length + 1];
        System.arraycopy(tasks, 0, allTasks, 0, tasks.length);
        allTasks[tasks.length] = "-PsuppressibleErrorProneVersion=" + projectVersion;

        if (DEBUGGING_ERROR_PRONES) {
            return gradle.withArgs(allTasks).buildsSuccessfully();
        } else {
            // Note: Configuration cache is disabled at class level due to file manipulation
            return gradle.withArgs(allTasks).buildsSuccessfully();
        }
    }

    private InvocationResult runTasksWithFailure(GradleInvoker gradle, String... tasks) {
        String[] allTasks = new String[tasks.length + 1];
        System.arraycopy(tasks, 0, allTasks, 0, tasks.length);
        allTasks[tasks.length] = "-PsuppressibleErrorProneVersion=" + projectVersion;

        if (DEBUGGING_ERROR_PRONES) {
            return gradle.withArgs(allTasks).buildsWithFailure();
        } else {
            // Note: Configuration cache is disabled at class level due to file manipulation
            return gradle.withArgs(allTasks).buildsWithFailure();
        }
    }

    private void writeJavaSourceFileToSourceSets(RootProject rootProject, String source) {
        rootProject.sourceSet("main").java().writeClass(source.stripIndent());
        rootProject.sourceSet("other").java().writeClass(source.stripIndent());
    }

    private void writeJavaSourceFile(RootProject rootProject, String source, Path sourceDir) throws IOException {
        // Extract package and class name to create proper file structure
        String packageName = "app";
        String className = "App";
        if (source.contains("App2")) {
            className = "App2";
        }

        Path packageDir = sourceDir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve(className + ".java");
        Files.writeString(javaFile, source.stripIndent());
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

    @SuppressWarnings("UnusedMethod")
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

            // Ensure test fixtures are properly formatted
            assertThat("\n" + expected)
                    .as("Please update your text fixtures to be in palantir-java-format")
                    .isEqualTo(source);
            assertThat(output).isEqualTo(expected);

            String outputOther = normalizeSource(otherContent);
            String expectedOther = normalizeSource(source);
            assertThat(outputOther).isEqualTo(expectedOther);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Java source files", e);
        }
    }

    @SuppressWarnings("UnusedMethod")
    private void javaSourceIsSyntacticallyNotEqualTo(RootProject rootProject, String source) {
        Path mainJava = rootProject.path().resolve("src/main/java/app/App.java");
        Path otherJava = rootProject.path().resolve("src/other/java/app/App.java");

        try {
            String mainContent = Files.readString(mainJava);
            String otherContent = Files.readString(otherJava);

            String output = normalizeSource(mainContent);
            String expected = normalizeSource(source);

            // Ensure test fixtures are properly formatted
            assertThat("\n" + expected)
                    .as("Please update your text fixtures to be in palantir-java-format")
                    .isEqualTo(source);
            assertThat(output).isNotEqualTo(expected);

            String outputOther = normalizeSource(otherContent);
            String expectedOther = normalizeSource(source);
            assertThat(outputOther).isNotEqualTo(expectedOther);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Java source files", e);
        }
    }
}
