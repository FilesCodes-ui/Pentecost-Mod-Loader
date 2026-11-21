package khi.pentecost.game.menuworld.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MenuProfile {
    public enum SourceKind { SEED, SAVE }
    public enum CenterMode { SPAWN, COORDS }
    public enum Travel { FLY, CUT, MIXED }
    public enum TimeMode { CYCLE, REAL_CLOCK, FIXED }
    public enum Targets {
        ALL_ENABLED,
        PLAYER_BUILDS,
        REGION_ONLY
    }

    public static final double PLAYER_STRUCTURE_MINUTES = 5;

    public boolean enabled = false;
    public int version = 3;
    public SourceKind sourceKind = SourceKind.SEED;
    public long seed = 4556775L;
    public String savePath = "";
    public CenterMode centerMode = CenterMode.SPAWN;
    public int centerX = 0;
    public int centerZ = 0;
    public int radiusChunks = 32;
    public boolean scanWholeSave = true;
    public Travel travel = Travel.MIXED;
    public Targets targets = Targets.ALL_ENABLED;
    public double shotSecondsMin = 14;
    public double shotSecondsMax = 30;
    public double cutChance = 0.35;
    public List<String> moves = new ArrayList<>();
    public double handheld = 1.0;
    public double clearance = 7;
    public double maxHeight = 30;
    public double fadeSeconds = 1.2;
    public double fovOffset = 0;
    public TimeMode timeMode = TimeMode.CYCLE;
    public double secondsPerDay = 480;
    public int startTicks = 1000;
    public int viewChunks = 12;
    public boolean mobs = true;
    public boolean eulaAccepted = false;
    public String scanKey = "";
    public String pointsSource = "";
    public Map<String, List<Poi>> stashed = new HashMap<>();
    public List<Poi> points = new ArrayList<>();

    public List<Poi> activePoints() {
        List<Poi> out = new ArrayList<>();
        if (targets == Targets.REGION_ONLY) return out;
        for (Poi p : points) if (p.enabled && p.weight > 0) out.add(p);
        if (targets == Targets.PLAYER_BUILDS) {
            List<Poi> mine = new ArrayList<>();
            for (Poi p : out) {
                boolean visited = p.kind == Poi.Kind.STRUCTURE && p.playerMinutes >= PLAYER_STRUCTURE_MINUTES;
                if (p.kind == Poi.Kind.BUILD || p.kind == Poi.Kind.CUSTOM || visited) mine.add(p);
            }
            if (!mine.isEmpty()) return mine;
        }
        return out;
    }

    public int[] resolveCenter(int spawnX, int spawnZ) {
        return centerMode == CenterMode.SPAWN ? new int[] {spawnX, spawnZ} : new int[] {centerX, centerZ};
    }

    public static MenuProfile forSave(String savePath) {
        MenuProfile p = new MenuProfile();
        p.sourceKind = SourceKind.SAVE;
        p.savePath = savePath;
        p.targets = Targets.PLAYER_BUILDS;
        return p;
    }

    public String sourceKey() {
        if (sourceKind == SourceKind.SEED) return "seed:" + seed;
        return "save:" + (savePath == null ? "" : savePath.replace('\\', '/').toLowerCase(java.util.Locale.ROOT));
    }

    public void switchPointsTo(String key) {
        if (key.equals(pointsSource)) return;
        if (pointsSource.isEmpty()) {
            pointsSource = key;
            return;
        }
        if (!points.isEmpty()) stashed.put(pointsSource, new ArrayList<>(points));
        List<Poi> back = stashed.remove(key);
        points = back != null ? back : new ArrayList<>();
        pointsSource = key;
    }

    public Poi find(String id) {
        for (Poi p : points) if (p.id.equals(id)) return p;
        return null;
    }

    public Poi addCustom(String name, int x, int y, int z, int size) {
        Poi p = Poi.of(Poi.Kind.CUSTOM, name, x, y, z, size, size, size);
        p.edited = true;
        String base = p.id;
        for (int n = 2; find(p.id) != null; n++) p.id = base + "#" + n;
        points.add(p);
        return p;
    }

    public boolean remove(String id) {
        return points.removeIf(p -> p.id.equals(id));
    }

    public int mergeScanned(List<Poi> scanned) {
        Map<String, Poi> byId = new HashMap<>();
        for (Poi p : points) byId.put(p.id, p);
        int added = 0;
        for (Poi s : scanned) {
            Poi old = byId.get(s.id);
            if (old != null) {
                old.playerMinutes = s.playerMinutes;
                old.manMadeBlocks = s.manMadeBlocks;
            }
            if (old == null) {
                points.add(s);
                byId.put(s.id, s);
                added++;
            } else if (!old.edited && old.kind != Poi.Kind.CUSTOM) {
                old.x = s.x; old.y = s.y; old.z = s.z;
                old.sizeX = s.sizeX; old.sizeY = s.sizeY; old.sizeZ = s.sizeZ;
            }
        }
        return added;
    }
}
