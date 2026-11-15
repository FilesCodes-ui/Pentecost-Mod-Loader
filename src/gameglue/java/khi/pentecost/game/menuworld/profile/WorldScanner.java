package khi.pentecost.game.menuworld.profile;

import khi.pentecost.game.menuworld.source.ChunkData;
import khi.pentecost.game.menuworld.source.ChunkSource;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Looks through the chunks of a world and proposes places for the camera:
 * <ul>
 * <li>vanilla structures (villages, mansions, outposts, ...), from the structure data the world saved, and</li>
 * <li>builds, meaning places a player made. A chunk only counts when the world says a player spent time there (its
 * InhabitedTime counter) AND it holds many blocks that only people place (bricks, glass, lanterns, concrete ...)
 * near the surface. A freshly generated world has no inhabited chunks, so it has no builds, and underground
 * structures cannot be mistaken for houses.</li>
 * </ul>
 * Structures also get the minutes a player spent there, so a village the player lived in outranks an untouched one.
 * It only reads the saved chunk data, so it works the same on a pregenerated seed and on the player's own save.
 */
public final class WorldScanner {
    private WorldScanner() {}

    public record Result(List<Poi> structures, List<Poi> builds, int chunksRead, int playerChunks) {}

    /** Structures that are underground or buried: real, but not worth a camera shot, so they start switched off. */
    private static final Set<String> HIDDEN = Set.of("mineshaft", "mineshaft_mesa", "stronghold", "buried_treasure",
            "ancient_city", "nether_fossil", "trail_ruins");

    private static final String[] MAN_MADE = {
            "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks", "bricks",
            "deepslate_bricks", "deepslate_tiles", "polished_andesite", "polished_diorite", "polished_granite",
            "glass", "glass_pane", "white_stained_glass", "white_stained_glass_pane",
            "torch", "wall_torch", "lantern", "soul_lantern", "campfire", "chest", "barrel", "crafting_table",
            "furnace", "blast_furnace", "smoker", "bookshelf", "bed", "white_bed", "red_bed", "ladder", "oak_door",
            "spruce_door", "birch_door", "dark_oak_door", "oak_fence", "spruce_fence", "oak_trapdoor", "oak_stairs",
            "spruce_stairs", "stone_brick_stairs", "cobblestone_stairs", "oak_planks", "spruce_planks", "birch_planks",
            "dark_oak_planks", "acacia_planks", "jungle_planks", "cherry_planks", "mangrove_planks", "cobblestone_wall",
            "white_concrete", "gray_concrete", "black_concrete", "red_concrete", "blue_concrete", "white_wool",
            "terracotta", "smooth_stone", "quartz_block", "sea_lantern", "glowstone", "iron_bars"
    };

    /** A chunk needs this many game ticks of a player nearby (1200 = one minute) before it can be part of a build. */
    static final long MIN_INHABITED = 1200;
    static final long TICKS_PER_MINUTE = 1200;
    static final int MIN_CHUNK_SCORE = 30;     // man-made blocks near the surface for a chunk to count as part of a build
    static final int MIN_CLUSTER_SCORE = 120;  // man-made blocks for a cluster to be worth a shot
    private static final int BAND_BELOW_SURFACE = 20; // blocks below the surface that still count (cellars, basements)

    private record Stat(long inhabited, int manMade, int surface) {}

    /** Scans the chunk box (inclusive). {@code biomes} is the vanilla biome registry the chunk reader needs. */
    public static Result scan(ChunkSource source, Registry<Biome> biomes, int minCx, int minCz, int maxCx, int maxCz)
            throws IOException {
        Set<Block> manMade = new HashSet<>();
        for (String name : MAN_MADE) {
            Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft", name));
            if (b != Blocks.AIR) manMade.add(b);
        }

        Map<String, Poi> structures = new HashMap<>();
        Map<Long, Stat> inhabited = new HashMap<>();
        int[] read = {0};
        source.forEach(minCx, minCz, maxCx, maxCz, (cx, cz, tag) -> {
            read[0]++;
            readStructures(tag, structures);
            long time = tag.getLong("InhabitedTime");
            if (time < MIN_INHABITED) return;
            int manMadeCount = 0;
            int surface = 63;
            ChunkData d = ChunkData.build(tag, biomes, -4, 24);
            if (d != null) {
                surface = surfaceAverage(d.heightmaps, d.minSectionY * 16, 63);
                int[] acc = {0};
                for (int i = 0; i < d.sections.length; i++) {
                    if (d.sections[i].hasOnlyAir()) continue;
                    int top = (i + d.minSectionY + 1) * 16;
                    if (top <= surface - BAND_BELOW_SURFACE) continue; // deep underground does not count
                    d.sections[i].getStates().count((state, n) -> {
                        if (manMade.contains(state.getBlock())) acc[0] += n;
                    });
                }
                manMadeCount = acc[0];
            }
            inhabited.put(key(cx, cz), new Stat(time, manMadeCount, surface));
        });

        List<Poi> structureList = new ArrayList<>(structures.values());
        structureList.sort(Comparator.comparing((Poi p) -> p.name).thenComparingInt(p -> p.x));
        for (Poi s : structureList) {
            long most = 0;
            for (Map.Entry<Long, Stat> e : inhabited.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                if (chunkTouches(s, cx, cz)) most = Math.max(most, e.getValue().inhabited());
            }
            s.playerMinutes = most / (double) TICKS_PER_MINUTE;
        }

        // Builds come from chunks a player spent time in, outside the boxes of visible structures.
        Map<Long, Stat> hot = new HashMap<>();
        for (Map.Entry<Long, Stat> e : inhabited.entrySet()) {
            Stat st = e.getValue();
            if (st.manMade() < MIN_CHUNK_SCORE) continue;
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            if (insideVisibleStructure(structureList, cx, cz)) continue;
            hot.put(e.getKey(), st);
        }
        return new Result(structureList, clusterBuilds(hot), read[0], inhabited.size());
    }

    private static void readStructures(CompoundTag chunk, Map<String, Poi> out) {
        CompoundTag starts = chunk.getCompound("structures").getCompound("starts");
        for (String key : starts.getAllKeys()) {
            CompoundTag start = starts.getCompound(key);
            String id = start.getString("id");
            if (id.isEmpty() || id.equals("INVALID")) continue;
            // A start does not store its own box. It is the union of its pieces' boxes.
            ListTag pieces = start.getList("Children", Tag.TAG_COMPOUND);
            int[] box = null;
            for (int i = 0; i < pieces.size(); i++) {
                int[] bb = pieces.getCompound(i).getIntArray("BB");
                if (bb.length != 6) continue;
                if (box == null) box = bb.clone();
                else for (int k = 0; k < 3; k++) { box[k] = Math.min(box[k], bb[k]); box[k + 3] = Math.max(box[k + 3], bb[k + 3]); }
            }
            if (box == null) continue;
            int cx = (box[0] + box[3]) / 2, cy = (box[1] + box[4]) / 2, cz = (box[2] + box[5]) / 2;
            Poi p = Poi.of(Poi.Kind.STRUCTURE, prettyName(id), cx, cy, cz,
                    box[3] - box[0] + 1, box[4] - box[1] + 1, box[5] - box[2] + 1);
            p.structureId = id;
            String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            p.enabled = !HIDDEN.contains(shortId);
            out.putIfAbsent(p.id + "/" + id, p);
        }
    }

    /** Joins neighbouring hot chunks (including diagonals) into builds and turns each big one into a point. */
    private static List<Poi> clusterBuilds(Map<Long, Stat> hot) {
        Set<Long> seen = new HashSet<>();
        List<Poi> builds = new ArrayList<>();
        for (long start : hot.keySet()) {
            if (!seen.add(start)) continue;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            long total = 0;
            double sx = 0, sz = 0, sy = 0;
            long most = 0;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            while (!queue.isEmpty()) {
                long k = queue.poll();
                int cx = (int) (k >> 32), cz = (int) k;
                Stat a = hot.get(k);
                total += a.manMade();
                most = Math.max(most, a.inhabited());
                sx += (cx * 16 + 8.0) * a.manMade();
                sz += (cz * 16 + 8.0) * a.manMade();
                sy += (double) a.surface() * a.manMade();
                minX = Math.min(minX, cx); maxX = Math.max(maxX, cx);
                minZ = Math.min(minZ, cz); maxZ = Math.max(maxZ, cz);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = key(cx + dx, cz + dz);
                        if (hot.containsKey(nk) && seen.add(nk)) queue.add(nk);
                    }
                }
            }
            if (total < MIN_CLUSTER_SCORE) continue;
            int x = (int) Math.round(sx / total), z = (int) Math.round(sz / total), y = (int) Math.round(sy / total) + 8;
            Poi p = Poi.of(Poi.Kind.BUILD, "Build at " + x + ", " + z, x, y, z,
                    (maxX - minX + 1) * 16, 48, (maxZ - minZ + 1) * 16);
            p.manMadeBlocks = (int) Math.min(Integer.MAX_VALUE, total);
            p.playerMinutes = most / (double) TICKS_PER_MINUTE;
            // bigger builds, and builds a player spent longer in, get shown more often
            p.weight = Math.min(3.0, 0.5 + total / 500.0 + Math.min(1.0, p.playerMinutes / 60.0));
            builds.add(p);
        }
        builds.sort(Comparator.comparingDouble((Poi p) -> -p.weight).thenComparingInt(p -> p.x));
        return builds;
    }

    /** Average surface height of a chunk from its WORLD_SURFACE heightmap, or the fallback when it is unreadable. */
    static int surfaceAverage(CompoundTag heightmaps, int minY, int fallback) {
        long[] data = heightmaps.getLongArray("WORLD_SURFACE");
        if (data.length == 0) return fallback;
        try {
            SimpleBitStorage bits = new SimpleBitStorage(9, 256, data);
            long sum = 0;
            for (int i = 0; i < 256; i++) sum += bits.get(i);
            return (int) (sum / 256) + minY - 1;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean chunkTouches(Poi s, int cx, int cz) {
        int x0 = s.x - s.sizeX / 2, x1 = s.x + s.sizeX / 2, z0 = s.z - s.sizeZ / 2, z1 = s.z + s.sizeZ / 2;
        return cx * 16 + 15 >= x0 && cx * 16 <= x1 && cz * 16 + 15 >= z0 && cz * 16 <= z1;
    }

    private static boolean insideVisibleStructure(List<Poi> structures, int cx, int cz) {
        for (Poi s : structures) {
            if (s.enabled && chunkTouches(s, cx, cz)) return true;
        }
        return false;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** minecraft:village_plains becomes "Village Plains". */
    static String prettyName(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder out = new StringBuilder();
        for (String w : s.split("_")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return out.toString();
    }
}
