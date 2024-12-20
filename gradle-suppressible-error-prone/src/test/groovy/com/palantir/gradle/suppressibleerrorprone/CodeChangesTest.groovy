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

import com.google.common.base.Splitter
import one.util.streamex.StreamEx
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.stream.Stream

@Unroll
class CodeChangesTest extends AbstractSuppressibleErrorPronePluginIntegrationTest {
    def '#testDescription'() {
        def testText = new File('src/test/resources/suppression-test', testFile).text
        def testLines = Splitter.on('\n').splitToList(testText)

        def firstLine = testLines.get(0)
        if (!firstLine.startsWith('// Args: ')) {
            throw new IllegalArgumentException("First line of test file must start with '// Args: '")
        }

        def args = Splitter.on(' ')
                .omitEmptyStrings()
                .splitToList(firstLine.replace("// Args: ", ""))

        def secondLine = testLines.get(1)
        if (!secondLine.startsWith("// Type:")) {
            throw new IllegalArgumentException("Second line of test file must start with '// Type: '")
        }

        def type = secondLine.replace("// Type: ", "")

        def beforeLines = testLines.stream()
                .dropWhile { !it.startsWith('// Before:') }
                .skip(1)
                .takeWhile { !it.startsWith('// After:') }
                .collect(Collectors.joining('\n'))
                .strip()

        def afterLines = testLines.stream()
                .dropWhile { !it.startsWith('// After:') }
                .skip(1)
                .collect(Collectors.joining('\n'))
                .strip()

        writeJavaSourceFileToSourceSets beforeLines

        when:
        runTasksSuccessfully(StreamEx.of('compileAllErrorProne').append(args).toArray(String))

        then:
        runTasksSuccessfully('compileAllErrorProne')

        appJavaTextEquals afterLines


        where:
        testFile << suppressionTestFileNames().toList()
        testDescription << suppressionTestFileNames()
                .map { it.replaceAll('([A-Z])', ' $1').strip().toLowerCase().replace('.java', '') }
                .toList()
    }

    private static Stream<String> suppressionTestFileNames() {
        return Files.list(Path.of("src/test/resources/suppression-test")).map { it.fileName.toString() }
    }
}
