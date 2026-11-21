package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.core.LodBridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

final class CpuGovernor {
    private CpuGovernor() {}

    private static final int CORES = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static long next;
    private static int applied = -1;
    private static Path log;

    static int workers() {
        double b = LodBridge.budget();
        int full = Math.max(1, CORES - 1);
        int least = Math.max(2, CORES / 2);
        return Math.max(least, (int) Math.round(least + (full - least) * b));
    }

    static void frame() {
        long now = System.currentTimeMillis();
        if (now < next) return;
        next = now + 1000;
        if ("false".equals(khi.pentecost.control.Settings.get("threads.governor"))) return;
        int want = workers();
        try {
            ExecutorService pool = net.minecraft.Util.backgroundExecutor();
            if (pool instanceof ForkJoinPool fj && want != applied) {
                fj.setParallelism(want);
                applied = want;
            }
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Worker governor off: " + e);
            applied = -2;
        }
        record(now, want);
    }

    private static void record(long now, int workers) {
        double ms = LodBridge.frameMs;
        if (ms <= 0) return;
        try {
            if (log == null) {
                log = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("pentecost-frames.txt");
                Files.createDirectories(log.getParent());
                Files.writeString(log, "time, fps, frame ms, budget, workers allowed\n", StandardCharsets.UTF_8);
            }
            String line = String.format("%tT, %.0f, %.1f, %.2f, %d%n", now, 1000.0 / ms, ms, LodBridge.budget(), workers);
            Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log = null;
        }
    }
}
