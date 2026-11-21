package khi.pentecost.game;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class ChunkDrain {
    public static volatile int lastTasks;
    public static volatile double lastMs;
    public static volatile int lastWaiting;
    private static int failures;

    private ChunkDrain() {}

    public static void tick(Object serverObject) {
        if (failures >= 3 || System.getenv("PENTECOST_NO_CHUNK_DRAIN") != null
                || !(serverObject instanceof MinecraftServer server)) {
            return;
        }
        try {
            int waiting = 0;
            for (ServerLevel level : server.getAllLevels()) {
                waiting += level.getChunkSource().getPendingTasksCount();
            }
            lastWaiting = waiting;
            long budgetNs = waiting > 500 ? 15_000_000L : waiting > 50 ? 8_000_000L : waiting > 0 ? 3_000_000L : 0;
            if (budgetNs == 0) {
                lastTasks = 0;
                lastMs = 0;
                return;
            }
            long start = System.nanoTime();
            long end = start + budgetNs;
            int tasks = 0;
            while (System.nanoTime() < end) {
                boolean any = false;
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.getChunkSource().pollTask()) {
                        any = true;
                        tasks++;
                    }
                }
                if (!any) {
                    break;
                }
            }
            lastTasks = tasks;
            lastMs = (System.nanoTime() - start) / 1e6;
        } catch (RuntimeException e) {
            if (++failures >= 3) {
                khi.pentecost.boot.Log.error("Extra chunk work is off until restart: " + e);
            }
        }
    }
}
