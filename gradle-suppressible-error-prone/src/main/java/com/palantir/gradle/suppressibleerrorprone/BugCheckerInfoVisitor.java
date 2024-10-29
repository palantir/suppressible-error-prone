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

package com.palantir.gradle.suppressibleerrorprone;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * We want to change some of the fields inside each BugCheckerInfo (primarily to add a name for each check
 * that has a "for-rollout:" prefix, so we can @SuppressWarnings("for-rollout:CheckName")). In the interests of
 * reducing the amount of asm modification we do, this will change the constructors that do not call other constructors
 * in this class to call a static method once construction has finished. This static method uses reflection to change
 * the fields in the way we need.
 */
final class BugCheckerInfoVisitor extends ClassVisitor {
    BugCheckerInfoVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (isConstructor(name)) {
            return new CallMethodAtEndOfConstructor(methodVisitor);
        }

        return methodVisitor;
    }

    private static final class CallMethodAtEndOfConstructor extends MethodVisitor {
        private boolean callsConstructorInThisClass = false;

        CallMethodAtEndOfConstructor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL
                    && "com/google/errorprone/BugCheckerInfo".equals(owner)
                    && isConstructor(name)) {
                callsConstructorInThisClass = true;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInsn(int opcode) {
            boolean insideConstructorThatDoesntCallOtherConstructor = !callsConstructorInThisClass;
            boolean constructorHasFinished = opcode == Opcodes.RETURN;

            if (insideConstructorThatDoesntCallOtherConstructor && constructorHasFinished) {
                // Load this
                mv.visitVarInsn(Opcodes.ALOAD, 0);

                // Modify the "this" instance using the below method
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "com/palantir/suppressibleerrorprone/BugCheckerInfoModifications",
                        "addAutomaticallyAddedPrefix",
                        "(Lcom/google/errorprone/BugCheckerInfo;)V",
                        false);
            }
            super.visitInsn(opcode);
        }
    }

    private static boolean isConstructor(String name) {
        return "<init>".equals(name);
    }
}
