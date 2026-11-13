package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Two small changes to the game for the zoom key: the field of view is divided by the zoom factor,
 * and the mouse wheel changes the zoom level while the key is held instead of scrolling the hotbar.
 * The key itself, the smoothing and the settings live in khi.pentecost.game.Zoom.
 */
public final class ZoomHooks {
    /** How far the view is zoomed in right now. 1 means not zoomed. Set every frame. */
    public static volatile double factor = 1.0;
    /** True while the zoom key is held with no screen open. */
    public static volatile boolean holding;
    /** How far the view zooms while the key is held. The mouse wheel changes it. */
    public static volatile double level = 4.0;

    private ZoomHooks() {}

    /** Called by the game when it works out the field of view. */
    public static double fov(double fov, boolean useFovSetting) {
        double f = factor;
        return useFovSetting && f > 1.0001 ? fov / f : fov;
    }

    /** Called by the game for every wheel turn. Returns 0 when the zoom used it. */
    public static double scroll(double amount) {
        if (holding && amount != 0.0) {
            level = Math.max(1.5, Math.min(40.0, level * Math.pow(1.2, amount)));
            return 0.0;
        }
        return amount;
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            boolean renderer = name.equals("net.minecraft.client.renderer.GameRenderer");
            boolean mouse = name.equals("net.minecraft.client.MouseHandler");
            if (!renderer && !mouse) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (renderer && n.equals("getFov") && d.equals("(Lnet/minecraft/client/Camera;FZ)D")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.DRETURN) {
                                    super.visitVarInsn(Opcodes.ILOAD, 3);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ZoomHooks", "fov",
                                            "(DZ)D", false);
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(maxStack + 1, maxLocals);
                            }
                        };
                    }
                    if (mouse && n.equals("onScroll") && d.equals("(JDD)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.DLOAD, 5);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ZoomHooks", "scroll",
                                        "(D)D", false);
                                super.visitVarInsn(Opcodes.DSTORE, 5);
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
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
