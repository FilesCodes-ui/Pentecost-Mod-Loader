package khi.pentecost.game.menuworld.source;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.SimpleBitStorage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class CacheTerrain implements Terrain {
    private static final int MIN_Y = -64;
    private static final int HEIGHT_BITS = 9;
    private static final int OUTSIDE = 62;

    private final Map<Long, short[]> tops = new HashMap<>();
    private final Map<Long, long[]> wet = new HashMap<>();

    private CacheTerrain() {}

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static CacheTerrain load(ChunkSource source, int minCx, int minCz, int maxCx, int maxCz) throws IOException {
        CacheTerrain t = new CacheTerrain();
        source.forEach(minCx, minCz, maxCx, maxCz, (cx, cz, tag) -> t.add(cx, cz, tag));
        return t;
    }

    private void add(int cx, int cz, CompoundTag tag) {
        String status = tag.getString("Status");
        if (!status.equals("minecraft:full") && !status.equals("full")) return;
        CompoundTag maps = tag.getCompound("Heightmaps");
        if (!maps.contains("MOTION_BLOCKING", Tag.TAG_LONG_ARRAY)) return;
        SimpleBitStorage top = storage(maps, "MOTION_BLOCKING");
        if (top == null) return;
        SimpleBitStorage noLeaves = storage(maps, "MOTION_BLOCKING_NO_LEAVES");
        SimpleBitStorage floor = storage(maps, "OCEAN_FLOOR");
        short[] heights = new short[256];
        long[] water = new long[4];
        for (int i = 0; i < 256; i++) {
            heights[i] = (short) (top.get(i) + MIN_Y - 1);
            if (noLeaves != null && floor != null && noLeaves.get(i) > floor.get(i)) water[i >> 6] |= 1L << (i & 63);
        }
        long k = key(cx, cz);
        tops.put(k, heights);
        wet.put(k, water);
    }

    private static SimpleBitStorage storage(CompoundTag maps, String name) {
        if (!maps.contains(name, Tag.TAG_LONG_ARRAY)) return null;
        try {
            return new SimpleBitStorage(HEIGHT_BITS, 256, maps.getLongArray(name));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public int chunkCount() {
        return tops.size();
    }

    private static int index(int x, int z) {
        return (x & 15) | ((z & 15) << 4);
    }

    @Override
    public int heightAt(int x, int z) {
        short[] h = tops.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
        return h == null ? OUTSIDE : h[index(x, z)];
    }

    @Override
    public boolean waterAt(int x, int z) {
        long[] w = wet.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
        if (w == null) return false;
        int i = index(x, z);
        return (w[i >> 6] & (1L << (i & 63))) != 0;
    }

    @Override
    public int colorAt(int x, int z) {
        if (waterAt(x, z)) return 0x3F76E4;
        int h = heightAt(x, z);
        if (h > 150) return 0xEDEDED;
        if (h > 100) return 0x8A8A8A;
        return h < 66 ? 0xD8CC8F : 0x5E9B3A;
    }
}
