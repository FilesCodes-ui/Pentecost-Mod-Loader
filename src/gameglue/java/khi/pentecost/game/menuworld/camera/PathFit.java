package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayList;
import java.util.List;

public final class PathFit {
    private PathFit() {}

    public static final double STEP = 5.0;

    public record Limits(double clearance, double maxHeight, double footprint, double upSlope, double downSlope) {
        public static Limits of(double clearance, double maxHeight) {
            return new Limits(clearance, maxHeight, 6, 0.5, 0.3);
        }
    }

    public static Rail fit(Rail eye, Terrain t, Limits lim) {
        double len = eye.length();
        int n = Math.max(6, (int) Math.ceil(len / STEP));
        int m = n + 1;
        double[] x = new double[m];
        double[] z = new double[m];
        double[] want = new double[m];
        double[] floor = new double[m];
        double[] cap = new double[m];
        for (int i = 0; i < m; i++) {
            Vec p = eye.at(i / (double) n);
            x[i] = p.x();
            z[i] = p.z();
            want[i] = p.y();
            double env = GroundField.envelope(t, p.x(), p.z(), lim.footprint());
            floor[i] = env + lim.clearance();
            cap[i] = env + lim.maxHeight();
        }
        double[] y = fitHeights(floor, want, cap, Math.max(0.5, len / n), lim, true);
        List<Vec> pts = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            pts.add(new Vec(x[i], y[i], z[i]));
        }
        return new Rail(pts);
    }

    public static double[] fitHeights(double[] floor, double[] want, double[] cap, double d, Limits lim, boolean pinEnds) {
        int n = floor.length;
        double[] cone = new double[n];
        for (int i = 0; i < n; i++) {
            double best = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < n; j++) {
                double dist = Math.abs(i - j) * d;
                double slope = j > i ? lim.upSlope() : lim.downSlope();
                best = Math.max(best, floor[j] - slope * dist);
            }
            cone[i] = best;
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = Math.max(cone[i], Math.min(want[i], cap[i]));
        }
        double start = y[0];
        double end = y[n - 1];
        for (int pass = 0; pass < 4; pass++) {
            y = blur(blur(y));
            for (int i = 0; i < n; i++) {
                y[i] = Math.max(y[i], cone[i]);
            }
        }
        if (pinEnds && n > 2) {
            double d0 = start - y[0];
            double d1 = end - y[n - 1];
            double blend = 40.0;
            for (int i = 0; i < n; i++) {
                double a = Math.max(0, 1 - i * d / blend);
                double b = Math.max(0, 1 - (n - 1 - i) * d / blend);
                a = a * a * (3 - 2 * a);
                b = b * b * (3 - 2 * b);
                y[i] = Math.max(cone[i], y[i] + d0 * a + d1 * b);
            }
        }
        return y;
    }

    private static double[] blur(double[] a) {
        int n = a.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double l = a[Math.max(0, i - 1)];
            double r = a[Math.min(n - 1, i + 1)];
            out[i] = (l + 2 * a[i] + r) / 4.0;
        }
        return out;
    }
}
