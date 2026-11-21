package khi.pentecost.game.pregen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FlightCone {
    static final double SPINE_WEIGHT = 2.0;
    private static final double MAX_REACH = 128.0;
    private static final double MIN_HALF_ANGLE = 0.17;
    private static final double MAX_HALF_ANGLE = 0.9;

    private FlightCone() {}

    static double halfAngle(double turnRadPerSec) {
        return Math.max(MIN_HALF_ANGLE, Math.min(MAX_HALF_ANGLE, MIN_HALF_ANGLE + turnRadPerSec * 1.5));
    }

    static double reach(double bps, double lookaheadSeconds, int viewRadius) {
        return Math.min(MAX_REACH, bps / 16.0 * lookaheadSeconds + viewRadius);
    }

    private record Item(double key, long packed) implements Comparable<Item> {
        @Override
        public int compareTo(Item o) {
            int c = Double.compare(key, o.key);
            return c != 0 ? c : Long.compare(packed, o.packed);
        }
    }

    static long[] ordered(double px, double pz, double dirX, double dirZ, double bps, double halfAngle,
                          int viewRadius, double lookaheadSeconds, int maxChunks) {
        double len = Math.hypot(dirX, dirZ);
        if (len < 1e-6 || maxChunks <= 0) {
            return new long[0];
        }
        dirX /= len;
        dirZ /= len;
        double pcx = px / 16.0;
        double pcz = pz / 16.0;
        double reach = reach(bps, lookaheadSeconds, viewRadius);
        double tan = Math.tan(Math.max(0.05, Math.min(1.2, halfAngle)));
        double maxLateral = viewRadius + reach * tan;
        int r = (int) Math.ceil(Math.max(reach, maxLateral)) + 1;
        int baseX = (int) Math.floor(pcx);
        int baseZ = (int) Math.floor(pcz);
        List<Item> items = new ArrayList<>();
        for (int cx = baseX - r; cx <= baseX + r; cx++) {
            for (int cz = baseZ - r; cz <= baseZ + r; cz++) {
                double rx = cx + 0.5 - pcx;
                double rz = cz + 0.5 - pcz;
                double along = rx * dirX + rz * dirZ;
                if (along < 0 || along > reach) {
                    continue;
                }
                double lateral = Math.abs(rx * dirZ - rz * dirX);
                if (lateral > viewRadius + along * tan) {
                    continue;
                }
                items.add(new Item(along + lateral * SPINE_WEIGHT, PregenPlan.pack(cx, cz)));
            }
        }
        Collections.sort(items);
        int n = Math.min(maxChunks, items.size());
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = items.get(i).packed();
        }
        return out;
    }
}
