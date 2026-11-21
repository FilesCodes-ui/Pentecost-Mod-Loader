package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class TagHooks {
    public interface Drawer {
        void nameTag(Object renderer, Object entity, Object pose, Object buffer, int light);

        boolean replaces(Object entity);
    }

    public interface Marker {
        boolean showOwn(Object entity);
        Object tab(Object component, Object info);
        Object displayName(Object component, Object entity);
        Object untag(Object component);
    }

    public static volatile Drawer drawer;
    public static volatile Marker marker;

    private TagHooks() {}

    public static void after(Object renderer, Object entity, Object pose, Object buffer, int light) {
        Drawer d = drawer;
        if (d != null) {
            d.nameTag(renderer, entity, pose, buffer, light);
        }
    }

    public static boolean replaces(Object entity) {
        Drawer d = drawer;
        return d != null && d.replaces(entity);
    }

    public static boolean show(boolean original, Object entity) {
        Marker m = marker;
        return original || (m != null && m.showOwn(entity));
    }

    public static Object tab(Object component, Object info) {
        Marker m = marker;
        return m == null ? component : m.tab(component, info);
    }

    public static Object displayName(Object component, Object entity) {
        Marker m = marker;
        return m == null ? component : m.displayName(component, entity);
    }

    public static Object untag(Object component) {
        Marker m = marker;
        return m == null ? component : m.untag(component);
    }

    private static final String HOOKS = "khi/pentecost/core/TagHooks";
    private static final String OBJ = "Ljava/lang/Object;";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";

    private enum Kind { NAME_TAG, SHOW_NAME, TAB, DISPLAY_NAME, SOURCE_NAME }

    private record Target(String owner, String method, String desc, Kind kind) {}

    private static final Target[] TARGETS = {
            new Target("net.minecraft.client.renderer.entity.EntityRenderer", "renderNameTag",
                    "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
                    Kind.NAME_TAG),
            new Target("net.minecraft.client.renderer.entity.LivingEntityRenderer", "shouldShowName",
                    "(Lnet/minecraft/world/entity/LivingEntity;)Z", Kind.SHOW_NAME),
            new Target("net.minecraft.client.gui.components.PlayerTabOverlay", "getNameForDisplay",
                    "(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;", Kind.TAB),
            new Target("net.minecraft.world.entity.Entity", "getDisplayName",
                    "()Lnet/minecraft/network/chat/Component;", Kind.DISPLAY_NAME),
            new Target("net.minecraft.world.entity.player.Player", "getDisplayName",
                    "()Lnet/minecraft/network/chat/Component;", Kind.DISPLAY_NAME),
            new Target("net.minecraft.commands.CommandSourceStack", "getDisplayName",
                    "()Lnet/minecraft/network/chat/Component;", Kind.SOURCE_NAME),
    };

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            Target target = null;
            for (Target t : TARGETS) {
                if (t.owner().equals(name)) {
                    target = t;
                }
            }
            if (target == null) {
                return bytes;
            }
            final Target hit = target;
            final String internal = name.replace('.', '/');
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if (!n.equals(hit.method()) || !d.equals(hit.desc()) || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
                        return super.visitMethod(access, n, d, s, e);
                    }
                    changed[0] = true;
                    String kept = n + "$pentecost";
                    MethodVisitor w = super.visitMethod(access, n, d, s, e);
                    w.visitCode();
                    switch (hit.kind()) {
                        case NAME_TAG -> {
                            Label draw = new Label();
                            w.visitVarInsn(Opcodes.ALOAD, 1);
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "replaces", "(" + OBJ + ")Z", false);
                            w.visitJumpInsn(Opcodes.IFNE, draw);
                            w.visitVarInsn(Opcodes.ALOAD, 0);
                            w.visitVarInsn(Opcodes.ALOAD, 1);
                            w.visitVarInsn(Opcodes.ALOAD, 2);
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "untag", "(" + OBJ + ")" + OBJ, false);
                            w.visitTypeInsn(Opcodes.CHECKCAST, COMPONENT);
                            w.visitVarInsn(Opcodes.ALOAD, 3);
                            w.visitVarInsn(Opcodes.ALOAD, 4);
                            w.visitVarInsn(Opcodes.ILOAD, 5);
                            w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                            w.visitLabel(draw);
                            w.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            w.visitVarInsn(Opcodes.ALOAD, 0);
                            w.visitVarInsn(Opcodes.ALOAD, 1);
                            w.visitVarInsn(Opcodes.ALOAD, 3);
                            w.visitVarInsn(Opcodes.ALOAD, 4);
                            w.visitVarInsn(Opcodes.ILOAD, 5);
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "after", "(" + OBJ + OBJ + OBJ + OBJ + "I)V", false);
                            w.visitInsn(Opcodes.RETURN);
                            w.visitMaxs(6, 6);
                        }
                        case SHOW_NAME -> {
                            w.visitVarInsn(Opcodes.ALOAD, 0);
                            w.visitVarInsn(Opcodes.ALOAD, 1);
                            w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                            w.visitVarInsn(Opcodes.ALOAD, 1);
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "show", "(Z" + OBJ + ")Z", false);
                            w.visitInsn(Opcodes.IRETURN);
                            w.visitMaxs(3, 2);
                        }
                        case TAB -> {
                            w.visitVarInsn(Opcodes.ALOAD, 0);
                            w.visitVarInsn(Opcodes.ALOAD, 1);
                            w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                            w.visitVarInsn(Opcodes.ALOAD, 1);
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "tab", "(" + OBJ + OBJ + ")" + OBJ, false);
                            w.visitTypeInsn(Opcodes.CHECKCAST, COMPONENT);
                            w.visitInsn(Opcodes.ARETURN);
                            w.visitMaxs(3, 2);
                        }
                        case DISPLAY_NAME -> {
                            w.visitVarInsn(Opcodes.ALOAD, 0);
                            w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                            w.visitVarInsn(Opcodes.ALOAD, 0);
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "displayName", "(" + OBJ + OBJ + ")" + OBJ, false);
                            w.visitTypeInsn(Opcodes.CHECKCAST, COMPONENT);
                            w.visitInsn(Opcodes.ARETURN);
                            w.visitMaxs(3, 1);
                        }
                        case SOURCE_NAME -> {
                            w.visitVarInsn(Opcodes.ALOAD, 0);
                            w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                            w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "untag", "(" + OBJ + ")" + OBJ, false);
                            w.visitTypeInsn(Opcodes.CHECKCAST, COMPONENT);
                            w.visitInsn(Opcodes.ARETURN);
                            w.visitMaxs(2, 1);
                        }
                    }
                    w.visitEnd();
                    return super.visitMethod(access, kept, d, s, e);
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
