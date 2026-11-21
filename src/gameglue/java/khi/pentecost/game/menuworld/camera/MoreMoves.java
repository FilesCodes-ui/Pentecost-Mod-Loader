package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.camera.Shot.MoveType;
import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class MoreMoves {
    private MoreMoves() {}

    private static double between(Random r, double lo, double hi) {
        return lo + r.nextDouble() * (hi - lo);
    }

    private static double ground(Terrain t, Vec p) {
        return GroundField.envelope(t, p.x(), p.z(), 6);
    }

    private static Vec forward(Vantage v) {
        Vec f = v.focus().sub(v.eye()).withY(0).normalized();
        return f.horizontal() < 1e-6 ? new Vec(1, 0, 0) : f;
    }

    private static Vec sideways(Vec f) {
        return new Vec(-f.z(), 0, f.x());
    }

    private enum Heading { WATER, VALLEY, RIDGE }

    private static Shot glide(Vantage v, Terrain t, Random r, String id, MoveType type, Heading kind, double clearance,
                              double len, double lateralLook, double secMin, double secMax, double fov0, double fov1,
                              double bank, double handheld) {
        Vec o = v.eye();
        double bestScore = -1e18;
        double bestA = 0;
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8 + r.nextDouble() * 0.2;
            double score = 0;
            double rough = 0;
            double mean = 0;
            double prev = Double.NaN;
            for (int s = 1; s <= 12; s++) {
                double px = o.x() + Math.cos(a) * len * s / 12;
                double pz = o.z() + Math.sin(a) * len * s / 12;
                double h = GroundField.ground(t, px, pz);
                if (t.waterAt((int) Math.floor(px), (int) Math.floor(pz))) {
                    score += 1;
                }
                if (!Double.isNaN(prev)) {
                    rough += Math.abs(h - prev);
                }
                mean += h / 12.0;
                prev = h;
            }
            double total = switch (kind) {
                case WATER -> score * 2 - rough * 0.03;
                case VALLEY -> -rough * 0.05 - mean * 0.02;
                case RIDGE -> mean * 0.1 - rough * 0.01;
            };
            if (total > bestScore) {
                bestScore = total;
                bestA = a;
            }
        }
        Vec f = new Vec(Math.cos(bestA), 0, Math.sin(bestA));
        Vec sd = sideways(f);
        Vec mid = o.add(f.scale(len / 2));
        double hl = GroundField.ground(t, mid.x() + sd.x() * 60, mid.z() + sd.z() * 60);
        double hr = GroundField.ground(t, mid.x() - sd.x() * 60, mid.z() - sd.z() * 60);
        double lateral = (hl < hr ? 1 : -1) * lateralLook;
        double weave = kind == Heading.RIDGE ? 6 : 14;
        double phase = r.nextDouble() * Math.PI * 2;
        List<Vec> eye = new ArrayList<>();
        List<Vec> look = new ArrayList<>();
        int n = 6;
        for (int k = 0; k <= n; k++) {
            double ahead = len * k / n;
            Vec p = o.add(f.scale(ahead)).add(sd.scale(Math.sin(k * 1.1 + phase) * weave));
            eye.add(p.withY(ground(t, p) + clearance));
            Vec q = o.add(f.scale(ahead + 70)).add(sd.scale(lateral));
            look.add(q.withY(ground(t, q) + (kind == Heading.WATER ? 3 : 4)));
        }
        return new Shot(id, type, new Rail(eye), Rail.uniform(look), between(r, secMin, secMax), Ease.SMOOTH, fov0, fov1,
                0, handheld, bank, clearance, false);
    }

    static Shot skim(Vantage v, Terrain t, Random r, String id) {
        return glide(v, t, r, id, MoveType.SKIM, Heading.WATER, between(r, 3.5, 5), between(r, 240, 340), 12,
                13, 19, 78, 86, 1.0, 0.7);
    }

    static Shot ridge(Vantage v, Terrain t, Random r, String id) {
        return glide(v, t, r, id, MoveType.RIDGE, Heading.RIDGE, between(r, 9, 14), between(r, 220, 320), 55,
                16, 24, 70, 74, 0.7, 0.5);
    }

    static Shot spiral(Vantage v, Terrain t, Random r, String id) {
        Vec c = v.focus();
        double radius = between(r, 95, 160);
        double a0 = Math.atan2(v.eye().z() - c.z(), v.eye().x() - c.x());
        double sweep = Math.toRadians(between(r, 200, 320)) * (r.nextBoolean() ? 1 : -1);
        double rise = between(r, 18, 38);
        List<Vec> pts = new ArrayList<>();
        int n = 10;
        for (int i = 0; i <= n; i++) {
            double f = i / (double) n;
            double a = a0 + sweep * f;
            double x = c.x() + Math.cos(a) * radius;
            double z = c.z() + Math.sin(a) * radius;
            pts.add(new Vec(x, GroundField.envelope(t, x, z, 6) + 9 + rise * f, z));
        }
        return new Shot(id, MoveType.SPIRAL, new Rail(pts), Rail.line(c.add(0, -4, 0), c.add(0, 14, 0)),
                between(r, 28, 40), Ease.SMOOTHER, 62, 70, 0, 0.4, 0.4, 0, false);
    }

    static Shot birdsEye(Vantage v, Terrain t, Random r, String id) {
        Vec c = v.focus();
        double radius = between(r, 34, 52);
        double a0 = r.nextDouble() * Math.PI * 2;
        double sweep = Math.toRadians(between(r, 110, 170)) * (r.nextBoolean() ? 1 : -1);
        List<Vec> pts = new ArrayList<>();
        int n = 8;
        for (int i = 0; i <= n; i++) {
            double a = a0 + sweep * i / n;
            double x = c.x() + Math.cos(a) * radius;
            double z = c.z() + Math.sin(a) * radius;
            pts.add(new Vec(x, GroundField.envelope(t, x, z, 6) + 55, z));
        }
        return new Shot(id, MoveType.BIRDS_EYE, new Rail(pts), Rail.point(c), between(r, 26, 36), Ease.SMOOTHER,
                68, 68, 0, 0.25, 0, 0, false);
    }

    static Shot tiltUp(Vantage v, Terrain t, Random r, String id) {
        Vec f = forward(v);
        Vec a = v.eye().withY(ground(t, v.eye()) + 5);
        Vec b = a.add(f.scale(between(r, 10, 26)));
        Vec lookA = a.add(f.scale(60)).withY(a.y() - 10);
        Vec lookB = v.focus().add(0, between(r, 50, 90), 0);
        return new Shot(id, MoveType.TILT_UP, Rail.line(a, b), Rail.line(lookA, lookB), between(r, 16, 22),
                Ease.SMOOTHER, 68, 60, 0, 0.3, 0, 5, false);
    }

    static Shot sunset(Vantage v, Terrain t, Random r, String id, double tod) {
        double sx = (tod >= 6000 && tod < 18000) ? -1 : 1;
        Vec dir = new Vec(sx, 0, between(r, -0.35, 0.35)).normalized();
        double clear = between(r, 5, 9);
        Vec a = v.eye().withY(ground(t, v.eye()) + clear);
        Vec b0 = a.add(dir.scale(between(r, 50, 90)));
        Vec b = b0.withY(ground(t, b0) + clear + between(r, 0, 10));
        Vec lookA = a.add(dir.scale(300)).withY(a.y() + between(r, 12, 26));
        Vec lookB = b.add(dir.scale(300)).withY(b.y() + between(r, 38, 60));
        return new Shot(id, MoveType.SUNSET, Rail.line(a, b), Rail.line(lookA, lookB), between(r, 18, 26),
                Ease.SMOOTHER, 66, 58, 0, 0.35, 0, 5, false);
    }

    static Shot dutch(Vantage v, Terrain t, Random r, String id) {
        Shot s = Templates.orbit(v, t, r, id);
        double tilt = between(r, 7, 11) * (r.nextBoolean() ? 1 : -1);
        return new Shot(id, MoveType.DUTCH, s.eye(), s.look(), s.seconds(), s.ease(), s.fovFrom(), s.fovTo(), tilt,
                s.handheld(), 0.2, 0, false);
    }

    static Shot telephoto(Vantage v, Terrain t, Random r, String id) {
        Vec f = forward(v);
        Vec focus = v.focus();
        double dist = between(r, 300, 440);
        Vec a0 = focus.sub(f.scale(dist));
        Vec a = a0.withY(GroundField.envelope(t, a0.x(), a0.z(), 6) + between(r, 14, 30));
        Vec b0 = a.add(sideways(f).scale(between(r, -45, 45)));
        Vec b = b0.withY(GroundField.envelope(t, b0.x(), b0.z(), 6) + between(r, 14, 30));
        Vec drift = sideways(f).scale(between(r, -30, 30));
        return new Shot(id, MoveType.TELEPHOTO, Rail.line(a, b), Rail.line(focus, focus.add(drift)),
                between(r, 24, 34), Ease.SMOOTHER, 26, 22, 0, 0.1, 0, 0, false);
    }

    static Shot flyby(Vantage v, Terrain t, Random r, String id) {
        Vec f = forward(v);
        Vec sd = sideways(f);
        double off = between(r, 70, 110) * (r.nextBoolean() ? 1 : -1);
        double len = between(r, 300, 420);
        Vec c = v.focus();
        List<Vec> pts = new ArrayList<>();
        int n = 6;
        double clear = between(r, 14, 22);
        for (int i = 0; i <= n; i++) {
            Vec p = c.add(f.scale(len * (i / (double) n - 0.5))).add(sd.scale(off));
            pts.add(p.withY(ground(t, p) + clear));
        }
        return new Shot(id, MoveType.FLYBY, new Rail(pts), Rail.point(c), between(r, 14, 20), Ease.SMOOTH, 70, 66, 0,
                0.6, 1.0, 0, false);
    }
}
