package khi.pentecost.game.menuworld.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.game.menuworld.source.MenuRegistries;
import khi.pentecost.game.menuworld.source.RegionReader;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless check, no game window. Usage: ProfileCheck <cacheDir> <scratchDir> <profileFile>
 * Scans the cache, fills a profile, saves and reloads it, then makes sure a player's edits survive a rescan.
 */
public final class ProfileCheck {
    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[0]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.Frozen access = MenuRegistries.loadVanilla(Path.of(args[1]));
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);

        JsonObject meta = JsonParser.parseString(Files.readString(cache.resolve("meta.json"))).getAsJsonObject();
        int r = meta.get("radiusChunks").getAsInt();
        int cx = Math.floorDiv(meta.get("centerBlockX").getAsInt(), 16);
        int cz = Math.floorDiv(meta.get("centerBlockZ").getAsInt(), 16);

        long t0 = System.currentTimeMillis();
        WorldScanner.Result res = WorldScanner.scan(new RegionReader(cache.resolve("region")), biomes,
                cx - r, cz - r, cx + r - 1, cz + r - 1);
        System.out.println("scanned " + res.chunksRead() + " chunks in " + (System.currentTimeMillis() - t0) + " ms: "
                + res.structures().size() + " structures, " + res.builds().size() + " builds");
        for (Poi p : res.structures()) {
            System.out.println("  structure " + p.name + " at " + p.x + "," + p.y + "," + p.z + " size " + p.sizeX + "x"
                    + p.sizeY + "x" + p.sizeZ + (p.enabled ? "" : " (off by default)"));
        }
        for (Poi p : res.builds().subList(0, Math.min(8, res.builds().size()))) {
            System.out.println("  build " + p.name + " y~" + p.y + " size " + p.sizeX + "x" + p.sizeZ + " weight "
                    + String.format("%.2f", p.weight));
        }

        MenuProfile profile = new MenuProfile();
        profile.seed = meta.get("seed").getAsLong();
        int added = profile.mergeScanned(res.structures()) + profile.mergeScanned(res.builds());
        System.out.println("profile points after first scan: " + profile.points.size() + " (added " + added + ")");

        // Edit like the editor would: switch one off, add a custom point, re-weight another.
        Poi first = profile.points.get(0);
        first.enabled = false;
        first.edited = true;
        Poi custom = profile.addCustom("My spot", 100, 80, 100, 64);
        if (profile.points.size() > 2) {
            profile.points.get(1).weight = 2.5;
            profile.points.get(1).edited = true;
        }

        Path file = Path.of(args[2]);
        ProfileStore.save(file, profile);
        MenuProfile back = ProfileStore.load(file);
        boolean same = back.points.size() == profile.points.size() && back.seed == profile.seed
                && back.find(custom.id) != null && !back.find(first.id).enabled;
        System.out.println("saved and reloaded: " + (same ? "matches" : "MISMATCH") + ", active points "
                + back.activePoints().size() + " of " + back.points.size());

        // A rescan must not undo the player's edits or duplicate points.
        int again = back.mergeScanned(res.structures()) + back.mergeScanned(res.builds());
        System.out.println("rescan added " + again + ", first point still off: " + !back.find(first.id).enabled
                + ", custom kept: " + (back.find(custom.id) != null) + ", total " + back.points.size());

        back.targets = MenuProfile.Targets.REGION_ONLY;
        System.out.println("REGION_ONLY active points: " + back.activePoints().size());

        // A damaged file must give defaults, not an error.
        Path bad = file.resolveSibling("profile_bad.json");
        Files.writeString(bad, "{ not json");
        System.out.println("damaged file loads defaults: " + (ProfileStore.load(bad).points.isEmpty()));
        Files.deleteIfExists(bad);
    }
}
