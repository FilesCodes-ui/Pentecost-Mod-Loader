package khi.pentecost.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.install.Http;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class ModFetcher {
    private static final String API = "https://api.modrinth.com/v2";

    private ModFetcher() {}

    public static Path fetchTop(String mcVersion, String loader, int count) throws IOException {
        Path dest = Dirs.mk(Dirs.cache().resolve("testmods").resolve(loader));
        String facets = "[[\"versions:" + mcVersion + "\"],[\"categories:" + loader + "\"],[\"project_type:mod\"]]";
        String search = API + "/search?limit=" + count + "&index=downloads&facets=" + enc(facets);
        JsonArray hits = JsonParser.parseString(Http.text(search)).getAsJsonObject().getAsJsonArray("hits");
        int got = 0;
        for (JsonElement h : hits) {
            JsonObject hit = h.getAsJsonObject();
            String slug = hit.get("slug").getAsString();
            try {
                String versions = API + "/project/" + hit.get("project_id").getAsString() + "/version?game_versions="
                        + enc("[\"" + mcVersion + "\"]") + "&loaders=" + enc("[\"" + loader + "\"]");
                JsonArray list = JsonParser.parseString(Http.text(versions)).getAsJsonArray();
                if (list.isEmpty()) {
                    continue;
                }
                JsonArray files = list.get(0).getAsJsonObject().getAsJsonArray("files");
                JsonObject file = files.get(0).getAsJsonObject();
                for (JsonElement f : files) {
                    if (f.getAsJsonObject().get("primary").getAsBoolean()) {
                        file = f.getAsJsonObject();
                    }
                }
                String name = file.get("filename").getAsString();
                if (!name.endsWith(".jar")) {
                    continue;
                }
                Http.download(file.get("url").getAsString(), dest.resolve(name),
                        file.getAsJsonObject("hashes").get("sha1").getAsString());
                got++;
                Log.info("  " + got + ". " + slug + " -> " + name);
            } catch (IOException | RuntimeException e) {
                Log.warn("  skipped " + slug + ": " + e.getMessage());
            }
        }
        Log.info("Fetched " + got + " " + loader + " mod(s) into " + dest);
        return dest;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
