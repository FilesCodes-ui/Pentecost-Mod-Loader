package khi.pentecost.core;

import khi.pentecost.control.Settings;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class FluidDefer {
    public interface Impl {
        boolean defer(Object fluidState, Object level, Object pos, Object chunk);
    }

    public static volatile Impl impl;
    private static volatile java.lang.reflect.Method tickNow;
    private static volatile boolean on = true;
    private static long refreshed;

    private FluidDefer() {}

    public static void tick(Object fluidState, Object level, Object pos, Object chunk) {
        long now = System.currentTimeMillis();
        if (now - refreshed > 1000) {
            refreshed = now;
            on = Settings.on(Settings.SPREAD_WATER);
        }
        Impl i = impl;
        if (on && i != null) {
            try {
                if (i.defer(fluidState, level, pos, chunk)) {
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
                impl = null;
                khi.pentecost.boot.Log.warn("Spreading new water over ticks is off: " + e);
            }
        }
        runNow(fluidState, level, pos);
    }

    private static void runNow(Object fluidState, Object level, Object pos) {
        try {
            java.lang.reflect.Method m = tickNow;
            if (m == null) {
                for (java.lang.reflect.Method c : fluidState.getClass().getMethods()) {
                    if (c.getName().equals("tick") && c.getParameterCount() == 2) {
                        m = c;
                        break;
                    }
                }
                tickNow = m;
            }
            m.invoke(fluidState, level, pos);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) {
                throw r;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(c);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.world.level.chunk.LevelChunk") || System.getenv("PENTECOST_NO_FLUID_DEFER") != null) {
                return bytes;
            }
            try {
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(0);
                int[] done = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                        MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                        if (!n.equals("postProcessGeneration") || !d.equals("()V")) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                                if (op == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/world/level/material/FluidState")
                                        && mn.equals("tick")
                                        && md.equals("(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V")) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/FluidDefer", "tick",
                                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                    done[0]++;
                                    return;
                                }
                                super.visitMethodInsn(op, owner, mn, md, itf);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(maxStack + 1, maxLocals);
                            }
                        };
                    }
                }, 0);
                return done[0] > 0 ? writer.toByteArray() : bytes;
            } catch (RuntimeException e) {
                khi.pentecost.boot.Log.warn("Spreading new water over ticks skipped: " + e);
                return bytes;
            }
        };
    }
}
