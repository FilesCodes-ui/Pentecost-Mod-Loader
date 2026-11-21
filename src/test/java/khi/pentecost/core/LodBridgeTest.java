package khi.pentecost.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LodBridgeTest {
    private static final String[] HOOK = {"test.Wrapper", "isServerThreadHealthy", "()Z", "getServerTickTimesNano"};

    private static byte[] wrapper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Wrapper", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "times", "[J", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor t = cw.visitMethod(Opcodes.ACC_PRIVATE, "getServerTickTimesNano", "()[J", null, null);
        t.visitCode();
        t.visitFieldInsn(Opcodes.GETSTATIC, "test/Wrapper", "times", "[J");
        t.visitInsn(Opcodes.ARETURN);
        t.visitMaxs(0, 0);
        t.visitEnd();
        MethodVisitor h = cw.visitMethod(Opcodes.ACC_PUBLIC, "isServerThreadHealthy", "()Z", null, null);
        h.visitCode();
        h.visitInsn(Opcodes.ICONST_0);
        h.visitInsn(Opcodes.IRETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void healthComesFromPentecost() throws Exception {
        byte[] out = LodBridge.patch("test.Wrapper", wrapper(), HOOK);
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(out), getClass().getClassLoader(), false, new PrintWriter(problems));
        assertEquals("", problems.toString());
        Class<?> c = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("test.Wrapper", out, 0, out.length);
            }
        }.define();
        Object w = c.getConstructor().newInstance();
        long[] ticks = new long[100];
        Arrays.fill(ticks, 10_000_000L);
        ticks[0] = 120_000_000L;
        c.getField("times").set(null, ticks);
        assertTrue((Boolean) c.getMethod("isServerThreadHealthy").invoke(w));
        Arrays.fill(ticks, 60_000_000L);
        assertFalse((Boolean) c.getMethod("isServerThreadHealthy").invoke(w));
    }
}
