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
import org.apache.commons.io.FileUtils
import org.gradle.testkit.runner.BuildResult
import spock.lang.Unroll

class SuppressibleErrorPronePluginIntegrationTest extends ConfigurationCacheSpec {
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
        // To debug the tests in this file
        // 1. Run the "Debug errorprones" debug configuration, which ships with this repository
        // 2. Go to the test you're looking for and run debug on it as well, but without any special configurations

        // An implementation detail: this is already set in IntegrationTestKitSpec, but only at runner creation time,
        // which is too late! We set it early here
        debug = isJwdpLoaded()
        sourceSetRoot = new File(nebulatestSourceSets, projectDir.name)
        mainSourceSet = directory('src/main/java', sourceSetRoot)
        otherSourceSet = directory('src/other/java', sourceSetRoot)

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
            }
            
            
            suppressibleErrorProne {
                configureEachErrorProneOptions {
                    // These interfere with some tests, so disable them
                    // TODO(callumr): Rewrite the tests to use custom testing error-prones rather than built in checks
                    //                to make upgrading error-prone easier.
                    disable('Varifier', 'ReturnValueIgnored', 'UnusedVariable', 'IdentifierName')
                    ignoreUnknownCheckNames = true
                }
            }
        '''.stripIndent(true)

        buildFile << """
            sourceSets.main.java.srcDirs('${projectDir.relativePath(mainSourceSet)}')
            sourceSets.other.java.srcDirs('${projectDir.relativePath(otherSourceSet)}')
        """.stripIndent(true)

        if (debug) {
            // language=Gradle
            buildFile << '''
                apply plugin: 'com.palantir.jdwp-remote-debug'
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
                    new int[3].toString();
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
                    new int[3].toString();
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
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        // Doesn't actually do any patching as the set is empty. It just does a normal compile that fails.
        def stderr = runTasksWithFailure('compileAllErrorProne', '-PerrorProneApply').output

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
                    new int[3].toString();
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
                    new int[3].toString();
                    new int[2].equals(new int[1]);
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
                    new int[3].toString();
                }
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneSuppress')

        then:
        appJavaTextContains('@SuppressWarnings(\"for-rollout:ArrayToString\")')


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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneApply', '-PerrorProneSuppress')

        then:
        // language=Java
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
                    new int[3].toString();
                    new int[2].equals(new int[1]);
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
                    new int[3].toString();
                    new int[2].equals(new int[1]);
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
        appJavaTextNotContains("SuppressWarnings")
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextNotEquals originalSource

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
        appJavaTextEquals '''
            package app;
            public final class App {}
        '''.stripIndent(true)
    }

    // This test also verifies we're properly passing the arguments to the errorprone plugin
    def 'supports removing all error prone suppressions'() {
        // language=Java
        writeJavaSourceFileToSourceSets '''
            package app;
            @SuppressWarnings("for-rollout:Test")
            public final class App {}
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('compileAllErrorProne', '-PerrorProneRemoveRollout')

        then:
        // language=Java
        appJavaTextEquals '''
            package app;
            public final class App {}
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
            package app;

            import java.util.Arrays;
            public final class App {
                public static void main(String[] args) {
                    Arrays.toString(new int[3]);
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        appJavaTextEquals '''
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
        if (debug) {
            return super.runTasks(strings)
        } else {
            return super.runTasksWithConfigurationCache(strings)
        }
    }

    BuildResult runTasksWithFailure(String... tasks) {
        def projectVersion = Optional.ofNullable(System.getProperty('projectVersion')).orElseThrow()
        String[] strings = tasks + ["-PsuppressibleErrorProneVersion=${projectVersion}".toString()]
        if (debug) {
            return super.runTasksAndFail(strings)
        } else {
            return super.runTasksAndFailWithConfigurationCache(strings)
        }
    }

    void writeJavaSourceFileToSourceSets(String source) {
        super.writeJavaSourceFile(source, 'src/main/java', sourceSetRoot)
        super.writeJavaSourceFile(source, 'src/other/java', sourceSetRoot)
    }

    void appJavaTextContains(String substring) {
        assert file('app/App.java', mainSourceSet).text.contains(substring)
        assert file('app/App.java', otherSourceSet).text.contains(substring)
    }

    void appJavaTextNotContains(String substring) {
        assert !file('app/App.java', mainSourceSet).text.contains(substring)
        assert !file('app/App.java', otherSourceSet).text.contains(substring)
    }

    void appJavaTextEquals(String source) {
        assert file('app/App.java', mainSourceSet).text == source
        assert file('app/App.java', otherSourceSet).text == source
    }

    void appJavaTextNotEquals(String source) {
        assert file('app/App.java', mainSourceSet).text != source
        assert file('app/App.java', otherSourceSet).text != source
    }
}
