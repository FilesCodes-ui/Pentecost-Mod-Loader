package khi.pentecost.messenger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits mobs into islands that cannot touch each other, and spreads the islands over threads.
 *
 * The world is cut into square cells (CELL blocks wide). Cells with mobs that touch, side or corner, join one island.
 * Two islands are therefore at least one empty cell apart, too far for a mob in one to reach a mob in the other
 * within one tick. Each island goes whole to one thread, biggest first onto the least loaded thread.
 */
public final class Islands {
    /** Cell width in blocks: 2 chunks. */
    public static final int CELL = 32;

    private Islands() {}

    /** The cell a block coordinate is in. */
    public static int cell(double blockCoord) {
        return (int) Math.floor(blockCoord) >> 5;
    }

    /**
     * For each item (given by its cell), the thread it runs on, 0 to threads - 1.
     * Items in the same island always get the same thread.
     */
    public static int[] assign(int[] cellX, int[] cellZ, int threads) {
        int n = cellX.length;
        int[] out = new int[n];
        if (n == 0 || threads <= 1) {
            return out;
        }
        Map<Long, Integer> cellIndex = new HashMap<>();
        List<long[]> cells = new ArrayList<>();
        int[] cellOf = new int[n];
        for (int i = 0; i < n; i++) {
            long key = key(cellX[i], cellZ[i]);
            Integer c = cellIndex.get(key);
            if (c == null) {
                c = cells.size();
                cellIndex.put(key, c);
                cells.add(new long[]{cellX[i], cellZ[i]});
            }
            cellOf[i] = c;
        }
        int[] parent = new int[cells.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int c = 0; c < cells.size(); c++) {
            long x = cells.get(c)[0];
            long z = cells.get(c)[1];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Integer o = cellIndex.get(key((int) x + dx, (int) z + dz));
                    if (o != null) {
                        union(parent, c, o);
                    }
                }
            }
        }
        int[] size = new int[parent.length];
        for (int i = 0; i < n; i++) {
            size[find(parent, cellOf[i])]++;
        }
        List<Integer> roots = new ArrayList<>();
        for (int c = 0; c < parent.length; c++) {
            if (find(parent, c) == c) {
                roots.add(c);
            }
        }
        roots.sort(Comparator.comparingInt((Integer r) -> size[r]).reversed().thenComparingInt(r -> r));
        int[] load = new int[threads];
        int[] threadOfRoot = new int[parent.length];
        for (int r : roots) {
            int best = 0;
            for (int t = 1; t < threads; t++) {
                if (load[t] < load[best]) {
                    best = t;
                }
            }
            load[best] += size[r];
            threadOfRoot[r] = best;
        }
        for (int i = 0; i < n; i++) {
            out[i] = threadOfRoot[find(parent, cellOf[i])];
        }
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a);
        int rb = find(p, b);
        if (ra != rb) {
            p[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
