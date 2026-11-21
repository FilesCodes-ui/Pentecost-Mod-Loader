package khi.pentecost.core;

import khi.pentecost.control.Settings;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ParallelGen {
    private static volatile Object surface;
    private static volatile Object carvers;
    private static volatile Executor pool;
    private static volatile Method original;
    private static volatile boolean broken;
    private static volatile boolean on = true;
    private static long refreshed;

    private ParallelGen() {}

    public static boolean parallel(Object status) {
        long now = System.currentTimeMillis();
        if (now - refreshed > 1000) {
            refreshed = now;
            on = Settings.on(Settings.PARALLEL_GEN);
        }
        if (!on || broken) {
            return false;
        }
        if (surface == null) {
            try {
                Class<?> c = status.getClass();
                surface = c.getField("SURFACE").get(null);
                carvers = c.getField("CARVERS").get(null);
                pool = ownPool();
                for (Method m : c.getMethods()) {
                    if (m.getName().equals("generate$pentecost")) {
                        original = m;
                    }
                }
                if (original == null) {
                    throw new NoSuchMethodException("generate$pentecost");
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                broken = true;
                khi.pentecost.boot.Log.warn("Parallel world generation is off: " + e);
                return false;
            }
        }
        return status == surface || status == carvers;
    }

    @SuppressWarnings("unchecked")
    public static CompletableFuture<Object> async(Object status, Object[] args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return (CompletableFuture<Object>) original.invoke(status, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                fail(cause);
                throw new java.util.concurrent.CompletionException(cause);
            } catch (ReflectiveOperationException | RuntimeException e) {
                fail(e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, pool).thenCompose(f -> f);
    }

    private static Executor ownPool() {
        int n = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        return java.util.concurrent.Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "pentecost-gen-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private static final ThreadLocal<Boolean> LOWERED = new ThreadLocal<>();

    private static void lowerPriority() {
        if (LOWERED.get() == null) {
            LOWERED.set(Boolean.TRUE);
            try {
                Thread t = Thread.currentThread();
                if (t.getPriority() >= Thread.NORM_PRIORITY) {
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                }
            } catch (SecurityException e) {
            }
        }
    }

    private static void fail(Throwable e) {
        if (!broken) {
            broken = true;
            khi.pentecost.boot.Log.error("Parallel world generation failed and is off until restart: " + e);
        }
    }

    private static final String DESC = "(Ljava/util/concurrent/Executor;Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;"
            + "Lnet/minecraft/server/level/ThreadedLevelLightEngine;Ljava/util/function/Function;Ljava/util/List;)"
            + "Ljava/util/concurrent/CompletableFuture;";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.world.level.chunk.ChunkStatus")
                    || System.getenv("PENTECOST_NO_PARALLEL_GEN") != null) {
                return bytes;
            }
            try {
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(0);
                boolean[] done = {false};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                        if (!n.equals("generate") || !d.equals(DESC) || (access & Opcodes.ACC_STATIC) != 0) {
                            return super.visitMethod(access, n, d, s, e);
                        }
                        done[0] = true;
                        MethodVisitor w = super.visitMethod(access, n, d, s, e);
                        w.visitCode();
                        Label plain = new Label();
                        w.visitVarInsn(Opcodes.ALOAD, 0);
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ParallelGen", "parallel", "(Ljava/lang/Object;)Z", false);
                        w.visitJumpInsn(Opcodes.IFEQ, plain);
                        w.visitVarInsn(Opcodes.ALOAD, 0);
                        w.visitIntInsn(Opcodes.BIPUSH, 7);
                        w.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                        for (int i = 0; i < 7; i++) {
                            w.visitInsn(Opcodes.DUP);
                            w.visitIntInsn(Opcodes.BIPUSH, i);
                            w.visitVarInsn(Opcodes.ALOAD, i + 1);
                            w.visitInsn(Opcodes.AASTORE);
                        }
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/ParallelGen", "async",
                                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;", false);
                        w.visitInsn(Opcodes.ARETURN);
                        w.visitLabel(plain);
                        w.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        for (int i = 0; i < 8; i++) {
                            w.visitVarInsn(Opcodes.ALOAD, i);
                        }
                        w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/ChunkStatus", "generate$pentecost", d, false);
                        w.visitInsn(Opcodes.ARETURN);
                        w.visitMaxs(8, 8);
                        w.visitEnd();
                        return super.visitMethod((access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC,
                                "generate$pentecost", d, s, e);
                    }
                }, 0);
                return done[0] ? writer.toByteArray() : bytes;
            } catch (RuntimeException e) {
                khi.pentecost.boot.Log.warn("Parallel world generation skipped: " + e);
                return bytes;
            }
        };
    }
}
