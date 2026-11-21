package khi.pentecost.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import khi.pentecost.core.PartProbe;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

final class PerfDips {
    private static final String[] NAMES = {"Terrain", "Chunk culling", "Chunk uploads", "Entities", "Block entities",
            "Particles", "Sky and weather", "Shadow pass", "Other world work", "HUD and overlays", "Other drawing",
            "Game ticks", "Everything else", "Garbage collection"};
    private static final int GC = 13;
    private static final double STALL_MS = 20.0;

    private static final double[] parts = new double[12];
    private static final double[] value = new double[NAMES.length];
    private static final double[] usual = new double[NAMES.length];
    private static final int[] count = new int[NAMES.length];
    private static final double[] worst = new double[NAMES.length];
    private static final double[] secSum = new double[NAMES.length];
    private static final double[] secMax = new double[NAMES.length];
    private static final List<String> stalls = new ArrayList<>();
    private static int secFrames;
    private static int dips;
    private static int frames;
    private static double slowFrame;
    private static double threshold;
    private static long lastGcMs = -1;

    private PerfDips() {}

    static void reset() {
        java.util.Arrays.fill(count, 0);
        java.util.Arrays.fill(worst, 0);
        java.util.Arrays.fill(usual, 0);
        dips = 0;
        frames = 0;
        slowFrame = 0;
        lastGcMs = -1;
    }

    static void record(double frameMs, double workMs) {
        PartProbe.takeFrameParts(parts);
        long gcNow = gcMs();
        double gcDelta = lastGcMs < 0 ? 0 : Math.max(0, gcNow - lastGcMs);
        lastGcMs = gcNow;

        double world = parts[PartProbe.WORLD];
        double sub = 0;
        int[] order = {PartProbe.TERRAIN, PartProbe.CULL, PartProbe.UPLOAD, PartProbe.ENTITIES,
                PartProbe.BLOCK_ENTITIES, PartProbe.PARTICLES, PartProbe.SKY, PartProbe.SHADOW};
        for (int i = 0; i < order.length; i++) {
            value[i] = parts[order[i]];
            sub += value[i];
        }
        value[8] = Math.max(0, world - sub);
        value[9] = parts[PartProbe.HUD];
        value[10] = Math.max(0, parts[PartProbe.RENDER] - world - parts[PartProbe.HUD]);
        value[11] = parts[PartProbe.CLIENT_TICK];
        value[12] = Math.max(0, workMs - parts[PartProbe.RENDER] - parts[PartProbe.CLIENT_TICK]);
        value[GC] = gcDelta;

        for (int i = 0; i < value.length; i++) {
            secSum[i] += value[i];
            secMax[i] = Math.max(secMax[i], value[i]);
        }
        secFrames++;

        if (slowFrame == 0) {
            slowFrame = frameMs;
        }
        threshold = Math.max(12.0, slowFrame * 1.25);
        frames++;
        boolean slow = frameMs > threshold && frames > 30;
        boolean stall = frameMs > STALL_MS && frames > 30;
        if (slow || stall) {
            int blame = 0;
            double most = -1;
            for (int i = 0; i < value.length; i++) {
                double over = value[i] - usual[i];
                if (over > most) {
                    most = over;
                    blame = i;
                }
            }
            if (slow) {
                dips++;
                count[blame]++;
                worst[blame] = Math.max(worst[blame], value[blame]);
            }
            if (stall && stalls.size() < 20) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%.1f ms frame, blamed %s at %.1f ms (usual %.1f)", frameMs, NAMES[blame],
                        value[blame], usual[blame]));
                for (int i = 0; i < value.length; i++) {
                    if (i != blame && value[i] > 1.0) {
                        sb.append(String.format(", %s %.1f", NAMES[i], value[i]));
                    }
                }
                stalls.add(sb.toString());
            }
        }
        if (!slow) {
            slowFrame = slowFrame * 0.99 + frameMs * 0.01;
        }
        for (int i = 0; i < value.length; i++) {
            usual[i] = usual[i] * 0.98 + value[i] * 0.02;
        }
    }

    static List<String[]> rows() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"Slow frames", dips + " of " + frames + String.format(" (over %.1f ms)", threshold)});
        boolean[] used = new boolean[NAMES.length];
        for (int n = 0; n < 6; n++) {
            int best = -1;
            for (int i = 0; i < NAMES.length; i++) {
                if (!used[i] && count[i] > 0 && (best < 0 || count[i] > count[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            used[best] = true;
            out.add(new String[]{"  " + NAMES[best], count[best] + " dips, worst " + String.format("%.1f ms", worst[best])});
        }
        return out;
    }

    static void drainSecond(JsonObject o) {
        JsonObject avg = new JsonObject();
        JsonObject max = new JsonObject();
        for (int i = 0; i < NAMES.length; i++) {
            avg.addProperty(NAMES[i], round(secFrames == 0 ? 0 : secSum[i] / secFrames));
            max.addProperty(NAMES[i], round(secMax[i]));
        }
        JsonObject tick = new JsonObject();
        tick.addProperty("whole", round(PartProbe.frameMs(PartProbe.CLIENT_TICK)));
        tick.addProperty("entities", round(PartProbe.frameMs(PartProbe.TICK_ENTITIES)));
        tick.addProperty("ambient", round(PartProbe.frameMs(PartProbe.TICK_AMBIENT)));
        tick.addProperty("blockEntities", round(PartProbe.frameMs(PartProbe.TICK_BLOCK_ENTITIES)));
        tick.addProperty("particles", round(PartProbe.frameMs(PartProbe.TICK_PARTICLES)));
        o.add("tickAvgMs", tick);
        o.add("partsAvgMs", avg);
        o.add("partsMaxMs", max);
        JsonArray st = new JsonArray();
        for (String s : stalls) {
            st.add(s);
        }
        o.add("stalls", st);
        o.addProperty("slowFramesTotal", dips);
        o.addProperty("framesTotal", frames);
        o.addProperty("slowThresholdMs", round(threshold));
        java.util.Arrays.fill(secSum, 0);
        java.util.Arrays.fill(secMax, 0);
        secFrames = 0;
        stalls.clear();
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static long gcMs() {
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            t += Math.max(0, b.getCollectionTime());
        }
        return t;
    }
}
