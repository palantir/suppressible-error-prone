/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.suppressibleerrorprone;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Splitter;
import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.bugpatterns.BugChecker;

/**
 * {@link RefactoringValidator} delegates to a {@link BugCheckerRefactoringTestHelper}, but also validates the output
 * passes validation.
 */
final class RefactoringValidator {

    private final BugCheckerRefactoringTestHelper delegate;
    private final CompilationTestHelper compilationHelper;
    private String outputPath;
    private String[] outputLines;

    private RefactoringValidator(Class<? extends BugChecker> checkerClass, Class<?> clazz, String... args) {
        this.delegate =
                BugCheckerRefactoringTestHelper.newInstance(checkerClass, clazz).setArgs(args);
        this.compilationHelper =
                CompilationTestHelper.newInstance(checkerClass, clazz).setArgs(args);
    }

    @CheckReturnValue
    static RefactoringValidator of(Class<? extends BugChecker> checkerClass, Class<?> clazz, String... args) {
        return new RefactoringValidator(checkerClass, clazz, args);
    }

    @CheckReturnValue
    OutputStage addInput(String path, String input) {
        // If expectUnchanged is unused, the input is used as output
        this.outputPath = path;
        this.outputLines = Splitter.on('\n').splitToList(input).toArray(String[]::new);
        return new OutputStage(this, delegate.addInputLines(path, input));
    }

    static final class OutputStage {
        private final RefactoringValidator helper;
        private final BugCheckerRefactoringTestHelper.ExpectOutput delegate;

        private OutputStage(RefactoringValidator helper, BugCheckerRefactoringTestHelper.ExpectOutput delegate) {
            this.helper = helper;
            this.delegate = delegate;
        }

        @CheckReturnValue
        TestStage addOutput(String path, String output) {
            helper.outputPath = path;
            helper.outputLines = Splitter.on('\n').splitToList(output).toArray(String[]::new);
            return new TestStage(helper, delegate.addOutputLines(path, output));
        }

        @CheckReturnValue
        TestStage expectUnchanged() {
            return new TestStage(helper, delegate.expectUnchanged());
        }
    }

    static final class TestStage {

        private final RefactoringValidator helper;
        private final BugCheckerRefactoringTestHelper delegate;

        private TestStage(RefactoringValidator helper, BugCheckerRefactoringTestHelper delegate) {
            this.helper = helper;
            this.delegate = delegate;
        }

        void doTest() {
            delegate.doTest();
            helper.compilationHelper
                    .addSourceLines(helper.outputPath, helper.outputLines)
                    .matchAllDiagnostics()
                    .doTest();
        }

        void doTest(BugCheckerRefactoringTestHelper.TestMode testMode) {
            delegate.doTest(testMode);
            helper.compilationHelper
                    .addSourceLines(helper.outputPath, helper.outputLines)
                    .matchAllDiagnostics()
                    .doTest();
        }

        void doTestExpectingFailure(BugCheckerRefactoringTestHelper.TestMode testMode) {
            delegate.doTest(testMode);
            assertThatThrownBy(() -> helper.compilationHelper
                            .addSourceLines(helper.outputPath, helper.outputLines)
                            .doTest())
                    .describedAs("Expected the result to fail validation")
                    .isInstanceOf(AssertionError.class);
        }
    }
}
