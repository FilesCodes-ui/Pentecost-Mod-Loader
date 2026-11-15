package khi.pentecost.game.menuworld.source;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Headless check: the terrain read from heightmaps agrees with the blocks themselves.
 * Usage: CacheTerrainCheck <seedCacheDir> <scratchDir>
 */
public final class CacheTerrainCheck {
    private static int failures;

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failures++;
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
        CacheTerrain terrain = CacheTerrain.load(reader, minCx, minCz, maxCx, maxCz);
        long ms = System.currentTimeMillis() - t0;
        int expected = (maxCx - minCx + 1) * (maxCz - minCz + 1);
        check("every chunk of the box is read", terrain.chunkCount() == expected, terrain.chunkCount() + " of " + expected + " in " + ms + " ms");

        // Compare 400 random columns with the top block found by walking the real block data down.
        Random rnd = new Random(42);
        int same = 0, wetAgree = 0, total = 0, wetColumns = 0, tried = 0;
        StringBuilder firstBad = new StringBuilder();
        while (total < 400 && tried++ < 4000) {
            int cx = minCx + rnd.nextInt(maxCx - minCx + 1), cz = minCz + rnd.nextInt(maxCz - minCz + 1);
            var tag = reader.readChunk(cx, cz);
            ChunkData d = tag == null ? null : ChunkData.build(tag, biomes, -4, 24);
            if (d == null) continue;
            int lx = rnd.nextInt(16), lz = rnd.nextInt(16);
            int top = Integer.MIN_VALUE;
            boolean fluidOnTop = false;
            for (int y = 319; y >= -64; y--) {
                BlockState s = d.sections[(y >> 4) - d.minSectionY].getBlockState(lx, y & 15, lz);
                if (s.blocksMotion() || !s.getFluidState().isEmpty()) {
                    top = y;
                    fluidOnTop = !s.getFluidState().isEmpty() && !s.blocksMotion();
                    break;
                }
            }
            total++;
            int x = cx * 16 + lx, z = cz * 16 + lz;
            if (terrain.heightAt(x, z) == top) {
                same++;
            } else if (firstBad.length() == 0) {
                firstBad.append("first miss at ").append(x).append(",").append(z).append(": heightmap ").append(terrain.heightAt(x, z))
                        .append(" blocks ").append(top);
            }
            if (fluidOnTop) {
                wetColumns++;
                if (terrain.waterAt(x, z)) wetAgree++;
            } else if (!terrain.waterAt(x, z)) {
                wetAgree++;
            }
        }
        check("heights match the block data", same == total, same + " of " + total + " columns" + (firstBad.length() > 0 ? ", " + firstBad : ""));
        check("water flags match the block data", wetAgree >= total * 0.98, wetAgree + " of " + total + " columns, " + wetColumns + " with water on top");
        check("a column outside the box is flat and dry", terrain.heightAt(minCx * 16 - 500, minCz * 16 - 500) == 62
                && !terrain.waterAt(minCx * 16 - 500, minCz * 16 - 500), "");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
