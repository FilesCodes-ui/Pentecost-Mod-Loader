package khi.pentecost.game;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import java.util.stream.Stream;

final class Profiles {
    private Profiles() {}

    private static Map<String, OptionInstance<?>> gameOptions() {
        net.minecraft.client.Options o = Minecraft.getInstance().options;
        Map<String, OptionInstance<?>> m = new LinkedHashMap<>();
        m.put("renderDistance", o.renderDistance());
        m.put("simulationDistance", o.simulationDistance());
        m.put("entityDistanceScaling", o.entityDistanceScaling());
        m.put("framerateLimit", o.framerateLimit());
        m.put("enableVsync", o.enableVsync());
        m.put("fov", o.fov());
        m.put("guiScale", o.guiScale());
        m.put("biomeBlendRadius", o.biomeBlendRadius());
        m.put("mipmapLevels", o.mipmapLevels());
        m.put("graphicsMode", o.graphicsMode());
        m.put("particles", o.particles());
        m.put("cloudStatus", o.cloudStatus());
        return m;
    }

    private static String text(Object v) {
        return v instanceof Enum<?> e ? e.name() : String.valueOf(v);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setOption(OptionInstance option, String value) {
        Object now = option.get();
        Object next;
        if (now instanceof Integer) {
            next = Integer.valueOf(value);
        } else if (now instanceof Double) {
            next = Double.valueOf(value);
        } else if (now instanceof Boolean) {
            next = Boolean.valueOf(value);
        } else if (now instanceof Enum<?> e) {
            next = Enum.valueOf(e.getDeclaringClass(), value);
        } else {
            return;
        }
        option.set(next);
    }

    static Path folder() {
        return Dirs.mk(Dirs.config().resolve("pentecost").resolve("profiles"));
    }

    static List<String> names() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder())) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().forEach(out::add);
        } catch (IOException e) {
            Log.error("Cannot list setups: " + e);
        }
        return out;
    }

    static void saveNew() {
        List<String> have = names();
        int n = have.size() + 1;
        while (have.contains("Setup " + n)) {
            n++;
        }
        JsonObject root = new JsonObject();
        Settings.snapshot().forEach(root::addProperty);
        try {
            gameOptions().forEach((k, v) -> root.addProperty("game." + k, text(v.get())));
        } catch (RuntimeException e) {
            Log.error("Cannot read game options for the setup: " + e);
        }
        try {
            Files.writeString(folder().resolve("Setup " + n + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("Cannot save setup: " + e);
        }
    }

    static void apply(String name) {
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(folder().resolve(name + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> values = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    values.put(e.getKey(), e.getValue().getAsString());
                }
            }
            Map<String, String> own = new TreeMap<>();
            Map<String, String> game = new TreeMap<>();
            values.forEach((k, v) -> {
                if (k.startsWith("game.")) {
                    game.put(k.substring(5), v);
                } else {
                    own.put(k, v);
                }
            });
            Settings.restore(own);
            Map<String, OptionInstance<?>> options = gameOptions();
            game.forEach((k, v) -> {
                OptionInstance<?> option = options.get(k);
                if (option == null) {
                    return;
                }
                try {
                    setOption(option, v);
                } catch (RuntimeException e) {
                    Log.error("Setup value " + k + " not applied: " + e);
                }
            });
            Minecraft.getInstance().options.save();
        } catch (IOException | RuntimeException e) {
            Log.error("Cannot read setup " + name + ": " + e);
        }
    }

    static void delete(String name) {
        try {
            Files.deleteIfExists(folder().resolve(name + ".json"));
        } catch (IOException e) {
            Log.error("Cannot delete setup " + name + ": " + e);
        }
    }
}
