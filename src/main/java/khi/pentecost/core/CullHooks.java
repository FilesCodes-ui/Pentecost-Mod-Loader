package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;

/**
 * Skips drawing mobs a worker found fully behind solid blocks. The game asks EntityRenderDispatcher.shouldRender for
 * every entity every frame; Pentecost answers "no" first for the ids in the hidden list. The list is made on the
 * messenger's workers by khi.pentecost.game.EntityCulling and swapped in whole, so the render thread only reads it.
 */
public final class CullHooks {
    private static volatile int[] hidden = new int[0];

    private CullHooks() {}

    /** Called by the game for every entity it is about to draw. */
    public static boolean hidden(int id) {
        int[] h = hidden;
        return h.length != 0 && Arrays.binarySearch(h, id) >= 0;
    }

    /** Replaces the hidden list. The array must be sorted. */
    public static void set(int[] sortedIds) {
        hidden = sortedIds;
    }

    public static void clear() {
        hidden = new int[0];
    }

    public static int count() {
        return hidden.length;
    }

    private static final String DISPATCHER = "net.minecraft.client.renderer.entity.EntityRenderDispatcher";
    static final String SHOULD_RENDER = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!DISPATCHER.equals(name) || System.getenv("PENTECOST_NO_CULL") != null) {
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
