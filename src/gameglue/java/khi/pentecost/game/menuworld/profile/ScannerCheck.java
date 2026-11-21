package khi.pentecost.game.menuworld.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.game.menuworld.source.ChunkSource;
import khi.pentecost.game.menuworld.source.MenuRegistries;
import khi.pentecost.game.menuworld.source.RegionReader;
import khi.pentecost.game.menuworld.source.SaveSource;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ScannerCheck {
    private static int failures;

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failures++;
    }

    private static final class Planter implements ChunkSource {
        final ChunkSource inner;
        final Set<Long> built = new HashSet<>();
        final Set<Long> bricksOnly = new HashSet<>();
        final Set<Long> visited = new HashSet<>();

        Planter(ChunkSource inner) { this.inner = inner; }

        static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

        @Override
        public void forEach(int a, int b, int c, int d, Visitor v) throws IOException {
            inner.forEach(a, b, c, d, (cx, cz, tag) -> {
                long k = key(cx, cz);
                if (built.contains(k)) {
                    tag.putLong("InhabitedTime", 30000);
                    bricks(tag);
                } else if (bricksOnly.contains(k)) {
                    bricks(tag);
                } else if (visited.contains(k)) {
                    tag.putLong("InhabitedTime", 40000);
                }
                v.chunk(cx, cz, tag);
            });
        }

        @Override
        public int[] chunkBounds() { return inner.chunkBounds(); }

        private static void bricks(CompoundTag tag) {
            ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
            CompoundTag target = null;
            for (int i = 0; i < sections.size(); i++) {
                if (sections.getCompound(i).getByte("Y") == 5) target = sections.getCompound(i);
            }
            if (target == null) {
                target = new CompoundTag();
                target.putByte("Y", (byte) 5);
                sections.add(target);
                tag.put("sections", sections);
            }
            CompoundTag states = new CompoundTag();
            ListTag palette = new ListTag();
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", "minecraft:stone_bricks");
            palette.add(entry);
            states.put("palette", palette);
            target.put("block_states", states);
        }
    }

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[0]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.Frozen access = MenuRegistries.loadVanilla(Path.of(args[1]));
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);

        JsonObject meta = JsonParser.parseString(Files.readString(cache.resolve("meta.json"))).getAsJsonObject();
        int r = meta.get("radiusChunks").getAsInt();
        int ccx = Math.floorDiv(meta.get("centerBlockX").getAsInt(), 16);
        int ccz = Math.floorDiv(meta.get("centerBlockZ").getAsInt(), 16);
        int minCx = ccx - r, minCz = ccz - r, maxCx = ccx + r - 1, maxCz = ccz + r - 1;
        RegionReader reader = new RegionReader(cache.resolve("region"));

        long t0 = System.currentTimeMillis();
        WorldScanner.Result base = WorldScanner.scan(reader, biomes, minCx, minCz, maxCx, maxCz);
        long ms = System.currentTimeMillis() - t0;
        boolean noPlayer = base.structures().stream().allMatch(p -> p.playerMinutes == 0);
        check("unplayed world: no builds", base.builds().isEmpty(), base.builds().size() + " builds, "
                + base.structures().size() + " structures, " + base.chunksRead() + " chunks in " + ms + " ms");
        check("unplayed world: no player minutes anywhere", noPlayer && base.playerChunks() == 0, "");

        Planter planter = new Planter(reader);
        int[] free = freeSpot(base.structures(), ccx, ccz, 0);
        int[] free2 = freeSpot(base.structures(), ccx, ccz, 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                planter.built.add(Planter.key(free[0] + dx, free[1] + dz));
                planter.bricksOnly.add(Planter.key(free2[0] + dx, free2[1] + dz));
            }
        }
        Poi village = base.structures().stream().filter(p -> p.structureId.contains("village")).findFirst().orElse(null);
        check("the seed has a village to test with", village != null, "");
        if (village != null) planter.visited.add(Planter.key(Math.floorDiv(village.x, 16), Math.floorDiv(village.z, 16)));

        WorldScanner.Result planted = WorldScanner.scan(planter, biomes, minCx, minCz, maxCx, maxCz);
        check("a 3x3 build with player time is found once", planted.builds().size() == 1, planted.builds().size() + " builds");
        if (planted.builds().size() == 1) {
            Poi b = planted.builds().get(0);
            int px = free[0] * 16 + 8, pz = free[1] * 16 + 8;
            check("it is located where it was planted", Math.abs(b.x - px) <= 24 && Math.abs(b.z - pz) <= 24,
                    "found " + b.x + "," + b.z + " planted " + px + "," + pz);
            check("its player time is read", Math.abs(b.playerMinutes - 25) < 0.5 && b.manMadeBlocks >= 9 * 4096 / 2,
                    String.format("%.1f min, %d blocks", b.playerMinutes, b.manMadeBlocks));
        }
        check("bricks without player time are not a build", planted.builds().stream().noneMatch(b ->
                Math.abs(b.x - (free2[0] * 16 + 8)) <= 32 && Math.abs(b.z - (free2[1] * 16 + 8)) <= 32), "");
        if (village != null) {
            Poi v2 = planted.structures().stream().filter(p -> p.id.equals(village.id) && p.structureId.equals(village.structureId))
                    .findFirst().orElse(null);
            check("a village a player spent time in gets its minutes, and is not also a build",
                    v2 != null && v2.playerMinutes > 30 && planted.builds().size() == 1,
                    v2 == null ? "village missing" : String.format("%.1f min", v2.playerMinutes));
        }

        MenuProfile save = MenuProfile.forSave("x");
        save.mergeScanned(planted.structures());
        save.mergeScanned(planted.builds());
        List<Poi> active = save.activePoints();
        boolean onlyPlayer = active.stream().allMatch(p -> p.kind == Poi.Kind.BUILD || p.playerMinutes >= MenuProfile.PLAYER_STRUCTURE_MINUTES);
        check("PLAYER_BUILDS shows only the build and the visited village", onlyPlayer && active.size() == (village != null ? 2 : 1),
                active.size() + " active of " + save.points.size());
        MenuProfile unplayed = MenuProfile.forSave("x");
        unplayed.mergeScanned(base.structures());
        check("PLAYER_BUILDS falls back to all enabled points when nothing was built",
                unplayed.activePoints().size() == unplayed.points.stream().filter(p -> p.enabled).count() && !unplayed.activePoints().isEmpty(),
                unplayed.activePoints().size() + " points");

        String oldJson = "{\"version\":1,\"points\":[{\"id\":\"build@-123,3\",\"name\":\"Build at -1969, 60\",\"kind\":\"BUILD\"},"
                + "{\"id\":\"build@5,5\",\"name\":\"Mine\",\"kind\":\"BUILD\",\"edited\":true},"
                + "{\"id\":\"custom@1,1\",\"name\":\"Spot\",\"kind\":\"CUSTOM\"}]}";
        Path tmp = Files.createTempDirectory(Path.of(args[1]), "profile");
        Path oldFile = tmp.resolve("profile.json");
        Files.writeString(oldFile, oldJson);
        MenuProfile migrated = ProfileStore.load(oldFile);
        check("version 1 profile: guessed build dropped, edited build and custom point kept",
                migrated.version == 3 && migrated.find("build@-123,3") == null && migrated.find("build@5,5") != null
                        && migrated.find("custom@1,1") != null, migrated.points.size() + " points left");

        Path root = cache.getParent();
        Path cherry = root.resolve("-769185976586290351");
        if (Files.isRegularFile(cherry.resolve("level.dat"))) {
            SaveSource.Info info = SaveSource.inspect(cherry);
            JsonObject cm = JsonParser.parseString(Files.readString(cherry.resolve("meta.json"))).getAsJsonObject();
            check("a save with level.dat and chunks is usable, and its spawn is read",
                    info.usable() && info.spawn() != null && info.spawn()[0] == cm.get("spawnX").getAsInt()
                            && info.spawn()[2] == cm.get("spawnZ").getAsInt(),
                    info.name() + " v" + info.dataVersion() + " spawn " + (info.spawn() == null ? "?" : info.spawn()[0] + "," + info.spawn()[2]));
            int[] centre = MenuProfile.forSave(cherry.toString()).resolveCenter(info.spawn()[0], info.spawn()[2]);
            check("the default centre of a save is its world spawn", centre[0] == info.spawn()[0] && centre[1] == info.spawn()[2], "");

            for (int[] c : new int[][] {{2500, 0}, {4000, 0}}) {
                Path fake = Files.createDirectory(tmp.resolve("fake" + c[0]));
                CompoundTag lv = NbtIo.readCompressed(cherry.resolve("level.dat").toFile());
                lv.getCompound("Data").putInt("DataVersion", c[0]);
                NbtIo.writeCompressed(lv, fake.resolve("level.dat").toFile());
                SaveSource.Info bad = SaveSource.inspect(fake);
                check("DataVersion " + c[0] + " is refused with a reason", !bad.usable() && bad.problem() != null, bad.problem());
            }
            Path noChunks = Files.createDirectory(tmp.resolve("empty"));
            Files.copy(cherry.resolve("level.dat"), noChunks.resolve("level.dat"));
            SaveSource.Info empty = SaveSource.inspect(noChunks);
            check("a world with no chunks is refused", !empty.usable() && empty.problem() != null, empty.problem());
            Path notWorld = Files.createDirectory(tmp.resolve("notaworld"));
            SaveSource.Info nw = SaveSource.inspect(notWorld);
            check("a folder that is not a world is refused", !nw.usable() && nw.problem() != null, nw.problem());

            List<SaveSource.Info> listed = SaveSource.list(root);
            check("listing a saves folder finds the usable world and flags the one without level.dat",
                    listed.stream().anyMatch(i -> i.usable()) && listed.stream().anyMatch(i -> !i.usable()),
                    listed.size() + " folders");

            int[] whole = SaveSource.scanBox(cherry, true, 0, 0, 4);
            int[] around = SaveSource.scanBox(cherry, false, 64, -96, 4);
            check("scan box: whole save uses the region files, otherwise a box around the centre",
                    whole[0] <= whole[2] && around[2] - around[0] == 7, java.util.Arrays.toString(whole));
            WorldScanner.Result full = WorldScanner.scan(new RegionReader(cherry.resolve("region")), biomes, whole[0], whole[1], whole[2], whole[3]);
            System.out.println("whole-save scan of the Cherry mines folder: " + full.chunksRead() + " chunks, "
                    + full.structures().size() + " structures, " + full.builds().size() + " builds");
        } else {
            System.out.println("SKIP save checks: Cherry mines cache has no level.dat");
        }

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    private static int[] freeSpot(List<Poi> structures, int cx0, int cz0, int skip) {
        for (int ring = 3; ring < 30; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int cx = cx0 + dx, cz = cz0 + dz;
                    boolean free = true;
                    for (Poi s : structures) {
                        int x0 = s.x - s.sizeX / 2 - 32, x1 = s.x + s.sizeX / 2 + 32, z0 = s.z - s.sizeZ / 2 - 32, z1 = s.z + s.sizeZ / 2 + 32;
                        if (cx * 16 + 31 >= x0 && cx * 16 - 16 <= x1 && cz * 16 + 31 >= z0 && cz * 16 - 16 <= z1) {
                            free = false;
                            break;
                        }
                    }
                    if (free && skip-- == 0) return new int[] {cx, cz};
                    if (free) { dx += 6; }
                }
            }
        }
        throw new IllegalStateException("no free spot");
    }
}
