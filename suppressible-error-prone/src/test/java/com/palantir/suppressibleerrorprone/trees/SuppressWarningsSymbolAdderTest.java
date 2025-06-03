/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.suppressibleerrorprone.trees;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Attribute.Array;
import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Attribute.Constant;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuppressWarningsSymbolAdderTest {
    VisitorState visitorState;
    SuppressWarningsSymbolAdder symbolAdder;

    @BeforeEach
    void beforeEach() {
        Context context = new Context();
        JavacFileManager.preRegister(context);
        JavaCompiler compiler = JavaCompiler.instance(context);
        compiler.initModules(List.nil());

        // Initialize names
        Names.instance(context);
        visitorState = VisitorState.createForUtilityPurposes(context);
        symbolAdder = new SuppressWarningsSymbolAdder(visitorState);
    }

    @Test
    void empty_input_list() {
        assertThat(symbolAdder.addToSuppressWarnings(List.nil(), "foo"))
                .hasToString("@java.lang.SuppressWarnings({\"foo\"})");
    }

    @Test
    void add_to_existing_suppress_warnings() {
        assertThat(symbolAdder.addToSuppressWarnings(List.of(suppressWarnings("bar")), "foo"))
                .hasToString("@java.lang.SuppressWarnings({\"bar\", \"foo\"})");
    }

    @Test
    void add_to_existing_attributes_where_there_are_no_suppress_warnings() {
        assertThat(symbolAdder.addToSuppressWarnings(List.of(override()), "foo"))
                .hasToString("@java.lang.Override,@java.lang.SuppressWarnings({\"foo\"})");
    }

    @Test
    void add_to_existing_attributes_where_there_is_a_suppress_warnings() {
        assertThat(symbolAdder.addToSuppressWarnings(List.of(override(), suppressWarnings("bar"), deprecated()), "foo"))
                .hasToString(
                        "@java.lang.Override,@java.lang.Deprecated,@java.lang.SuppressWarnings({\"bar\", \"foo\"})");
    }

    private Compound override() {
        return simpleAttribute("java.lang.Override");
    }

    private Compound deprecated() {
        return simpleAttribute("java.lang.Deprecated");
    }

    private Compound simpleAttribute(String typeStr) {
        return new Compound(visitorState.getTypeFromString(typeStr), List.nil());
    }

    private Compound suppressWarnings(String suppression) {
        return new Compound(
                SuppressWarningsSymbolAdder.SUPPRESS_WARNINGS.get(visitorState),
                List.of(Pair.of(
                        SuppressWarningsSymbolAdder.SUPPRESS_WARNINGS_VALUE.get(visitorState),
                        new Array(
                                SuppressWarningsSymbolAdder.STRING_ARRAY_TYPE.get(visitorState),
                                List.of(new Constant(
                                        SuppressWarningsSymbolAdder.STRING_TYPE.get(visitorState), suppression))))));
    }
}
