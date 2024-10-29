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

import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult

class SuppressibleErrorPronePluginIntegrationTest extends IntegrationSpec {
    File appJava

    def setup() {
        // language=Gradle
        buildFile << '''
            apply plugin: 'com.palantir.suppressible-error-prone'
            apply plugin: 'java'
            
            repositories {
                mavenCentral()
                mavenLocal()
            }
            
            dependencies {
                errorprone 'com.google.errorprone:error_prone_core:2.31.0'
            }
            
            tasks.withType(JavaCompile).configureEach {
                // This makes debugging the errorprone running inside the compiler "just work" from inside these tests
                // Change this to true to enable it (after setting up the standalone debugger)
                boolean debuggingErrorPrones = false
                if (debuggingErrorPrones) {
                    it.options.forkOptions.jvmArgumentProviders.add(new CommandLineArgumentProvider() {
                        @Override
                        public Iterable<String> asArguments() {
                            return List.of("-agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5005")
                        }
                    })
                }
            }
        '''.stripIndent(true)

        appJava = file('src/main/java/app/App.java')
    }

    def 'reports a failing error prone'() {
        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        def stderr = runTasksWithFailure('compileJava').standardError

        then:
        stderr.contains('[ArrayToString]')
    }

    def 'can suppress an error prone with for-rollout prefix'() {
        // This test is explicitly checking we suppress the for-rollout prefix as that is what exists
        // in people's codebases

        when:
        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        then:
        runTasksSuccessfully('compileJava')
    }

    def 'ensure warnings are disabled in generated code'() {
        // language=Java
        def erroringCode = '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        writeJavaSourceFile(erroringCode, 'src/main/generated')
        writeJavaSourceFile(erroringCode.replace('App', 'App2'), 'build/somePlace')

        // language=Gradle
        buildFile << '''
            sourceSets.main.java.srcDirs('src/main/generated', 'build/somePlace')
        '''.stripIndent(true)

        then:
        runTasksSuccessfully('compileJava')
    }

    def 'can apply patches for a check if added to the patchChecks list'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileJava', '-PerrorProneApply')

        then:
        runTasksSuccessfully('compileJava')

        appJava.text.contains('Arrays.toString(new int[3])')
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
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileJava', '-PerrorProneApply')

        then:
        appJava.text.contains('new int[3].toString()')
    }

    def 'does not apply patches if there is nothing in patchChecks set'() {
        // language=Gradle
        buildFile << '''
            suppressibleErrorProne {
                patchChecks.empty()
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        // Doesn't actually do any patching as the set is empty. It just does a normal compile that fails.
        def stderr = runTasksWithFailure('compileJava', '-PerrorProneApply').standardError

        then:
        stderr.contains('[ArrayToString]')
        appJava.text.contains('new int[3].toString()')
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
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileJava', '-PerrorProneApply')

        then:
        appJava.text.contains('new int[3].toString()')
    }

    def 'can patch specific checks using -PerrorProneApply'() {
        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                    System.out.println(new int[2].equals(new int[1]));
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileJava', '-PerrorProneApply=ArrayToString,ArrayEquals')

        then:
        def patchedSource = appJava.text
        patchedSource.contains('Arrays.toString(new int[3])')
        patchedSource.contains('Arrays.equals(new int[2], new int[1])')
    }

    def 'can suppress a failing check (even if not in patchChecks set)'() {
        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileJava', '-PerrorProneSuppressStage1')
        runTasksSuccessfully('compileJava', '-PerrorProneSuppressStage2')

        then:
        runTasksSuccessfully('compileJava')

        appJava.text.contains('@SuppressWarnings(\"for-rollout:ArrayToString\")')
    }

    def 'can disable errorprone using property'() {
        when:
        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        then:
        runTasksSuccessfully('compileJava', '-PerrorProneDisable')
        runTasksSuccessfully('compileJava', '-Pcom.palantir.baseline-error-prone.disable')
    }

    def 'should be able to refactor near usages of deprecated methods'() {
        // language=Gradle
        buildFile << '''
            tasks.withType(JavaCompile) {
                options.compilerArgs += ['-Werror', '-Xlint:deprecation']
            }
            
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    Character.isJavaLetter('c'); // deprecated method
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileJava', '-PerrorProneApply')

        then:
        appJava.text.contains('Arrays.toString(new int[3])')
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
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                    System.out.println(new int[2].equals(new int[1]));
                }
            }
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileJava', '-PerrorProneApply')


        then:
        def patchedText = appJava.text
        patchedText.contains('Arrays.toString(new int[3])')
        patchedText.contains('new int[2].equals(new int[1])')
    }

    def 'IfModuleIsUsed works properly'() {
        // language=Gradle
        buildFile << '''
            import com.palantir.gradle.suppressibleerrorprone.ConditionalPatchCheck
            import com.palantir.gradle.suppressibleerrorprone.IfModuleIsUsed

            suppressibleErrorProne {
                conditionalPatchChecks.add(new ConditionalPatchCheck(new IfModuleIsUsed('com.fasterxml.jackson.core', 'jackson-core'), 'ArrayToString'))
                conditionalPatchChecks.add(new ConditionalPatchCheck(new IfModuleIsUsed('donesnt', 'exist'), 'ArrayEquals'))
            }
            
            dependencies {
                // Depends on jackson-core
                implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.1'
            }
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFile '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                    System.out.println(new int[2].equals(new int[1]));
                }
            }
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileJava', '-PerrorProneApply')

        then:
        def patchedText = appJava.text
        patchedText.contains('Arrays.toString(new int[3])')
        patchedText.contains('new int[2].equals(new int[1])')
    }

    @Override
    ExecutionResult runTasksSuccessfully(String... tasks) {
        def result = runTasks(tasks)
        println result.standardError
        println result.standardOutput
        result.rethrowFailure()
    }

    @Override
    ExecutionResult runTasks(String... tasks) {
        def projectVersion = Optional.ofNullable(System.getProperty('projectVersion')).orElseThrow()
        String[] strings = tasks + ["-PsuppressibleErrorProneVersion=${projectVersion}".toString()]
        return super.runTasks(strings)
    }
}