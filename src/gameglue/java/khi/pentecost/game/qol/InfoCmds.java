package khi.pentecost.game.qol;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class InfoCmds {
    private static final String C = "Info";

    private InfoCmds() {}

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    private static String duration(long seconds) {
        long d = seconds / 86400;
        long h = seconds % 86400 / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        return (d > 0 ? d + "d " : "") + (d > 0 || h > 0 ? h + "h " : "") + m + "m " + s + "s";
    }

    private static String yesNo(boolean v) {
        return v ? "on" : "off";
    }

    static void add() {
        Qol.player(C, "ping", 0, "Show a player's latency", (c, t) ->
                Qol.ok(c, name(t) + " has a ping of " + t.latency + " ms."));

        Qol.none(C, "tps", 0, "Show how fast the server is ticking", c -> {
            float ms = c.getSource().getServer().getAverageTickTime();
            double tps = Math.min(20.0, 1000.0 / Math.max(ms, 1.0F));
            return Qol.ok(c, String.format("%.1f ticks per second (%.1f ms per tick; 20 and 50 are perfect).", tps, ms));
        });

        Qol.none(C, "mem", 0, "Show memory use", c -> {
            Runtime r = Runtime.getRuntime();
            long used = (r.totalMemory() - r.freeMemory()) >> 20;
            return Qol.ok(c, used + " MB used of " + (r.maxMemory() >> 20) + " MB allowed.");
        });

        Qol.none(C, "uptime", 0, "Show how long this world has been running", c ->
                Qol.ok(c, "Running for " + duration((System.currentTimeMillis() - Qol.startedAt) / 1000L) + "."));

        Qol.none(C, "online", 0, "List who is online", c -> {
            List<String> names = new ArrayList<>();
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                names.add(name(p));
            }
            return Qol.ok(c, names.size() + " online: " + String.join(", ", names));
        });

        Qol.player(C, "playtime", 0, "Show how long a player has played this world", (c, t) -> {
            int ticks = t.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
            return Qol.ok(c, name(t) + " has played " + duration(ticks / 20L) + ".");
        });

        Qol.player(C, "health", 0, "Show a player's health, food and level", (c, t) ->
                Qol.ok(c, name(t) + ": " + Math.round(t.getHealth()) + " of " + Math.round(t.getMaxHealth()) + " health, food "
                        + t.getFoodData().getFoodLevel() + ", level " + t.experienceLevel + "."));

        Qol.playerReq(C, "whois", 2, "Show who a player is", (c, t) -> {
            Qol.ok(c, name(t) + " (" + t.getUUID() + ")");
            Qol.ok(c, "  " + t.gameMode.getGameModeForPlayer().getName() + " mode, " + Math.round(t.getHealth()) + " health, level "
                    + t.experienceLevel + ", ping " + t.latency + " ms");
            return Qol.ok(c, "  at " + Where.describe(Where.of(t)));
        });

        Qol.none(C, "worldinfo", 0, "Show the world's name, day, difficulty and weather", c -> {
            MinecraftServer s = c.getSource().getServer();
            ServerLevel o = s.overworld();
            Qol.ok(c, "World " + s.getWorldData().getLevelName() + ", day " + (o.getDayTime() / 24000L + 1)
                    + ", " + s.getWorldData().getDifficulty().getKey() + " difficulty");
            return Qol.ok(c, "  weather: " + (o.isThundering() ? "thunderstorm" : o.isRaining() ? "rain" : "clear")
                    + ", seed " + o.getSeed());
        });

        Qol.none(C, "daycount", 0, "Show which day of the world it is", c -> {
            long t = c.getSource().getServer().overworld().getDayTime();
            return Qol.ok(c, "Day " + (t / 24000L + 1) + ", time " + (t % 24000L) + " of 24000.");
        });

        Qol.none(C, "realtime", 0, "Show the real date and time on the server", c ->
                Qol.ok(c, LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm:ss")) + "."));

        Qol.none(C, "entities", 2, "Count the loaded entities in each dimension", c -> {
            for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
                int n = 0;
                for (Entity e : l.getAllEntities()) {
                    n++;
                }
                Qol.ok(c, Where.dimension(l) + ": " + n + " entities");
            }
            return 1;
        });

        Qol.none(C, "chunks", 2, "Count the loaded chunks in each dimension", c -> {
            for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
                Qol.ok(c, Where.dimension(l) + ": " + l.getChunkSource().getLoadedChunksCount() + " chunks");
            }
            return 1;
        });

        Qol.none(C, "rules", 0, "Show the main game rules", c -> {
            MinecraftServer s = c.getSource().getServer();
            GameRules g = s.getGameRules();
            return Qol.ok(c, "Keep items " + yesNo(g.getBoolean(GameRules.RULE_KEEPINVENTORY))
                    + ", mob griefing " + yesNo(g.getBoolean(GameRules.RULE_MOBGRIEFING))
                    + ", day cycle " + yesNo(g.getBoolean(GameRules.RULE_DAYLIGHT))
                    + ", weather cycle " + yesNo(g.getBoolean(GameRules.RULE_WEATHER_CYCLE))
                    + ", fire spread " + yesNo(g.getBoolean(GameRules.RULE_DOFIRETICK))
                    + ", pvp " + yesNo(s.isPvpAllowed()) + ".");
        });
    }
}
