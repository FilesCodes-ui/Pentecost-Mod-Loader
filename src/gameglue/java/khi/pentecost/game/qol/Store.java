package khi.pentecost.game.qol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import khi.pentecost.boot.Log;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What the commands remember. Homes and warps are saved in the world folder (pentecost/qol.json) so they survive a
 * restart. The place you were before your last teleport is kept in memory only.
 */
final class Store {
    static final class Loc {
        String dim;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
    }

    private static final class Data {
        Map<String, Map<String, Loc>> homes = new LinkedHashMap<>();
        Map<String, Loc> warps = new LinkedHashMap<>();
        double dayFactor = 1.0;
        double nightFactor = 1.0;
        int firePct = 100;
        Map<String, Map<String, String>> presets = new LinkedHashMap<>();
    }

    static final int MAX_HOMES = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MinecraftServer server;
    private static Data data = new Data();
    private static final Map<UUID, Loc> BACK = new HashMap<>();

    private Store() {}

    static synchronized void bind(MinecraftServer s) {
        server = s;
        BACK.clear();
        data = new Data();
        try {
            Path f = file();
            if (Files.exists(f)) {
                Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
                if (d != null) {
                    if (d.homes == null) {
                        d.homes = new LinkedHashMap<>();
                    }
                    if (d.warps == null) {
                        d.warps = new LinkedHashMap<>();
                    }
                    data = d;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.error("QOL: cannot read the homes and warps file: " + e);
        }
    }

    private static Path file() {
        return server.getWorldPath(LevelResource.ROOT).resolve("pentecost").resolve("qol.json");
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("QOL: cannot save the homes and warps file: " + e);
        }
    }

    private static Loc loc(Where.Spot s) {
        Loc l = new Loc();
        l.dim = s.level().dimension().location().toString();
        l.x = s.pos().x;
        l.y = s.pos().y;
        l.z = s.pos().z;
        l.yaw = s.yRot();
        l.pitch = s.xRot();
        return l;
    }

    private static Where.Spot spot(Loc l) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(l.dim)));
        return level == null ? null : new Where.Spot(level, new Vec3(l.x, l.y, l.z), l.yaw, l.pitch);
    }

    // ---------------------------------------------------------------- back

    static synchronized void remember(ServerPlayer p) {
        BACK.put(p.getUUID(), loc(Where.of(p)));
    }

    static synchronized Where.Spot back(ServerPlayer p) {
        Loc l = BACK.get(p.getUUID());
        return l == null ? null : spot(l);
    }

    // ---------------------------------------------------------------- homes

    static synchronized Where.Spot home(ServerPlayer p, String name) {
        Map<String, Loc> m = data.homes.get(p.getUUID().toString());
        Loc l = m == null ? null : m.get(name.toLowerCase());
        return l == null ? null : spot(l);
    }

    /** Returns false when the player already has the most homes allowed. */
    static synchronized boolean setHome(ServerPlayer p, String name, Where.Spot s) {
        Map<String, Loc> m = data.homes.computeIfAbsent(p.getUUID().toString(), k -> new LinkedHashMap<>());
        String key = name.toLowerCase();
        if (!m.containsKey(key) && m.size() >= MAX_HOMES) {
            return false;
        }
        m.put(key, loc(s));
        save();
        return true;
    }

    static synchronized boolean delHome(ServerPlayer p, String name) {
        Map<String, Loc> m = data.homes.get(p.getUUID().toString());
        boolean gone = m != null && m.remove(name.toLowerCase()) != null;
        if (gone) {
            save();
        }
        return gone;
    }

    static synchronized TreeSet<String> homeNames(ServerPlayer p) {
        Map<String, Loc> m = data.homes.get(p.getUUID().toString());
        return m == null ? new TreeSet<>() : new TreeSet<>(m.keySet());
    }

    // ---------------------------------------------------------------- server settings that survive a restart

    static synchronized double dayFactor() {
        return data.dayFactor;
    }

    static synchronized double nightFactor() {
        return data.nightFactor;
    }

    static synchronized void setDayFactors(double day, double night) {
        data.dayFactor = day;
        data.nightFactor = night;
        save();
    }

    /** How much of the normal fire speed is wanted, 0 to 100. */
    static synchronized int firePct() {
        return data.firePct;
    }

    static synchronized void setFirePct(int pct) {
        data.firePct = pct;
        save();
    }

    static synchronized Map<String, String> customPreset(String name) {
        return data.presets == null ? null : data.presets.get(name.toLowerCase());
    }

    static synchronized void savePreset(String name, Map<String, String> rules) {
        if (data.presets == null) {
            data.presets = new LinkedHashMap<>();
        }
        data.presets.put(name.toLowerCase(), rules);
        save();
    }

    static synchronized boolean delPreset(String name) {
        boolean gone = data.presets != null && data.presets.remove(name.toLowerCase()) != null;
        if (gone) {
            save();
        }
        return gone;
    }

    static synchronized TreeSet<String> presetNames() {
        return data.presets == null ? new TreeSet<>() : new TreeSet<>(data.presets.keySet());
    }
    // ---------------------------------------------------------------- warps

    static synchronized Where.Spot warp(String name) {
        Loc l = data.warps.get(name.toLowerCase());
        return l == null ? null : spot(l);
    }

    static synchronized void setWarp(String name, Where.Spot s) {
        data.warps.put(name.toLowerCase(), loc(s));
        save();
    }

    static synchronized boolean delWarp(String name) {
        boolean gone = data.warps.remove(name.toLowerCase()) != null;
        if (gone) {
            save();
        }
        return gone;
    }

    static synchronized TreeSet<String> warpNames() {
        return new TreeSet<>(data.warps.keySet());
    }
}
