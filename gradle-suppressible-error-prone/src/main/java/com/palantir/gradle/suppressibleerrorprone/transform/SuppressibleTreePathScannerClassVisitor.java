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

package com.palantir.gradle.suppressibleerrorprone.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class SuppressibleTreePathScannerClassVisitor extends ClassVisitor {
    SuppressibleTreePathScannerClassVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals("suppressed") && descriptor.equals("(Lcom/sun/source/tree/Tree;)Z")) {
            return new SuppressedMethodVisitor(methodVisitor);
        }

        return methodVisitor;
    }

    private static final class SuppressedMethodVisitor extends MethodVisitor {
        SuppressedMethodVisitor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // Load this (SuppressibleTreePathScanner instance)
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // Get the state field
            mv.visitFieldInsn(
                    Opcodes.GETFIELD,
                    "com/google/errorprone/bugpatterns/BugChecker$SuppressibleTreePathScanner",
                    "state",
                    "Lcom/google/errorprone/VisitorState;");
            // Check condition and potentially return false early
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/palantir/suppressibleerrorprone/SuppressibleTreePathScannerModifications",
                    "shouldBypassSuppressions",
                    "(Lcom/google/errorprone/VisitorState;)Z",
                    false);

            // If condition is true, return false immediately
            Label continueLabel = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, continueLabel);
            mv.visitInsn(Opcodes.ICONST_0); // push false
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(continueLabel);
            // Add a frame here to fix verification
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
    }
}
