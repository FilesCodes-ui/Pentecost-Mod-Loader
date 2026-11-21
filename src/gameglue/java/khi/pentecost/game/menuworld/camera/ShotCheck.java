package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.camera.Shot.MoveType;
import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class ShotCheck {
    private ShotCheck() {}

    public record Rating(double score, boolean reject, String why) {}

    private static final int SAMPLES = 24;

    public static Rating rate(Shot s, Terrain t, int[] eyeBox) {
        if (s.guided()) {
            return new Rating(1, false, "");
        }
        int blocked = 0;
        int looks = 0;
        double closest = 1e18;
        double pitchSum = 0;
        double lo = 1e18;
        double hi = -1e18;
        int wet = 0;
        for (int i = 0; i < SAMPLES; i++) {
            double u = i / (double) (SAMPLES - 1);
            Vec e = s.eye().at(u);
            Vec a = s.look().at(u);
            if (eyeBox != null && (e.x() < eyeBox[0] || e.z() < eyeBox[1] || e.x() > eyeBox[2] || e.z() > eyeBox[3])) {
                return new Rating(-1, true, "leaves the loaded land");
            }
            Vec d = a.sub(e);
            closest = Math.min(closest, d.length());
            pitchSum += Math.toDegrees(Math.atan2(d.y(), d.horizontal()));
            if (t.waterAt((int) Math.floor(e.x()), (int) Math.floor(e.z()))) {
                wet++;
            }
            if (i % 3 == 0) {
                for (int k = 2; k <= 10; k++) {
                    Vec p = Vec.lerp(e, a, k / 10.0);
                    double h = t.heightAt((int) Math.floor(p.x()), (int) Math.floor(p.z()));
                    looks++;
                    if (h + 1 > p.y() && k < 10) {
                        blocked++;
                    }
                    lo = Math.min(lo, h);
                    hi = Math.max(hi, h);
                }
            }
        }
        double blockedFrac = looks == 0 ? 0 : blocked / (double) looks;
        if (blockedFrac > 0.35) {
            return new Rating(1 - 1.6 * blockedFrac, true, "stares into the land");
        }
        double score = 1 - 1.6 * blockedFrac;
        if (closest < 18 && s.type() != MoveType.BIRDS_EYE) {
            score -= 0.3;
        }
        double pitch = pitchSum / SAMPLES;
        if (pitch < -65 && s.type() != MoveType.BIRDS_EYE) {
            score -= 0.3;
        }
        score += 0.15 * Math.min(1, (hi - lo) / 60.0);
        if (wet > SAMPLES * 0.3) {
            score += 0.1;
        }
        return new Rating(score, false, "");
    }

    private static final Set<MoveType> FRAMED = EnumSet.of(MoveType.ORBIT, MoveType.DOLLY, MoveType.PUSH_IN, MoveType.REVEAL,
            MoveType.ESTABLISH, MoveType.SPIRAL, MoveType.DUTCH, MoveType.TELEPHOTO, MoveType.CRANE);

    public static Shot frame(Shot s, Random r) {
        if (s.guided() || !FRAMED.contains(s.type())) {
            return s;
        }
        double sign = r.nextBoolean() ? 1 : -1;
        int n = 12;
        List<Vec> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            Vec e = s.eye().at(u);
            Vec a = s.look().at(u);
            Vec dir = a.sub(e).withY(0);
            double h = dir.horizontal();
            if (h < 1e-6) {
                pts.add(a);
                continue;
            }
            Vec side = new Vec(-dir.z() / h, 0, dir.x() / h);
            double shift = Math.min(40, 0.10 * h) * sign;
            pts.add(a.add(side.scale(shift)));
        }
        return s.withRails(s.eye(), Rail.uniform(pts));
    }
}
