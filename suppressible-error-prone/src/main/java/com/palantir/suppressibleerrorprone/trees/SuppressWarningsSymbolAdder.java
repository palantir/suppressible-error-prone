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

import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Attribute.Array;
import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Pair;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class SuppressWarningsSymbolAdder {
    static final Supplier<Type> STRING_TYPE = Suppliers.typeFromString("java.lang.String");
    static final Supplier<Type> STRING_ARRAY_TYPE = Suppliers.arrayOf(STRING_TYPE);
    static final Supplier<Type> SUPPRESS_WARNINGS = Suppliers.typeFromString("java.lang.SuppressWarnings");
    static final Supplier<MethodSymbol> SUPPRESS_WARNINGS_VALUE =
            VisitorState.memoize(state -> (MethodSymbol) SUPPRESS_WARNINGS
                    .get(state)
                    .tsym
                    .members()
                    .getSymbolsByName(state.getName("value"))
                    .iterator()
                    .next());

    private final VisitorState visitorState;

    SuppressWarningsSymbolAdder(VisitorState visitorState) {
        this.visitorState = visitorState;
    }

    public List<Attribute.Compound> addToSuppressWarnings(
            List<Attribute.Compound> originalAttributes, String suppressionName) {
        Type suppressWarnings = SUPPRESS_WARNINGS.get(visitorState);

        Map<Boolean, List<Compound>> suppressWarningsOrNotAttributes = originalAttributes.stream()
                .collect(Collectors.groupingBy(
                        compound -> visitorState.getTypes().isSameType(compound.type, suppressWarnings),
                        List.collector()));

        List<Attribute.Compound> suppressWarningsAttributes =
                Optional.ofNullable(suppressWarningsOrNotAttributes.get(true)).orElseGet(List::nil);

        List<Attribute.Compound> otherAttributes =
                Optional.ofNullable(suppressWarningsOrNotAttributes.get(false)).orElseGet(List::nil);

        List<Attribute> existingSuppressWarningsValues = suppressWarningsAttributes.stream()
                .map(attribute -> attribute.member(
                        SUPPRESS_WARNINGS_VALUE.get(visitorState).getQualifiedName()))
                .filter(Array.class::isInstance)
                .flatMap(arrayMember -> Arrays.stream(((Array) arrayMember).values))
                .collect(List.collector());

        Attribute.Compound newSuppressWarnings = new Compound(
                suppressWarnings,
                List.of(Pair.of(
                        SUPPRESS_WARNINGS_VALUE.get(visitorState),
                        new Attribute.Array(
                                STRING_ARRAY_TYPE.get(visitorState),
                                existingSuppressWarningsValues.append(
                                        new Attribute.Constant(STRING_TYPE.get(visitorState), suppressionName))))));

        return otherAttributes.append(newSuppressWarnings);
    }
}
