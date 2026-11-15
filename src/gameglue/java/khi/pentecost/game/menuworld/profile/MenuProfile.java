package khi.pentecost.game.menuworld.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the player can set about the menu world, in one object that is saved as JSON. The settings screen and
 * the point editor change this and nothing else, so they can be built and tested separately from the renderer.
 */
public final class MenuProfile {
    public enum SourceKind { SEED, SAVE }
    /** SPAWN centres on the world's spawn point (the default). COORDS centres on centerX, centerZ. */
    public enum CenterMode { SPAWN, COORDS }
    /** FLY glides between places, CUT jumps with a fade, MIXED does both. */
    public enum Travel { FLY, CUT, MIXED }
    /** CYCLE runs a day in secondsPerDay. REAL_CLOCK follows the player's clock. FIXED holds startTicks. */
    public enum TimeMode { CYCLE, REAL_CLOCK, FIXED }
    /** ALL_ENABLED visits the enabled points. REGION_ONLY ignores the list and lets the director find views. */
    public enum Targets {
        ALL_ENABLED,
        /**
         * Places a player made: builds the scanner found from player-placed blocks, custom points, and structures a
         * player spent time in. When the world has none of those, it falls back to ALL_ENABLED so the menu is never empty.
         */
        PLAYER_BUILDS,
        REGION_ONLY
    }

    /** Minutes a player must have spent near a vanilla structure for PLAYER_BUILDS to count it. */
    public static final double PLAYER_STRUCTURE_MINUTES = 5;

    /** Draw the live world behind the main menu. Off until the player turns it on. */
    public boolean enabled = false;
    public int version = 3;
    public SourceKind sourceKind = SourceKind.SEED;
    /** Used when sourceKind is SEED. Defaults to the first catalog seed. */
    public long seed = 4556775L;
    /** Folder of a Minecraft save, used when sourceKind is SAVE. */
    public String savePath = "";
    public CenterMode centerMode = CenterMode.SPAWN;
    public int centerX = 0;
    public int centerZ = 0;
    /** How far from the centre to load, in chunks. 32 is about 1000 blocks across. */
    public int radiusChunks = 32;
    /** For a SAVE: scan every region file the save has instead of only the box around the centre, to find all builds. */
    public boolean scanWholeSave = true;
    public Travel travel = Travel.MIXED;
    public Targets targets = Targets.ALL_ENABLED;
    public double shotSecondsMin = 14;
    public double shotSecondsMax = 30;
    /** In MIXED travel, the chance that the next shot cuts to a new place instead of flying there. */
    public double cutChance = 0.35;
    /** Camera moves allowed everywhere (names as in Shot.MoveType). Empty means every move. A point's own list wins. */
    public List<String> moves = new ArrayList<>();
    /** 0 holds the camera still, 1 is the normal handheld drift, 2 doubles it. */
    public double handheld = 1.0;
    /** Least height of the camera above the ground and water, in blocks. */
    public double clearance = 7;
    /** The camera stays at most this far above the ground. A low camera sees less land, so less is loaded and drawn. */
    public double maxHeight = 30;
    /** Length of the fade at each cut, in seconds. */
    public double fadeSeconds = 1.2;
    /** Degrees added to the field of view of every shot. */
    public double fovOffset = 0;
    public TimeMode timeMode = TimeMode.CYCLE;
    /** Real seconds a whole day lasts in CYCLE mode. */
    public double secondsPerDay = 480;
    /** Time of day at the start, in game ticks: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight. */
    public int startTicks = 1000;
    /** Chunks around the camera that are really loaded. */
    public int viewChunks = 12;
    /** Animals in view (when the ambient life system is built). */
    public boolean mobs = true;
    /** The player agreed to the Mojang EULA for generating a seed. Generation never starts without it. */
    public boolean eulaAccepted = false;
    /** Which scan the points came from, so a world that did not change is not scanned again at every start. */
    public String scanKey = "";
    /** The world the points belong to: "seed:123" or "save:<folder>". Points of other worlds wait in stashed. */
    public String pointsSource = "";
    public Map<String, List<Poi>> stashed = new HashMap<>();
    public List<Poi> points = new ArrayList<>();

    /** Enabled points with a weight above zero. Empty when the player chose REGION_ONLY. */
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

    /** The block the area is centred on: the world's spawn, or the typed coordinates. {x, z}. */
    public int[] resolveCenter(int spawnX, int spawnZ) {
        return centerMode == CenterMode.SPAWN ? new int[] {spawnX, spawnZ} : new int[] {centerX, centerZ};
    }

    /** Defaults for showing one of the player's own worlds: spawn centre, and prefer what the player built. */
    public static MenuProfile forSave(String savePath) {
        MenuProfile p = new MenuProfile();
        p.sourceKind = SourceKind.SAVE;
        p.savePath = savePath;
        p.targets = Targets.PLAYER_BUILDS;
        return p;
    }

    /** The key of the world this profile points at now. Places belong to one world, so each world keeps its own. */
    public String sourceKey() {
        if (sourceKind == SourceKind.SEED) return "seed:" + seed;
        return "save:" + (savePath == null ? "" : savePath.replace('\\', '/').toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Makes the list of points the one for {@code key}. The points of the world left behind are kept aside and come
     * back when the player returns to it. A profile from before this existed adopts its points for the first world.
     */
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

    /** Adds a point the player placed by hand. It is marked edited so a rescan never touches it. */
    public Poi addCustom(String name, int x, int y, int z, int size) {
        Poi p = Poi.of(Poi.Kind.CUSTOM, name, x, y, z, size, size, size);
        p.edited = true;
        // two custom points in the same 16-block cell get distinct ids
        String base = p.id;
        for (int n = 2; find(p.id) != null; n++) p.id = base + "#" + n;
        points.add(p);
        return p;
    }

    public boolean remove(String id) {
        return points.removeIf(p -> p.id.equals(id));
    }

    /**
     * Folds a fresh scan into the list. Points the scan found again keep the player's changes (enabled, weight,
     * moves, name) and only get a refreshed position and size if the player never edited them. New finds are added.
     * Points the scan no longer finds stay, because the player may have changed them or the area may be smaller.
     * Returns how many points were added.
     */
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
