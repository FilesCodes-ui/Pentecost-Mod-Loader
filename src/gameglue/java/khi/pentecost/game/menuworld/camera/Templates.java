package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.camera.Shot.MoveType;
import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The library of camera moves. Each turns a vantage into a shot, with its own random choices. */
public final class Templates {
    private Templates() {}

    public interface Template {
        MoveType type();

        Shot compose(Vantage v, Terrain t, Random r, String id);

        /** Same, for a moment of the day (game ticks). Moves that care about the sun use it. */
        default Shot compose(Vantage v, Terrain t, Random r, String id, double tod) {
            return compose(v, t, r, id);
        }
    }

    public static final List<Template> ALL = List.of(
            new Simple(MoveType.ORBIT, Templates::orbit),
            new Simple(MoveType.DOLLY, Templates::dolly),
            new Simple(MoveType.CRANE, Templates::crane),
            new Simple(MoveType.PUSH_IN, Templates::pushIn),
            new Simple(MoveType.FLY_THROUGH, Templates::flyThrough),
            new Simple(MoveType.REVEAL, Templates::reveal),
            new Simple(MoveType.ESTABLISH, Templates::establish),
            new Simple(MoveType.SPIRAL, MoreMoves::spiral),
            new Simple(MoveType.SKIM, MoreMoves::skim),
            new Simple(MoveType.RIDGE, MoreMoves::ridge),
            new Simple(MoveType.BIRDS_EYE, MoreMoves::birdsEye),
            new Simple(MoveType.TILT_UP, MoreMoves::tiltUp),
            new Timed(MoveType.SUNSET, MoreMoves::sunset),
            new Simple(MoveType.DUTCH, MoreMoves::dutch),
            new Simple(MoveType.TELEPHOTO, MoreMoves::telephoto),
            new Simple(MoveType.FLYBY, MoreMoves::flyby));

    private interface TimedMaker {
        Shot make(Vantage v, Terrain t, Random r, String id, double tod);
    }

    private record Timed(MoveType type, TimedMaker maker) implements Template {
        @Override
        public Shot compose(Vantage v, Terrain t, Random r, String id) {
            return maker.make(v, t, r, id, 0);
        }

        @Override
        public Shot compose(Vantage v, Terrain t, Random r, String id, double tod) {
            return maker.make(v, t, r, id, tod);
        }
    }

    private interface Maker {
        Shot make(Vantage v, Terrain t, Random r, String id);
    }

    private record Simple(MoveType type, Maker maker) implements Template {
        @Override
        public Shot compose(Vantage v, Terrain t, Random r, String id) {
            return maker.make(v, t, r, id);
        }
    }

    private static double between(Random r, double lo, double hi) {
        return lo + r.nextDouble() * (hi - lo);
    }

    private static double ground(Terrain t, Vec p) {
        return Math.max(t.heightAt((int) p.x(), (int) p.z()), t.seaLevel());
    }

    private static Vec forward(Vantage v) {
        return v.focus().sub(v.eye()).withY(0).normalized();
    }

    private static Vec sideways(Vec f) {
        return new Vec(-f.z(), 0, f.x());
    }

    /** Circle the subject, a little higher by the end. */
    static Shot orbit(Vantage v, Terrain t, Random r, String id) {
        Vec c = v.focus();
        Vec off = v.eye().sub(c).withY(0);
        double radius = Math.max(70, Math.min(220, off.horizontal()));
        double a0 = Math.atan2(off.z(), off.x());
        double sweep = Math.toRadians(between(r, 40, 110)) * (r.nextBoolean() ? 1 : -1);
        double rise = between(r, 0, 22);
        List<Vec> pts = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            double f = i / 6.0;
            double a = a0 + sweep * f;
            pts.add(new Vec(c.x() + Math.cos(a) * radius, v.eye().y() + rise * f, c.z() + Math.sin(a) * radius));
        }
        return new Shot(id, MoveType.ORBIT, new Rail(pts), Rail.point(c), between(r, 22, 36), Ease.SMOOTHER,
                65, 65, between(r, -1.5, 1.5), 0.5);
    }

    /** Slide sideways while the aim drifts the other way, so the foreground slides past the view. */
    private static Shot dolly(Vantage v, Terrain t, Random r, String id) {
        Vec side = sideways(forward(v));
        double travel = between(r, 70, 170) * (r.nextBoolean() ? 1 : -1);
        Vec a = v.eye().add(side.scale(-travel / 2));
        Vec b = v.eye().add(side.scale(travel / 2));
        Vec f = v.focus();
        return new Shot(id, MoveType.DOLLY, Rail.line(a, b),
                Rail.line(f.add(side.scale(travel * 0.1)), f.add(side.scale(-travel * 0.1))),
                between(r, 18, 28), Ease.SMOOTH, between(r, 55, 75), between(r, 55, 75), 0, 0.6);
    }

    /** Start low, rise high, and tip up to the horizon. */
    private static Shot crane(Vantage v, Terrain t, Random r, String id) {
        Vec f = forward(v);
        Vec base = v.eye().withY(ground(t, v.eye()) + 6);
        Vec top = base.add(f.scale(40)).withY(base.y() + between(r, 45, 95));
        Vec focus = v.focus();
        return new Shot(id, MoveType.CRANE, Rail.line(base, top),
                Rail.line(focus.add(0, -6, 0), focus.add(f.scale(60)).add(0, 20, 0)),
                between(r, 20, 30), Ease.SMOOTHER, 60, 75, 0, 0.35);
    }

    /** Move straight at the subject. Sometimes the field of view widens as it closes in, the vertigo effect. */
    private static Shot pushIn(Vantage v, Terrain t, Random r, String id) {
        Vec f = forward(v);
        Vec end = v.eye().add(f.scale(between(r, 80, 200))).add(0, -between(r, 0, 12), 0);
        boolean vertigo = r.nextDouble() < 0.3;
        return new Shot(id, MoveType.PUSH_IN, Rail.line(v.eye(), end), Rail.point(v.focus()),
                between(r, 16, 24), Ease.SMOOTH, vertigo ? 45 : 70, vertigo ? 85 : 58, 0, 0.4);
    }

    /** Skim forward along a weaving line close to the ground. */
    private static Shot flyThrough(Vantage v, Terrain t, Random r, String id) {
        Vec f = forward(v);
        Vec side = sideways(f);
        double len = between(r, 160, 320);
        double phase = r.nextDouble() * Math.PI * 2;
        List<Vec> eye = new ArrayList<>();
        List<Vec> look = new ArrayList<>();
        for (int k = 0; k <= 4; k++) {
            eye.add(along(t, v.eye(), f, side, len * k / 4, Math.sin(k * 1.3 + phase) * 22, between(r, 10, 24)));
            look.add(along(t, v.eye(), f, side, len * (k + 1) / 4 + 30, Math.sin((k + 1) * 1.3 + phase) * 22, 6));
        }
        return new Shot(id, MoveType.FLY_THROUGH, new Rail(eye), new Rail(look), between(r, 14, 22), Ease.SMOOTH,
                78, 82, 0, 0.8);
    }

    private static Vec along(Terrain t, Vec origin, Vec f, Vec side, double ahead, double lateral, double clearance) {
        Vec p = origin.add(f.scale(ahead)).add(side.scale(lateral));
        return p.withY(ground(t, p) + clearance);
    }

    /** Start low behind the land, rise over it, and let the view open up. */
    private static Shot reveal(Vantage v, Terrain t, Random r, String id) {
        Vec f = forward(v);
        Vec start = v.eye().withY(ground(t, v.eye()) + 3);
        Vec end = v.eye().add(f.scale(70)).withY(v.eye().y() + 30);
        return new Shot(id, MoveType.REVEAL, Rail.line(start, end),
                Rail.line(v.focus().add(0, 80, 0), v.focus()), between(r, 14, 20), Ease.OUT_QUINT, 60, 68, 0, 0.3);
    }

    /** High and wide, barely moving. Suits night and long looks at the horizon. */
    private static Shot establish(Vantage v, Terrain t, Random r, String id) {
        Vec side = sideways(forward(v));
        Vec a = v.eye().add(0, 50, 0);
        Vec b = a.add(side.scale(between(r, -50, 50))).add(0, between(r, 5, 25), 0);
        return new Shot(id, MoveType.ESTABLISH, Rail.line(a, b),
                Rail.line(v.focus(), v.focus().add(side.scale(between(r, -30, 30)))),
                between(r, 26, 38), Ease.SMOOTHER, 85, 85, 0, 0.15);
    }
}
