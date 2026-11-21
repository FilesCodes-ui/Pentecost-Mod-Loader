package khi.pentecost.game.menuworld.lod;

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
