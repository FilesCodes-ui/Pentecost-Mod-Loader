package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.BooleanSupplier;

public final class PortalShadow {
    public static volatile BooleanSupplier check;
    public static int skipped;
    public static int total;

    private static final String IRIS = "net.irisshaders.iris.shadows.ShadowRenderer";
    private static final String IRIS_OLD = "net.coderbot.iris.pipeline.ShadowRenderer";
    private static final String IRIS_OLD_SHADOWS = "net.coderbot.iris.shadows.ShadowRenderer";

    private PortalShadow() {}

    public static boolean skip() {
        total++;
        BooleanSupplier c = check;
        if (c == null) {
            return false;
        }
        try {
            if (c.getAsBoolean()) {
                skipped++;
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            check = null;
            khi.pentecost.boot.Log.warn("Portal shadow skip switched off after an error: " + e);
        }
        return false;
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (System.getenv("PENTECOST_NO_PORTAL_SHADOW") != null) {
                return bytes;
            }
            if (!IRIS.equals(name) && !IRIS_OLD.equals(name) && !IRIS_OLD_SHADOWS.equals(name)) {
                return bytes;
            }
            boolean[] done = new boolean[1];
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("renderShadows") || !d.endsWith(")V") || (access & Opcodes.ACC_STATIC) != 0) {
                        return mv;
                    }
                    done[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label go = new Label();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/PortalShadow", "skip", "()Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, go);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(go);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                        }
                    };
                }
            }, 0);
            if (!done[0]) {
                khi.pentecost.boot.Log.error("Portal shadow skip: ShadowRenderer.renderShadows was not found. Shadows are drawn as always.");
                return bytes;
            }
            return writer.toByteArray();
        };
    }
}
