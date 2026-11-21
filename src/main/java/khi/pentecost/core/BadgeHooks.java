package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class BadgeHooks {
    public interface Impl {
        boolean server(Object listener, Object packet);

        boolean client(Object listener, Object packet);

        void serverTick(Object server);
    }

    public static volatile Impl impl;

    private BadgeHooks() {}

    public static boolean server(Object listener, Object packet) {
        Impl i = impl;
        try {
            return i != null && i.server(listener, packet);
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Pentecost badge message failed: " + e);
            return false;
        }
    }

    public static boolean client(Object listener, Object packet) {
        Impl i = impl;
        try {
            return i != null && i.client(listener, packet);
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Pentecost badge message failed: " + e);
            return false;
        }
    }

    public static void serverTick(Object server) {
        Impl i = impl;
        if (i != null) {
            try {
                i.serverTick(server);
            } catch (RuntimeException | LinkageError e) {
                Log.warn("Pentecost badge greeting failed: " + e);
            }
        }
    }

    private static final String SELF = "khi/pentecost/core/BadgeHooks";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            String desc;
            String hook;
            if (name.equals("net.minecraft.server.network.ServerGamePacketListenerImpl")) {
                desc = "(Lnet/minecraft/network/protocol/game/ServerboundCustomPayloadPacket;)V";
                hook = "server";
            } else if (name.equals("net.minecraft.client.multiplayer.ClientPacketListener")) {
                desc = "(Lnet/minecraft/network/protocol/game/ClientboundCustomPayloadPacket;)V";
                hook = "client";
            } else {
                return bytes;
            }
            if (System.getenv("PENTECOST_NO_BADGE") != null) {
                return bytes;
            }
            int[] done = new int[1];
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("handleCustomPayload") || !d.equals(desc)) {
                        return mv;
                    }
                    done[0]++;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label go = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, hook, "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
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
            if (done[0] != 1) {
                Log.error("Pentecost badge: " + name + " did not match. No badge on this side.");
                return bytes;
            }
            return writer.toByteArray();
        };
    }
}
