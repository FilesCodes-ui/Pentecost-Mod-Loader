package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.source.Terrain;

public final class GroundField {
    private GroundField() {}

    public static double ground(Terrain t, double x, double z) {
        return Math.max(t.heightAt((int) Math.floor(x), (int) Math.floor(z)), t.seaLevel());
    }

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
