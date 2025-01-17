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

class CodeChangesTest extends AbstractSuppressibleErrorPronePluginIntegrationTest {
    // Change this to be the filename of the test (copy from the file viewer) to run a single test
     static String testOverride = 'AugmentExistingSuppresionsList.java'
//    static String testOverride = ''

    @Unroll
    def '#testFile'() {
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
        Parser parser = parserForType(type)
        parser.parseLines(testLines.subList(2, testLines.size()))

        writeJavaSourceFileToSourceSets parser.before().strip()

        when:
        runTasksSuccessfully(StreamEx.of('compileAllErrorProne').append(args).toArray(String))

        then:
        appJavaTextEquals parser.after().strip()

        runTasksSuccessfully('compileAllErrorProne')

        where:
        testFile << suppressionTestFileNames().toList()
    }

    def 'test override should not be set and then merged into develop'() {
        when:
        true

        then:
        testOverride == ''
    }

    private static Stream<String> suppressionTestFileNames() {
        if (testOverride != '') {
            return Stream.of(testOverride)
        }
        return Files.list(Path.of("src/test/resources/suppression-test")).map { it.fileName.toString() }.sorted()
    }

    private static Parser parserForType(String type) {
        switch (type) {
            case "SingleClass": return new SingleClassParser()
            case "Methods": return new MethodsParser()
        }

        throw new IllegalArgumentException("Unknown test type: " + type)
    }

    private abstract static class Parser {
        private Optional<BlockType> currentBlockType = Optional.empty()
        private final List<String> currentBlock = new ArrayList<>()

        protected abstract void beforeBlock(String block);
        protected abstract void afterBlock(String block);

        abstract String before();
        abstract String after();

        void parseLines(List<String> lines) {
            for (String line : lines) {
                if (line.startsWith("// Before:")) {
                    handleBlock()
                    currentBlockType = Optional.of(BlockType.BEFORE)
                } else if (line.startsWith("// After:")) {
                    handleBlock()
                    currentBlockType = Optional.of(BlockType.AFTER)
                } else {
                    currentBlock.add(line)
                }
            }

            handleBlock()
        }

        private void handleBlock() {
            def block = String.join("\n", this.currentBlock).strip()
            currentBlock.clear()

            if (currentBlockType.isEmpty()) {
                return
            }

            if (currentBlockType.get() == BlockType.BEFORE) {
                beforeBlock(block)
            } else {
                afterBlock(block)
            }
        }

        private static enum BlockType {
            BEFORE, AFTER
        }
    }

    private final static class SingleClassParser extends Parser {
        private String before
        private String after

        @Override
        void beforeBlock(String block) {
            if (before != null) {
                throw new IllegalStateException("Cannot have multiple before blocks")
            }
            before = block
        }

        @Override
        void afterBlock(String block) {
            if (after != null) {
                throw new IllegalStateException("Cannot have multiple  after blocks")
            }
            after = block
        }

        @Override
        String before() {
            return "package app;\n" + before
        }

        @Override
        String after() {
            return "package app;\n" + after
        }
    }

    private final static class MethodsParser extends Parser {
        private final List<String> befores = new ArrayList<>()
        private final List<String> afters = new ArrayList<>()

        @Override
        void beforeBlock(String block) {
            befores.add(block)
        }

        @Override
        void afterBlock(String block) {
            afters.add(block)
        }

        @Override
        String before() {
            fix(befores)
        }

        @Override
        String after() {
            fix(afters)
        }

        private String fix(List<String> methodBlocks) {
            String joinedTogetherAndIndented = StreamEx.of(befores)
                    .map { prefixLinesWith(' // ', it) }
                    .zipWith(
                            methodBlocks.stream().map { prefixLinesWith('    ', it) },
                            (before, block) -> " // Before:\n" + before + '\n // After:\n' + block)
                    .joining('\n\n\n')


            return String.join('\n',
                    'package app;',
                    'public final class App {',
                    joinedTogetherAndIndented,
                    '}')
        }

        private String prefixLinesWith(String prefix, String block) {
            return Splitter.on('\n').splitToStream(block)
                    .map { prefix + it }
                    .collect(Collectors.joining('\n'))
        }
    }
}
