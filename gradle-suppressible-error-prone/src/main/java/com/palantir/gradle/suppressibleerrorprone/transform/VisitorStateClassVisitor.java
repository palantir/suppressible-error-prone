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

import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

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
final class VisitorStateClassVisitor extends ClassVisitor {
    private final boolean changeReportMatch;
    private final boolean disableTimingSpan;

    VisitorStateClassVisitor(ClassVisitor classVisitor, boolean changeReportMatch, boolean disableTimingSpan) {
        super(Opcodes.ASM9, classVisitor);
        this.changeReportMatch = changeReportMatch;
        this.disableTimingSpan = disableTimingSpan;
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals("reportMatch") && changeReportMatch) {
            return new ReportMatchMethodVisitor(methodVisitor);
        }

        if (name.equals("timingSpan")) {
            if (disableTimingSpan) {
                return new DisableTimingSpanMethodVisitor(methodVisitor);
            } else {
                return new RedirectTimingSpanMethodVisitor(methodVisitor);
            }
        }

        return methodVisitor;
    }

    private static final class ReportMatchMethodVisitor extends MethodVisitor {
        ReportMatchMethodVisitor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // Load this aka VisitorState
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // Load the first argument aka the Description
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            // Modify the description using the method below, giving the method the parameters loaded above.
            // Result is on the stack.
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/palantir/suppressibleerrorprone/VisitorStateModifications",
                    "interceptDescription",
                    "(Lcom/google/errorprone/VisitorState;Lcom/google/errorprone/matchers/Description;)"
                            + "Lcom/google/errorprone/matchers/Description;",
                    false);
            // Move modified result from the stack back into the description parameter variable
            mv.visitVarInsn(Opcodes.ASTORE, 1);
        }
    }

    private static class RedirectTimingSpanMethodVisitor extends MethodVisitor {
        RedirectTimingSpanMethodVisitor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitCode() {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(
                    GETFIELD,
                    "com/google/errorprone/VisitorState",
                    "sharedState",
                    "Lcom/google/errorprone/VisitorState$SharedState;");
            mv.visitFieldInsn(
                    GETFIELD,
                    "com/google/errorprone/VisitorState$SharedState",
                    "suppressibleErrorProneTimings",
                    "Lcom/palantir/suppressibleerrorprone/timings/SuppressibleErrorProneTimings;");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "com/palantir/suppressibleerrorprone/timings/SuppressibleErrorProneTimings",
                    "span",
                    "(Lcom/google/errorprone/VisitorState;Lcom/google/errorprone/matchers/Suppressible;)"
                            + "Ljava/lang/AutoCloseable;",
                    false);
            mv.visitInsn(Opcodes.ARETURN);
        }
    }

    private static class DisableTimingSpanMethodVisitor extends MethodVisitor {
        DisableTimingSpanMethodVisitor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitCode() {
            mv.visitFieldInsn(
                    GETSTATIC,
                    "com/palantir/suppressibleerrorprone/timings/NoopAutoCloseable",
                    "INSTANCE",
                    "Ljava/lang/AutoCloseable;");
            mv.visitInsn(Opcodes.ARETURN);
        }
    }
}
