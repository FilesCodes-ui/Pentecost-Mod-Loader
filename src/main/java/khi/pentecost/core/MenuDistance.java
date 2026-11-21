package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class MenuDistance {
    public static volatile int chunks;

    private MenuDistance() {}

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.client.Options")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if ((access & Opcodes.ACC_STATIC) == 0 && n.equals("getEffectiveRenderDistance") && d.equals("()I")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label own = new Label();
                                super.visitFieldInsn(Opcodes.GETSTATIC, "khi/pentecost/core/MenuDistance", "chunks", "I");
                                super.visitJumpInsn(Opcodes.IFLE, own);
                                super.visitFieldInsn(Opcodes.GETSTATIC, "khi/pentecost/core/MenuDistance", "chunks", "I");
                                super.visitInsn(Opcodes.IRETURN);
                                super.visitLabel(own);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    return mv;
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
