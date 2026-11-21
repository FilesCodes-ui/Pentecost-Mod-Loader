package khi.pentecost.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class OggSeekGuardTest {
    private static byte[] spinning() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Ogg", null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "setPositionRandomly", "(J)V", null, new String[]{"java/io/IOException"});
        m.visitCode();
        Label top = new Label();
        m.visitLabel(top);
        m.visitJumpInsn(Opcodes.GOTO, top);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void emptiesTheSeek() throws Exception {
        byte[] out = OggSeekGuard.patch(spinning());
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(out), getClass().getClassLoader(), false, new PrintWriter(problems));
        assertEquals("", problems.toString());
        Class<?> c = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("test.Ogg", out, 0, out.length);
            }
        }.define();
        Object o = c.getConstructor().newInstance();
        Method seek = c.getMethod("setPositionRandomly", long.class);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> seek.invoke(o, 12345L));
    }

    @Test
    void leavesOtherClassesAlone() {
        byte[] in = new byte[]{1, 2, 3};
        assertSame(in, OggSeekGuard.transformer().transform("some.Other", in));
    }
}
