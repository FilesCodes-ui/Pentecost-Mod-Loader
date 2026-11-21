package khi.pentecost.game.menuworld.camera;

import java.util.ArrayList;
import java.util.List;

public final class Rail {
    private static final int PER_SEGMENT = 48;

    private final Vec[] samples;
    private final double[] along;
    private final double length;
    private final boolean uniform;

    public static Rail uniform(List<Vec> control) {
        return new Rail(control, true);
    }

    public Rail(List<Vec> control) {
        this(control, false);
    }

    private Rail(List<Vec> control, boolean uniform) {
        this.uniform = uniform;
        if (control.isEmpty()) {
            throw new IllegalArgumentException("A rail needs at least one point");
        }
        List<Vec> pts = new ArrayList<>(control);
        if (pts.size() == 1) {
            pts.add(pts.get(0));
        }
        List<Vec> out = new ArrayList<>();
        int n = pts.size();
        for (int i = 0; i < n - 1; i++) {
            Vec p0 = pts.get(Math.max(0, i - 1));
            Vec p1 = pts.get(i);
            Vec p2 = pts.get(i + 1);
            Vec p3 = pts.get(Math.min(n - 1, i + 2));
            for (int s = 0; s < PER_SEGMENT; s++) {
                out.add(catmullRom(p0, p1, p2, p3, s / (double) PER_SEGMENT));
            }
        }
        out.add(pts.get(n - 1));
        samples = out.toArray(new Vec[0]);
        along = new double[samples.length];
        double sum = 0;
        for (int i = 1; i < samples.length; i++) {
            sum += samples[i].dist(samples[i - 1]);
            along[i] = sum;
        }
        length = sum;
    }

    public static Rail line(Vec a, Vec b) {
        return new Rail(List.of(a, b));
    }

    public static Rail point(Vec a) {
        return new Rail(List.of(a));
    }

    private static Vec catmullRom(Vec p0, Vec p1, Vec p2, Vec p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return new Vec(
                spline(p0.x(), p1.x(), p2.x(), p3.x(), t, t2, t3),
                spline(p0.y(), p1.y(), p2.y(), p3.y(), t, t2, t3),
                spline(p0.z(), p1.z(), p2.z(), p3.z(), t, t2, t3));
    }

    private static double spline(double a, double b, double c, double d, double t, double t2, double t3) {
        return 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
    }

    public double length() {
        return length;
    }

    public Vec at(double u) {
        if (uniform) {
            double pos = Math.max(0, Math.min(1, u)) * (samples.length - 1);
            int i = (int) Math.floor(pos);
            if (i >= samples.length - 1) {
                return samples[samples.length - 1];
            }
            return Vec.lerp(samples[i], samples[i + 1], pos - i);
        }
        if (length < 1e-9) {
            return samples[0];
        }
        double target = Math.max(0, Math.min(1, u)) * length;
        int lo = 0;
        int hi = along.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (along[mid] <= target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = along[hi] - along[lo];
        double f = span < 1e-9 ? 0 : (target - along[lo]) / span;
        return Vec.lerp(samples[lo], samples[hi], f);
    }
}
