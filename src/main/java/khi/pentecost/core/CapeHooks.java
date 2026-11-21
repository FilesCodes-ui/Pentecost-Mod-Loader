package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class CapeHooks {
    public interface Impl {
        Object cape(Object player);
    }

    public static volatile Impl impl;

    private CapeHooks() {}

    public static Object cloak(Object original, Object player) {
        Impl i = impl;
        if (i == null) {
            return original;
        }
        try {
            Object mine = i.cape(player);
            return mine != null ? mine : original;
        } catch (RuntimeException | LinkageError e) {
            impl = null;
            khi.pentecost.boot.Log.error("Pentecost cape stopped and is off until restart: " + e);
            return original;
        }
    }

    public static boolean loaded(boolean original, Object player) {
        Impl i = impl;
        if (original || i == null) {
            return original;
        }
        try {
            return i.cape(player) != null;
        } catch (RuntimeException | LinkageError e) {
            return original;
        }
    }

    private static final String SELF = "khi/pentecost/core/CapeHooks";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.client.player.AbstractClientPlayer")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            int[] done = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (n.equals("getCloakTextureLocation") && d.equals("()Lnet/minecraft/resources/ResourceLocation;")) {
                        done[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "cloak",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/resources/ResourceLocation");
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(maxStack + 1, maxLocals);
                            }
                        };
                    }
                    if (n.equals("isCapeLoaded") && d.equals("()Z")) {
                        done[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.IRETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "loaded", "(ZLjava/lang/Object;)Z", false);
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(maxStack + 1, maxLocals);
                            }
                        };
                    }
                    return mv;
                }
            }, 0);
            if (done[0] != 2) {
                khi.pentecost.boot.Log.error("Pentecost cape: AbstractClientPlayer did not match (" + done[0] + "). No cape.");
                return bytes;
            }
            return writer.toByteArray();
        };
    }
}
