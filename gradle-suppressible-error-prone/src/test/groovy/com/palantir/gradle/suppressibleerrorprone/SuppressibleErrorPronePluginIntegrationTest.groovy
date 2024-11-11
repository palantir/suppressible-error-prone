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
import org.apache.commons.io.FileUtils

class SuppressibleErrorPronePluginIntegrationTest extends IntegrationSpec {
    // We need to put the source sets in a different directory that does not contain the any words that would hit
    // the errorprone excludedPathRegex, ie build in build/nebulatest
    static File nebulatestSourceSets = new File('nebulatestSourceSets/' + SuppressibleErrorPronePluginIntegrationTest.class.simpleName)
    File sourceSetRoot
    File mainSourceSet
    File otherSourceSet

    def setupSpec() {
        FileUtils.deleteDirectory(nebulatestSourceSets)
    }

    def setup() {
        sourceSetRoot = new File(nebulatestSourceSets, projectDir.name)
        mainSourceSet = directory('src/main/java', sourceSetRoot)
        otherSourceSet = directory('src/other/java', sourceSetRoot)

        // language=Gradle
        buildFile << '''
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
            }
            
            tasks.withType(JavaCompile).configureEach {
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

        buildFile << """
            sourceSets.main.java.srcDirs('${projectDir.relativePath(mainSourceSet)}')
            sourceSets.other.java.srcDirs('${projectDir.relativePath(otherSourceSet)}')
        """.stripIndent(true)

        file('gradle.properties') << '''
            __TESTING=true
            __TESTING_CACHE_BUST_ERRORPRONE_TRANSFORM=true
        '''.stripIndent(true)
    }

    def 'reports a failing error prone'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        def stderr = runTasksWithFailure('compileAllErrorProne').standardError

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
                    System.out.println(new int[3].toString());
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
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)


        when:
        def sourceDir1 = new File(sourceSetRoot, '/src/generated')
        def sourceDir2 = new File(sourceSetRoot, '/build/somePlace')

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
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        runTasksSuccessfully('compileAllErrorProne')

        appJavaTextContains('Arrays.toString(new int[3])')
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
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        appJavaTextContains('new int[3].toString()')
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
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        // Doesn't actually do any patching as the set is empty. It just does a normal compile that fails.
        def stderr = runTasksWithFailure('compileAllErrorProne', '-PerrorProneApply').standardError

        then:
        stderr.contains('[ArrayToString]')
        appJavaTextContains('new int[3].toString()')
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
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        appJavaTextContains('new int[3].toString()')
    }

    def 'can patch specific checks using -PerrorProneApply'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                    System.out.println(new int[2].equals(new int[1]));
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply=ArrayToString,ArrayEquals')

        then:
        appJavaTextContains('Arrays.toString(new int[3])')
        appJavaTextContains('Arrays.equals(new int[2], new int[1])')
    }

    def 'can suppress a failing check (even if not in patchChecks set)'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage1')
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage2')

        then:
        runTasksSuccessfully('compileAllErrorProne')

        appJavaTextContains('@SuppressWarnings(\"for-rollout:ArrayToString\")')
    }

    def 'demonstrate suppressions on different source elements'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public final class App {
                public final String field = new int[3].toString();

                public App() {
                    System.out.println(new int[3].toString());
                }
                
                public void method() {
                    System.out.println(new int[3].toString());
                }

                public void variables() {
                    String variable = new int[3].toString();
                    System.out.println(variable);
                }
                
                public static class SomeClass {
                    static {
                        System.out.println(new int[3].toString());
                    }
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage1')
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage2')

        then:
        runTasksSuccessfully('compileAllErrorProne')

        // language=Java
        appJavaTextEquals '''
            package app;
            public final class App {
                @SuppressWarnings("for-rollout:ArrayToString")
                public final String field = new int[3].toString();
                
                @SuppressWarnings("for-rollout:ArrayToString")
                public App() {
                    System.out.println(new int[3].toString());
                }
                
                @SuppressWarnings("for-rollout:ArrayToString")
                public void method() {
                    System.out.println(new int[3].toString());
                }

                public void variables() {
                    @SuppressWarnings("for-rollout:ArrayToString")
                    String variable = new int[3].toString();
                    System.out.println(variable);
                }
                
                @SuppressWarnings("for-rollout:ArrayToString")
                public static class SomeClass {
                    static {
                        System.out.println(new int[3].toString());
                    }
                }
            }
        '''.stripIndent(true)
    }

    def 'supports errorprone checks that match on a larger element than they report errors on'() {
        // The UnusedVariable check implements CompilationUnitTreeMatcher, so will start with a whole
        // CompilationUnitTree and then narrows down to the specific variable declaration that is unused.
        // This trips up the "naive" suppression logic, which looks at where the visitor has got to rather
        // than where the diagnostic description was produced.

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
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage1')
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage2')

        then:
        runTasksSuccessfully('compileAllErrorProne')

        // language=Java
        appJavaTextEquals '''
            package app;
            public final class App {
                public void variables() {
                    @SuppressWarnings("for-rollout:UnusedVariable")
                    String variable;
                }
            }
        '''.stripIndent(true)
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
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage1')
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppressStage2')

        then:
        runTasksSuccessfully('compileAllErrorProne')

        // language=Java
        appJavaTextEquals '''
            package app;
            public final class App {
                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                static class exports {}
                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                interface opens {}
                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                record provides(int cat) {}
                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                enum to {;}
                @SuppressWarnings("for-rollout:NamedLikeContextualKeyword")
                @interface module {}
            }
        '''.stripIndent(true)
    }

    def 'can disable errorprone using property'() {
        when: 'there is some that will fail an errorprone'
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    System.out.println(new int[3].toString());
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
            }
            
            suppressibleErrorProne {
                patchChecks.add('ArrayToString')
            }
            
            println 'other src:' + sourceSets.other.allSource.srcDirs
        '''.stripIndent(true)

        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            public final class App {
                public static void main(String[] args) {
                    Character.isJavaLetter('c'); // deprecated method
                    System.out.println(new int[3].toString());
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        appJavaTextContains('Arrays.toString(new int[3])')
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
                    System.out.println(new int[3].toString());
                    System.out.println(new int[2].equals(new int[1]));
                }
            }
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')


        then:
        appJavaTextContains('Arrays.toString(new int[3])')
        appJavaTextContains('new int[2].equals(new int[1])')
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
                    System.out.println(new int[3].toString());
                    System.out.println(new int[2].equals(new int[1]));
                }
            }
        '''.stripIndent(true)
        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply')

        then:
        appJavaTextContains('Arrays.toString(new int[3])')
        appJavaTextContains('new int[2].equals(new int[1])')
    }

    def 'compileAllErrorProne only depends on compile tasks with errorprone enabled'() {
        // language=Gradle
        buildFile << '''
            tasks.named('compileTestJava').configure {
                options.errorprone.enabled = false
            }
        '''.stripIndent(true)

        when:
        def stdout = runTasksSuccessfully('compileAllErrorProne', '--dry-run').standardOutput

        then:
        stdout.contains(':compileJava SKIPPED')
        !stdout.contains(':compileTestJava SKIPPED')
        stdout.contains(':compileOtherJava SKIPPED')
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


    void writeJavaSourceFileToSourceSets(String source) {
        super.writeJavaSourceFile(source, 'src/main/java', sourceSetRoot)
        super.writeJavaSourceFile(source, 'src/other/java', sourceSetRoot)
    }

    void appJavaTextContains(String substring) {
        assert file('app/App.java', mainSourceSet).text.contains(substring)
        assert file('app/App.java', otherSourceSet).text.contains(substring)
    }

    void appJavaTextEquals(String substring) {
        assert file('app/App.java', mainSourceSet).text == substring
        assert file('app/App.java', otherSourceSet).text == substring
    }
}
