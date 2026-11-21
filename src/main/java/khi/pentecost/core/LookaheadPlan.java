package khi.pentecost.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class LookaheadPlan {
    public record Result(long[] keys, float[] seconds) {
        public static final Result EMPTY = new Result(new long[0], new float[0]);

        public int size() {
            return keys.length;
        }
    }

    public static final double STEP_SECONDS = 0.25;
    public static final double MAX_REACH_CHUNKS = 96;
    private static final double TURN_FADE_SECONDS = 2.5;
    private static final double SPREAD = 0.08;
    private static final double MAX_SPREAD = 5;

    private LookaheadPlan() {}

    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >> 32);
    }

    public static double horizon(double speedBps, double maxSeconds) {
        if (speedBps <= 0) {
            return 0;
        }
        return Math.min(maxSeconds, MAX_REACH_CHUNKS * 16 / speedBps);
    }

    public static Result plan(double px, double pz, double vx, double vz, double turnRadPerSec, int view,
                              double horizonSec, int maxChunks) {
        double speed = Math.hypot(vx, vz);
        if (speed < 0.5 || horizonSec <= 0 || maxChunks <= 0) {
            return Result.EMPTY;
        }
        int startX = (int) Math.floor(px / 16.0);
        int startZ = (int) Math.floor(pz / 16.0);
        double heading = Math.atan2(vz, vx);
        double x = px;
        double z = pz;
        double travelled = 0;
        Map<Long, Float> first = new HashMap<>();
        int steps = (int) Math.ceil(horizonSec / STEP_SECONDS);
        for (int i = 1; i <= steps; i++) {
            double t = i * STEP_SECONDS;
            heading += turnRadPerSec * Math.exp(-t / TURN_FADE_SECONDS) * STEP_SECONDS;
            x += Math.cos(heading) * speed * STEP_SECONDS;
            z += Math.sin(heading) * speed * STEP_SECONDS;
            travelled += speed * STEP_SECONDS / 16.0;
            if (travelled > MAX_REACH_CHUNKS) {
                break;
            }
            int cx = (int) Math.floor(x / 16.0);
            int cz = (int) Math.floor(z / 16.0);
            int r = view + (int) Math.floor(Math.min(MAX_SPREAD, travelled * SPREAD));
            float when = (float) t;
            for (int ax = cx - r; ax <= cx + r; ax++) {
                boolean nearX = Math.abs(ax - startX) <= view;
                for (int az = cz - r; az <= cz + r; az++) {
                    if (nearX && Math.abs(az - startZ) <= view) {
                        continue;
                    }
                    first.putIfAbsent(pack(ax, az), when);
                }
            }
        }
        int n = first.size();
        long[] keys = new long[n];
        float[] secs = new float[n];
        Long[] order = first.keySet().toArray(new Long[0]);
        Arrays.sort(order, (a, b) -> {
            int c = Float.compare(first.get(a), first.get(b));
            if (c != 0) {
                return c;
            }
            long da = dist2(a, startX, startZ);
            long db = dist2(b, startX, startZ);
            return da != db ? Long.compare(da, db) : Long.compare(a, b);
        });
        int keep = Math.min(maxChunks, n);
        for (int i = 0; i < keep; i++) {
            keys[i] = order[i];
            secs[i] = first.get(order[i]);
        }
        return new Result(Arrays.copyOf(keys, keep), Arrays.copyOf(secs, keep));
    }

    private static long dist2(long key, int sx, int sz) {
        long dx = x(key) - sx;
        long dz = z(key) - sz;
        return dx * dx + dz * dz;
    }
}
