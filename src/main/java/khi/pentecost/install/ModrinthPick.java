package khi.pentecost.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record ModrinthPick(String filename, String url, String sha1, List<String> required) {

    public static ModrinthPick of(String versionsJson) throws IOException {
        JsonArray versions;
        try {
            versions = JsonParser.parseString(versionsJson).getAsJsonArray();
        } catch (RuntimeException e) {
            throw new IOException("Modrinth answered with something unreadable");
        }
        if (versions.isEmpty()) {
            throw new IOException("No build for this Minecraft version and loader");
        }
        JsonObject chosen = versions.get(0).getAsJsonObject();
        for (JsonElement v : versions) {
            JsonObject o = v.getAsJsonObject();
            if (o.has("version_type") && "release".equals(o.get("version_type").getAsString())) {
                chosen = o;
                break;
            }
        }
        JsonArray files = chosen.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            throw new IOException("That build has no file");
        }
        JsonObject file = files.get(0).getAsJsonObject();
        for (JsonElement f : files) {
            JsonObject fo = f.getAsJsonObject();
            if (fo.has("primary") && fo.get("primary").getAsBoolean()) {
                file = fo;
            }
        }
        String name = safeJarName(file.get("filename").getAsString());
        String sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
        List<String> required = new ArrayList<>();
        if (chosen.has("dependencies") && chosen.get("dependencies").isJsonArray()) {
            for (JsonElement d : chosen.getAsJsonArray("dependencies")) {
                JsonObject dep = d.getAsJsonObject();
                if (dep.has("dependency_type") && "required".equals(dep.get("dependency_type").getAsString())
                        && dep.has("project_id") && !dep.get("project_id").isJsonNull()) {
                    required.add(dep.get("project_id").getAsString());
                }
            }
        }
        return new ModrinthPick(name, file.get("url").getAsString(), sha1, List.copyOf(required));
    }

    public static String safeJarName(String raw) throws IOException {
        if (raw == null || !raw.matches("[A-Za-z0-9._+-]{1,150}\\.jar") || raw.contains("..")) {
            throw new IOException("Refusing a download with an unsafe file name");
        }
        return raw;
    }

    public static String baseName(String jarName) {
        String s = jarName.toLowerCase(java.util.Locale.ROOT);
        if (s.endsWith(".jar")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.split("[-_+ ](v|mc)?\\d", 2)[0];
    }
}
