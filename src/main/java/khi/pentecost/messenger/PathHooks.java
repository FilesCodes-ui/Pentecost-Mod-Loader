package khi.pentecost.messenger;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

public final class PathHooks {
    public interface Impl {
        Object findPath(Object finder, Object region, Object mob, Set<?> targets, float range, int accuracy, float multiplier, Object navigation);
    }

    public static volatile Impl impl;
    private static volatile boolean installTried;
    private static volatile Method direct;

    public static final LongAdder onWorkers = new LongAdder();
    public static final LongAdder forced = new LongAdder();
    public static final LongAdder onServer = new LongAdder();
    public static final LongAdder workerNanos = new LongAdder();

    private record Site(LongAdder tries, LongAdder forced) {}

    private static final java.util.Map<String, Site> SITES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> ON_SERVER = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong PROBES = new java.util.concurrent.atomic.AtomicLong();
    static final int LEARN_AFTER = 20;
    static final double EARLY_SHARE = 0.5;
    static final int PROBE_EVERY = 64;

    public static boolean siteAsync(String site) {
        Site s = SITES.get(site);
        if (s == null || s.tries().sum() < LEARN_AFTER) {
            return true;
        }
        boolean early = s.forced().sum() > s.tries().sum() * EARLY_SHARE;
        if (!early) {
            if (ON_SERVER.remove(site)) {
                khi.pentecost.messenger.Messenger.get().note("Path search from " + site + " waits for its answer again. It goes back to the workers.");
            }
            return true;
        }
        if (ON_SERVER.add(site)) {
            khi.pentecost.messenger.Messenger.get().note("Path search from " + site + " reads its answer right away, so it stays on the server thread.");
        }
        return PROBES.incrementAndGet() % PROBE_EVERY == 0;
    }

    public static void tried(String site) {
        SITES.computeIfAbsent(site, k -> new Site(new LongAdder(), new LongAdder())).tries().increment();
    }

    public static void forcedAt(String site) {
        forced.increment();
        Site s = SITES.get(site);
        if (s != null) {
            s.forced().increment();
        }
    }

    public static int sitesOnServer() {
        return ON_SERVER.size();
    }

    static void forgetSites() {
        SITES.clear();
        ON_SERVER.clear();
    }

    private PathHooks() {}

    public static Object findPath(Object finder, Object region, Object mob, Set<?> targets, float range, int accuracy,
                                  float multiplier, Object navigation) {
        Impl i = impl;
        if (i == null && !installTried) {
            installTried = true;
            try {
                ClassLoader cl = navigation.getClass().getClassLoader();
                Class.forName("khi.pentecost.game.AsyncPaths", true, cl).getMethod("install").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Log.error("Async pathfinding could not be installed. Paths are searched the old way: " + e);
            }
            i = impl;
        }
        if (i != null) {
            return i.findPath(finder, region, mob, targets, range, accuracy, multiplier, navigation);
        }
        return direct(finder, region, mob, targets, range, accuracy, multiplier);
    }

    private static Object direct(Object finder, Object region, Object mob, Set<?> targets, float range, int accuracy, float multiplier) {
        try {
            Method m = direct;
            if (m == null) {
                for (Method x : finder.getClass().getMethods()) {
                    if (x.getName().equals("findPath") && x.getParameterCount() == 6) {
                        m = x;
                    }
                }
                direct = m;
            }
            return m.invoke(finder, region, mob, targets, range, accuracy, multiplier);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot search a path", e);
        }
    }

    public static boolean pending(Object path) {
        return path instanceof PendingPath p && p.pending();
    }

    private static final String NAV = "net/minecraft/world/entity/ai/navigation/PathNavigation";
    private static final String PATH = "net/minecraft/world/level/pathfinder/Path";
    private static final String SELF = "khi/pentecost/messenger/PathHooks";
    private static final String FIND_DESC = "(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)L" + PATH + ";";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.world.entity.ai.navigation.PathNavigation")) {
                return bytes;
            }
            if (System.getenv("PENTECOST_NO_ASYNC_PATHS") != null) {
                return bytes;
            }
            int[] done = new int[1];
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (n.equals("createPath") && d.equals("(Ljava/util/Set;IZIF)L" + PATH + ";")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            boolean hit;

                            @Override
                            public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                                if (!hit && op == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/world/level/pathfinder/PathFinder")
                                        && mn.equals("findPath") && md.equals(FIND_DESC)) {
                                    hit = true;
                                    done[0] |= 1;
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "findPath",
                                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Set;FIFLjava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, PATH);
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
                    if (n.equals("moveTo") && d.equals("(L" + PATH + ";D)Z")) {
                        done[0] |= 2;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label go = new Label();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "pending", "(Ljava/lang/Object;)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitFieldInsn(Opcodes.PUTFIELD, NAV, "path", "L" + PATH + ";");
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.DLOAD, 2);
                                super.visitFieldInsn(Opcodes.PUTFIELD, NAV, "speedModifier", "D");
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitInsn(Opcodes.IRETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 3), maxLocals);
                            }
                        };
                    }
                    if (n.equals("tick") && d.equals("()V") && (access & Opcodes.ACC_ABSTRACT) == 0) {
                        done[0] |= 4;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label go = new Label();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, NAV, "path", "L" + PATH + ";");
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "pending", "(Ljava/lang/Object;)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.DUP);
                                super.visitFieldInsn(Opcodes.GETFIELD, NAV, "tick", "I");
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitInsn(Opcodes.IADD);
                                super.visitFieldInsn(Opcodes.PUTFIELD, NAV, "tick", "I");
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 3), maxLocals);
                            }
                        };
                    }
                    return mv;
                }

                @Override
                public void visitEnd() {
                    MethodVisitor m = super.visitMethod(Opcodes.ACC_PUBLIC, "pentecost$settled", "()V", null, null);
                    m.visitCode();
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NAV, "trimPath", "()V", false);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitFieldInsn(Opcodes.GETFIELD, NAV, "tick", "I");
                    m.visitFieldInsn(Opcodes.PUTFIELD, NAV, "lastStuckCheck", "I");
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitVarInsn(Opcodes.ALOAD, 0);
                    m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NAV, "getTempMobPos", "()Lnet/minecraft/world/phys/Vec3;", false);
                    m.visitFieldInsn(Opcodes.PUTFIELD, NAV, "lastStuckCheckPos", "Lnet/minecraft/world/phys/Vec3;");
                    m.visitInsn(Opcodes.RETURN);
                    m.visitMaxs(2, 1);
                    m.visitEnd();
                    super.visitEnd();
                }
            }, 0);
            if (done[0] != 7) {
                Log.error("Async pathfinding: PathNavigation did not match (" + done[0] + "). Paths are searched the old way.");
                return bytes;
            }
            return writer.toByteArray();
        };
    }
}
