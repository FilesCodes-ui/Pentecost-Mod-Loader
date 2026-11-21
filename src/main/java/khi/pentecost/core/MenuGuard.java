package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class MenuGuard {
    public interface Impl {
        void beforeRealLevel();

        Object music();
    }

    public static volatile Impl impl;

    private MenuGuard() {}

    public static void beforeRealLevel() {
        Impl i = impl;
        if (i != null) {
            try {
                i.beforeRealLevel();
            } catch (RuntimeException | LinkageError e) {
                Log.warn("Menu world could not step aside: " + e);
            }
        }
    }

    public static Object music() {
        Impl i = impl;
        try {
            return i == null ? null : i.music();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static final String SELF = "khi/pentecost/core/MenuGuard";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.client.Minecraft") || System.getenv("PENTECOST_NO_MENU_GUARD") != null) {
                return bytes;
            }
            int[] done = new int[1];
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    boolean level = (n.equals("setLevel") && d.equals("(Lnet/minecraft/client/multiplayer/ClientLevel;)V"))
                            || (n.equals("clearLevel") && d.equals("(Lnet/minecraft/client/gui/screens/Screen;)V"));
                    if (level) {
                        done[0] |= n.equals("setLevel") ? 1 : 2;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "beforeRealLevel", "()V", false);
                            }
                        };
                    }
                    if (n.equals("getSituationalMusic") && d.equals("()Lnet/minecraft/sounds/Music;")) {
                        done[0] |= 4;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label go = new Label();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "music", "()Ljava/lang/Object;", false);
                                super.visitInsn(Opcodes.DUP);
                                super.visitJumpInsn(Opcodes.IFNULL, go);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/sounds/Music");
                                super.visitInsn(Opcodes.ARETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"});
                                super.visitInsn(Opcodes.POP);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                            }
                        };
                    }
                    return mv;
                }
            }, 0);
            if (done[0] != 7) {
                Log.error("Menu guard: Minecraft did not match (" + done[0] + "). The menu world stays on the title screen only.");
                return bytes;
            }
            installed = true;
            return writer.toByteArray();
        };
    }

    public static volatile boolean installed;
}
