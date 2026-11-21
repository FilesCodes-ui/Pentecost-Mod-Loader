package khi.pentecost.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunawaysTest {
    private static byte[] looping() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Spin", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "spin", "()V", null, null);
        m.visitCode();
        Label top = new Label();
        m.visitLabel(top);
        m.visitJumpInsn(Opcodes.GOTO, top);
        m.visitMaxs(0, 0);
        m.visitEnd();
        MethodVisitor o = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "once", "()I", null, null);
        o.visitCode();
        o.visitInsn(Opcodes.ICONST_0);
        o.visitVarInsn(Opcodes.ISTORE, 0);
        Label loop = new Label();
        o.visitLabel(loop);
        o.visitIincInsn(0, 1);
        o.visitVarInsn(Opcodes.ILOAD, 0);
        o.visitInsn(Opcodes.ICONST_3);
        o.visitJumpInsn(Opcodes.IF_ICMPLT, loop);
        o.visitVarInsn(Opcodes.ILOAD, 0);
        o.visitInsn(Opcodes.IRETURN);
        o.visitMaxs(0, 0);
        o.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Class<?> load(byte[] b) {
        return new ClassLoader(RunawaysTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass("test.Spin", b, 0, b.length);
            }
        }.define();
    }

    @Test
    void endlessLoopIsStopped() throws Exception {
        byte[] out = Runaways.guard("test.Spin", looping(), List.of("spin", "once"));
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(out), getClass().getClassLoader(), false, new PrintWriter(problems));
        assertEquals("", problems.toString());
        Class<?> c = load(out);
        assertEquals(3, c.getMethod("once").invoke(null));
        Method spin = c.getMethod("spin");
        InvocationTargetException e = assertTimeoutPreemptively(Duration.ofSeconds(9),
                () -> assertThrows(InvocationTargetException.class, () -> spin.invoke(null)));
        assertInstanceOf(Runaways.Stopped.class, e.getCause());
    }

    @Test
    void stuckLoopAndRealWorkLookDifferent() {
        StackTraceElement read = new StackTraceElement("java.io.BufferedInputStream", "fill", null, 291);
        StackTraceElement seek = new StackTraceElement("com.mojang.Ogg", "seek", null, 576);
        List<StackTraceElement[]> stuck = java.util.Collections.nCopies(8, new StackTraceElement[]{read, seek});
        assertEquals(1, Runaways.places(stuck));
        java.util.List<StackTraceElement[]> working = new java.util.ArrayList<>();
        StackTraceElement noise = new StackTraceElement("noise.Perlin", "getValue", null, 162);
        for (int i = 0; i < 8; i++) {
            working.add(new StackTraceElement[]{noise, noise, new StackTraceElement("noise.Density", "compute", null, 100 + i * 7), seek});
        }
        assertTrue(Runaways.places(working) > 2);
        assertTrue(Runaways.shared("Worker-Main-3"));
        assertFalse(Runaways.shared("DH-World Gen Thread[2]"));
    }

    @Test
    void loopMethodsAreTheSharedPath() {
        StackTraceElement[] a = {
                new StackTraceElement("java.io.BufferedInputStream", "fill", null, 1),
                new StackTraceElement("com.mojang.Ogg", "refill", null, 1),
                new StackTraceElement("com.mojang.Ogg", "seek", null, 1),
                new StackTraceElement("mod.Sound", "create", null, 1),
                new StackTraceElement("java.util.concurrent.ForkJoinWorkerThread", "run", null, 1)};
        StackTraceElement[] b = {
                new StackTraceElement("org.lwjgl.Stb", "decode", null, 1),
                new StackTraceElement("com.mojang.Ogg", "seek", null, 1),
                new StackTraceElement("mod.Sound", "create", null, 1),
                new StackTraceElement("java.util.concurrent.ForkJoinWorkerThread", "run", null, 1)};
        assertEquals(List.of("mod.Sound#create", "com.mojang.Ogg#seek"), Runaways.loopMethods(List.of(a, b)));
    }
}
