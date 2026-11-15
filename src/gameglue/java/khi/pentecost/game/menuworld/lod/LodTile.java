package khi.pentecost.game.menuworld.lod;

/**
 * A square of coarse terrain, one value per cell. Cells are `cell` blocks wide, the tile is `size` x `size` cells,
 * and it starts at block (tileX * size * cell, tileZ * size * cell).
 */
public record LodTile(int tileX, int tileZ, int cell, int size, short[] height, int[] color, boolean[] water) {

    public int index(int cx, int cz) {
        return cz * size + cx;
    }

    public int blockX() {
        return tileX * size * cell;
    }

    public int blockZ() {
        return tileZ * size * cell;
    }
}
