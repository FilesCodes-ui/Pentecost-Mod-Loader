package khi.pentecost.game.menuworld.source;

public interface Terrain {
    int heightAt(int x, int z);

    boolean waterAt(int x, int z);

    int colorAt(int x, int z);

    default int seaLevel() {
        return 63;
    }
}
