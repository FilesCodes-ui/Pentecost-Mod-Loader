package khi.pentecost.control;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the player decided. The player has the last word on what every mod does:
 * whole mods can be off, and so can single things a mod changes.
 * Saved in config/pentecost/choices.json.
 */
public final class Choices {
    private static final Set<String> MODS_OFF = new TreeSet<>();
    private static final Map<String, Set<String>> FEATURES_OFF = new TreeMap<>();
    /** Mod id, then the vanilla screen class it replaced, then the mod's screen class. */
    private static final Map<String, Map<String, String>> SEEN_SCREENS = new TreeMap<>();
    private static Set<String> atLaunch = Set.of();
    private static boolean loaded;

    private Choices() {}

    private static Path file() {
        return Dirs.mk(Dirs.config().resolve("pentecost")).resolve("choices.json");
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        if (Files.isRegularFile(f)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("modsOff")) {
                    root.getAsJsonArray("modsOff").forEach(e -> MODS_OFF.add(e.getAsString()));
                }
                if (root.has("featuresOff")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("featuresOff").entrySet()) {
                        Set<String> keys = new TreeSet<>();
                        e.getValue().getAsJsonArray().forEach(k -> keys.add(k.getAsString()));
                        FEATURES_OFF.put(e.getKey(), keys);
                    }
                }
                if (root.has("seenScreens")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("seenScreens").entrySet()) {
                        Map<String, String> m = new TreeMap<>();
                        e.getValue().getAsJsonObject().entrySet().forEach(s -> m.put(s.getKey(), s.getValue().getAsString()));
                        SEEN_SCREENS.put(e.getKey(), m);
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.error("Cannot read " + f + ": " + e + ". Starting with every mod feature on");
            }
        }
        atLaunch = restartState();
    }

    private static void save() {
        JsonObject root = new JsonObject();
        JsonArray mods = new JsonArray();
        MODS_OFF.forEach(mods::add);
        root.add("modsOff", mods);
        JsonObject feats = new JsonObject();
        FEATURES_OFF.forEach((mod, keys) -> {
            if (!keys.isEmpty()) {
                JsonArray arr = new JsonArray();
                keys.forEach(arr::add);
                feats.add(mod, arr);
            }
        });
        root.add("featuresOff", feats);
        JsonObject seen = new JsonObject();
        SEEN_SCREENS.forEach((mod, map) -> {
            JsonObject o = new JsonObject();
            map.forEach(o::addProperty);
            seen.add(mod, o);
        });
        root.add("seenScreens", seen);
        try {
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("Cannot save " + file() + ": " + e);
        }
    }

    public static synchronized boolean isModOff(String modId) {
        return MODS_OFF.contains(modId);
    }

    public static synchronized void setModOff(String modId, boolean off) {
        if (off ? MODS_OFF.add(modId) : MODS_OFF.remove(modId)) {
            save();
        }
    }

    public static synchronized boolean isOff(String modId, String key) {
        Set<String> keys = FEATURES_OFF.get(modId);
        return keys != null && keys.contains(key);
    }

    public static synchronized void setOff(String modId, String key, boolean off) {
        Set<String> keys = FEATURES_OFF.computeIfAbsent(modId, k -> new TreeSet<>());
        if (off ? keys.add(key) : keys.remove(key)) {
            save();
        }
    }

    /** Records that a mod showed its own screen in place of a vanilla one. */
    public static synchronized void seeScreen(String modId, String vanillaClass, String modClass) {
        Map<String, String> m = SEEN_SCREENS.computeIfAbsent(modId, k -> new TreeMap<>());
        if (!modClass.equals(m.put(vanillaClass, modClass))) {
            Log.info(modId + " replaces the screen " + vanillaClass.substring(vanillaClass.lastIndexOf('.') + 1)
                    + ". The player can turn that off in the Mods screen");
            save();
        }
    }

    public static synchronized Map<String, String> seenScreens(String modId) {
        Map<String, String> m = SEEN_SCREENS.get(modId);
        return m == null ? Map.of() : new TreeMap<>(m);
    }

    /** Every choice that only takes effect on the next launch. Screen swaps apply right away, so they are left out. */
    private static Set<String> restartState() {
        Set<String> out = new HashSet<>();
        MODS_OFF.forEach(m -> out.add("mod:" + m));
        FEATURES_OFF.forEach((mod, keys) -> keys.forEach(k -> {
            if (!k.startsWith("screen:")) {
                out.add("feature:" + mod + "/" + k);
            }
        }));
        return out;
    }

    /** How many choices wait for a restart. */
    public static synchronized int pendingRestart() {
        Set<String> now = restartState();
        int n = 0;
        for (String s : now) {
            if (!atLaunch.contains(s)) {
                n++;
            }
        }
        for (String s : atLaunch) {
            if (!now.contains(s)) {
                n++;
            }
        }
        return n;
    }
}
