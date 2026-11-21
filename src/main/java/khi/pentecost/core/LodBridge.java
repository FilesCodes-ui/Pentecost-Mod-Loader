package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;

public final class LodBridge {
    private LodBridge() {}

    private static final String[][] HOOKS = {
            {"com.seibel.distanthorizons.common.wrappers.minecraft.AbstractMinecraftSharedWrapper_forge", "isServerThreadHealthy", "()Z", "getServerTickTimesNano"},
            {"com.seibel.distanthorizons.common.wrappers.minecraft.AbstractMinecraftSharedWrapper", "isServerThreadHealthy", "()Z", "getServerTickTimesNano"},
    };

    private static volatile boolean lastHealthy = true;

    public static volatile double frameMs;
    public static volatile int frameLimit;
    private static volatile long behindSince;

    private static double budget = 1.0;
    private static long budgetAt;

    public static double budget() {
        long now = System.currentTimeMillis();
        if (now - budgetAt >= 1000) {
            budgetAt = now;
            double ms = frameMs;
            if (ms > 0) {
                double want = 1000.0 / targetFps();
                if (ms > want * 1.1) budget = Math.max(0.15, budget * 0.6);
                else if (ms < want) budget = Math.min(1.0, budget + 0.1);
            }
        }
        return budget;
    }

    public static int targetFps() {
        int t = 75;
        try {
            String s = khi.pentecost.control.Settings.get("lod.targetFps");
            if (s != null) t = Integer.parseInt(s.trim());
        } catch (RuntimeException ignored) {
        }
        int limit = frameLimit;
        if (limit > 0 && limit < 260) t = Math.min(t, limit * 9 / 10);
        return Math.max(20, t);
    }

    static boolean framesOk() {
        double ms = frameMs;
        if (ms <= 0) return true;
        long now = System.currentTimeMillis();
        if (ms <= 1000.0 / targetFps() * 1.1) {
            behindSince = 0;
            return true;
        }
        if (behindSince == 0) behindSince = now;
        return ((now - behindSince) % 4000) < 1000;
    }
    private static volatile long lastLog;

    public static boolean healthy(long[] tickNanos) {
        if (tickNanos == null || tickNanos.length == 0) return framesOk();
        long[] t = tickNanos.clone();
        Arrays.sort(t);
        double avgMs = Arrays.stream(t).average().orElse(0) / 1e6;
        double p90Ms = t[Math.max(0, (int) Math.ceil(t.length * 0.9) - 1)] / 1e6;
        boolean ok = avgMs < 40 && p90Ms < 50 && framesOk();
        if (ok != lastHealthy && System.currentTimeMillis() - lastLog > 10_000) {
            lastLog = System.currentTimeMillis();
            Log.info(String.format("Distant land generation %s: server average tick %.1f ms, 9 in 10 under %.1f ms, frame %.1f ms (target %d fps)",
                    ok ? "on" : "slowed", avgMs, p90Ms, frameMs, targetFps()));
        }
        lastHealthy = ok;
        return ok;
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            for (String[] h : HOOKS) {
                if (h[0].equals(name) && System.getenv("PENTECOST_NO_LOD_BRIDGE") == null) return patch(name, bytes, h);
            }
            return bytes;
        };
    }

    static byte[] patch(String name, byte[] bytes, String[] hook) {
        try {
            ClassReader reader = new ClassReader(bytes);
            boolean[] hasTimes = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if (n.equals(hook[3]) && d.equals("()[J")) hasTimes[0] = true;
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            if (!hasTimes[0]) return bytes;
            ClassWriter writer = new ClassWriter(0);
            String owner = name.replace('.', '/');
            boolean[] done = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals(hook[1]) || !d.equals(hook[2]) || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
                        return mv;
                    }
                    done[0] = true;
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, hook[3], "()[J", false);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/LodBridge", "healthy", "([J)Z", false);
                    mv.visitInsn(Opcodes.IRETURN);
                    mv.visitMaxs(1, 1);
                    mv.visitEnd();
                    return null;
                }
            }, 0);
            if (!done[0]) return bytes;
            Log.info("LOD bridge: " + name + " asks Pentecost when distant land may be generated");
            return writer.toByteArray();
        } catch (RuntimeException e) {
            Log.warn("LOD bridge skipped " + name + ": " + e);
            return bytes;
        }
    }
}
