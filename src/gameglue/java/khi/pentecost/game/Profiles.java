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
import java.util.stream.Stream;

/** Saved setups. Each is one .json file holding every Pentecost setting that differs from the default. */
final class Profiles {
    private Profiles() {}

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
            Settings.restore(values);
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
