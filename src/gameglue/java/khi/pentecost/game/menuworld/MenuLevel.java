package khi.pentecost.game.menuworld;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import khi.pentecost.game.menuworld.source.ChunkData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A client-side overworld with no server behind it. Chunks come from {@link ChunkData}, which comes from the
 * pregenerated cache, so nothing is generated here and no mod block can appear. It is a real {@link ClientLevel},
 * so the normal level renderer (and Embeddium and Oculus on top of it) draws it.
 *
 * Status: compiles, not yet run in the game. Nothing swaps it in yet; {@link MenuWorld#ready()} stays false.
 * Call everything here on the render thread. Chunk data itself can be read and built on a worker thread.
 */
public final class MenuLevel {
    public final ClientLevel level;
    private final ClientPacketListener listener;
    private final boolean hasRenderer;

    private MenuLevel(ClientLevel level, ClientPacketListener listener, boolean hasRenderer) {
        this.level = level;
        this.listener = listener;
        this.hasRenderer = hasRenderer;
    }

    /**
     * Builds the level. {@code access} must come from {@code MenuRegistries.loadVanilla}, so the dimension types
     * and biomes are the vanilla ones. The shared level renderer is passed in but not told about the level yet.
     */
    public static MenuLevel create(Minecraft mc, RegistryAccess.Frozen access, long seed, int viewDistanceChunks) {
        return createWith(mc, mc.levelRenderer, mc::getProfiler, access, seed, viewDistanceChunks);
    }

    /** Same as create, with the renderer and profiler passed in. This is what lets a headless check build a level. */
    public static MenuLevel createWith(Minecraft mc, LevelRenderer renderer, Supplier<ProfilerFiller> profiler,
                                       RegistryAccess.Frozen access, long seed, int viewDistanceChunks) {
        // A connection that never connects: nothing is sent and nothing arrives.
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "menu_world");
        ClientPacketListener listener = new ClientPacketListener(mc, null, connection, null, profile, null);
        setRegistryAccess(listener, access);

        Holder<DimensionType> overworld = access.registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD);
        ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(Difficulty.PEACEFUL, false, false);
        ClientLevel level = new ClientLevel(listener, data, Level.OVERWORLD, overworld, viewDistanceChunks,
                viewDistanceChunks, profiler, renderer, false, seed);
        return new MenuLevel(level, listener, renderer != null);
    }

    public ClientPacketListener listener() {
        return listener;
    }

    /** Moves the window of loaded chunks. Chunks outside it are rejected by the chunk cache. */
    public void setViewCenter(int chunkX, int chunkZ) {
        level.getChunkSource().updateViewCenter(chunkX, chunkZ);
    }

    /** Hands one chunk to the level the way the game's own chunk packet handler does, then applies its light. */
    public boolean feed(ChunkData d) {
        ClientChunkCache cache = level.getChunkSource();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(d.packetBytes()));
        // Block entities (chests, signs) are left out on purpose: the menu world is only for looking at.
        if (cache.replaceWithPacketData(d.x, d.z, buf, d.heightmaps, out -> { }) == null) {
            return false; // outside the view window
        }

        LevelLightEngine light = level.getLightEngine();
        ChunkPos pos = new ChunkPos(d.x, d.z);
        light.setLightEnabled(pos, true);
        for (Map.Entry<Integer, DataLayer> e : d.skyLight.entrySet()) {
            light.queueSectionData(LightLayer.SKY, SectionPos.of(d.x, e.getKey(), d.z), e.getValue());
        }
        for (Map.Entry<Integer, DataLayer> e : d.blockLight.entrySet()) {
            light.queueSectionData(LightLayer.BLOCK, SectionPos.of(d.x, e.getKey(), d.z), e.getValue());
        }
        level.onChunkLoaded(pos);
        EmbeddiumHook.chunkIn(level, d.x, d.z);
        if (hasRenderer) { // a headless check has none
            int minY = level.getMinSection();
            for (int i = 0; i < d.sections.length; i++) {
                level.setSectionDirtyWithNeighbors(d.x, minY + i, d.z);
            }
        }
        return true;
    }

    /** Applies queued light data. Call once after a batch of {@link #feed} calls. */
    public void finishBatch() {
        level.getLightEngine().runLightUpdates();
    }

    public void drop(int chunkX, int chunkZ) {
        level.getChunkSource().drop(chunkX, chunkZ);
        EmbeddiumHook.chunkOut(level, chunkX, chunkZ);
        level.getLightEngine().setLightEnabled(new ChunkPos(chunkX, chunkZ), false);
    }

    /**
     * Embeddium draws a chunk only after its chunk tracker has seen both the block data and the light data arrive. The
     * game's network handler tells it; this level has no network, so it is told here. Without Embeddium nothing happens.
     */
    private static final class EmbeddiumHook {
        private static java.lang.reflect.Method get, added, removed;
        private static int all;
        private static boolean tried, ok;

        private static synchronized boolean resolve() {
            if (!tried) {
                tried = true;
                try {
                    Class<?> holder = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder");
                    Class<?> tracker = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker");
                    Class<?> status = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus");
                    get = holder.getMethod("get", ClientLevel.class);
                    added = tracker.getMethod("onChunkStatusAdded", int.class, int.class, int.class);
                    removed = tracker.getMethod("onChunkStatusRemoved", int.class, int.class, int.class);
                    all = status.getField("FLAG_ALL").getInt(null);
                    ok = true;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    ok = false; // Embeddium is not installed
                }
            }
            return ok;
        }

        static void chunkIn(ClientLevel level, int x, int z) {
            if (!resolve()) return;
            try {
                added.invoke(get.invoke(null, level), x, z, all);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // a tracker that cannot be told only means the chunk is not drawn
            }
        }

        static void chunkOut(ClientLevel level, int x, int z) {
            if (!resolve()) return;
            try {
                removed.invoke(get.invoke(null, level), x, z, all);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // see chunkIn
            }
        }
    }

    /** A real login replaces the REMOTE layer of the listener's layered registry access. Do the same, found by field type. */
    @SuppressWarnings("unchecked")
    private static void setRegistryAccess(ClientPacketListener listener, RegistryAccess.Frozen access) {
        for (Class<?> c = listener.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == LayeredRegistryAccess.class && !Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        LayeredRegistryAccess<ClientRegistryLayer> current = (LayeredRegistryAccess<ClientRegistryLayer>) f.get(listener);
                        f.set(listener, current.replaceFrom(ClientRegistryLayer.REMOTE, access));
                        return;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        throw new IllegalStateException("Could not set the menu world's registries", e);
                    }
                }
            }
        }
        throw new IllegalStateException("The client packet listener has no registry access field");
    }
}
