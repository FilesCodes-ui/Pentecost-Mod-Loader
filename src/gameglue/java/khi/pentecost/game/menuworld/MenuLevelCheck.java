package khi.pentecost.game.menuworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.game.menuworld.source.ChunkData;
import khi.pentecost.game.menuworld.source.MenuRegistries;
import khi.pentecost.game.menuworld.source.RegionReader;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless check, no game window and no Minecraft instance: builds the menu level, feeds it every cached chunk
 * and reads blocks, light, biome and heightmap back out of it. Usage: MenuLevelCheck <cacheDir> <scratchDir>
 * What it cannot show: drawing. That needs the running game.
 */
public final class MenuLevelCheck {
    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[0]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.Frozen access = MenuRegistries.loadVanilla(Path.of(args[1]));
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);

        JsonObject meta = JsonParser.parseString(Files.readString(cache.resolve("meta.json"))).getAsJsonObject();
        int radius = meta.get("radiusChunks").getAsInt();
        int cx = Math.floorDiv(meta.get("centerBlockX").getAsInt(), 16);
        int cz = Math.floorDiv(meta.get("centerBlockZ").getAsInt(), 16);

        MenuLevel menu = MenuLevel.createWith(null, null, () -> InactiveProfiler.INSTANCE, access,
                meta.get("seed").getAsLong(), 8);
        System.out.println("level built: " + menu.level.dimension().location() + ", min y " + menu.level.getMinBuildHeight()
                + ", height " + menu.level.getHeight());
        menu.setViewCenter(cx, cz);

        RegionReader reader = new RegionReader(cache.resolve("region"));
        int fed = 0, rejected = 0;
        for (int x = cx - radius; x < cx + radius; x++) {
            for (int z = cz - radius; z < cz + radius; z++) {
                var tag = reader.readChunk(x, z);
                ChunkData d = tag == null ? null : ChunkData.build(tag, biomes, -4, 24);
                if (d == null) continue;
                if (menu.feed(d)) fed++; else rejected++;
            }
        }
        menu.finishBatch();
        System.out.println("chunks fed " + fed + ", rejected " + rejected);

        int bx = cx * 16 + 8, bz = cz * 16 + 8;
        int surface = menu.level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        BlockPos below = new BlockPos(bx, surface - 1, bz), above = new BlockPos(bx, surface, bz);
        System.out.println("column " + bx + "," + bz + ": surface y " + surface + ", block " + menu.level.getBlockState(below).getBlock()
                + ", sky light above " + menu.level.getBrightness(LightLayer.SKY, above)
                + ", biome " + menu.level.getBiome(below).unwrapKey().map(k -> k.location().toString()).orElse("?")
                + ", loaded " + menu.level.getChunkSource().hasChunk(cx, cz));
    }
}
