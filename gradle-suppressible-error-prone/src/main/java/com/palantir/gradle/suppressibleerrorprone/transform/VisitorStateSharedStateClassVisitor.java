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

package com.palantir.gradle.suppressibleerrorprone.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * We want to intercept the error descriptions produced by errorprone checks that eventually go through `reportMatch`
 * in `VisitorState` (even if the checks use the `buildDescription` approach). This is so we can modify the error
 * descriptions to add our own fix. In the interests of minimising bytecode modifications, we just slot in a call
 * to a static method at the start of the `reportMatch` method that modifies the description and then sets the
 * description parameter to be the new value.
 */
final class VisitorStateSharedStateClassVisitor extends ClassVisitor {

    VisitorStateSharedStateClassVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public void visitEnd() {
        cv.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "suppressibleErrorProneTimings",
                "Lcom/palantir/suppressibleerrorprone/timings/SuppressibleErrorProneTimings;",
                null,
                null);

        super.visitEnd();
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

        if ("<init>".equals(name)) {
            return new AddFieldToConstructorMethodVisitor(methodVisitor);
        }
        return methodVisitor;
    }

    private class AddFieldToConstructorMethodVisitor extends MethodVisitor {
        AddFieldToConstructorMethodVisitor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitInsn(int opcode) {
            // Check for the return opcode of the constructor
            if (opcode == Opcodes.RETURN) {
                // Load 'this' onto the stack
                mv.visitVarInsn(Opcodes.ALOAD, 0);

                // Load the first argument (context) onto the stack
                mv.visitVarInsn(Opcodes.ALOAD, 1);

                // Call the static method 'SuppressibleErrorProneTimings.instance(context)'
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "com/palantir/suppressibleerrorprone/timings/SuppressibleErrorProneTimings",
                        "initOnVisitorStateSharedState",
                        "(Ljava/lang/Object;Lcom/sun/tools/javac/util/Context;)V",
                        false);
            }
            super.visitInsn(opcode);
        }
    }
}
