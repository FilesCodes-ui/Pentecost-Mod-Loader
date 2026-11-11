package khi.pentecost.adapter.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.install.Http;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModDiscovery;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Forge and Fabric each rewrite the same parts of the game: registries, loot, fluids, rendering.
 * Stock Fabric API expects the unpatched game, so on the Forge layer its hooks miss or collide.
 * The bridge swaps it for Forgified Fabric API: the same Fabric API surface for mods,
 * with every feature carried out by Forge's own systems.
 */
public final class FabricBridge {
    private static final String PROJECT = "forgified-fabric-api";
    /** Pentecost is the Fabric loader here. The bridge's own copy of one stays out. */
    private static final String OWN_LOADER = "fabric-loader-";

    private FabricBridge() {}

    /** Returns the mod list with stock Fabric API replaced by the bridge. No Fabric mods: no change. */
    public static List<ModInfo> apply(List<ModInfo> found, String minecraft) throws IOException {
        boolean anyFabric = found.stream()
                .anyMatch(m -> m.loader() == LoaderKind.FABRIC || m.loader() == LoaderKind.QUILT);
        if (!anyFabric) {
            return found;
        }
        Path dir = Dirs.mk(Dirs.cache().resolve("bridge"));
        if (jarIn(dir) == null) {
            download(dir, minecraft);
        }
        List<ModInfo> bridge = new ArrayList<>();
        for (ModInfo m : ModDiscovery.scan(dir)) {
            if (!m.jar().getFileName().toString().startsWith(OWN_LOADER)) {
                bridge.add(m);
            }
        }
        Set<String> covered = new HashSet<>();
        for (ModInfo m : bridge) {
            covered.add(m.id());
            if (m.raw() != null && m.raw().has("provides") && m.raw().get("provides").isJsonArray()) {
                for (JsonElement e : m.raw().getAsJsonArray("provides")) {
                    covered.add(e.getAsString());
                }
            }
        }
        List<ModInfo> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int replaced = 0;
        for (ModInfo m : found) {
            boolean fabricSide = m.loader() == LoaderKind.FABRIC || m.loader() == LoaderKind.QUILT;
            if (fabricSide && covered.contains(m.id())) {
                replaced++;
                continue;
            }
            if (ids.add(m.id())) {
                out.add(m);
            }
        }
        int added = 0;
        for (ModInfo m : bridge) {
            if (ids.add(m.id())) {
                out.add(m);
                added++;
            }
        }
        Log.info("Fabric bridge: " + added + " Forge-native Fabric API module(s) in, "
                + replaced + " stock module(s) out");
        return out;
    }

    private static Path jarIn(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar")).findFirst().orElse(null);
        }
    }

    private static void download(Path dir, String minecraft) throws IOException {
        Log.info("Fabric bridge: downloading Forgified Fabric API for " + minecraft + " (one time)");
        String url = "https://api.modrinth.com/v2/project/" + PROJECT + "/version?game_versions=%5B%22"
                + minecraft + "%22%5D&loaders=%5B%22forge%22%5D";
        JsonArray versions = JsonParser.parseString(Http.text(url)).getAsJsonArray();
        if (versions.isEmpty()) {
            throw new IOException("No Forgified Fabric API build for Minecraft " + minecraft);
        }
        JsonArray files = versions.get(0).getAsJsonObject().getAsJsonArray("files");
        JsonObject file = files.get(0).getAsJsonObject();
        for (JsonElement f : files) {
            if (f.getAsJsonObject().get("primary").getAsBoolean()) {
                file = f.getAsJsonObject();
            }
        }
        Http.download(file.get("url").getAsString(), dir.resolve(file.get("filename").getAsString()),
                file.getAsJsonObject("hashes").get("sha1").getAsString());
    }
}
