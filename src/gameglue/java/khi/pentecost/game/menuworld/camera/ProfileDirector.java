package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.camera.Shot.MoveType;
import khi.pentecost.game.menuworld.profile.MenuProfile;
import khi.pentecost.game.menuworld.profile.Poi;
import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

public final class ProfileDirector {

    public record Step(Shot transit, Shot shot, Poi poi, boolean cut) {}

    private static final double FLY_MAX = 2600;
    private static final double STAY_CHANCE = 0.3;
    private static final int STAY_MAX = 3;
    private static final int CANDIDATES = 3;
    private static final double STREET_CHANCE = 0.5;

    private final MenuProfile profile;
    private final Terrain terrain;
    private final ShotDirector shots;
    private final Random rnd;
    private final Deque<String> recent = new ArrayDeque<>();
    private final Set<String> noRoute = new HashSet<>();
    private final Set<String> noStreet = new HashSet<>();
    private Poi last;
    private int atLast;
    private double lastAngle = Double.NaN;
    private Vec endEye;
    private Vec endLook;
    private int counter;
    private Voxels.Provider voxels;
    private int[] area;
    private int[] eyeBox;

    public ProfileDirector(long masterSeed, MenuProfile profile, Terrain terrain, int centerX, int centerZ) {
        this.profile = profile;
        this.terrain = terrain;
        this.shots = new ShotDirector(masterSeed, terrain, centerX, centerZ, Math.max(64, (int) (profile.radiusChunks * 16 * 0.8)));
        this.rnd = new Random(masterSeed ^ 0x9E3779B97F4A7C15L);
    }

    public void voxels(Voxels.Provider provider) {
        this.voxels = provider;
    }

    public void area(int minX, int minZ, int maxX, int maxZ) {
        area = minX < maxX && minZ < maxZ ? new int[] {minX, minZ, maxX, maxZ} : null;
    }

    public void eyeBox(int minX, int minZ, int maxX, int maxZ) {
        eyeBox = minX < maxX && minZ < maxZ ? new int[] {minX, minZ, maxX, maxZ} : null;
    }

    public Step next(double timeOfDay) {
        Set<MoveType> global = parseMoves(profile.moves);
        List<Poi> candidates = new ArrayList<>(inside(profile.activePoints()));
        if (voxels != null && profile.targets != MenuProfile.Targets.REGION_ONLY && allows(global, MoveType.TUNNEL)) {
            Set<String> have = new HashSet<>();
            for (Poi p : candidates) have.add(p.id);
            for (Poi p : inside(profile.points)) {
                if (!have.contains(p.id) && p.weight > 0 && hiddenUnderground(p)) candidates.add(p);
            }
        }
        candidates.removeIf(p -> noRoute.contains(p.id));

        Poi poi = null;
        Shot shot = null;
        for (int tries = 0; tries < 6 && !candidates.isEmpty() && shot == null; tries++) {
            Poi p = choose(candidates);
            Set<MoveType> own = moves(p);
            Set<MoveType> allowed = own != null ? own : global;
            if (isUnderground(p)) {
                Shot s = allows(allowed, MoveType.TUNNEL) ? tunnelShot(p) : null;
                if (s == null) {
                    noRoute.add(p.id);
                    candidates.remove(p);
                    last = null;
                    atLast = 0;
                    continue;
                }
                poi = p;
                shot = s;
            } else {
                poi = p;
                if (isVillage(p) && voxels != null && !noStreet.contains(p.id) && allows(allowed, MoveType.STREET)
                        && rnd.nextDouble() < STREET_CHANCE) {
                    shot = streetShot(p);
                    if (shot == null) noStreet.add(p.id);
                }
                if (shot == null) shot = bestOpenShot(poi, timeOfDay, allowed);
            }
        }
        if (shot == null) {
            poi = null;
            last = null;
            atLast = 0;
            shot = bestOpenShot(null, timeOfDay, global);
        }
        shots.note(shot.type());

        Shot transit = null;
        boolean cut = true;
        if (endEye != null && !shot.guided()) {
            Vec toEye = shot.eye().at(0);
            double dist = endEye.dist(toEye);
            if (wantsFlight(dist)) {
                transit = transit(shot, dist);
                cut = false;
            }
        }
        if (shot.guided()) {
            endEye = null;
            endLook = null;
        } else {
            endEye = shot.eye().at(1);
            endLook = shot.look().at(1);
        }
        return new Step(transit, shot, poi, cut);
    }

    private static boolean allows(Set<MoveType> allowed, MoveType type) {
        return allowed == null || allowed.contains(type);
    }

    private Shot bestOpenShot(Poi poi, double tod, Set<MoveType> allowed) {
        Shot best = null;
        double bestScore = -1e18;
        boolean bestRejected = true;
        Set<MoveType> tried = EnumSet.noneOf(MoveType.class);
        for (int c = 0; c < CANDIDATES; c++) {
            Shot s;
            if (poi == null) {
                s = shots.candidate(tod, allowed, tried);
            } else {
                Vantage v = vantage(poi);
                s = limitApproach(shots.nextAt(v, tod, allowed, tried), v.focus());
            }
            tried.add(s.type());
            s = polish(s);
            ShotCheck.Rating rating = ShotCheck.rate(s, terrain, eyeBox);
            boolean better = best == null || (bestRejected && !rating.reject()) || (bestRejected == rating.reject() && rating.score() > bestScore);
            if (better) {
                best = s;
                bestScore = rating.score();
                bestRejected = rating.reject();
            }
            if (!rating.reject() && rating.score() > 0.85) break;
        }
        return best;
    }

    private Shot polish(Shot s) {
        if (s.guided()) return s;
        double clear = Math.max(3, s.clearance() > 0 ? s.clearance() : profile.clearance);
        PathFit.Limits lim = PathFit.Limits.of(clear, Math.max(profile.maxHeight, clear + 6));
        Shot fitted = s.withRails(PathFit.fit(s.eye(), terrain, lim), s.look());
        return clampLength(ShotCheck.frame(fitted, rnd));
    }

    private static boolean isVillage(Poi p) {
        return p.structureId != null && p.structureId.contains("village");
    }

    private static boolean hiddenUnderground(Poi p) {
        String id = p.structureId == null ? "" : p.structureId;
        return id.contains("mineshaft") || id.contains("stronghold") || id.contains("ancient_city");
    }

    private boolean isUnderground(Poi p) {
        if (hiddenUnderground(p)) return true;
        return p.kind == Poi.Kind.STRUCTURE && p.y < ground(p.x, p.z) - 14;
    }

    private Shot tunnelShot(Poi p) {
        if (voxels == null) return null;
        int rx = Math.min(48, p.sizeX / 2 + 10);
        int rz = Math.min(48, p.sizeZ / 2 + 10);
        int ry = Math.min(36, p.sizeY / 2 + 8);
        Voxels vox = voxels.around(p.x, p.z, Math.max(rx, rz) + 16);
        if (vox == null) return null;
        WalkRoute.Route r = WalkRoute.plan(vox, WalkRoute.Mode.TUNNEL, p.x, p.y, p.z, rx, ry, rz, rnd, 130);
        return r == null ? null : guided(r, MoveType.TUNNEL, 3.6, 80, 0.4, 0.5);
    }

    private Shot streetShot(Poi p) {
        if (voxels == null) return null;
        int rx = Math.min(56, Math.max(24, p.sizeX / 2 + 6));
        int rz = Math.min(56, Math.max(24, p.sizeZ / 2 + 6));
        int cy = (int) ground(p.x, p.z) - 6;
        Voxels vox = voxels.around(p.x, p.z, Math.max(rx, rz) + 16);
        if (vox == null) return null;
        WalkRoute.Route r = WalkRoute.plan(vox, WalkRoute.Mode.STREET, p.x, cy, p.z, rx, 16, rz, rnd, 110);
        return r == null ? null : guided(r, MoveType.STREET, 4.0, 74, 0.5, 0.45);
    }

    private Shot guided(WalkRoute.Route r, MoveType type, double speed, double fov, double bank, double handheld) {
        double seconds = Math.max(12, Math.min(48, r.length() / speed));
        return new Shot("shot-" + counter++, type, Rail.uniform(r.eye()), Rail.uniform(r.look()), seconds, Ease.SMOOTH,
                fov, fov, 0, handheld, bank, 0, true);
    }

    private List<Poi> inside(List<Poi> all) {
        if (area == null) return all;
        List<Poi> out = new ArrayList<>();
        for (Poi p : all) {
            if (p.x >= area[0] && p.x <= area[2] && p.z >= area[1] && p.z <= area[3]) out.add(p);
        }
        return out;
    }

    private boolean wantsFlight(double dist) {
        return dist <= FLY_MAX;
    }

    private Poi choose(List<Poi> active) {
        Poi pick = null;
        if (last != null && atLast < STAY_MAX && active.size() > 1 && rnd.nextDouble() < STAY_CHANCE) {
            for (Poi p : active) {
                if (p.id.equals(last.id)) pick = p;
            }
        }
        if (pick == null) {
            double total = 0;
            double[] w = new double[active.size()];
            for (int i = 0; i < w.length; i++) {
                Poi p = active.get(i);
                w[i] = Math.max(0, p.weight);
                if (active.size() > 1) w[i] *= recencyFactor(p.id);
                if (active.size() > 1 && last != null && atLast >= STAY_MAX && p.id.equals(last.id)) w[i] = 0;
                total += w[i];
            }
            double roll = rnd.nextDouble() * total;
            pick = active.get(active.size() - 1);
            for (int i = 0; i < w.length; i++) {
                roll -= w[i];
                if (roll <= 0) {
                    pick = active.get(i);
                    break;
                }
            }
        }
        if (last != null && pick.id.equals(last.id)) {
            atLast++;
        } else {
            atLast = 1;
            lastAngle = Double.NaN;
            recent.addFirst(pick.id);
            while (recent.size() > Math.max(0, Math.min(3, active.size() - 1))) recent.removeLast();
        }
        last = pick;
        return pick;
    }

    private double recencyFactor(String id) {
        int age = 0;
        for (String r : recent) {
            if (r.equals(id)) return age == 0 ? 0.05 : age == 1 ? 0.4 : 0.7;
            age++;
        }
        return 1;
    }

    private Vantage vantage(Poi p) {
        double r = Math.max(100, Math.min(260, Math.max(p.sizeX, p.sizeZ) * 0.8 + 50));
        double a = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 6 && !Double.isNaN(lastAngle); i++) {
            double diff = Math.abs(Math.atan2(Math.sin(a - lastAngle), Math.cos(a - lastAngle)));
            if (diff >= Math.toRadians(50)) break;
            a = rnd.nextDouble() * Math.PI * 2;
        }
        lastAngle = a;
        int ex = p.x + (int) (Math.cos(a) * r);
        int ez = p.z + (int) (Math.sin(a) * r);
        double focusGround = ground(p.x, p.z);
        double fy = p.y >= focusGround - 4 ? p.y + Math.min(p.sizeY, 60) * 0.3 : focusGround + Math.min(p.sizeY, 40) * 0.4;
        double rise = Math.min(profile.maxHeight * 0.6, 10 + rnd.nextDouble() * (p.sizeY * 0.5 + 25));
        double eyeY = Math.min(Math.max(ground(ex, ez), focusGround) + rise, ground(ex, ez) + profile.maxHeight);
        return new Vantage(new Vec(ex, eyeY, ez), new Vec(p.x, fy, p.z), 1.0);
    }

    static Shot limitApproach(Shot s, Vec focus) {
        if (s.type() != MoveType.PUSH_IN && s.type() != MoveType.REVEAL) return s;
        Vec start = s.eye().at(0);
        Vec end = s.eye().at(1);
        double toFocus = Math.hypot(focus.x() - start.x(), focus.z() - start.z());
        double travel = Math.hypot(end.x() - start.x(), end.z() - start.z());
        double room = toFocus - 80;
        if (travel <= room || travel < 1e-6) return s;
        double k = Math.max(0.1, room / travel);
        Vec shorter = start.add(end.sub(start).scale(k));
        return s.withRails(Rail.line(start, shorter), s.look());
    }

    static Set<MoveType> moves(Poi p) {
        return parseMoves(p.moves);
    }

    private Shot clampLength(Shot s) {
        double seconds = Math.max(profile.shotSecondsMin, Math.min(profile.shotSecondsMax, s.seconds()));
        if (Math.abs(seconds - s.seconds()) < 1e-9) return s;
        return s.withSeconds(seconds);
    }

    static Set<MoveType> parseMoves(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        Set<MoveType> out = EnumSet.noneOf(MoveType.class);
        for (String raw : names) {
            if (raw == null) continue;
            String n = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            n = switch (n) {
                case "ESTABLISHING" -> "ESTABLISH";
                case "FLYTHROUGH" -> "FLY_THROUGH";
                case "PUSHIN" -> "PUSH_IN";
                case "BIRDSEYE" -> "BIRDS_EYE";
                case "TILTUP" -> "TILT_UP";
                default -> n;
            };
            try {
                MoveType t = MoveType.valueOf(n);
                if (t != MoveType.TRACK && t != MoveType.TRANSIT) out.add(t);
            } catch (IllegalArgumentException unknown) {
            }
        }
        return out.isEmpty() ? null : out;
    }

    private Shot transit(Shot to, double dist) {
        Vec toEye = to.eye().at(0);
        Vec toLook = to.look().at(0);
        double clear = Math.max(6, Math.min(profile.clearance, profile.maxHeight * 0.6));
        double lift = Math.min(Math.max(20, profile.maxHeight), 14 + dist * 0.04);
        int n = (int) Math.max(8, Math.min(140, Math.ceil(dist / 18)));
        int m = n + 1;
        double[] x = new double[m], z = new double[m], want = new double[m], floor = new double[m], cap = new double[m];
        for (int i = 0; i < m; i++) {
            double f = i / (double) n;
            Vec e = Vec.lerp(endEye, toEye, f);
            x[i] = e.x();
            z[i] = e.z();
            double env = GroundField.envelope(terrain, e.x(), e.z(), 6);
            want[i] = e.y() + lift * Math.sin(Math.PI * f);
            floor[i] = env + clear;
            cap[i] = env + Math.max(profile.maxHeight, clear + 6);
        }
        double[] y = PathFit.fitHeights(floor, want, cap, Math.max(0.5, dist / n),
                PathFit.Limits.of(clear, Math.max(profile.maxHeight, clear + 6)), true);
        List<Vec> eye = new ArrayList<>(m);
        for (int i = 0; i < m; i++) eye.add(new Vec(x[i], y[i], z[i]));

        Vec off0 = endLook.sub(endEye);
        Vec off1 = toLook.sub(toEye);
        double a0 = Math.atan2(off0.z(), off0.x());
        double da = Math.atan2(Math.sin(Math.atan2(off1.z(), off1.x()) - a0), Math.cos(Math.atan2(off1.z(), off1.x()) - a0));
        List<Vec> look = new ArrayList<>(m);
        for (int j = 0; j < m; j++) {
            double f = j / (double) n;
            Vec e = eye.get(j);
            double a = a0 + da * f;
            double h = off0.horizontal() + (off1.horizontal() - off0.horizontal()) * f;
            double dy = off0.y() + (off1.y() - off0.y()) * f;
            look.add(new Vec(e.x() + Math.cos(a) * h, e.y() + dy, e.z() + Math.sin(a) * h));
        }
        double seconds = Math.max(8, Math.min(60, dist / 40));
        return new Shot("transit-" + counter++, MoveType.TRANSIT, Rail.uniform(eye), Rail.uniform(look), seconds,
                Ease.CRUISE, 70, 70, 0, 0.5, 0.8, 0, false);
    }

    private double ground(int x, int z) {
        return Math.max(terrain.heightAt(x, z), terrain.seaLevel());
    }
}
