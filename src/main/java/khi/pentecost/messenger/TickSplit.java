package khi.pentecost.messenger;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class TickSplit {
    public interface Impl {
        Object[] split(Object[] listeners, Object event);
    }

    public static volatile Impl impl;
    public static volatile boolean installed;
    public static final java.util.Set<String> SENT_HOME = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static volatile int lastHandlers;
    public static volatile int lastGroups;
    public static volatile double lastMs;

    private TickSplit() {}

    public static Object[] split(Object[] listeners, Object event) {
        Impl i = impl;
        if (i == null || listeners == null || listeners.length == 0) {
            return listeners;
        }
        try {
            Object[] rest = i.split(listeners, event);
            return rest == null ? listeners : rest;
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Mod tick threads failed, this tick runs the old way: " + e);
            return listeners;
        }
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraftforge.eventbus.EventBus") || System.getenv("PENTECOST_NO_TICK_SPLIT") != null) {
                return bytes;
            }
            int[] done = new int[1];
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("post") || !d.equals("(Lnet/minecraftforge/eventbus/api/Event;Lnet/minecraftforge/eventbus/api/IEventBusInvokeDispatcher;)Z")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            super.visitMethodInsn(op, owner, mn, md, itf);
                            if (owner.equals("net/minecraftforge/eventbus/ListenerList") && mn.equals("getListeners") && done[0] == 0) {
                                done[0] = 1;
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/messenger/TickSplit", "split",
                                        "([Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "[Lnet/minecraftforge/eventbus/api/IEventListener;");
                            }
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 1, maxLocals);
                        }
                    };
                }
            }, 0);
            if (done[0] != 1) {
                Log.error("Mod tick threads: Forge's event bus did not match. Mod tick work stays on the server thread.");
                return bytes;
            }
            installed = true;
            Log.info("Mod tick threads: event bus patched");
            return writer.toByteArray();
        };
    }
}
