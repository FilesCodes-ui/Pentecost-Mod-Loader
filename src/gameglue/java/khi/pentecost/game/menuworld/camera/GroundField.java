package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.source.Terrain;

/**
 * The ground as a camera should feel it: not the exact height under one point, but the highest land within a small
 * footprint around it. A camera that follows this does not hop over single blocks, trees or roof edges.
 */
public final class GroundField {
    private GroundField() {}

    /** Ground or water surface at a point. */
    public static double ground(Terrain t, double x, double z) {
        return Math.max(t.heightAt((int) Math.floor(x), (int) Math.floor(z)), t.seaLevel());
    }

    /** The highest ground within {@code radius} blocks of the point (nine samples). */
    public static double envelope(Terrain t, double x, double z, double radius) {
        double m = ground(t, x, z);
        if (radius <= 0) {
            return m;
        }
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            m = Math.max(m, ground(t, x + Math.cos(a) * radius, z + Math.sin(a) * radius));
        }
        return m;
    }
}
