package khi.pentecost.game.menuworld.lod;

import khi.pentecost.game.menuworld.source.Terrain;

public final class LodBuilder {
    private LodBuilder() {}

    public static final int REAL_RADIUS = 12 * 16;

    public static int cellFor(double distance) {
        if (distance < REAL_RADIUS) {
            return 1;
        }
        if (distance < 512) {
            return 4;
        }
        if (distance < 2048) {
            return 16;
        }
        return 64;
    }

    public static LodTile build(Terrain terrain, int tileX, int tileZ, int cell, int size) {
        short[] height = new short[size * size];
        int[] color = new int[size * size];
        boolean[] water = new boolean[size * size];
        int baseX = tileX * size * cell;
        int baseZ = tileZ * size * cell;
        for (int cz = 0; cz < size; cz++) {
            for (int cx = 0; cx < size; cx++) {
                int x = baseX + cx * cell + cell / 2;
                int z = baseZ + cz * cell + cell / 2;
                int i = cz * size + cx;
                height[i] = (short) terrain.heightAt(x, z);
                color[i] = terrain.colorAt(x, z);
                water[i] = terrain.waterAt(x, z);
            }
        }
        return new LodTile(tileX, tileZ, cell, size, height, color, water);
    }
}
