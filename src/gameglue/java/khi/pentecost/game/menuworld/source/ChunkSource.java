package khi.pentecost.game.menuworld.source;

import net.minecraft.nbt.CompoundTag;

import java.io.IOException;

public interface ChunkSource {

    interface Visitor {
        void chunk(int chunkX, int chunkZ, CompoundTag tag) throws IOException;
    }

    void forEach(int minCx, int minCz, int maxCx, int maxCz, Visitor visitor) throws IOException;

    int[] chunkBounds();
}
