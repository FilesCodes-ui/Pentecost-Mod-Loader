package khi.pentecost.mods;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ModInfo(String id, String name, String version, LoaderKind loader, Path jar,
                      Map<String, List<String>> entrypoints, List<String> depends,
                      List<String> mixins, List<String> accessWideners, JsonObject raw) {
    public List<String> entrypoints(String key) {
        return entrypoints.getOrDefault(key, List.of());
    }

    public ModInfo withJar(Path newJar) {
        return new ModInfo(id, name, version, loader, newJar, entrypoints, depends, mixins, accessWideners, raw);
    }

    public String label() {
        return name + " " + version + " (" + loader.label + ", id " + id + ")";
    }
}
