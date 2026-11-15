package khi.pentecost.game.menuworld.lod;

import khi.pentecost.game.menuworld.source.Terrain;

/**
 * Builds the coarse terrain beyond the real chunks. The rings below say how big a cell is at each distance from
 * the camera. Building samples the terrain on a worker thread. Drawing the tiles is not built yet.
 */
public final class LodBuilder {
    private LodBuilder() {}

    /** Real chunks cover this many blocks around the camera. */
    public static final int REAL_RADIUS = 12 * 16;

    /** Cell size in blocks for a given distance from the camera. */
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

    /** Samples one tile. Each cell takes the middle column's height and colour, and is water if that column is. */
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
