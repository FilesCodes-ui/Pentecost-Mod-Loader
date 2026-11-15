package khi.pentecost.game.menuworld.camera;

/**
 * Blocks as the camera planner sees them: solid (the camera cannot be there) or not. Land that is not known counts as
 * solid, so a route never leaves what is loaded. Pure interface, so the route planner runs without the game.
 */
public interface Voxels {
    boolean solid(int x, int y, int z);

    /** Lowest block Y of the world. */
    int minY();

    /** One above the highest block Y of the world. */
    int maxY();

    /**
     * Hands out the blocks around a point. It may read files, so call it off the render thread.
     * Returns null when the blocks are not available.
     */
    interface Provider {
        Voxels around(int x, int z, int radius);
    }
}
