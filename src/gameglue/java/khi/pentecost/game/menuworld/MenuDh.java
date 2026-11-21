package khi.pentecost.game.menuworld;

import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class MenuDh {
    private MenuDh() {}

    private static boolean tried, ok;
    private static Object clientApi;
    private static Method connected, disconnected, getWorld, levelWrapper, applyUpdate, queued, worldGetLevel;
    private static Object sharedApi, updateManager;
    private static Constructor<?> chunkWrapper;
    private static volatile boolean active;
    private static ServerData ours;

    private static Class<?> find(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = {Thread.currentThread().getContextClassLoader(), MenuDh.class.getClassLoader()};
        for (ClassLoader l : loaders) {
            if (l == null) continue;
            try {
                return Class.forName(name, false, l);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(name);
    }

    private static synchronized boolean resolve() {
        if (tried) return ok;
        tried = true;
        try {
            String p = "com.seibel.distanthorizons.";
            Class<?> api = find(p + "core.api.internal.ClientApi");
            Class<?> shared = find(p + "core.api.internal.SharedApi");
            Class<?> proxy = find(p + "common.util.ProxyUtil_forge");
            Class<?> wrap = find(p + "common.wrappers.chunk.ChunkWrapper_forge");
            Class<?> iChunk = find(p + "core.wrapperInterfaces.chunk.IChunkWrapper");
            Class<?> iLevel = find(p + "core.wrapperInterfaces.world.ILevelWrapper");
            Class<?> manager = find(p + "core.api.internal.chunkUpdating.WorldChunkUpdateManager");
            Class<?> world = find(p + "core.world.IDhWorld");
            clientApi = api.getField("INSTANCE").get(null);
            connected = api.getMethod("onClientOnlyConnected");
            disconnected = api.getMethod("onClientOnlyDisconnected");
            sharedApi = shared.getField("INSTANCE").get(null);
            getWorld = shared.getMethod("getAbstractDhWorld");
            applyUpdate = shared.getMethod("applyChunkUpdate", iChunk, iLevel, boolean.class);
            levelWrapper = proxy.getMethod("getLevelWrapper", LevelAccessor.class);
            chunkWrapper = wrap.getConstructor(ChunkAccess.class, iLevel);
            updateManager = manager.getField("INSTANCE").get(null);
            queued = manager.getMethod("getTotalQueuedCount");
            worldGetLevel = world.getMethod("getLevel", iLevel);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ok = false;
            if (!(e instanceof ClassNotFoundException)) Log.warn("Menu world: Distant Horizons could not be reached: " + e);
        }
        return ok;
    }

    public static boolean active() {
        return active;
    }

    public static void start(Minecraft mc, long seed) {
        if (active || !resolve()) return;
        try {
            if (mc.getCurrentServer() != null || getWorld.invoke(null) != null) return;
            String name = "pentecost_menu_" + Long.toUnsignedString(seed);
            ours = new ServerData(name, name, false);
            setServer(mc, ours);
            connected.invoke(clientApi);
            if (getWorld.invoke(null) == null) {
                setServer(mc, null);
                ours = null;
                return;
            }
            active = true;
            Log.info("Menu world: Distant Horizons draws its LOD for it");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.warn("Menu world: Distant Horizons could not start for it: " + e);
            try {
                if (mc.getCurrentServer() == ours) setServer(mc, null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            ours = null;
        }
    }

    public static void stop(Minecraft mc) {
        if (!active) return;
        active = false;
        try {
            disconnected.invoke(clientApi);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.warn("Menu world: Distant Horizons did not close cleanly: " + e);
        }
        try {
            if (mc.getCurrentServer() == ours) setServer(mc, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Menu world: could not clear the server name: " + e);
        }
        ours = null;
    }

    public static boolean levelReady(ClientLevel level) {
        if (!active) return false;
        try {
            Object world = getWorld.invoke(null);
            return world != null && worldGetLevel.invoke(world, levelWrapper.invoke(null, level)) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static int queued() {
        if (!active) return 0;
        try {
            return (Integer) queued.invoke(updateManager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return 0;
        }
    }

    public static void chunk(ClientLevel level, ChunkAccess chunk) {
        if (!active || chunk == null) return;
        try {
            Object lw = levelWrapper.invoke(null, level);
            applyUpdate.invoke(sharedApi, chunkWrapper.newInstance(chunk, lw), lw, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                Log.warn("Menu world: a chunk could not be handed to Distant Horizons: " + e);
            }
        }
    }

    private static volatile boolean warned;
    private static java.lang.reflect.Field serverField;

    private static void setServer(Minecraft mc, ServerData data) throws ReflectiveOperationException {
        if (serverField == null) {
            for (java.lang.reflect.Field f : Minecraft.class.getDeclaredFields()) {
                if (f.getType() == ServerData.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    serverField = f;
                    break;
                }
            }
            if (serverField == null) throw new NoSuchFieldException("Minecraft.currentServer");
        }
        serverField.set(mc, data);
    }
}
