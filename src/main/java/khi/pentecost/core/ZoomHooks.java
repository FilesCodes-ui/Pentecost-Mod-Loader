package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Small changes to the game for the zoom key: the field of view is divided by the zoom factor,
 * the first-person hand is not drawn while zoomed, and the mouse wheel changes the zoom level
 * while the key is held instead of scrolling the hotbar.
 * The key itself, the smoothing and the settings live in khi.pentecost.game.Zoom.
 */
public final class ZoomHooks {
    /** How far the view is zoomed in right now. 1 means not zoomed. Set every frame. */
    public static volatile double factor = 1.0;
    /** True while the zoom key is held with no screen open. */
    public static volatile boolean holding;
    /** How far the view zooms while the key is held. The mouse wheel changes it. */
    public static volatile double level = 4.0;

    /** A field of view in degrees that replaces the game's own while it is above 0 (the pause camera). */
    public static volatile double fovOverride = 0;

    /** Runs at the start of every frame, before the game sets up its camera. The pause camera moves here. */
    public static volatile Runnable frameStart;

    /** Draws the whole in-game interface. Installed by the game side, so the interface can slide away while zooming. */
    public interface GuiPass {
        void draw(Object gui, Object graphics, float partialTick);
    }

    public static volatile GuiPass guiPass;

    private ZoomHooks() {}

    /** Called by the game where it draws the interface, with the Gui, the GuiGraphics and the partial tick. */
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
            // nothing to draw with
        }
    }

    /** Called by the game when it works out the field of view. */
    public static double fov(double fov, boolean useFovSetting) {
        double o = fovOverride;
        if (useFovSetting && o > 0) {
            return o;
        }
        double f = factor;
        return useFovSetting && f > 1.0001 ? fov / f : fov;
    }

    /** Called by the game at the top of GameRenderer.render, before the world and the camera of this frame. */
    public static void frameStart() {
        Runnable r = frameStart;
        if (r != null) {
            r.run();
        }
    }
    /** Called by the game before it draws the first-person hand. True means skip the hand (zooming in or out). */
    public static boolean hideHand() {
        return factor > 1.0001;
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
                    // Shader mods draw the hand through this method and skip the game's renderItemInHand.
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
