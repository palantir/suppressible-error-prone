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

package com.palantir.gradle.suppressibleerrorprone


import com.palantir.gradle.plugintesting.ConfigurationCacheSpec
import com.palantir.javaformat.java.JavaFormatterOptions
import org.apache.commons.io.FileUtils
import org.gradle.testkit.runner.BuildResult
import spock.lang.Unroll
import com.palantir.javaformat.java.Formatter


class SuppressibleErrorPronePluginIntegrationTest extends ConfigurationCacheSpec {
    static Formatter formatter = Formatter.createFormatter(JavaFormatterOptions.builder().style(JavaFormatterOptions.Style.PALANTIR).build())

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
    boolean debuggingErrorPrones = false

    def setup() {
        // language=Gradle
        buildFile << '''
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
            apply plugin: 'java'
            
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
                errorprone "com.uber.nullaway:nullaway:0.12.12"
                // NullAway is turned off by default, but is used in some tests
            }
            
            suppressibleErrorProne {
                configureEachErrorProneOptions {
                    // These interfere with some tests, so disable them
                    // TODO(callumr): Rewrite the tests to use custom testing error-prones rather than built in checks
                    //                to make upgrading error-prone easier.
                    disable('Varifier', 'ReturnValueIgnored', 'UnusedVariable', 'IdentifierName', 'UnusedMethod', 'NullAway')
                    ignoreUnknownCheckNames = true
                }
            }
        '''.stripIndent(true)

        if (debuggingErrorPrones) {
            // language=Gradle
            buildFile << '''
                tasks.withType(JavaCompile).configureEach {
                    it.options.forkOptions.jvmArgumentProviders.add(new CommandLineArgumentProvider() {
                        @Override
                        public Iterable<String> asArguments() {
                            return List.of("-agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5005")
                        }
                    })
                }
            '''.stripIndent(true)
        }

        file('gradle.properties') << '''
            __TESTING=true
            __TESTING_CACHE_BUST_ERRORPRONE_TRANSFORM=true
        '''.stripIndent(true)

        file('versions.lock')
    }

    def 'reports a failing error prone'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        def stderr = runTasksWithFailure('compileAllErrorProne').output

        then:
        stderr.contains('[ArrayToString]')
    }

    def 'can suppress an error prone with for-rollout prefix'() {
        // This test is explicitly checking we suppress the for-rollout prefix as that is what exists
        // in people's codebases

        when:
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        then:
        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'ensure error prone checks are disabled in generated code'() {
        // language=Java
        def erroringCode = '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)


        when:
        def sourceDir1 = new File(projectDir, 'src/generated')
        def sourceDir2 = new File(projectDir, 'build/generated')

        writeJavaSourceFile(erroringCode, sourceDir1)
        writeJavaSourceFile(erroringCode.replace('App', 'App2'), sourceDir2)

        buildFile << """
            sourceSets.main.java.srcDirs('${projectDir.relativePath(sourceDir1)}', '${projectDir.relativePath(sourceDir2)}')
        """.stripIndent(true)

        then:
        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'can apply patches for a check if added to the patchChecks list'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        runTasksSuccessfully('compileAllErrorProne')

        javaSourceContains('Arrays.toString(new int[3])')
    }

    def 'does not apply patches for a check if not added to the patchChecks list'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                // To make sure set is not empty
                patchChecks = ['SomeCheck']
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        javaSourceContains('new int[3].toString()')
    }

    def 'does not apply patches if there is nothing in patchChecks set'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.empty()
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        // Doesn't actually do any patching as the set is empty. It just does a normal compile that fails.
        def stderr = runTasksWithFailure('compileAllErrorProne', '-PerrorProneApply').output

        then:
        stderr.contains('[ArrayToString]')
        javaSourceContains('new int[3].toString()')
    }

    def 'does not apply patches for check that was explicitly disabled'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
            
            tasks.withType(JavaCompile).configureEach {
                options.errorprone.disable 'ArrayToString'
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        javaSourceContains('new int[3].toString()')
    }

    def 'can patch specific checks using -PerrorProneApply'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[2].equals(new int[1]);
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply=ArrayToString,ArrayEquals')

        then:
        javaSourceContains('Arrays.toString(new int[3])')
        javaSourceContains('Arrays.equals(new int[2], new int[1])')
    }

    def 'can suppress a failing check (even if not in patchChecks set)'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        javaSourceContains('@SuppressWarnings(\"for-rollout:ArrayToString\")')


        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'does not apply SuppressWarnings to implicit lambda parameters'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            import java.util.stream.Stream;
            
            public class App {
                void test() {
                    Stream.of(new Object()).forEach(o -> o.toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // Suppression should be on the method, not the lambda parameter
        // language=Java
        javaSourceIsSyntacticallyEqualTo """
            package app;
            
            import java.util.stream.Stream;
            
            public class App {
                @SuppressWarnings("for-rollout:TestCheckNoSingleLetterVariable")
                void test() {
                    Stream.of(new Object()).forEach(o -> o.toString());
                }
            }
        """.stripIndent(true)

        // Verify the code still compiles after the suppression has been applied, as previous versions
        //   were adding the annotation to the lambda implicit parameter which is not valid java
        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'does not apply SuppressWarnings to explicit lambda parameters'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            import java.util.stream.Stream;
            
            public class App {
                void test() {
                    Stream.of(new Object()).forEach((Object o) -> o.toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // Suppression should be on the method, not the lambda parameter
        // language=Java
        javaSourceIsSyntacticallyEqualTo("""
            package app;
            
            import java.util.stream.Stream;
            
            public class App {
                @SuppressWarnings("for-rollout:TestCheckNoSingleLetterVariable")
                void test() {
                    Stream.of(new Object()).forEach((Object o) -> o.toString());
                }
            }
        """.stripIndent(true))

        // Verify the code still compiles after the suppression has been applied, as previous versions
        //   were adding the annotation to the lambda implicit parameter which is not valid java
        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'does not apply SuppressWarnings to anonymous classes'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // Suppression should be on the method, not the anonymous class
        // language=Java
        javaSourceIsSyntacticallyEqualTo("""
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
        """.stripIndent(true))

        // Verify the code still compiles after the suppression has been applied, as previous versions
        //   were adding the annotation to the anonymous class which is not valid java
        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'demonstrate suppressions on different source elements'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)

        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'supports errorprone checks that match on a larger element than they report errors on'() {
        // The UnusedVariable check implements CompilationUnitTreeMatcher, so will start with a whole
        // CompilationUnitTree and then narrows down to the specific variable declaration that is unused.
        // This trips up the "naive" suppression logic, which looks at where the visitor has got to rather
        // than where the diagnostic description was produced.

        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                configureEachErrorProneOptions {
                    enable('UnusedVariable')
                }
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public void variables() {
                    String variable;
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {
                public void variables() {
                    @SuppressWarnings("for-rollout:UnusedVariable")
                    String variable;
                }
            }
        '''.stripIndent(true)

        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'supports suppressing errorprone checks on classes, interfaces, records, enums, etc'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                static class exports {}
                interface opens {}
                record provides(int cat) {}
                enum to {;}
                @interface module {}
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)


        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'does not place suppress warnings annotation in the middle of a Type.Builder variables reference'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)


        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'can run apply and suppress at the same time - it uses the suggested fix if a patch check, suppresses otherwise'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply', '-PerrorProneSuppress')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)

        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'can run apply and suppress at the same time with IfModuleIsUsed without exploding'() {
        // language=Gradle
        buildFile << '''
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
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[3].equals(new int[3]);
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply', '-PerrorProneSuppress')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            import java.util.Arrays;
            
            public final class App {
                @SuppressWarnings("for-rollout:ArrayEquals")
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                    new int[3].equals(new int[3]);
                }
            }
        '''.stripIndent(true)

        runTasksSuccessfully('compileAllErrorProne')
    }

    def 'can disable errorprone using property'() {
        when: 'there is java code some that will fail an errorprone during compilation'
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        then: 'compilation succeeds when errorprone is disabled'
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneDisable')
        runTasksSuccessfully('compileAllErrorProne', '-Pcom.palantir.baseline-error-prone.disable')
        runTasksSuccessfully('compileAllErrorProne', '-Pcom.palantir.baseline-error-prone.disable=true')

        then: 'compilation fails the legacy baseline errorprone disable property is set to false'
        runTasksWithFailure('compileAllErrorProne', '-Pcom.palantir.baseline-error-prone.disable=false')
    }

    def 'should be able to refactor near usages of deprecated methods'() {
        // If a deprecated method usage appears in a compilation unit that is being refactored, the compiler will
        // raise a warning about the deprecated method usage. If -Werror is also enabled, compilation will fail
        // rather than succeed, even when patching checks. The code should make sure to disable the -Werror
        // behaviour so patching always succeeds.

        // language=Gradle
        buildFile << '''
            tasks.withType(JavaCompile) {
                options.compilerArgs += ['-Werror', '-Xlint:deprecation']
                doFirst {
                    println "COMPILER ARGS: ${options.compilerArgs}"
                }
            }
            
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
         
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    Character.isJavaLetter('c'); // deprecated method
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        javaSourceContains('Arrays.toString(new int[3])')
    }

    def 'can conditionally add patch checks'() {
        // language=Gradle
        buildFile << '''
            import com.palantir.gradle.suppressibleerrorprone.ConditionalPatchCheck

            suppressibleErrorProne {
                patchChecks.add('Something')
                conditionalPatchChecks.add(new ConditionalPatchCheck({ true }, 'ArrayToString'))
                conditionalPatchChecks.add(new ConditionalPatchCheck({ false }, Set.of('ArrayEquals')))
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[2].equals(new int[1]);
                }
            }
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')


        then:
        javaSourceContains('Arrays.toString(new int[3])')
        javaSourceContains('new int[2].equals(new int[1])')
    }

    def 'IfModuleIsUsed works properly'() {
        // language=Gradle
        buildFile << '''
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
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                    new int[2].equals(new int[1]);
                }
            }
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        javaSourceContains('Arrays.toString(new int[3])')
        javaSourceContains('new int[2].equals(new int[1])')
    }

    def 'compileAllErrorProne only depends on compile tasks with errorprone enabled'() {
        // language=Gradle
        buildFile << '''
            tasks.named('compileTestJava').configure {
                options.errorprone.enabled = false
            }
        '''.stripIndent(true)

        when:
        def stdout = runTasksSuccessfully('compileAllErrorProne', '--dry-run').output

        then:
        stdout.contains(':compileJava SKIPPED')
        !stdout.contains(':compileTestJava SKIPPED')
        stdout.contains(':compileOtherJava SKIPPED')
    }

    def 'SUGGESTION level checks are not suppressed'() {
        def originalBuildFile = buildFile.text

        // The code below should hit the FieldCanBeFinal suggestion level check
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when: 'a suggestion check is made error level'
        // language=Gradle
        buildFile << '''
            tasks.withType(JavaCompile).configureEach {
                options.errorprone.error('FieldCanBeFinal')
            }
        '''.stripIndent(true)

        then: 'it causes the test code to fail compilation, confirming the check is being run on the code'
        def stderr = runTasksWithFailure('compileAllErrorProne').output
        stderr.contains('[FieldCanBeFinal]')

        when: 'the check is run at the default SUGGESTION level, and then automated suppressions are not applied'
        buildFile.text = originalBuildFile
        // language=Gradle
        buildFile << '''
            tasks.withType(JavaCompile).configureEach {
                // This is disabled by default in error-prone, so enable it
                //   https://github.com/google/error-prone/blob/04f05c24882152d3c84f4caf9345efd15859b928/core/src/main/java/com/google/errorprone/scanner/BuiltInCheckerSuppliers.java#L1191
                options.errorprone.enable('FieldCanBeFinal')
            }
        '''.stripIndent(true)

        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then: 'it is not suppressed'
        javaSourceDoesNotContain("SuppressWarnings")
    }

    def 'WARNING level checks are suppressed'() {
        when: 'the check is at warning level'
        // language=Gradle
        buildFile << '''
            tasks.withType(JavaCompile).configureEach {
                options.errorprone.warn('ArrayToString')
            }
        '''.stripIndent(true)

        // The code below should hit the LongDoubleConversion warning level check
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String... args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        then: 'compilation does not fail'
        runTasksSuccessfully('compileAllErrorProne')

        when: 'the check is run at the default WARNING level, and then automated suppressions are applied'
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then: 'it is suppressed'
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String... args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)
    }

    def 'makes no changes when there is an error on an import'() {
        when: 'theres an illegal import'
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public class A {
                public static class Inner {}
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public class B extends A {}
        '''.stripIndent(true)

        // This below hits the NonCanonicalStaticImport as it should refer to app.A.Inner, not app.B.Inner
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            import static app.B.Inner;
            public final class App {}
        '''.stripIndent(true)

        then: 'compilation fails'
        def stderr = runTasksWithFailure('compileAllErrorProne').output
        stderr.contains('[NonCanonicalStaticImport]')

        when: 'we try to suppress it'
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then: 'nothing has changed as we cant put SuppressWarnings on an import'
        def stderr2 = runTasksWithFailure('compileAllErrorProne').output
        stderr2.contains('[NonCanonicalStaticImport]')

        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            import static app.B.Inner;
            
            public final class App {}
        '''.stripIndent(true)
    }

    def 'timings are outputted'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String... args) {}
            }
        '''.stripIndent(true)

        when: 'a compilation happens but -PerrorProneTimings is not applied'
        runTasksSuccessfully('compileAllErrorProne')

        then: 'timings are not outputted'
        !new File(projectDir, 'build/errorprone-timings/compileJava').exists()
        !new File(projectDir, 'build/errorprone-timings/compileOtherJava').exists()

        when: 'a compilation happens and -PerrorProneTimings is applied'
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneTimings')

        then: 'timings are outputted'
        new File(projectDir, 'build/errorprone-timings/compileJava').exists()
        new File(projectDir, 'build/errorprone-timings/compileOtherJava').exists()
    }

    @Unroll
    def 'compile tasks are never up-to-date when applying changes under #mode'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
        '''.stripIndent(true)

        // language=Java
        def originalSource = '''
            package app;
            
            public final class App {
                public static void main(String... args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        writeJavaSourceFileToSourceSets originalSource

        when: 'a compilation with code changes happens'
        runTasksSuccessfully('compileAllErrorProne', *mode)

        then: 'the source code is reset back to the original state'
        writeJavaSourceFileToSourceSets originalSource

        when: 'compilation with changes runs again'
        runTasksSuccessfully('compileAllErrorProne', *mode)

        then: 'changes are actually made, it was not up-to-date'
        javaSourceIsSyntacticallyNotEqualTo originalSource

        where:
        mode << [
                ['-PerrorProneApply'],
                ['-PerrorProneSuppress'],
                ['-PerrorProneApply', '-PerrorProneSuppress']
        ]
    }

    def 'throws exception when -PerrorProneDisable is combined with -PerrorProneApply or -PerrorProneSuppress'() {
        when:
        def applyOutput = runTasksWithFailure('compileAllErrorProne', '-PerrorProneDisable', '-PerrorProneApply').output

        then:
        applyOutput.contains '-PerrorProneDisable cannot be used'

        when:
        def suppressOutput = runTasksWithFailure('compileAllErrorProne', '-PerrorProneDisable', '-PerrorProneSuppress').output

        then:
        suppressOutput.contains '-PerrorProneDisable cannot be used'
    }

    // This test also verifies we're properly passing the arguments to the errorprone plugin
    def 'supports removing specific error prone suppressions'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            @SuppressWarnings("for-rollout:Test")
            public final class App {}
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=Test')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {}
        '''.stripIndent(true)
    }

    // This test also verifies we're properly passing the arguments to the errorprone plugin
    def 'supports removing all error prone suppressions'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            // We can remove entire lines
            @SuppressWarnings("for-rollout:Test")
            public final class App {
                // We keep non-rollout suppressions untouched
                @SuppressWarnings({"for-rollout:Test", "Test"})
                void nested() {}
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            // We can remove entire lines
            public final class App {
                // We keep non-rollout suppressions untouched
                @SuppressWarnings("Test")
                void nested() {}
            }
        '''.stripIndent(true)
    }

    // This test also verifies we're properly passing the arguments to the errorprone plugin
    def 'does not remove suppressions other than requested'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            @SuppressWarnings("for-rollout:Test")
            public final class App {}
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=Other')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            @SuppressWarnings("for-rollout:Test")
            public final class App {}
        '''.stripIndent(true)
    }

    def 'does not suppress RemoveRolloutSuppressions'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            @SuppressWarnings("for-rollout:Test")
            public final class App {}
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            @SuppressWarnings("for-rollout:Test")
            public final class App {}
        '''.stripIndent(true)
    }

    def 'RemoveRolloutSuppressions can remove itself'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            @SuppressWarnings("for-rollout:RemoveRolloutSuppressions")
            public final class App {}
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=RemoveRolloutSuppressions')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {}
        '''.stripIndent(true)
    }

    def 'can patch checks while using -PerrorProneRemoveRollout, even if suppressed for rollout'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=ArrayToString', '-PerrorProneApply=ArrayToString')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            import java.util.Arrays;
            
            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
        '''.stripIndent(true)
    }

    def 'can patch checks while using -PerrorProneRemoveRollout, which also add annotations as fixes'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=ShouldBeNullable,ShouldBePrivate', '-PerrorProneApply=ShouldBeNullable,ShouldBePrivate')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)
    }

    def '-PerrorProneSuppress then -PerrorProneRemoveRollout does not add newlines'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;

            public final class App {
                private Object fixme() {
                    return null;
                }
            }
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress=ShouldBeNullable')
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=ShouldBeNullable')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            public final class App {
                private Object fixme() {
                    return null;
                }
            }
        '''.stripIndent(true)
    }


    def 'does not patch checks while using -PerrorProneRemoveRollout, if suppressed normally'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                @SuppressWarnings("ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=ArrayToString', '-PerrorProneApply=ArrayToString')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {
                @SuppressWarnings("ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)
    }

    def 'can patch checks while using -PerrorProneRemoveRollout, if not suppressed'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=ArrayToString', '-PerrorProneApply=ArrayToString')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            import java.util.Arrays;
            
            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
        '''.stripIndent(true)
    }

    def 'errorProneRemoveRollout does not patch unrelated checks'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=NullAway', '-PerrorProneApply=NullAway')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)
    }

    def 'errorProneRemoveRollout does not patch by itself'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=ArrayToString')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)
    }

    def 'errorProneRemoveRollout does not patch if specific check is not selected'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            @SuppressWarnings("for-rollout:Test")
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout=ArrayToString,Test', '-PerrorProneApply=Test')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;
            
            public final class App {
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)
    }

    def 'can patch specific checks even if errorProneRemoveRollout argument is empty'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout', '-PerrorProneApply=ArrayToString')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            import java.util.Arrays;
            
            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
        '''.stripIndent(true)
    }

    def 'can patch configured checks even if errorProneRemoveRollout argument is empty'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout', '-PerrorProneApply')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            import java.util.Arrays;
            
            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
                }
            }
        '''.stripIndent(true)
    }

    def 'RemoveRolloutSuppressions does not appear as a Note: [RemoveRolloutSuppressions] in unrelated errors'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            
            public final class App {
                @SuppressWarnings("for-rollout:NullAway")
                public static void method() {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        def output = runTasksWithFailure('compileAllErrorProne').output

        then:
        !output.contains('[RemoveRolloutSuppressions]')
    }

    def 'errorProneRemoveUnused removes only unused error-prone suppressions, and leaves unknown suppressions untouched'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;

            @SuppressWarnings({"ArrayToString", "UnnecessaryFinal", "InlineTrivialConstant", "NotAnErrorProne", "ShouldBePrivate", "checkstyle:Bla",})
            public final class App {
                private static final String EMPTY_STRING = "";
 
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            @SuppressWarnings({"ArrayToString", "InlineTrivialConstant", "NotAnErrorProne", "checkstyle:Bla"})
            public final class App {
                private static final String EMPTY_STRING = "";
 
                public static void main(String[] args) {
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused understands alt-names'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;

            public final class App {
                @SuppressWarnings("ShouldBePrivate")
                void fixme(String s) {}

                @SuppressWarnings("MustBePrivate")
                void fixme(Integer s) {}
                
                @SuppressWarnings("ShouldBePrivate")
                private void fixme(Float s) {}

                @SuppressWarnings("MustBePrivate")
                private void fixme(Character s) {}
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            public final class App {
                @SuppressWarnings("ShouldBePrivate")
                void fixme(String s) {}

                @SuppressWarnings("MustBePrivate")
                void fixme(Integer s) {}
                
                private void fixme(Float s) {}

                private void fixme(Character s) {}
            }
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused does not apply fixes'() {
        given:
        // language=Java
        def initialSource = '''
            package app;

            public final class App {
                class Inner {
                    class InnerInner {}
                }
            }
        '''.stripIndent(true)

        writeJavaSourceFileToSourceSets initialSource

        when: 'errorProneRemoveUnused is run by itself'
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused')

        then: 'No checks are applied'
        javaSourceIsSyntacticallyEqualTo initialSource

        when: 'ClassCanBeStatic is applied alongside errorProneRemoveUnused'
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused', '-PerrorProneApply=ClassCanBeStatic')

        then: 'ClassCanBeStatic is applied'
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
            package app;

            public final class App {
                static class Inner {
                    static class InnerInner {}
                }
            }
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused only keeps the closest suppression to a violation'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        def result = runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused')

        then:

        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused handles multiple suppressions on different tree types gracefully'() {
        // Here we test the three types of trees you can suppress — ClassTree, MethodTree, VariableTree

        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused')

        then:

        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused removes entire SuppressWarnings annotation when all suppressions are unused'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
        package app;

        @SuppressWarnings({"UnusedVariable", "ArrayToString"})
        public final class App {
            public static void main(String[] args) {
                System.out.println("No violations here");
            }
        }
    '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused')

        then:
        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
        package app;

        public final class App {
            public static void main(String[] args) {
                System.out.println("No violations here");
            }
        }
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused and errorProneSuppress uses existing suppressions if possible'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused', '-PerrorProneSuppress')

        then:

        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused and errorProneApply applies fixes on previously suppressed elements'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused', '-PerrorProneApply=ArrayEquals')

        then:

        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)
    }

    def 'errorProneRemoveUnused + errorProneApply + errorProneSuppress applies fixes and suppressions on previously suppressed elements'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveUnused', '-PerrorProneSuppress', '-PerrorProneApply=ArrayEquals')

        then:

        // language=Java
        javaSourceIsSyntacticallyEqualTo '''
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
        '''.stripIndent(true)
    }

    def 'error-prone dependencies have versions bound together by a virtual platform'() {
        setup: 'when an error-prone dependency is forced to certain version'
        // language=Gradle
        buildFile << '''
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
        '''.stripIndent(true)

        when:
        def output = runTasksSuccessfully('printErrorProneVersions').output

        then: 'every single error-prone dependency has the same version'
        output.contains('ERROR-PRONE: error_prone_annotation-2.3.4.jar')
        output.contains('ERROR-PRONE: error_prone_core-2.3.4.jar')
    }

    // Running CC with debuggingErrorPrones (see setup method above) causes this issue:
    // ERROR: transport error 202: connect failed: Connection refused
    // ERROR: JDWP Transport dt_socket failed to initialize, TRANSPORT_INIT(510)
    // JDWP exit error AGENT_ERROR_TRANSPORT_INIT(197): No transports initialized [src/jdk.jdwp.agent/share/native/libjdwp/debugInit.c:700]
    BuildResult runTasksSuccessfully(String... tasks) {
        def projectVersion = Optional.ofNullable(System.getProperty('projectVersion')).orElseThrow()
        String[] strings = tasks + ["-PsuppressibleErrorProneVersion=${projectVersion}".toString()]
        if (debuggingErrorPrones) {
            return super.runTasks(strings)
        } else {
            return super.runTasksWithConfigurationCache(strings)
        }
    }

    BuildResult runTasksWithFailure(String... tasks) {
        def projectVersion = Optional.ofNullable(System.getProperty('projectVersion')).orElseThrow()
        String[] strings = tasks + ["-PsuppressibleErrorProneVersion=${projectVersion}".toString()]
        if (debuggingErrorPrones) {
            return super.runTasksAndFail(strings)
        } else {
            return super.runTasksAndFailWithConfigurationCache(strings)
        }
    }

    void writeJavaSourceFileToSourceSets(String source) {
        super.writeJavaSourceFile(source, 'src/main/java')
        super.writeJavaSourceFile(source, 'src/other/java')
    }

    void javaSourceContains(String substring) {
        assert file('src/main/java/app/App.java').text.contains(substring)
        assert file('src/other/java/app/App.java').text.contains(substring)
    }

    void javaSourceDoesNotContain(String substring) {
        assert !file('src/main/java/app/App.java').text.contains(substring)
        assert !file('src/other/java/app/App.java').text.contains(substring)
    }

    // Normalizes Java source by trimming whitespace and applying consistent formatting.
    // Preserves newlines since the formatter allows them within methods, and we need
    // to test that error-prone doesn't introduce unwanted line breaks.
    private static String normalizeSource(String content) {
        String stripped = content.readLines()
                .collect { it.trim() }           // Remove leading/trailing whitespace
                .join('\n')

        return formatter.formatSource(stripped)
    }

    void javaSourceIsSyntacticallyEqualTo(String source) {
        def output = normalizeSource(file('src/main/java/app/App.java').text)
        def expected = normalizeSource(source)

        // Ensure test fixtures are properly formatted
        assert "\n" + expected == source, "Please update your text fixtures to be in palantir-java-format"
        assert output == expected

        def outputOther = normalizeSource(file('src/other/java/app/App.java').text)
        def expectedOther = normalizeSource(source)
        assert outputOther == expectedOther
    }

    void javaSourceIsSyntacticallyNotEqualTo(String source) {
        def output = normalizeSource(file('src/main/java/app/App.java').text)
        def expected = normalizeSource(source)

        // Ensure test fixtures are properly formatted
        assert "\n" + expected == source, "Please update your text fixtures to be in palantir-java-format"
        assert output != expected

        def outputOther = normalizeSource(file('src/other/java/app/App.java').text)
        def expectedOther = normalizeSource(source)
        assert outputOther != expectedOther
    }
}
