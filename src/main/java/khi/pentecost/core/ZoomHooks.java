package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ZoomHooks {
    public static volatile double factor = 1.0;
    public static volatile boolean holding;
    public static volatile double level = 4.0;

    public static volatile double fovOverride = 0;

    public static volatile Runnable frameStart;

    public static volatile java.util.function.DoubleUnaryOperator scrollFirst;

    public interface GuiPass {
        void draw(Object gui, Object graphics, float partialTick);
    }

    public static volatile GuiPass guiPass;

    public interface Press {
        boolean press(int button, int action);
    }

    public static volatile Press press;

    public static boolean press(int button, int action) {
        Press p = press;
        if (p == null) {
            return false;
        }
        try {
            return p.press(button, action);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private ZoomHooks() {}

    public static void renderGui(Object gui, Object graphics, float partialTick) {
        GuiPass pass = guiPass;
        if (pass != null) {
            pass.draw(gui, graphics, partialTick);
            return;
        }
        try {
            for (java.lang.reflect.Method m : gui.getClass().getMethods()) {
                if (m.getName().equals("render") && m.getParameterCount() == 2 && m.getParameterTypes()[1] == float.class
                        && m.getParameterTypes()[0].isInstance(graphics)) {
                    m.invoke(gui, graphics, partialTick);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
        }
    }

    public static double fov(double fov, boolean useFovSetting) {
        double o = fovOverride;
        if (useFovSetting && o > 0) {
            return o;
        }
        double f = factor;
        return useFovSetting && f > 1.0001 ? fov / f : fov;
    }

    public static void frameStart() {
        Runnable r = frameStart;
        if (r != null) {
            r.run();
        }
    }
    public static boolean hideHand() {
        return factor > 1.0001;
    }

    public static double scroll(double amount) {
        java.util.function.DoubleUnaryOperator first = scrollFirst;
        if (first != null && amount != 0.0) {
            try {
                amount = first.applyAsDouble(amount);
            } catch (RuntimeException | LinkageError e) {
                scrollFirst = null;
            }
            if (amount == 0.0) {
                return 0.0;
            }
        }
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
            boolean hands = name.equals("net.minecraft.client.renderer.ItemInHandRenderer");
            if (!renderer && !mouse && !hands) {
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
                    if (renderer && n.equals("renderItemInHand")
                            && d.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;F)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label draw = new Label();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ZoomHooks", "hideHand",
                                        "()Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, draw);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(draw);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    if (hands && n.equals("renderHandsWithItems") && d.equals("(FLcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                            + "Lnet/minecraft/client/player/LocalPlayer;I)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label draw = new Label();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ZoomHooks", "hideHand",
                                        "()Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, draw);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(draw);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    if (renderer && n.equals("render") && d.equals("(FJZ)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ZoomHooks", "frameStart",
                                        "()V", false);
                            }

                            @Override
                            public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                if (op == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/gui/Gui")
                                        && name.equals("render") && desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;F)V")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ZoomHooks", "renderGui",
                                            "(Ljava/lang/Object;Ljava/lang/Object;F)V", false);
                                    return;
                                }
                                super.visitMethodInsn(op, owner, name, desc, itf);
                            }
                        };
                    }
                    if (mouse && n.equals("onPress") && d.equals("(JIII)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label go = new Label();
                                super.visitVarInsn(Opcodes.ILOAD, 3);
                                super.visitVarInsn(Opcodes.ILOAD, 4);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ZoomHooks", "press",
                                        "(II)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 2), maxLocals);
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
