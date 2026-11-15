package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.camera.Shot.MoveType;
import khi.pentecost.game.menuworld.profile.MenuProfile;
import khi.pentecost.game.menuworld.profile.Poi;
import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Headless check of the profile director and the sequencer on a made-up landscape. No game needed. */
public final class DirectorCheck {
    private static int failures;

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failures++;
    }

    private static final Terrain HILLS = new Terrain() {
        public int heightAt(int x, int z) { return (int) (70 + 20 * Math.sin(x / 90.0) + 15 * Math.cos(z / 70.0)); }
        public boolean waterAt(int x, int z) { return false; }
        public int colorAt(int x, int z) { return 0x55AA55; }
    };

    private static MenuProfile profile(MenuProfile.Travel travel) {
        MenuProfile p = new MenuProfile();
        p.travel = travel;
        p.radiusChunks = 32;
        return p;
    }

    private static Poi point(String name, int x, int z, double weight) {
        Poi p = Poi.of(Poi.Kind.CUSTOM, name, x, 80, z, 48, 32, 48);
        p.weight = weight;
        return p;
    }

    public static void main(String[] args) {
        // 1. No points: the director finds views itself.
        MenuProfile none = profile(MenuProfile.Travel.MIXED);
        ProfileDirector d0 = new ProfileDirector(7, none, HILLS, 0, 0);
        boolean allNull = true;
        for (int i = 0; i < 30; i++) allNull &= d0.next(6000).poi() == null;
        check("no points: region views only", allNull, "");

        // 2. Weights, disabled points, no long runs at one place.
        MenuProfile weighted = profile(MenuProfile.Travel.MIXED);
        Poi a = point("a", 0, 0, 1), b = point("b", 600, 0, 4), c = point("c", 0, 300, 1), d = point("d", 300, 300, 1),
                off = point("off", 0, 600, 5);
        off.enabled = false;
        weighted.points.addAll(List.of(a, b, c, d, off));
        ProfileDirector d1 = new ProfileDirector(11, weighted, HILLS, 0, 0);
        Map<String, Integer> seen = new HashMap<>();
        int longest = 0, run = 0;
        String prev = "";
        for (int i = 0; i < 900; i++) {
            Poi p = d1.next(6000).poi();
            seen.merge(p.name, 1, Integer::sum);
            run = p.name.equals(prev) ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = p.name;
        }
        check("disabled point never shown", !seen.containsKey("off"), seen.toString());
        check("heavier point shown more often", seen.getOrDefault("b", 0) > 1.4 * seen.getOrDefault("a", 0)
                && seen.getOrDefault("b", 0) > 1.4 * seen.getOrDefault("c", 0), seen.toString());
        check("at most 3 shots in a row at one place", longest <= 3, "longest run " + longest);

        // 3. Allowed moves.
        MenuProfile limited = profile(MenuProfile.Travel.CUT);
        Poi o = point("orbit-only", 100, 100, 1);
        o.moves = List.of("orbit", "bogus");
        limited.points.add(o);
        ProfileDirector d2 = new ProfileDirector(3, limited, HILLS, 0, 0);
        boolean onlyOrbit = true;
        for (int i = 0; i < 60; i++) onlyOrbit &= d2.next(i * 400).shot().type() == MoveType.ORBIT;
        check("a point limited to orbit only gets orbits", onlyOrbit, "");

        // 4. Travel modes.
        int[] cutsFlies = travelCounts(MenuProfile.Travel.CUT, 0.35);
        check("old CUT setting is ignored, the camera flies", cutsFlies[0] <= 1, "cuts " + cutsFlies[0]);
        int[] fly = travelCounts(MenuProfile.Travel.FLY, 0.35);
        check("FLY mode flies every hop", fly[0] == 1 && fly[1] == 399, "cuts " + fly[0] + ", flights " + fly[1]);
        int[] mixed = travelCounts(MenuProfile.Travel.MIXED, 0.35);
        check("old MIXED setting is ignored, the camera flies", mixed[0] <= 1, "cuts " + mixed[0]);

        // 5. The sequencer: no jumps without a fade, no snaps while flying.
        MenuProfile run5 = profile(MenuProfile.Travel.MIXED);
        run5.points.addAll(List.of(point("p1", 0, 0, 1), point("p2", 300, 100, 1), point("p3", -400, 250, 1),
                point("p4", 100, -700, 1)));
        double[] clock = {0};
        ShotSequencer seq = new ShotSequencer(new ProfileDirector(21, run5, HILLS, 0, 0), HILLS, () -> clock[0]);
        double dt = 1 / 30.0;
        Pose prevPose = null;
        double prevFade = 0, maxFlyJump = 0, maxFlyTurn = 0;
        int badTurns = 0, cutJumps = 0, badCuts = 0, frames = 20 * 60 * 30;
        Map<MoveType, Integer> moves = new EnumMap<>(MoveType.class);
        Shot lastShot = null;
        for (int i = 0; i < frames; i++) {
            clock[0] = (i * dt * 20 / 4) % 24000; // a day in four minutes
            Pose pose = seq.advance(dt);
            double fade = seq.fade();
            if (seq.currentShot() != lastShot) {
                lastShot = seq.currentShot();
                moves.merge(lastShot.type(), 1, Integer::sum);
            }
            if (prevPose != null) {
                double jump = pose.pos().dist(prevPose.pos());
                double turn = Math.abs(Math.atan2(Math.sin(Math.toRadians(pose.yaw() - prevPose.yaw())),
                        Math.cos(Math.toRadians(pose.yaw() - prevPose.yaw())))) * 180 / Math.PI;
                if (jump > 25) {
                    cutJumps++;
                    if (fade > 0.1 || prevFade > 0.1) badCuts++;
                } else if (fade > 0.5 && prevFade > 0.5) { // only frames the player can see
                    maxFlyJump = Math.max(maxFlyJump, jump);
                    maxFlyTurn = Math.max(maxFlyTurn, turn);
                    if (turn > 5 && badTurns++ < 8) {
                        System.out.println("  fast turn " + String.format("%.1f", turn) + " deg in " + seq.currentShot().type()
                                + " " + seq.currentShot().id() + " frame " + i + " eye " + pose.pos() + " pitch "
                                + String.format("%.1f", pose.pitch()));
                    }
                }
            }
            prevPose = pose;
            prevFade = fade;
        }
        check("every position jump happens under a fade", badCuts == 0, cutJumps + " jumps, " + badCuts + " visible");
        check("flights move smoothly", maxFlyJump < 8, String.format("largest step %.2f blocks per frame", maxFlyJump));
        check("flights turn smoothly", maxFlyTurn < 5, String.format("largest turn %.2f degrees per frame", maxFlyTurn));
        System.out.println("20 minutes of shots: " + moves);

        // 5b. How steeply do shots and flights look down? Straight down makes the aim spin.
        MenuProfile steep = profile(MenuProfile.Travel.FLY);
        steep.points.addAll(run5.points);
        ProfileDirector ds = new ProfileDirector(99, steep, HILLS, 0, 0);
        double worst = 0;
        String worstWhere = "";
        for (int i = 0; i < 300; i++) {
            ProfileDirector.Step st = ds.next(6000);
            for (Shot sh : new Shot[] {st.transit(), st.shot()}) {
                if (sh == null) continue;
                for (int k = 0; k <= 50; k++) {
                    Vec e = sh.eye().at(k / 50.0), l = sh.look().at(k / 50.0);
                    double pitch = Math.toDegrees(Math.atan2(e.y() - l.y(), Math.hypot(e.x() - l.x(), e.z() - l.z())));
                    if (pitch > worst) {
                        worst = pitch;
                        worstWhere = sh.type() + " " + sh.id() + " at " + k * 2 + "%: eye y " + (int) e.y() + ", aim y " + (int) l.y()
                                + ", across " + (int) Math.hypot(e.x() - l.x(), e.z() - l.z());
                    }
                }
            }
        }
        System.out.println("steepest look-down over 300 steps: " + String.format("%.1f", worst) + " deg, " + worstWhere);

        // 6. Centre: spawn or typed coordinates.
        MenuProfile centre = new MenuProfile();
        int[] spawn = centre.resolveCenter(64, -96);
        centre.centerMode = MenuProfile.CenterMode.COORDS;
        centre.centerX = 1000;
        centre.centerZ = -2000;
        int[] typed = centre.resolveCenter(64, -96);
        check("centre is the spawn by default, the typed block otherwise",
                spawn[0] == 64 && spawn[1] == -96 && typed[0] == 1000 && typed[1] == -2000, "");

        // 7. The player's settings take effect.
        MenuProfile g = profile(MenuProfile.Travel.CUT);
        g.moves = List.of("crane");
        ProfileDirector dg = new ProfileDirector(21, g, HILLS, 0, 0);
        boolean allCrane = true;
        for (int i = 0; i < 60; i++) allCrane &= dg.next(6000).shot().type() == MoveType.CRANE;
        check("a global move list limits views the director finds itself", allCrane, "");

        MenuProfile g2 = profile(MenuProfile.Travel.CUT);
        g2.moves = List.of("crane");
        Poi own = point("own", 50, 50, 1);
        own.moves = List.of("orbit");
        g2.points.add(own);
        ProfileDirector dg2 = new ProfileDirector(22, g2, HILLS, 0, 0);
        boolean allOrbit = true;
        for (int i = 0; i < 60; i++) allOrbit &= dg2.next(6000).shot().type() == MoveType.ORBIT;
        check("a point's own moves win over the global list", allOrbit, "");

        MenuProfile len = profile(MenuProfile.Travel.MIXED);
        len.shotSecondsMin = 9;
        len.shotSecondsMax = 11;
        ProfileDirector dl = new ProfileDirector(5, len, HILLS, 0, 0);
        double lo = 1e9, hi = 0;
        for (int i = 0; i < 200; i++) {
            double secs = dl.next(6000).shot().seconds();
            lo = Math.min(lo, secs);
            hi = Math.max(hi, secs);
        }
        check("shot length stays inside the profile's limits", lo >= 9 - 1e-9 && hi <= 11 + 1e-9, String.format("%.1f to %.1f s", lo, hi));

        Shot first = new ProfileDirector(3, profile(MenuProfile.Travel.CUT), HILLS, 0, 0).next(6000).shot();
        CameraRig quick = new CameraRig();
        quick.style(1, 7, 0.5, 0);
        quick.start(first);
        quick.advance(0.4, HILLS);
        CameraRig slow = new CameraRig();
        slow.style(1, 7, 3.0, 0);
        slow.start(first);
        slow.advance(0.4, HILLS);
        check("a shorter fade setting brings the picture up sooner", quick.fade() > 0.7 && slow.fade() < 0.2,
                String.format("%.2f vs %.2f", quick.fade(), slow.fade()));
        CameraRig wide = new CameraRig();
        wide.style(1, 7, 1.2, 10);
        wide.start(first);
        CameraRig plain = new CameraRig();
        plain.style(1, 7, 1.2, 0);
        plain.start(first);
        double fovDiff = wide.advance(1.0, HILLS).fov() - plain.advance(1.0, HILLS).fov();
        check("the field of view offset is added to every shot", Math.abs(fovDiff - 10) < 1e-6, String.format("%.3f", fovDiff));
        CameraRig still = new CameraRig();
        still.style(0, 7, 1.2, 0);
        still.start(first);
        Pose sp = still.advance(1.0, HILLS);
        check("handheld at 0 holds the camera steady", Math.abs(sp.roll() - first.roll()) < 1e-9, String.format("roll %.3f", sp.roll()));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    /** {cuts, flights} over 400 steps between four far-apart points. */
    private static int[] travelCounts(MenuProfile.Travel travel, double cutChance) {
        MenuProfile p = profile(travel);
        p.cutChance = cutChance;
        p.points.addAll(List.of(point("a", 0, 0, 1), point("b", 500, 0, 1), point("c", 0, 500, 1), point("d", 500, 500, 1)));
        ProfileDirector d = new ProfileDirector(5, p, HILLS, 0, 0);
        int cuts = 0, flies = 0;
        for (int i = 0; i < 400; i++) {
            ProfileDirector.Step s = d.next(6000);
            if (s.transit() != null) flies++;
            else cuts++;
        }
        return new int[] {cuts, flies};
    }
}
