package khi.pentecost.game.menuworld.source;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.HashMap;
import java.util.Map;

/**
 * One finished chunk, ready to be handed to a client level: its sections, its heightmaps and the saved light.
 * Built from the chunk's saved NBT. Only chunks that reached the "full" status are accepted, because the others
 * (the generator's border chunks) are incomplete terrain.
 */
public final class ChunkData {
    public final int x;
    public final int z;
    public final int minSectionY;
    public final LevelChunkSection[] sections;
    public final CompoundTag heightmaps;
    /** Saved light by section Y. Includes the extra light-only sections above and below the world. */
    public final Map<Integer, DataLayer> skyLight;
    public final Map<Integer, DataLayer> blockLight;
    public final boolean lightOn;
    private volatile byte[] packet;

    private ChunkData(int x, int z, int minSectionY, LevelChunkSection[] sections, CompoundTag heightmaps,
                      Map<Integer, DataLayer> sky, Map<Integer, DataLayer> block, boolean lightOn) {
        this.x = x;
        this.z = z;
        this.minSectionY = minSectionY;
        this.sections = sections;
        this.heightmaps = heightmaps;
        this.skyLight = sky;
        this.blockLight = block;
        this.lightOn = lightOn;
    }

    /** Null when the chunk is not finished. {@code minSectionY} is -4 for the vanilla overworld, 24 sections. */
    public static ChunkData build(CompoundTag tag, Registry<Biome> biomes, int minSectionY, int sectionCount) {
        String status = tag.getString("Status");
        if (!status.equals("minecraft:full") && !status.equals("full")) return null;

        Codec<PalettedContainer<BlockState>> blockCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,
                BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        Holder<Biome> plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(biomes.asHolderIdMap(),
                biomes.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, plains);

        LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        Map<Integer, DataLayer> sky = new HashMap<>();
        Map<Integer, DataLayer> block = new HashMap<>();
        ListTag list = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            int y = s.getByte("Y");
            int index = y - minSectionY;
            if (index >= 0 && index < sectionCount) {
                PalettedContainer<BlockState> states = s.contains("block_states", Tag.TAG_COMPOUND)
                        ? blockCodec.parse(NbtOps.INSTANCE, s.getCompound("block_states")).getOrThrow(false, ChunkData::fail)
                        : new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                                PalettedContainer.Strategy.SECTION_STATES);
                PalettedContainerRO<Holder<Biome>> biome = s.contains("biomes", Tag.TAG_COMPOUND)
                        ? biomeCodec.parse(NbtOps.INSTANCE, s.getCompound("biomes")).getOrThrow(false, ChunkData::fail)
                        : new PalettedContainer<>(biomes.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES);
                sections[index] = new LevelChunkSection(states, biome);
            }
            if (s.contains("SkyLight", Tag.TAG_BYTE_ARRAY)) sky.put(y, new DataLayer(s.getByteArray("SkyLight")));
            if (s.contains("BlockLight", Tag.TAG_BYTE_ARRAY)) block.put(y, new DataLayer(s.getByteArray("BlockLight")));
        }
        for (int i = 0; i < sectionCount; i++) {
            if (sections[i] == null) sections[i] = new LevelChunkSection(biomes);
        }
        return new ChunkData(tag.getInt("xPos"), tag.getInt("zPos"), minSectionY, sections,
                tag.getCompound("Heightmaps"), sky, block, tag.getBoolean("isLightOn"));
    }

    /**
     * The sections as a chunk packet carries them. A server builds this on its own thread and the client only reads it.
     * Call it on a worker thread; the render thread then finds it ready.
     */
    public byte[] packetBytes() {
        byte[] b = packet;
        if (b == null) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            for (LevelChunkSection s : sections) s.write(buf);
            b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            packet = b;
        }
        return b;
    }

    private static void fail(String message) {
        throw new IllegalStateException("Bad chunk data: " + message);
    }
}
