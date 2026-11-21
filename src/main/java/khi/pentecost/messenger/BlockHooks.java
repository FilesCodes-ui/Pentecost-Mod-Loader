package khi.pentecost.messenger;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class BlockHooks {
    private BlockHooks() {}

    public interface Impl {
        boolean take(Object level, Object ticker);

        void tickNow(Object ticker);

        void flush(Object level);
    }

    public static volatile Impl impl;
    private static volatile boolean installTried;

    private static Impl impl(Object any) {
        Impl i = impl;
        if (i == null && !installTried) {
            installTried = true;
            try {
                Class.forName("khi.pentecost.game.BlockThreads", true, any.getClass().getClassLoader()).getMethod("install").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Log.error("Machine threads could not be installed. Machines tick the old way: " + e);
            }
            i = impl;
        }
        return i;
    }

    public static void tick(Object level, Object ticker) {
        Impl i = impl(level);
        if (i == null) {
            tickByName(ticker);
            return;
        }
        if (!i.take(level, ticker)) {
            i.tickNow(ticker);
        }
    }

    private static void tickByName(Object ticker) {
        try {
            java.lang.reflect.Method m = ticker.getClass().getMethod("tick");
            m.setAccessible(true);
            m.invoke(ticker);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) throw r;
            if (c instanceof Error err) throw err;
            throw new RuntimeException(c);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void flush(Object level) {
        Impl i = impl;
        if (i != null) {
            i.flush(level);
        }
    }

    private static final String LEVEL = "net.minecraft.world.level.Level";
    private static final String TICKER = "net/minecraft/world/level/block/entity/TickingBlockEntity";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!LEVEL.equals(name) || System.getenv("PENTECOST_NO_MACHINE_THREADS") != null) return bytes;
            try {
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(0);
                int[] n = {0, 0, 0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String m, String d, String s, String[] e) {
                        MethodVisitor mv = super.visitMethod(access, m, d, s, e);
                        if (m.equals("getBlockEntity") && d.equals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;")) {
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitFieldInsn(int op, String owner, String fn, String fd) {
                                    super.visitFieldInsn(op, owner, fn, fd);
                                    if (op == Opcodes.GETFIELD && fn.equals("thread") && fd.equals("Ljava/lang/Thread;")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/messenger/MobHooks", "ownThread",
                                                "(Ljava/lang/Thread;)Ljava/lang/Thread;", false);
                                        n[2]++;
                                    }
                                }
                            };
                        }
                        if (!m.equals("tickBlockEntities") || !d.equals("()V")) return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                                if (op == Opcodes.INVOKEINTERFACE && owner.equals(TICKER) && mn.equals("tick") && md.equals("()V")) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/messenger/BlockHooks", "tick",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                    n[0]++;
                                    return;
                                }
                                super.visitMethodInsn(op, owner, mn, md, itf);
                            }

                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/messenger/BlockHooks", "flush",
                                            "(Ljava/lang/Object;)V", false);
                                    n[1]++;
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(maxStack + 1, maxLocals);
                            }
                        };
                    }
                }, 0);
                if (n[0] == 0) {
                    Log.warn("Machine threads: Level.tickBlockEntities has no machine tick call. Machines tick the old way.");
                    return bytes;
                }
                if (n[2] == 0) {
                    Log.warn("Machine threads: Level.getBlockEntity has no thread check to adjust. Machines tick the old way.");
                    return bytes;
                }
                return writer.toByteArray();
            } catch (RuntimeException e) {
                Log.warn("Machine threads skipped: " + e);
                return bytes;
            }
        };
    }
}
