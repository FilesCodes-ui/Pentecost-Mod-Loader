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

public final class Settings {
    public static final String WINDOW = "interface.window";
    public static final String MENUS = "interface.menus";
    public static final String GRAY_BUTTONS = "interface.grayButtons";
    public static final String PATTERN = "interface.pattern";
    public static final String LOADING = "interface.loading";
    public static final String RESTYLE = "interface.restyle";
    public static final String OVERLAY = "interface.overlay";
    public static final String OVERLAY_DETAIL = "interface.overlayDetail";
    public static final String MENU_BUTTONS = "interface.menuButtons";

    public static final String FAR_MOBS = "server.farMobs";
    public static final String SPREAD_UNLOADS = "server.spreadUnloads";
    public static final String MESSENGER = "threads.messenger";
    public static final String ASYNC_PATHS = "threads.paths";
    public static final String CULL_ENTITIES = "threads.cullEntities";
    public static final String MOB_THREADS = "threads.mobs";
    public static final String VANILLA_MOB_THREADS = "threads.vanillaMobs";
    public static final String BLOCK_THREADS = "threads.blockEntities";
    public static final String MOD_TICKS = "threads.modTicks";
    public static final String PARALLEL_GEN = "chunks.parallelGen";
    public static final String FOLLOW_SOLO = "pregen.followSolo";
    public static final String SPREAD_WATER = "chunks.spreadWater";

    public static final String VANILLA_SETTINGS = "interface.vanillaSettings";
    public static final String PAUSE_CAMERA = "interface.pauseCamera";
    public static final String PAUSE_CAMERA_STYLE = "interface.pauseCameraStyle";
    public static final String NAME_TAGS = "interface.nameTags";
    public static final String TAG_LENGTH = "interface.tagLength";
    public static final String JAVA_MEMORY = "java.memory";
    public static final String JAVA_YOUNG = "java.young";
    public static final String JAVA_RESERVE = "java.reserve";
    public static final String JAVA_GC = "java.gc";
    public static final String FAR_RANGE = "server.farRange";
    public static final String FAR_EVERY = "server.farEvery";
    public static final String STALL_WARNING = "gameplay.stall";
    public static final String STALL_MS = "gameplay.stallMs";
    public static final String OUTLINE_COLOR = "building.outlineColor";
    public static final String OUTLINE_ALPHA = "building.outlineAlpha";
    public static final String OUTLINE_SHAPE = "building.shapeOutline";
    public static final String SOUND_MUTED_MODS = "sound.mutedMods";
    public static final String SOUND_MUTED = "sound.muted";
    public static final String SOUND_GAP = "sound.gap";
    public static final String SOUND_LIMIT = "sound.limit";
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
        DEFAULTS.put(MESSENGER, "false");
        DEFAULTS.put(ASYNC_PATHS, "true");
        DEFAULTS.put(CULL_ENTITIES, "false");
        DEFAULTS.put("patch.instanceofGate", "true");
        DEFAULTS.put("fix.portalRefresh", "true");
        DEFAULTS.put("camera.orbit", "true");
        DEFAULTS.put("cape.pentecost", "true");
        for (String k : new String[]{"useWide", "useFollow", "useClose", "overHead", "ghost", "crosshair",
                "smartCave", "smartCeiling", "smartSpeed", "smartLook", "smartCombat",
                "seeThrough", "shoulderKey", "autoShoulder", "bowZoom", "lockOn"}) {
            DEFAULTS.put("camera." + k, "true");
        }
        DEFAULTS.put("camera.crosshairWide", "false");
        DEFAULTS.put("camera.comfort", "false");
        DEFAULTS.put(MOB_THREADS, "false");
        DEFAULTS.put(VANILLA_MOB_THREADS, "false");
        DEFAULTS.put(BLOCK_THREADS, "false");
        DEFAULTS.put(OUTLINE_SHAPE, "true");
        DEFAULTS.put(MOD_TICKS, "false");
        DEFAULTS.put(PARALLEL_GEN, "true");
        DEFAULTS.put("render.glFilter", "true");
        DEFAULTS.put("render.portalShadow", "true");
        DEFAULTS.put("debug.telemetry", "true");
        DEFAULTS.put("debug.telemetry.stacks", "true");
        DEFAULTS.put(FOLLOW_SOLO, "true");
        DEFAULTS.put(SPREAD_WATER, "true");
        DEFAULTS.put("online.discordStatus", "true");
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
        if (khi.pentecost.auth.DevFeed.killed(key)) {
            return "false";
        }
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
