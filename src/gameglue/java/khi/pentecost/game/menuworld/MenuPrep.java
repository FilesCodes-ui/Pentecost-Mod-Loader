package khi.pentecost.game.menuworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;
import khi.pentecost.game.menuworld.profile.MenuProfile;
import khi.pentecost.game.menuworld.profile.Poi;
import khi.pentecost.game.menuworld.profile.WorldScanner;
import khi.pentecost.game.menuworld.source.CacheTerrain;
import khi.pentecost.game.menuworld.source.MenuRegistries;
import khi.pentecost.game.menuworld.source.RegionReader;
import khi.pentecost.game.menuworld.source.SaveSource;
import khi.pentecost.game.menuworld.source.WorldSource;
import khi.pentecost.menuworld.SeedBundle;
import khi.pentecost.menuworld.SeedPregen;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Gets the menu world ready, off the render thread: generates a seed if it has to, scans for places, reads the land
 * the camera needs. It reads the profile and changes nothing in it. The caller applies {@link Ready#scanned} to the
 * profile on its own thread, so the settings screen never sees a list change under it.
 */
public final class MenuPrep {
    private MenuPrep() {}

    public enum Phase { GENERATING, SCANNING, LOADING }

    public record Progress(Phase phase, String text, int done, int total) {}

    /** The seed is not cached yet and the player has not agreed to the Mojang EULA that generating needs. */
    public static final class NeedsEula extends Exception {
        public NeedsEula() {
            super("Generating a seed needs your agreement to the Minecraft EULA.");
        }
    }

    /** Something the player can fix. The message is one plain sentence for the settings screen. */
    public static final class Failed extends Exception {
        public Failed(String message) {
            super(message);
        }
    }

    /**
     * @param scanned the places found, or null when the world was not scanned again (nothing changed since last time)
     * @param box     {minCx, minCz, maxCx, maxCz}: the chunks the menu world may show
     */
    public record Ready(WorldSource source, Path regionDir, int spawnX, int spawnZ, int centerX, int centerZ, int[] box,
                        CacheTerrain terrain, List<Poi> scanned, String scanKey, RegistryAccess.Frozen access) {}

    public static Ready prepare(Path home, Path config, MenuProfile profile, boolean forceScan, Consumer<Progress> out)
            throws Exception {
        long clock = System.nanoTime();
        WorldSource source;
        Path regionDir;
        int spawnX, spawnZ, centerX, centerZ;
        int[] box;
        String stamp;

        if (profile.sourceKind == MenuProfile.SourceKind.SEED) {
            source = WorldSource.ofSeed(profile.seed);
            Path dir = source.cacheDir(config);
            boolean spawn = profile.centerMode == MenuProfile.CenterMode.SPAWN;
            SeedPregen.Area area = new SeedPregen.Area(profile.seed, spawn ? SeedPregen.Area.SPAWN : profile.centerX,
                    spawn ? SeedPregen.Area.SPAWN : profile.centerZ, profile.radiusChunks);
            if (!SeedPregen.isComplete(dir, area)) {
                SeedBundle.install(home, dir, area); // a packed copy of this seed skips generating (and the EULA step)
            }
            if (!SeedPregen.isComplete(dir, area)) {
                if (!profile.eulaAccepted) throw new NeedsEula();
                out.accept(new Progress(Phase.GENERATING, "Generating the world", 0, area.totalChunks()));
                try {
                    SeedPregen.run(home, dir, area, p -> out.accept(new Progress(Phase.GENERATING, text(p), p.chunksDone(), p.chunksTotal())));
                } catch (IOException e) {
                    Log.warn("Menu world: generation failed: " + e.getMessage());
                    throw new Failed("The world could not be generated: " + summary(e.getMessage()));
                }
                if (!SeedPregen.isComplete(dir, area)) throw new Failed("The world did not finish generating.");
            }
            JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("meta.json"))).getAsJsonObject();
            centerX = meta.get("centerBlockX").getAsInt();
            centerZ = meta.get("centerBlockZ").getAsInt();
            spawnX = meta.has("spawnX") ? meta.get("spawnX").getAsInt() : centerX;
            spawnZ = meta.has("spawnZ") ? meta.get("spawnZ").getAsInt() : centerZ;
            int r = Math.min(profile.radiusChunks, meta.get("radiusChunks").getAsInt());
            int ccx = Math.floorDiv(centerX, 16), ccz = Math.floorDiv(centerZ, 16);
            box = new int[] {ccx - r, ccz - r, ccx + r - 1, ccz + r - 1};
            regionDir = dir.resolve("region");
            stamp = "seed:" + profile.seed + ":" + centerX + "," + centerZ + ":" + r;
        } else {
            if (profile.savePath == null || profile.savePath.isBlank()) throw new Failed("No world is picked.");
            Path folder = Path.of(profile.savePath);
            source = WorldSource.ofSave(folder);
            SaveSource.Info info = SaveSource.inspect(folder);
            if (!info.usable()) throw new Failed(info.problem());
            spawnX = info.spawn()[0];
            spawnZ = info.spawn()[2];
            int[] c = profile.resolveCenter(spawnX, spawnZ);
            centerX = c[0];
            centerZ = c[1];
            box = SaveSource.scanBox(folder, false, centerX, centerZ, profile.radiusChunks);
            regionDir = folder.resolve("region");
            stamp = "save:" + folder + ":" + info.lastPlayed() + ":" + profile.scanWholeSave + ":" + centerX + "," + centerZ + ":" + profile.radiusChunks;
        }

        Log.info("Menu world: world files ready after " + ms(clock) + " ms");
        clock = System.nanoTime();
        Path scratch = Files.createDirectories(config.resolve("menu_world").resolve(".registries"));
        RegistryAccess.Frozen access = MenuRegistries.loadVanilla(scratch);
        Log.info("Menu world: registries loaded in " + ms(clock) + " ms");
        clock = System.nanoTime();
        RegionReader reader = new RegionReader(regionDir);

        List<Poi> scanned = null;
        if (forceScan || !stamp.equals(profile.scanKey)) {
            out.accept(new Progress(Phase.SCANNING, "Looking for places", 0, 0));
            Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
            int[] scanBox = profile.sourceKind == MenuProfile.SourceKind.SAVE
                    ? SaveSource.scanBox(source.save(), profile.scanWholeSave, centerX, centerZ, profile.radiusChunks) : box;
            WorldScanner.Result found = WorldScanner.scan(reader, biomes, scanBox[0], scanBox[1], scanBox[2], scanBox[3]);
            scanned = new ArrayList<>(found.structures());
            scanned.addAll(found.builds());
            Log.info("Menu world: scanned for places in " + ms(clock) + " ms, " + scanned.size() + " found");
            clock = System.nanoTime();
        }

        out.accept(new Progress(Phase.LOADING, "Reading the land", 0, 0));
        CacheTerrain terrain = CacheTerrain.load(reader, box[0], box[1], box[2], box[3]);
        Log.info("Menu world: read " + terrain.chunkCount() + " chunks of land in " + ms(clock) + " ms");
        if (terrain.chunkCount() == 0) throw new Failed("The world has no finished chunks in this area.");
        return new Ready(source, regionDir, spawnX, spawnZ, centerX, centerZ, box, terrain, scanned, stamp, access);
    }

    private static long ms(long since) {
        return (System.nanoTime() - since) / 1_000_000L;
    }

    private static String text(SeedPregen.Progress p) {
        return p.stage();
    }

    /** The first line and the last line of an error that carries the server's log, so the reason is in it. */
    private static String summary(String s) {
        if (s == null) return "unknown error";
        String[] lines = s.split("\\R");
        String first = lines[0].trim();
        String last = "";
        for (int i = lines.length - 1; i > 0; i--) {
            if (!lines[i].isBlank()) {
                last = lines[i].trim();
                break;
            }
        }
        if (last.length() > 140) last = last.substring(last.length() - 140);
        return last.isEmpty() ? first : first + " " + last;
    }

    private static String firstLine(String s) {
        if (s == null) return "unknown error";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}
