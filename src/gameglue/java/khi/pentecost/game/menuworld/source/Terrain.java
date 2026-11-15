package khi.pentecost.game.menuworld.source;

/**
 * What the camera and LOD stack know about the world: one answer per column. The real implementation reads the
 * pregenerated cache. Tests use a made-up landscape. Coordinates are block coordinates.
 */
public interface Terrain {
    /** Y of the highest solid or water surface block. */
    int heightAt(int x, int z);

    /** True when the surface at this column is water. */
    boolean waterAt(int x, int z);

    /** The colour of the surface, 0xRRGGBB, as the map would show it. */
    int colorAt(int x, int z);

    default int seaLevel() {
        return 63;
    }
}
