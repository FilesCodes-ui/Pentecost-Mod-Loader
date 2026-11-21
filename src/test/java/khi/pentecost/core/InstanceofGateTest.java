package khi.pentecost.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.jar.JarFile;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InstanceofGateTest {
    private static final String EVENT = "net/minecraftforge/event/entity/living/LivingEvent$LivingTickEvent";
    private static final String DESC = "(L" + EVENT + ";)V";

    private static byte[] handler(int checks, boolean sideEffectOutside) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Factory", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onEntityTick", DESC, null, null);
        mv.visitCode();
        Label end = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNULL, end);
        if (sideEffectOutside) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Other", "poke", "()V", false);
        }
        for (int i = 0; i < checks; i++) {
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;", false);
            mv.visitVarInsn(Opcodes.ASTORE, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, "test/Mob" + i);
            mv.visitJumpInsn(Opcodes.IFEQ, next);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Other", "poke", "()V", false);
            mv.visitLabel(next);
        }
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String m, String md, boolean itf) {
                        out.add(owner + "." + m);
                    }
                };
            }
        }, 0);
        return out;
    }

    @Test
    void adds_the_gate_to_a_long_chain() {
        byte[] out = InstanceofGate.rewrite("test.Factory", handler(40, false));
        assertTrue(calls(out).contains("khi/pentecost/core/InstanceofGate.skip"));
        new ClassReader(out);
    }

    @Test
    void leaves_a_short_chain_alone() {
        byte[] in = handler(5, false);
        assertSame(in, InstanceofGate.rewrite("test.Factory", in));
    }

    @Test
    void leaves_a_handler_with_work_outside_the_checks_alone() {
        byte[] in = handler(40, true);
        assertSame(in, InstanceofGate.rewrite("test.Factory", in));
    }

    @Test
    void skips_only_types_no_check_can_match() {
        int site = InstanceofGate.register(Set.of("java.lang.CharSequence", "java.lang.Integer"));
        assertTrue(InstanceofGate.skip(new Object(), site));
        assertFalse(InstanceofGate.skip("text", site));
        assertFalse(InstanceofGate.skip(5, site));
        assertFalse(InstanceofGate.skip(null, site));
        assertTrue(InstanceofGate.skip(2.5, site));
    }

    @Test
    void patches_the_real_handler_when_the_mod_is_present() throws Exception {
        Path jar = Path.of("mods", "management_wanted-1.3.3-forge-1.20.1.jar");
        Assumptions.assumeTrue(jar.toFile().isFile());
        byte[] in;
        try (JarFile jf = new JarFile(jar.toFile());
             InputStream s = jf.getInputStream(jf.getEntry("net/ovdrstudios/mw/init/EntityAnimationFactory.class"))) {
            in = s.readAllBytes();
        }
        byte[] out = InstanceofGate.rewrite("net.ovdrstudios.mw.init.EntityAnimationFactory", in);
        assertNotSame(in, out);
        assertTrue(calls(out).contains("khi/pentecost/core/InstanceofGate.skip"));
    }
}
