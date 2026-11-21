package khi.pentecost.core;

import khi.pentecost.control.Settings;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.BooleanSupplier;

public final class ServerTuning {
    public interface Mobs {
        boolean skip(Object mob);
    }

    public static volatile Mobs mobs;

    public interface Tick {
        void tick(Object server);
    }

    public static volatile Tick ticker;

    public static volatile Tick drain;

    public static volatile int unloadBoost;

    private static boolean installTried;
    private static int tickFailures;

    private static final int UNLOAD_PER_TICK = 16;

    private static volatile boolean farMobs = true;
    private static volatile boolean spreadUnloads = true;
    private static long refreshed;

    private ServerTuning() {}

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshed > 1000) {
            refreshed = now;
            farMobs = Settings.on(Settings.FAR_MOBS);
            spreadUnloads = Settings.on(Settings.SPREAD_UNLOADS);
        }
    }

    public static boolean skip(Object mob) {
        refresh();
        Mobs m = mobs;
        return farMobs && m != null && m.skip(mob);
    }

    public static Object budget(Object original) {
        refresh();
        if (!spreadUnloads) {
            return original;
        }
        BooleanSupplier o = (BooleanSupplier) original;
        int[] used = {0};
        int boost = unloadBoost;
        int limit = boost > 0 ? Math.max(UNLOAD_PER_TICK, boost) : UNLOAD_PER_TICK;
        return (BooleanSupplier) () -> o.getAsBoolean() && used[0]++ < limit;
    }

    public static void serverTick(Object server) {
        TickWatch.tickStart();
        ModImpact.tick();
        try {
            khi.pentecost.messenger.Threads.serverTick(server);
            BadgeHooks.serverTick(server);
        } catch (RuntimeException | LinkageError e) {
            khi.pentecost.boot.Log.error("The messenger failed at the start of a server tick: " + e);
        }
        Tick d = drain;
        if (d != null) {
            try {
                d.tick(server);
            } catch (RuntimeException | LinkageError e) {
                drain = null;
                khi.pentecost.boot.Log.error("Extra chunk work failed and is off until restart: " + e);
            }
        }
        Tick t = ticker;
        if (t == null) {
            if (installTried) {
                return;
            }
            installTried = true;
            try {
                ClassLoader cl = PentecostRuntime.loader != null ? PentecostRuntime.loader : ServerTuning.class.getClassLoader();
                Class.forName("khi.pentecost.game.pregen.Pregen", true, cl).getMethod("install").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                khi.pentecost.boot.Log.error("The pregenerator could not be installed: " + e);
                return;
            }
            t = ticker;
            if (t == null) {
                return;
            }
        }
        try {
            t.tick(server);
        } catch (RuntimeException | LinkageError e) {
            if (++tickFailures <= 3) {
                khi.pentecost.boot.Log.error("The pregenerator failed during a server tick: " + e);
            }
            if (tickFailures >= 3) {
                ticker = null;
                unloadBoost = 0;
            }
        }
    }

    private static final String HOOKS = "khi/pentecost/core/ServerTuning";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            boolean mob = name.equals("net.minecraft.world.entity.Mob");
            boolean chunkMap = name.equals("net.minecraft.server.level.ChunkMap");
            boolean server = name.equals("net.minecraft.server.MinecraftServer");
            if (!mob && !chunkMap && !server) {
                return bytes;
            }
            final String internal = name.replace('.', '/');
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if (server && n.equals("tickServer") && d.equals("(Ljava/util/function/BooleanSupplier;)V")
                            && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, n, d, s, e)) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "serverTick", "(Ljava/lang/Object;)V", false);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    boolean thinking = mob && n.equals("serverAiStep") && d.equals("()V");
                    boolean unloading = chunkMap && n.equals("processUnloads") && d.equals("(Ljava/util/function/BooleanSupplier;)V");
                    if (!thinking && !unloading || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
                        return super.visitMethod(access, n, d, s, e);
                    }
                    changed[0] = true;
                    String kept = n + "$pentecost";
                    MethodVisitor w = super.visitMethod(access, n, d, s, e);
                    w.visitCode();
                    if (thinking) {
                        Label go = new Label();
                        w.visitVarInsn(Opcodes.ALOAD, 0);
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "skip", "(Ljava/lang/Object;)Z", false);
                        w.visitJumpInsn(Opcodes.IFEQ, go);
                        w.visitInsn(Opcodes.RETURN);
                        w.visitLabel(go);
                        w.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        w.visitVarInsn(Opcodes.ALOAD, 0);
                        w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                        w.visitInsn(Opcodes.RETURN);
                        w.visitMaxs(1, 1);
                    } else {
                        w.visitVarInsn(Opcodes.ALOAD, 0);
                        w.visitVarInsn(Opcodes.ALOAD, 1);
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "budget", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        w.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BooleanSupplier");
                        w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                        w.visitInsn(Opcodes.RETURN);
                        w.visitMaxs(2, 2);
                    }
                    w.visitEnd();
                    MethodVisitor body = super.visitMethod(access, kept, d, s, e);
                    if (!unloading) {
                        return body;
                    }
                    return new MethodVisitor(Opcodes.ASM9, body) {
                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            if (opcode == Opcodes.SIPUSH && operand == 200) {
                                super.visitIntInsn(Opcodes.BIPUSH, UNLOAD_PER_TICK);
                            } else {
                                super.visitIntInsn(opcode, operand);
                            }
                        }
                    };
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
