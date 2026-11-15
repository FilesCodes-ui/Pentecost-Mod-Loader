package khi.pentecost.game.menuworld.source;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless check, no game window: loads the vanilla registries, reads every chunk of a cached seed and prints
 * what it found. Usage: ChunkReadCheck <cacheDir> <scratchDir>
 */
public final class ChunkReadCheck {
    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[0]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        long t0 = System.currentTimeMillis();
        RegistryAccess.Frozen access = MenuRegistries.loadVanilla(Path.of(args[1]));
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        System.out.println("vanilla registries loaded in " + (System.currentTimeMillis() - t0) + " ms, biomes "
                + biomes.size() + ", dimension types " + access.registryOrThrow(Registries.DIMENSION_TYPE).size());

        JsonObject meta = JsonParser.parseString(Files.readString(cache.resolve("meta.json"))).getAsJsonObject();
        int radius = meta.get("radiusChunks").getAsInt();
        int cx = Math.floorDiv(meta.get("centerBlockX").getAsInt(), 16);
        int cz = Math.floorDiv(meta.get("centerBlockZ").getAsInt(), 16);
        RegionReader reader = new RegionReader(cache.resolve("region"));

        int full = 0, missing = 0, notFull = 0, withLight = 0;
        long solid = 0;
        ChunkData centre = null;
        t0 = System.currentTimeMillis();
        for (int x = cx - radius; x < cx + radius; x++) {
            for (int z = cz - radius; z < cz + radius; z++) {
                CompoundTag tag = reader.readChunk(x, z);
                if (tag == null) { missing++; continue; }
                ChunkData data = ChunkData.build(tag, biomes, -4, 24);
                if (data == null) { notFull++; continue; }
                full++;
                if (data.lightOn) withLight++;
                for (LevelChunkSection s : data.sections) if (!s.hasOnlyAir()) solid++;
                if (x == cx && z == cz) centre = data;
            }
        }
        System.out.println("area " + (radius * 2) + "x" + (radius * 2) + " chunks: full " + full + ", not full " + notFull
                + ", missing " + missing + ", with saved light " + withLight + ", sections with blocks " + solid
                + ", read in " + (System.currentTimeMillis() - t0) + " ms");

        if (centre != null) {
            int topY = Integer.MIN_VALUE;
            BlockState top = null;
            for (int si = centre.sections.length - 1; si >= 0 && top == null; si--) {
                LevelChunkSection s = centre.sections[si];
                if (s.hasOnlyAir()) continue;
                for (int y = 15; y >= 0; y--) {
                    BlockState b = s.getBlockState(8, y, 8);
                    if (!b.isAir()) { top = b; topY = (si + centre.minSectionY) * 16 + y; break; }
                }
            }
            int sy = Math.floorDiv(topY, 16);
            LevelChunkSection sec = centre.sections[sy - centre.minSectionY];
            String biome = sec.getNoiseBiome(2, (topY & 15) >> 2, 2).unwrapKey().map(k -> k.location().toString()).orElse("?");
            var skyLayer = centre.skyLight.get(Math.floorDiv(topY + 1, 16));
            System.out.println("centre chunk " + cx + "," + cz + ": top block " + top.getBlock().getDescriptionId()
                    + " at y=" + topY + ", biome " + biome + ", sky light above "
                    + (skyLayer == null ? "none saved" : skyLayer.get(8, (topY + 1) & 15, 8))
                    + ", heightmaps " + centre.heightmaps.getAllKeys());
        }
    }
}
