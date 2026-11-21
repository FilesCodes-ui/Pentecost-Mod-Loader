package khi.pentecost.game.menuworld.camera;

public interface Voxels {
    boolean solid(int x, int y, int z);

    int minY();

    int maxY();

    interface Provider {
        Voxels around(int x, int z, int radius);
    }
}
