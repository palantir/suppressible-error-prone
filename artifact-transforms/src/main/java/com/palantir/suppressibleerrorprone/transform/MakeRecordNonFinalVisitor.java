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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Removes the ACC_FINAL flag from a record class and its equals/hashCode methods to allow it to be extended.
 * This is used to make error-prone's Replacement record (which is final by default)
 * extensible so that LazySuppressionReplacement can extend it and override equals/hashCode.
 */
final class MakeRecordNonFinalVisitor extends ClassVisitor {
    MakeRecordNonFinalVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public void visit(
            int version, int access, String name, String signature, String superName, String[] interfaces) {
        // Remove ACC_FINAL flag from the class access modifiers
        int modifiedAccess = access & ~Opcodes.ACC_FINAL;
        super.visit(version, modifiedAccess, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        // Remove ACC_FINAL from equals() and hashCode() methods so they can be overridden
        if ((name.equals("equals") && descriptor.equals("(Ljava/lang/Object;)Z"))
                || (name.equals("hashCode") && descriptor.equals("()I"))) {
            int modifiedAccess = access & ~Opcodes.ACC_FINAL;
            return super.visitMethod(modifiedAccess, name, descriptor, signature, exceptions);
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }
}
