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

class AbstractSuppressibleErrorPronePluginIntegrationTest extends IntegrationSpec {
    // We need to put the source sets in a different directory that does not contain the any words that would hit
    // the errorprone excludedPathRegex, ie build in build/nebulatest
    static File nebulatestSourceSets = new File('nebulatestSourceSets/')

    File sourceSetRoot
    File mainSourceSet
    File otherSourceSet

    def setup() {
        sourceSetRoot = new File(nebulatestSourceSets, getClass().simpleName + '/' + projectDir.name)
        FileUtils.deleteDirectory(sourceSetRoot)

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
        writeJavaSourceFile(source, 'src/main/java', sourceSetRoot)
        writeJavaSourceFile(source, 'src/other/java', sourceSetRoot)
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
