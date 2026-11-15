package khi.pentecost.game.menuworld.source;

import net.minecraft.nbt.CompoundTag;

import java.io.IOException;

/** Hands out the saved chunks of a world, one region file at a time. The scanner works against this. */
public interface ChunkSource {

    interface Visitor {
        void chunk(int chunkX, int chunkZ, CompoundTag tag) throws IOException;
    }

    /** Calls the visitor for every chunk that exists inside the box (chunk coordinates, inclusive). */
    void forEach(int minCx, int minCz, int maxCx, int maxCz, Visitor visitor) throws IOException;

    /** The box of chunks the world has files for, {minCx, minCz, maxCx, maxCz}, or null when it has none. */
    int[] chunkBounds();
}
