package khi.pentecost.game.pregen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The chunks in front of a flying player, in the order they should be made. No game classes, so it can be checked
 * headless.
 * <p>
 * The shape is a band as wide as the player can see, widening with distance by an angle that grows when they are
 * turning. The order is spine first: chunks on the line they are flying along come before the chunks at the sides, and
 * near ones before far ones. So if generation cannot keep up with the flight, the player still has ground straight
 * ahead, and the edges fill in behind it.
 */
final class FlightCone {
    /** How much a chunk to the side counts against a chunk straight ahead when ordering. */
    static final double SPINE_WEIGHT = 2.0;
    private static final double MAX_REACH = 128.0;
    private static final double MIN_HALF_ANGLE = 0.17; // about 10 degrees
    private static final double MAX_HALF_ANGLE = 0.9;  // about 50 degrees

    private FlightCone() {}

    /** Half the cone angle, in radians: a straight flight is narrow, a turning one is wider. */
    static double halfAngle(double turnRadPerSec) {
        return Math.max(MIN_HALF_ANGLE, Math.min(MAX_HALF_ANGLE, MIN_HALF_ANGLE + turnRadPerSec * 1.5));
    }

    /** How far ahead, in chunks, the cone reaches. */
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

    /**
     * @param px, pz           player position in blocks
     * @param dirX, dirZ       direction of flight (any length except zero)
     * @param bps              speed in blocks per second
     * @param halfAngle        see {@link #halfAngle}
     * @param viewRadius       how far the player sees, in chunks: the band is this wide on each side of the line
     * @param lookaheadSeconds how many seconds of flight the cone covers
     * @param maxChunks        at most this many are returned (the first ones in order)
     * @return chunks packed with {@link PregenPlan#pack}, best first
     */
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
