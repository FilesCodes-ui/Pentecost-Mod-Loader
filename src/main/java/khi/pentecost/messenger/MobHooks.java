package khi.pentecost.messenger;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class MobHooks {
    public interface Impl {
        boolean take(Object level, Consumer<Object> tick, Object entity);

        void flush(Object level);

        void tickNow(Object level, Consumer<Object> tick, Object entity);

        boolean foreign(Object entity);

        boolean hurtNow(Object entity, Object source, float amount);

        Object threadRandom();

        Object chunkNow(Object cache, int x, int z, Object status, boolean load);

        Object loadedNow(Object cache, int x, int z);

        void setBlockNow(Object level, Object pos, Object state, int flags, int depth);

        void addEntityNow(Object level, Object entity);

        void gameEventNow(Object level, Object event, Object pos, Object context);

        void onMoveNow(Object callback);

        void onRemoveNow(Object callback, Object reason);
    }

    public static volatile Impl impl;
    public static volatile long chunkWantedAt;
    public static volatile boolean phase;
    private static volatile boolean installTried;

    private MobHooks() {}

    private static Impl impl(Object any) {
        Impl i = impl;
        if (i == null && !installTried) {
            installTried = true;
            try {
                Class.forName("khi.pentecost.game.MobThreads", true, any.getClass().getClassLoader()).getMethod("install").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Log.error("Mob threads could not be installed. Mobs tick the old way: " + e);
            }
            i = impl;
        }
        return i;
    }

    public static boolean deferring() {
        return phase && impl != null && Thread.currentThread() instanceof ModThreads.Worker;
    }

    public static Thread ownThread(Thread levelThread) {
        return deferring() ? Thread.currentThread() : levelThread;
    }

    public static void defer(Runnable change) {
        ((ModThreads.Worker) Thread.currentThread()).deferred.add(change);
    }

    @SuppressWarnings("unchecked")
    public static void guard(Object level, Consumer<?> tick, Object entity) {
        Impl i = impl(level);
        Consumer<Object> t = (Consumer<Object>) tick;
        if (i == null) {
            t.accept(entity);
            return;
        }
        if (!i.take(level, t, entity)) {
            i.tickNow(level, t, entity);
        }
    }

    public static void flush(Object level) {
        Impl i = impl;
        if (i != null) {
            i.flush(level);
        }
    }

    public static boolean setBlock(Object level, Object pos, Object state, int flags, int depth) {
        if (!deferring()) {
            return false;
        }
        Impl i = impl;
        defer(() -> i.setBlockNow(level, pos, state, flags, depth));
        return true;
    }

    public static boolean addEntity(Object level, Object entity) {
        if (!deferring()) {
            return false;
        }
        Impl i = impl;
        defer(() -> i.addEntityNow(level, entity));
        return true;
    }

    public static boolean gameEvent(Object level, Object event, Object pos, Object context) {
        if (!deferring()) {
            return false;
        }
        Impl i = impl;
        defer(() -> i.gameEventNow(level, event, pos, context));
        return true;
    }

    public static boolean onMove(Object callback) {
        if (!deferring()) {
            return false;
        }
        Impl i = impl;
        defer(() -> i.onMoveNow(callback));
        return true;
    }

    public static boolean onRemove(Object callback, Object reason) {
        if (!deferring()) {
            return false;
        }
        Impl i = impl;
        defer(() -> i.onRemoveNow(callback, reason));
        return true;
    }

    public static boolean hurt(Object entity, Object source, float amount) {
        Impl i = impl(entity);
        if (i == null) {
            return (Boolean) invokeAnswer(entity, "hurt", source, amount);
        }
        if (deferring() && i.foreign(entity)) {
            defer(() -> i.hurtNow(entity, source, amount));
            return true;
        }
        return i.hurtNow(entity, source, amount);
    }

    public static Object random(Object level, Object worldRandom) {
        if (deferring()) {
            Impl i = impl;
            if (i != null) {
                return i.threadRandom();
            }
        }
        return worldRandom;
    }

    public static final Object NO_CHUNK = new Object();

    public static Object chunkNow(Object cache, int x, int z) {
        if (!deferring()) {
            return null;
        }
        Impl i = impl;
        return i == null ? null : i.loadedNow(cache, x, z);
    }

    public static Object chunk(Object cache, int x, int z, Object status, boolean load) {
        if (!deferring()) {
            return null;
        }
        Impl i = impl;
        return i == null ? null : i.chunkNow(cache, x, z, status, load);
    }

    private static final java.util.Map<String, java.lang.reflect.Method> METHODS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object invokeAnswer(Object target, String name, Object... args) {
        try {
            java.lang.reflect.Method m = METHODS.computeIfAbsent(target.getClass().getName() + "#" + name + "/" + args.length, k -> find(target.getClass(), name, args.length));
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) {
                throw r;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.lang.reflect.Method find(Class<?> c, String name, int count) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == count && !m.isBridge()) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalStateException("No method " + name + " with " + count + " arguments on " + c.getName());
    }

    private static final String SELF = "khi/pentecost/messenger/MobHooks";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String CALLBACK = "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback";
    private static final String CHUNKS = "net/minecraft/server/level/ServerChunkCache";
    private static final String HURT = "(Lnet/minecraft/world/damagesource/DamageSource;F)Z";
    private static final String RANDOM = "Lnet/minecraft/util/RandomSource;";
    private static final byte[] HURT_BYTES = "hurt".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RANDOM_BYTES = "net/minecraft/util/RandomSource".getBytes(StandardCharsets.UTF_8);

    private static boolean off() {
        return System.getenv("PENTECOST_NO_MOB_THREADS") != null;
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (off()) {
                return bytes;
            }
            return switch (name) {
                case "net.minecraft.server.level.ServerLevel" -> serverLevel(bytes);
                case "net.minecraft.world.level.Level" -> heads(bytes, name, "setBlock",
                        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", HeadKind.SET_BLOCK);
                case "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback" -> callback(bytes);
                case "net.minecraft.server.level.ServerChunkCache" -> heads(heads(bytes, name, "getChunk",
                        "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", HeadKind.CHUNK),
                        name, "getChunkNow", "(II)Lnet/minecraft/world/level/chunk/LevelChunk;", HeadKind.CHUNK_NOW);
                default -> bytes;
            };
        };
    }

    public static ClassTransformer callSites() {
        return (name, bytes) -> {
            if (off() || name.startsWith("khi.pentecost.") || name.startsWith("java.") || name.startsWith("org.objectweb.")) {
                return bytes;
            }
            boolean maybeHurt = contains(bytes, HURT_BYTES);
            boolean maybeRandom = contains(bytes, RANDOM_BYTES);
            if (!maybeHurt && !maybeRandom) {
                return bytes;
            }
            int[] changed = new int[1];
            ClassReader reader;
            try {
                reader = new ClassReader(bytes);
            } catch (RuntimeException bad) {
                return bytes;
            }
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, n, d, s, e)) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if ((op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE) && mn.equals("hurt") && md.equals(HURT)
                                    && owner.startsWith("net/minecraft/world/entity/") || (op == Opcodes.INVOKEVIRTUAL && mn.equals("hurt")
                                    && md.equals(HURT) && !owner.startsWith("net/minecraft/"))) {
                                changed[0]++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "hurt", "(Ljava/lang/Object;Ljava/lang/Object;F)Z", false);
                                return;
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                        }

                        @Override
                        public void visitFieldInsn(int op, String owner, String fn, String fd) {
                            if (op == Opcodes.GETFIELD && fn.equals("random") && fd.equals(RANDOM)
                                    && (owner.equals(LEVEL) || owner.equals(SERVER_LEVEL))) {
                                changed[0]++;
                                super.visitInsn(Opcodes.DUP);
                                super.visitFieldInsn(op, owner, fn, fd);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "random", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/util/RandomSource");
                                return;
                            }
                            super.visitFieldInsn(op, owner, fn, fd);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 1, maxLocals);
                        }
                    };
                }
            }, 0);
            return changed[0] == 0 ? bytes : writer.toByteArray();
        };
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] serverLevel(byte[] bytes) {
        int[] done = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                if (n.startsWith("lambda$tick$") && d.equals("(Lnet/minecraft/util/profiling/ProfilerFiller;L" + ENTITY + ";)V")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if (op == Opcodes.INVOKEVIRTUAL && owner.equals(SERVER_LEVEL) && mn.equals("guardEntityTick")
                                    && md.equals("(Ljava/util/function/Consumer;L" + ENTITY + ";)V")) {
                                done[0] |= 1;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "guard", "(Ljava/lang/Object;Ljava/util/function/Consumer;Ljava/lang/Object;)V", false);
                                return;
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                        }
                    };
                }
                if (n.equals("tick") && d.equals("(Ljava/util/function/BooleanSupplier;)V")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            super.visitMethodInsn(op, owner, mn, md, itf);
                            if (op == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/world/level/entity/EntityTickList")
                                    && mn.equals("forEach") && (done[0] & 2) == 0) {
                                done[0] |= 2;
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "flush", "(Ljava/lang/Object;)V", false);
                            }
                        }
                    };
                }
                if (n.equals("addEntity") && d.equals("(L" + ENTITY + ";)Z")) {
                    done[0] |= 4;
                    return head(mv, HeadKind.ADD_ENTITY);
                }
                if (n.equals("gameEvent") && d.equals("(Lnet/minecraft/world/level/gameevent/GameEvent;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V")) {
                    done[0] |= 8;
                    return head(mv, HeadKind.GAME_EVENT);
                }
                return mv;
            }
        }, 0);
        if (done[0] != 15) {
            Log.error("Mob threads: ServerLevel did not match (" + done[0] + "). Mobs tick the old way.");
            return bytes;
        }
        return writer.toByteArray();
    }

    private static byte[] callback(byte[] bytes) {
        int[] done = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                if (n.equals("onMove") && d.equals("()V")) {
                    done[0] |= 1;
                    return head(mv, HeadKind.ON_MOVE);
                }
                if (n.equals("onRemove") && d.equals("(L" + ENTITY + "$RemovalReason;)V")) {
                    done[0] |= 2;
                    return head(mv, HeadKind.ON_REMOVE);
                }
                return mv;
            }
        }, 0);
        if (done[0] != 3) {
            Log.error("Mob threads: the entity section callback did not match (" + done[0] + ").");
            return bytes;
        }
        return writer.toByteArray();
    }

    private static byte[] heads(byte[] bytes, String cls, String method, String desc, HeadKind kind) {
        int[] done = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                if (n.equals(method) && d.equals(desc)) {
                    done[0]++;
                    return head(mv, kind);
                }
                return mv;
            }
        }, 0);
        if (done[0] != 1) {
            Log.error("Mob threads: " + cls + "." + method + " did not match.");
            return bytes;
        }
        return writer.toByteArray();
    }

    enum HeadKind { SET_BLOCK, ADD_ENTITY, GAME_EVENT, ON_MOVE, ON_REMOVE, CHUNK, CHUNK_NOW }

    private static MethodVisitor head(MethodVisitor mv, HeadKind kind) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label go = new Label();
                switch (kind) {
                    case SET_BLOCK -> {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitVarInsn(Opcodes.ALOAD, 2);
                        super.visitVarInsn(Opcodes.ILOAD, 3);
                        super.visitVarInsn(Opcodes.ILOAD, 4);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "setBlock", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;II)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, go);
                        super.visitInsn(Opcodes.ICONST_1);
                        super.visitInsn(Opcodes.IRETURN);
                    }
                    case ADD_ENTITY -> {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "addEntity", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, go);
                        super.visitInsn(Opcodes.ICONST_1);
                        super.visitInsn(Opcodes.IRETURN);
                    }
                    case GAME_EVENT -> {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitVarInsn(Opcodes.ALOAD, 2);
                        super.visitVarInsn(Opcodes.ALOAD, 3);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "gameEvent", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, go);
                        super.visitInsn(Opcodes.RETURN);
                    }
                    case ON_MOVE -> {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "onMove", "(Ljava/lang/Object;)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, go);
                        super.visitInsn(Opcodes.RETURN);
                    }
                    case ON_REMOVE -> {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "onRemove", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, go);
                        super.visitInsn(Opcodes.RETURN);
                    }
                    case CHUNK_NOW -> {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ILOAD, 1);
                        super.visitVarInsn(Opcodes.ILOAD, 2);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "chunkNow", "(Ljava/lang/Object;II)Ljava/lang/Object;", false);
                        super.visitInsn(Opcodes.DUP);
                        Label none = new Label();
                        super.visitJumpInsn(Opcodes.IFNULL, none);
                        super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/level/chunk/LevelChunk");
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(none);
                        super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"});
                        super.visitInsn(Opcodes.POP);
                    }
                    case CHUNK -> {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ILOAD, 1);
                        super.visitVarInsn(Opcodes.ILOAD, 2);
                        super.visitVarInsn(Opcodes.ALOAD, 3);
                        super.visitVarInsn(Opcodes.ILOAD, 4);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "chunk", "(Ljava/lang/Object;IILjava/lang/Object;Z)Ljava/lang/Object;", false);
                        super.visitInsn(Opcodes.DUP);
                        Label none = new Label();
                        super.visitJumpInsn(Opcodes.IFNULL, none);
                        super.visitInsn(Opcodes.DUP);
                        super.visitFieldInsn(Opcodes.GETSTATIC, SELF, "NO_CHUNK", "Ljava/lang/Object;");
                        Label real = new Label();
                        super.visitJumpInsn(Opcodes.IF_ACMPNE, real);
                        super.visitInsn(Opcodes.POP);
                        super.visitInsn(Opcodes.ACONST_NULL);
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(real);
                        super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"});
                        super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/level/chunk/ChunkAccess");
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(none);
                        super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"});
                        super.visitInsn(Opcodes.POP);
                    }
                }
                super.visitLabel(go);
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                super.visitMaxs(Math.max(maxStack, 6), maxLocals);
            }
        };
    }
}
