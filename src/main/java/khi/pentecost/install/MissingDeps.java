package khi.pentecost.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModDiscovery;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MissingDeps {
    public record Offer(String id, List<String> neededBy, String title, ModrinthPick pick) {}

    private static final String API = "https://api.modrinth.com/v2/";
    private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "forge", "fabricloader", "fabric-loader",
            "quilt_loader", "pentecost", "mixinextras", "neoforge");

    private MissingDeps() {}

    public static boolean askAndAdd(List<ModInfo> mods, Path modsDir, String minecraft) {
        if (System.getenv("PENTECOST_NO_MISSING_DEPS") != null || !khi.pentecost.boot.Splash.canAsk()) {
            return false;
        }
        Map<String, List<ModInfo>> missing = find(mods);
        if (missing.isEmpty()) {
            return false;
        }
        khi.pentecost.boot.Splash.status("Looking up missing mods");
        List<Offer> offers = new ArrayList<>();
        for (Map.Entry<String, List<ModInfo>> e : missing.entrySet()) {
            List<String> names = e.getValue().stream().map(ModInfo::name).distinct().toList();
            offers.add(lookUp(e.getKey(), names, loaders(e.getValue()), minecraft));
        }
        List<String> chosen = khi.pentecost.boot.Splash.askToAdd(offers, 60_000);
        boolean added = false;
        for (Offer o : offers) {
            if (o.pick() == null) {
                continue;
            }
            if (chosen == null) {
                continue;
            }
            if (!chosen.contains(o.id())) {
                Settings.set("deps.skip." + o.id(), "true");
                Log.info("Missing mod " + o.id() + ": skipped, not asking again");
                continue;
            }
            try {
                khi.pentecost.boot.Splash.status("Adding " + o.title());
                Path dest = modsDir.resolve(o.pick().filename());
                Http.download(o.pick().url(), dest, o.pick().sha1());
                Log.info("Added the missing mod " + o.title() + " (" + o.pick().filename() + "), needed by "
                        + String.join(", ", o.neededBy()));
                added = true;
            } catch (IOException | RuntimeException ex) {
                Log.error("Could not add " + o.title() + ": " + ex);
            }
        }
        return added;
    }

    static Map<String, List<ModInfo>> find(List<ModInfo> mods) {
        Set<String> present = new HashSet<>(BUILT_IN);
        for (ModInfo m : mods) {
            present.add(m.id());
            if (m.raw() != null && m.raw().has("provides") && m.raw().get("provides").isJsonArray()) {
                m.raw().getAsJsonArray("provides").forEach(p -> present.add(p.getAsString()));
            }
        }
        Map<String, List<ModInfo>> out = new LinkedHashMap<>();
        for (ModInfo m : mods) {
            if (khi.pentecost.control.Choices.isModOff(m.id())) {
                continue;
            }
            for (String d : m.depends()) {
                String id = d.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty() || ModDiscovery.met(id, present) || Settings.on("deps.skip." + id)) {
                    continue;
                }
                out.computeIfAbsent(id, k -> new ArrayList<>()).add(m);
            }
        }
        return out;
    }

    private static List<String> loaders(List<ModInfo> neededBy) {
        boolean forge = neededBy.stream().anyMatch(m -> m.loader() == LoaderKind.FORGE);
        return forge ? List.of("forge", "fabric", "quilt") : List.of("fabric", "quilt", "forge");
    }

    private static Offer lookUp(String id, List<String> neededBy, List<String> loaders, String minecraft) {
        try {
            String slug = projectFor(id, minecraft);
            if (slug == null) {
                return new Offer(id, neededBy, id, null);
            }
            JsonObject project = JsonParser.parseString(Http.text(API + "project/" + enc(slug))).getAsJsonObject();
            String title = project.has("title") ? project.get("title").getAsString() : id;
            for (String loader : loaders) {
                String url = API + "project/" + enc(slug) + "/version?game_versions=" + enc("[\"" + minecraft + "\"]")
                        + "&loaders=" + enc("[\"" + loader + "\"]");
                try {
                    ModrinthPick pick = ModrinthPick.of(Http.text(url));
                    return new Offer(id, neededBy, title, pick);
                } catch (IOException none) {
                }
            }
            return new Offer(id, neededBy, title, null);
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not look up the missing mod " + id + " on Modrinth: " + e);
            return new Offer(id, neededBy, id, null);
        }
    }

    private static String projectFor(String id, String minecraft) throws IOException {
        try {
            JsonObject p = JsonParser.parseString(Http.text(API + "project/" + enc(id))).getAsJsonObject();
            if (p.has("slug")) {
                return p.get("slug").getAsString();
            }
        } catch (IOException | RuntimeException notThere) {
        }
        String url = API + "search?limit=8&query=" + enc(id.replace('_', ' ').replace('-', ' '))
                + "&facets=" + enc("[[\"versions:" + minecraft + "\"]]");
        JsonArray hits = JsonParser.parseString(Http.text(url)).getAsJsonObject().getAsJsonArray("hits");
        String want = norm(id);
        for (JsonElement h : hits) {
            JsonObject o = h.getAsJsonObject();
            String slug = o.has("slug") ? o.get("slug").getAsString() : "";
            String title = o.has("title") ? o.get("title").getAsString() : "";
            if (norm(slug).equals(want) || norm(title).equals(want)) {
                return slug;
            }
        }
        return null;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
