package khi.pentecost.game.pregen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PregenPlan {
    public static final int REGION = 32;

    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int[] regionX;
    private final int[] regionZ;
    private final long[] first;

    public PregenPlan(int centerChunkX, int centerChunkZ, int radiusChunks) {
        if (radiusChunks < 1) {
            throw new IllegalArgumentException("radius must be at least 1 chunk");
        }
        minX = centerChunkX - radiusChunks;
        minZ = centerChunkZ - radiusChunks;
        maxX = centerChunkX + radiusChunks;
        maxZ = centerChunkZ + radiusChunks;

        int rx0 = Math.floorDiv(minX, REGION);
        int rx1 = Math.floorDiv(maxX - 1, REGION);
        int rz0 = Math.floorDiv(minZ, REGION);
        int rz1 = Math.floorDiv(maxZ - 1, REGION);
        List<int[]> regions = new ArrayList<>();
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                regions.add(new int[] {rx, rz});
            }
        }
        final double cx = centerChunkX;
        final double cz = centerChunkZ;
        regions.sort(Comparator
                .comparingDouble((int[] r) -> {
                    double dx = r[0] * REGION + REGION / 2.0 - cx;
                    double dz = r[1] * REGION + REGION / 2.0 - cz;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(r -> r[0])
                .thenComparingInt(r -> r[1]));

        int n = regions.size();
        regionX = new int[n];
        regionZ = new int[n];
        first = new long[n + 1];
        for (int i = 0; i < n; i++) {
            regionX[i] = regions.get(i)[0];
            regionZ[i] = regions.get(i)[1];
            first[i + 1] = first[i] + width(i) * (long) depth(i);
        }
    }

    private int x0(int i) {
        return Math.max(minX, regionX[i] * REGION);
    }

    private int z0(int i) {
        return Math.max(minZ, regionZ[i] * REGION);
    }

    private int width(int i) {
        return Math.min(maxX, regionX[i] * REGION + REGION) - x0(i);
    }

    private int depth(int i) {
        return Math.min(maxZ, regionZ[i] * REGION + REGION) - z0(i);
    }

    public long total() {
        return first[regionX.length];
    }

    public int regions() {
        return regionX.length;
    }

    public long chunkAt(long index) {
        if (index < 0 || index >= total()) {
            throw new IndexOutOfBoundsException("chunk number " + index + " of " + total());
        }
        int lo = 0;
        int hi = regionX.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (first[mid] <= index) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        long within = index - first[lo];
        int w = width(lo);
        int x = x0(lo) + (int) (within % w);
        int z = z0(lo) + (int) (within / w);
        return pack(x, z);
    }

    public int regionOf(long index) {
        int lo = 0;
        int hi = regionX.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (first[mid] <= index) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static int x(long packed) {
        return (int) packed;
    }

    public static int z(long packed) {
        return (int) (packed >> 32);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    @Override
    public String toString() {
        return (maxX - minX) + "x" + (maxZ - minZ) + " chunks from " + minX + "," + minZ + ", " + total()
                + " chunks in " + regions() + " region file(s)";
    }
}
