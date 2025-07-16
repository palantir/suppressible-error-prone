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

package com.palantir.gradle.suppressibleerrorprone.flags.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palantir.gradle.suppressibleerrorprone.flags.common.ModifyCheckApiOption.DoNotModify;
import com.palantir.gradle.suppressibleerrorprone.flags.common.ModifyCheckApiOption.MustModify;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModifyCheckApiOptionTest {
    @Test
    void combining_must_modify_instances_with_different_visitor_states_results_in_true() {
        MustModify withoutVisitorState = new MustModify(false);
        MustModify withVisitorState = new MustModify(true);

        assertThat(withoutVisitorState.combine(withVisitorState).modifyVisitorState())
                .isTrue();
        assertThat(withVisitorState.combine(withoutVisitorState).modifyVisitorState())
                .isTrue();
    }

    @Test
    void combining_must_modify_instances_with_same_visitor_states_preserves_state() {
        MustModify bothFalse1 = new MustModify(false);
        MustModify bothFalse2 = new MustModify(false);
        assertThat(bothFalse1.combine(bothFalse2).modifyVisitorState()).isFalse();

        MustModify bothTrue1 = new MustModify(true);
        MustModify bothTrue2 = new MustModify(true);
        assertThat(bothTrue1.combine(bothTrue2).modifyVisitorState()).isTrue();
    }

    @Test
    void combining_empty_collection_returns_must_modify() {
        assertThat(ModifyCheckApiOption.combine(List.of())).isEqualTo(new MustModify(false));
    }

    @Test
    void combining_only_dont_care_returns_must_modify() {
        assertThat(ModifyCheckApiOption.combine(
                        List.of(ModifyCheckApiOption.dontCare(), ModifyCheckApiOption.dontCare())))
                .isEqualTo(new MustModify(false));
    }

    @Test
    void combining_do_not_modify_with_dont_care_returns_do_not_modify() {

        assertThat(ModifyCheckApiOption.combine(
                        List.of(ModifyCheckApiOption.doNotModify(), ModifyCheckApiOption.dontCare())))
                .isEqualTo(DoNotModify.INSTANCE);
    }

    @Test
    void combining_must_modify_with_dont_care_causes_no_change() {
        assertThat(ModifyCheckApiOption.combine(
                        List.of(ModifyCheckApiOption.mustModify(), ModifyCheckApiOption.dontCare())))
                .isEqualTo(new MustModify(false));
    }

    @Test
    void combining_must_modify_including_visitor_state_with_dont_care_causes_no_change() {
        assertThat(ModifyCheckApiOption.combine(List.of(
                        ModifyCheckApiOption.mustModifyIncludingVisitorState(), ModifyCheckApiOption.dontCare())))
                .isEqualTo(new MustModify(true));
    }

    @Test
    void combining_multiple_must_modify_options_combines_visitor_states() {
        assertThat(ModifyCheckApiOption.combine(List.of(
                        ModifyCheckApiOption.mustModify(),
                        ModifyCheckApiOption.mustModifyIncludingVisitorState(),
                        ModifyCheckApiOption.dontCare())))
                .isEqualTo(new MustModify(true));
    }

    @Test
    void combining_do_not_modify_with_must_modify_throws_exception() {
        assertThatThrownBy(() -> ModifyCheckApiOption.combine(
                        List.of(ModifyCheckApiOption.doNotModify(), ModifyCheckApiOption.mustModify())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot have both do not modify and must modify");
    }
}
