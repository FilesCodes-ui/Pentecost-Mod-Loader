package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** A place to put the camera and the thing it looks at, with a score for how good the view is. */
public record Vantage(Vec eye, Vec focus, double score) {

    /**
     * Looks for good views around a centre. Each try picks a spot, a height above the ground and a direction, then
     * scores the view: how open the line of sight is, how much the land rises and falls along it, how high the
     * viewpoint stands over its surroundings, and whether water is in view.
     */
    public static List<Vantage> scan(Terrain t, int centerX, int centerZ, int radius, int keep, Random rnd) {
        List<Vantage> found = new ArrayList<>();
        int tries = keep * 12;
        for (int i = 0; i < tries; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(rnd.nextDouble()) * radius;
            int x = centerX + (int) (Math.cos(a) * r);
            int z = centerZ + (int) (Math.sin(a) * r);
            double ground = t.heightAt(x, z);
            double eyeY = Math.max(ground, t.seaLevel()) + 6 + rnd.nextDouble() * 44;
            Vec eye = new Vec(x, eyeY, z);

            double look = rnd.nextDouble() * Math.PI * 2;
            double dist = 120 + rnd.nextDouble() * 380;
            int fx = x + (int) (Math.cos(look) * dist);
            int fz = z + (int) (Math.sin(look) * dist);
            Vec focus = new Vec(fx, Math.max(t.heightAt(fx, fz), t.seaLevel()) + 8, fz);

            found.add(new Vantage(eye, focus, rate(t, eye, focus, ground)));
        }
        found.sort(Comparator.comparingDouble(Vantage::score).reversed());
        return new ArrayList<>(found.subList(0, Math.min(keep, found.size())));
    }

    private static double rate(Terrain t, Vec eye, Vec focus, double ground) {
        int steps = 10;
        int open = 0;
        boolean water = false;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 1; i <= steps; i++) {
            double f = i / (double) steps;
            Vec p = Vec.lerp(eye, focus, f);
            int h = t.heightAt((int) p.x(), (int) p.z());
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
            if (h < p.y()) {
                open++;
            }
            water |= t.waterAt((int) p.x(), (int) p.z());
        }
        double openness = open / (double) steps;
        double relief = Math.min(1, (hi - lo) / 80.0);
        double ring = 0;
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2;
            ring += t.heightAt((int) (eye.x() + Math.cos(a) * 64), (int) (eye.z() + Math.sin(a) * 64));
        }
        double prominence = Math.max(0, Math.min(1, (ground - ring / 4) / 40.0));
        return 0.45 * openness + 0.25 * relief + 0.2 * prominence + (water ? 0.1 : 0);
    }
}
