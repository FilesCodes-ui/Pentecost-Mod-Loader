package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class OrbitHooks {
    public interface Impl {
        void camera(Object camera, Object entity, float partialTick);

        boolean turn(Object entity, double yaw, double pitch);

        float ghost(Object entity);

        Object perspective(Object newType);
    }

    public static volatile Impl impl;

    private OrbitHooks() {}

    public static void camera(Object camera, Object entity, float partialTick) {
        Impl i = impl;
        if (i == null) {
            return;
        }
        try {
            i.camera(camera, entity, partialTick);
        } catch (RuntimeException | LinkageError e) {
            impl = null;
            khi.pentecost.boot.Log.error("F5 camera stopped and is off until restart: " + e);
        }
    }

    public static boolean turn(Object entity, double yaw, double pitch) {
        Impl i = impl;
        if (i == null) {
            return false;
        }
        try {
            return i.turn(entity, yaw, pitch);
        } catch (RuntimeException | LinkageError e) {
            impl = null;
            khi.pentecost.boot.Log.error("F5 camera stopped and is off until restart: " + e);
            return false;
        }
    }

    private static float ghostOf(Object entity) {
        Impl i = impl;
        if (i == null) {
            return 1f;
        }
        try {
            return i.ghost(entity);
        } catch (RuntimeException | LinkageError e) {
            return 1f;
        }
    }

    public static boolean bodyVisible(Object entity, boolean visible) {
        return visible && ghostOf(entity) >= 0.999f;
    }

    public static float ghostAlpha(Object entity) {
        float g = ghostOf(entity);
        return g >= 0.999f ? 0.15f : Math.max(0.08f, g);
    }

    public static boolean layersHidden(Object entity, boolean spectator) {
        return spectator || ghostOf(entity) < 0.35f;
    }

    public static Object perspective(Object newType) {
        Impl i = impl;
        if (i == null) {
            return newType;
        }
        try {
            return i.perspective(newType);
        } catch (RuntimeException | LinkageError e) {
            return newType;
        }
    }

    private static final String SELF = "khi/pentecost/core/OrbitHooks";
    private static final String RENDER = "(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";
    private static final String SETUP = "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            boolean camera = name.equals("net.minecraft.client.Camera");
            boolean entity = name.equals("net.minecraft.world.entity.Entity");
            boolean living = name.equals("net.minecraft.client.renderer.entity.LivingEntityRenderer");
            boolean client = name.equals("net.minecraft.client.Minecraft");
            if ((!camera && !entity && !living && !client) || System.getenv("PENTECOST_NO_F5_CAMERA") != null) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            int[] done = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (camera && n.equals("setup") && d.equals(SETUP)) {
                        done[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitVarInsn(Opcodes.ALOAD, 2);
                                    super.visitVarInsn(Opcodes.FLOAD, 5);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "camera",
                                            "(Ljava/lang/Object;Ljava/lang/Object;F)V", false);
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 3), maxLocals);
                            }
                        };
                    }
                    if (client && n.equals("handleKeybinds") && d.equals("()V")) {
                        done[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            boolean hit;

                            @Override
                            public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                                if (!hit && op == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/Options")
                                        && mn.equals("setCameraType") && md.equals("(Lnet/minecraft/client/CameraType;)V")) {
                                    hit = true;
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "perspective",
                                            "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/client/CameraType");
                                }
                                super.visitMethodInsn(op, owner, mn, md, itf);
                            }
                        };
                    }
                    if (living && n.equals("render") && d.equals(RENDER)) {
                        done[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            boolean body, alpha, layers;

                            @Override
                            public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                                super.visitMethodInsn(op, owner, mn, md, itf);
                                if (!body && mn.equals("isBodyVisible") && md.equals("(Lnet/minecraft/world/entity/LivingEntity;)Z")) {
                                    body = true;
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "bodyVisible", "(Ljava/lang/Object;Z)Z", false);
                                } else if (!layers && mn.equals("isSpectator") && md.equals("()Z")) {
                                    layers = true;
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "layersHidden", "(Ljava/lang/Object;Z)Z", false);
                                }
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                if (!alpha && value instanceof Float f && f == 0.15f) {
                                    alpha = true;
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "ghostAlpha", "(Ljava/lang/Object;)F", false);
                                    return;
                                }
                                super.visitLdcInsn(value);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(maxStack + 2, maxLocals);
                            }
                        };
                    }
                    if (entity && n.equals("turn") && d.equals("(DD)V")) {
                        done[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label go = new Label();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.DLOAD, 1);
                                super.visitVarInsn(Opcodes.DLOAD, 3);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "turn", "(Ljava/lang/Object;DD)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 5), maxLocals);
                            }
                        };
                    }
                    return mv;
                }

                @Override
                public void visitEnd() {
                    if (camera) {
                        MethodVisitor m = super.visitMethod(Opcodes.ACC_PUBLIC, "pentecost$place", "(DDDFF)V", null, null);
                        m.visitCode();
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitVarInsn(Opcodes.FLOAD, 7);
                        m.visitVarInsn(Opcodes.FLOAD, 8);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Camera", "setRotation", "(FF)V", false);
                        m.visitVarInsn(Opcodes.ALOAD, 0);
                        m.visitVarInsn(Opcodes.DLOAD, 1);
                        m.visitVarInsn(Opcodes.DLOAD, 3);
                        m.visitVarInsn(Opcodes.DLOAD, 5);
                        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Camera", "setPosition", "(DDD)V", false);
                        m.visitInsn(Opcodes.RETURN);
                        m.visitMaxs(7, 9);
                        m.visitEnd();
                    }
                    super.visitEnd();
                }
            }, 0);
            if (done[0] != 1) {
                khi.pentecost.boot.Log.error("F5 camera: " + name + " did not match. F5 stays vanilla.");
                return bytes;
            }
            return writer.toByteArray();
        };
    }
}
