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

package com.palantir.suppressibleerrorprone.transform;

public enum ModifiedFile {
    BUG_CHECKER_INFO("BugCheckerInfo"),
    VISITOR_STATE("VisitorState"),
    SUPPRESSIBLE_TREE_PATH_SCANNER("SuppressibleTreePathScanner"),
    REPLACEMENT("Replacement");

    private final String className;

    ModifiedFile(String className) {
        this.className = className;
    }

    public String getClassName() {
        return className;
    }
}
