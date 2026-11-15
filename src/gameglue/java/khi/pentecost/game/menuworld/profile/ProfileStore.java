package khi.pentecost.game.menuworld.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads and writes the profile. A missing or unreadable file gives the defaults and never throws. */
public final class ProfileStore {
    private ProfileStore() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path file(Path pentecostConfig) {
        return pentecostConfig.resolve("menu_world").resolve("profile.json");
    }

    /** An independent copy. The background worker reads a copy, so a settings change never races with it. */
    public static MenuProfile copy(MenuProfile p) {
        return sanitize(GSON.fromJson(GSON.toJson(p), MenuProfile.class));
    }

    public static MenuProfile load(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                MenuProfile p = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MenuProfile.class);
                if (p != null) return sanitize(p);
            }
        } catch (Exception e) {
            // A broken file must not stop the game from starting. Fall through to the defaults.
        }
        return new MenuProfile();
    }

    /** Writes to a temporary file first, so a crash mid-save cannot leave half a profile. */
    public static void save(Path file, MenuProfile profile) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(profile), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Fixes values a hand-edited file could get wrong. */
    static MenuProfile sanitize(MenuProfile p) {
        if (p.sourceKind == null) p.sourceKind = MenuProfile.SourceKind.SEED;
        if (p.centerMode == null) p.centerMode = MenuProfile.CenterMode.SPAWN;
        if (p.travel == null) p.travel = MenuProfile.Travel.MIXED;
        if (p.targets == null) p.targets = MenuProfile.Targets.ALL_ENABLED;
        if (p.savePath == null) p.savePath = "";
        if (p.points == null) p.points = new java.util.ArrayList<>();
        if (p.timeMode == null) p.timeMode = MenuProfile.TimeMode.CYCLE;
        if (p.scanKey == null) p.scanKey = "";
        if (p.pointsSource == null) p.pointsSource = "";
        if (p.stashed == null) p.stashed = new java.util.HashMap<>();
        p.stashed.values().removeIf(java.util.Objects::isNull);
        for (java.util.List<Poi> list : p.stashed.values()) list.removeIf(q -> q == null || q.id == null || q.id.isEmpty());
        if (p.moves == null) p.moves = new java.util.ArrayList<>();
        p.moves.removeIf(m -> m == null || m.isBlank());
        p.handheld = Math.max(0, Math.min(2, p.handheld));
        p.clearance = Math.max(3, Math.min(40, p.clearance));
        p.fadeSeconds = Math.max(0.2, Math.min(3, p.fadeSeconds));
        p.fovOffset = Math.max(-30, Math.min(30, p.fovOffset));
        p.secondsPerDay = Math.max(30, Math.min(7200, p.secondsPerDay));
        p.startTicks = Math.floorMod(p.startTicks, 24000);
        p.viewChunks = Math.max(4, Math.min(24, p.viewChunks));
        p.radiusChunks = Math.max(8, Math.min(p.radiusChunks, 128));
        p.shotSecondsMin = Math.max(4, p.shotSecondsMin);
        p.shotSecondsMax = Math.max(p.shotSecondsMin, p.shotSecondsMax);
        p.cutChance = Math.max(0, Math.min(1, p.cutChance));
        p.points.removeIf(q -> q == null || q.id == null || q.id.isEmpty());
        if (p.version < 2) {
            // Version 1 guessed builds from block counts alone, which also hit underground structures. Drop those guesses.
            p.points.removeIf(q -> q.kind == Poi.Kind.BUILD && !q.edited && q.playerMinutes <= 0);
            p.version = 2;
        }
        p.version = Math.max(p.version, 3); // 3 only added settings, which have defaults
        for (Poi q : p.points) {
            if (q.kind == null) q.kind = Poi.Kind.CUSTOM;
            if (q.name == null) q.name = q.id;
            if (q.moves == null) q.moves = new java.util.ArrayList<>();
            if (q.structureId == null) q.structureId = "";
            q.weight = Math.max(0, q.weight);
        }
        return p;
    }
}
