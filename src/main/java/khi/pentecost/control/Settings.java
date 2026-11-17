package khi.pentecost.control;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pentecost's own settings, as the player set them in the Settings screen.
 * Saved in config/pentecost/settings.json. Every setting has a default, so a missing file is fine.
 */
public final class Settings {
    /** Window mode: windowed, fullscreen or borderless. */
    public static final String WINDOW = "interface.window";
    /** Pentecost draws the main and pause menus. */
    public static final String MENUS = "interface.menus";
    /** Mod buttons on Pentecost's menus show in black and white. */
    public static final String GRAY_BUTTONS = "interface.grayButtons";
    /** The slow dot pattern behind Pentecost's screens. */
    public static final String PATTERN = "interface.pattern";
    /** Pentecost draws the loading screen. */
    public static final String LOADING = "interface.loading";
    /** Pentecost's look for every screen's buttons, sliders, checkboxes and backgrounds. */
    public static final String RESTYLE = "interface.restyle";
    /** Pentecost draws the F3 screen. */
    public static final String OVERLAY = "interface.overlay";
    /** How much the F3 screen shows: standard or full. */
    public static final String OVERLAY_DETAIL = "interface.overlayDetail";
    /** Where the small Pentecost button sits on a mod's main menu: empty is top right, else topLeft, bottomRight, bottomLeft or off. */
    public static final String MENU_BUTTONS = "interface.menuButtons";

    /** Mobs far from every player think every third tick. */
    public static final String FAR_MOBS = "server.farMobs";
    /** Chunks unload a few at a time instead of all in one tick. */
    public static final String SPREAD_UNLOADS = "server.spreadUnloads";

    /** The game's own options screens open instead of Pentecost's settings. Off unless the player turns it on. */
    public static final String VANILLA_SETTINGS = "interface.vanillaSettings";
    /** The camera leaves the player and films them while the pause menu is open. */
    public static final String PAUSE_CAMERA = "interface.pauseCamera";
    /** "cinematic" plays every kind of shot, "orbit" only turns slowly around the player. */
    public static final String PAUSE_CAMERA_STYLE = "interface.pauseCameraStyle";
    /** "pentecost" draws players' name tags in Pentecost's look, "vanilla" leaves the game's own. */
    public static final String NAME_TAGS = "interface.nameTags";
    /** How long the name line and the dev tag are: "auto" follows your gaze, "short" or "long" stay put. */
    public static final String TAG_LENGTH = "interface.tagLength";
    public static final String JAVA_MEMORY = "java.memory";
    public static final String JAVA_YOUNG = "java.young";
    /** Backup memory in GB, on top of java.memory. Unset means the default for this PC, 0 means none. */
    public static final String JAVA_RESERVE = "java.reserve";
    public static final String JAVA_GC = "java.gc";
    public static final String FAR_RANGE = "server.farRange";
    public static final String FAR_EVERY = "server.farEvery";
    public static final String STALL_WARNING = "gameplay.stall";
    public static final String STALL_MS = "gameplay.stallMs";
    public static final String OUTLINE_COLOR = "building.outlineColor";
    public static final String OUTLINE_ALPHA = "building.outlineAlpha";
    public static final String SOUND_MUTED_MODS = "sound.mutedMods";
    public static final String SOUND_MUTED = "sound.muted";
    public static final String SOUND_GAP = "sound.gap";
    public static final String SOUND_LIMIT = "sound.limit";
    /** Pentecost looks at GitHub once at startup for a newer release and shows a toast. Nothing downloads until the player asks. */
    public static final String UPDATE_CHECK = "updates.check";

    private static final Map<String, String> DEFAULTS = new java.util.HashMap<>(Map.of(
            WINDOW, "borderless",
            MENUS, "true",
            GRAY_BUTTONS, "true",
            PATTERN, "true",
            LOADING, "true",
            RESTYLE, "true",
            OVERLAY, "true",
            OVERLAY_DETAIL, "standard",
            FAR_MOBS, "true",
            SPREAD_UNLOADS, "true"));

    static {
        DEFAULTS.put(PAUSE_CAMERA, "true");
        DEFAULTS.put(PAUSE_CAMERA_STYLE, "cinematic");
        DEFAULTS.put(NAME_TAGS, "pentecost");
        DEFAULTS.put(TAG_LENGTH, "auto");
        DEFAULTS.put(UPDATE_CHECK, "true");
    }

    private static final Map<String, String> VALUES = new TreeMap<>();
    private static boolean loaded;

    private Settings() {}

    private static Path file() {
        return Dirs.mk(Dirs.config().resolve("pentecost")).resolve("settings.json");
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    VALUES.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.error("Cannot read " + f + ": " + e + ". Using default settings");
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        VALUES.forEach(root::addProperty);
        try {
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("Cannot save " + file() + ": " + e);
        }
    }

    public static synchronized String get(String key) {
        load();
        String v = VALUES.get(key);
        return v != null ? v : DEFAULTS.getOrDefault(key, "");
    }

    public static boolean on(String key) {
        return Boolean.parseBoolean(get(key));
    }

    public static synchronized void set(String key, String value) {
        load();
        if (value.equals(DEFAULTS.get(key)) || (value.isEmpty() && !DEFAULTS.containsKey(key))) {
            if (VALUES.remove(key) != null) {
                save();
            }
        } else if (!value.equals(VALUES.put(key, value))) {
            save();
        }
    }

    /** A whole number setting, or def when it is unset or not a number. */
    public static int num(String key, int def) {
        try {
            String v = get(key);
            return v.isEmpty() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static synchronized Map<String, String> snapshot() {
        load();
        return new TreeMap<>(VALUES);
    }

    public static synchronized void restore(Map<String, String> values) {
        load();
        VALUES.clear();
        VALUES.putAll(values);
        save();
    }

    public static void toggle(String key) {
        set(key, String.valueOf(!on(key)));
    }

    public static void reset(String key) {
        set(key, DEFAULTS.getOrDefault(key, ""));
    }
}
