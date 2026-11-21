package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;

public final class CullHooks {
    private static volatile int[] hidden = new int[0];
    private static volatile java.util.Set<Object> hiddenBlocks = java.util.Set.of();
    public static volatile java.util.function.BiPredicate<Object, Object> blockCheck;

    private CullHooks() {}

    public static boolean hidden(int id) {
        int[] h = hidden;
        return h.length != 0 && Arrays.binarySearch(h, id) >= 0;
    }

    public static void set(int[] sortedIds) {
        hidden = sortedIds;
    }

    public static void clear() {
        hidden = new int[0];
        hiddenBlocks = java.util.Set.of();
    }

    public static boolean hiddenBlock(Object dispatcher, Object blockEntity) {
        java.util.Set<Object> h = hiddenBlocks;
        if (h.isEmpty() || !h.contains(blockEntity)) {
            return false;
        }
        java.util.function.BiPredicate<Object, Object> check = blockCheck;
        try {
            return check != null && check.test(dispatcher, blockEntity);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void setBlocks(java.util.Set<Object> machines) {
        hiddenBlocks = machines;
    }

    public static int blockCount() {
        return hiddenBlocks.size();
    }

    public static int count() {
        return hidden.length;
    }

    private static final String DISPATCHER = "net.minecraft.client.renderer.entity.EntityRenderDispatcher";
    private static final String BLOCK_DISPATCHER = "net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    static final String SHOULD_RENDER = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z";

    static byte[] blockDispatcher(byte[] bytes) {
        boolean[] done = new boolean[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                if (!n.equals("render") || !d.startsWith("(L" + BLOCK_ENTITY + ";F") || !d.endsWith(")V") || (access & Opcodes.ACC_STATIC) != 0) {
                    return mv;
                }
                done[0] = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label go = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/CullHooks", "hiddenBlock",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
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
        }, 0);
        if (!done[0]) {
            khi.pentecost.boot.Log.error("Machine culling: BlockEntityRenderDispatcher.render was not found. Machines are drawn as always.");
            return bytes;
        }
        return writer.toByteArray();
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (System.getenv("PENTECOST_NO_CULL") != null) {
                return bytes;
            }
            if (BLOCK_DISPATCHER.equals(name)) {
                return blockDispatcher(bytes);
            }
            if (!DISPATCHER.equals(name)) {
                return bytes;
            }
            boolean[] done = new boolean[1];
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("shouldRender") || !d.equals(SHOULD_RENDER)) {
                        return mv;
                    }
                    done[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label go = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "getId", "()I", false);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/CullHooks", "hidden", "(I)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, go);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitInsn(Opcodes.IRETURN);
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
                khi.pentecost.boot.Log.error("Mob culling: EntityRenderDispatcher.shouldRender was not found. Mobs are drawn as always.");
                return bytes;
            }
            return writer.toByteArray();
        };
    }
}
