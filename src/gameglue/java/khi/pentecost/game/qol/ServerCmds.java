package khi.pentecost.game.qol;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ServerCmds {
    private static final String C = "Server";
    private static final UUID SERVER = new UUID(0L, 0L);
    private static final Pattern TIME = Pattern.compile("(\\d{1,6})([smhdw]?)", Pattern.CASE_INSENSITIVE);

    private static long stopAt = -1L;
    private static String stopReason = "";
    private static long lastCall = Long.MAX_VALUE;

    private static int autosaveId = -1;
    private static String oldMotd;
    private static boolean maintenance;

    private static final SuggestionProvider<CommandSourceStack> KNOWN = (c, b) -> {
        TreeSet<String> names = new TreeSet<>();
        for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
            names.add(p.getGameProfile().getName());
        }
        return SharedSuggestionProvider.suggest(names, b);
    };

    private static final String[][] DEATHS = {
            {"fall", "death.fell.accident.generic"}, {"lava", "death.attack.lava"}, {"drown", "death.attack.drown"},
            {"explosion", "death.attack.explosion"}, {"anvil", "death.attack.anvil"}, {"fire", "death.attack.inFire"},
            {"void", "death.attack.outOfWorld"}, {"cactus", "death.attack.cactus"}, {"starve", "death.attack.starve"},
            {"magic", "death.attack.magic"}, {"lightning", "death.attack.lightningBolt"}, {"wither", "death.attack.wither"},
            {"freeze", "death.attack.freeze"}, {"generic", "death.attack.generic"}};

    private ServerCmds() {}

    static void reset() {
        stopAt = -1L;
        autosaveId = -1;
        maintenance = false;
    }

    private static GameProfile profile(CommandSourceStack src, String name) {
        MinecraftServer s = src.getServer();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                return p.getGameProfile();
            }
        }
        if (s.getProfileCache() != null) {
            Optional<GameProfile> gp = s.getProfileCache().get(name);
            if (gp.isPresent()) {
                return gp.get();
            }
        }
        throw new Bad("The server does not know a player called " + name + " (they have to have joined once).");
    }

    private static long millis(String word) {
        Matcher m = TIME.matcher(word);
        if (!m.matches()) {
            throw new Bad("Write the time like 30s, 10m, 2h, 1d or 1w.");
        }
        long n = Long.parseLong(m.group(1));
        return n * switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> 1000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            default -> 60_000L;
        };
    }

    private static String span(long ms) {
        long s = ms / 1000L;
        if (s >= 86400L) {
            return (s / 86400L) + "d " + ((s % 86400L) / 3600L) + "h";
        }
        if (s >= 3600L) {
            return (s / 3600L) + "h " + ((s % 3600L) / 60L) + "m";
        }
        if (s >= 60L) {
            return (s / 60L) + "m " + (s % 60L) + "s";
        }
        return s + "s";
    }

    private static void say(MinecraftServer s, Component c) {
        s.getPlayerList().broadcastSystemMessage(c, false);
    }

    static void tick(MinecraftServer server) {
        if (stopAt < 0L) {
            return;
        }
        long left = stopAt - server.getTickCount();
        long secs = (left + 19L) / 20L;
        if (left <= 0L) {
            stopAt = -1L;
            Component why = Component.literal(stopReason.isEmpty() ? "The server is stopping." : stopReason);
            for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
                p.connection.disconnect(why);
            }
            server.halt(false);
            return;
        }
        boolean call = secs <= 5L || secs == 10L || secs == 30L || secs == 60L || (secs > 60L && secs % 300L == 0L);
        if (call && secs != lastCall) {
            lastCall = secs;
            Qol.say(server, "The server stops in " + span(secs * 1000L) + (stopReason.isEmpty() ? "." : ": " + stopReason));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                ChatCmds.sound(p, net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, secs <= 5L ? 1.8F : 1.0F);
            }
        }
    }

    static void add() {
        Qol.none(C, "panel", 2, "A one-screen summary of the server and its main settings", c -> {
            MinecraftServer s = c.getSource().getServer();
            PlayerList pl = s.getPlayerList();
            float avg = s.getAverageTickTime();
            double tps = avg <= 50.0F ? 20.0 : 1000.0 / avg;
            Runtime r = Runtime.getRuntime();
            Qol.ok(c, "Players " + s.getPlayerCount() + "/" + s.getMaxPlayers() + ", " + String.format(Locale.ROOT, "%.1f", tps)
                    + " ticks per second, memory " + ((r.totalMemory() - r.freeMemory()) >> 20) + " of " + (r.maxMemory() >> 20) + " MB, up "
                    + span(System.currentTimeMillis() - Qol.startedAt) + ".");
            Qol.ok(c, "Difficulty " + s.getWorldData().getDifficulty().getKey() + ", new players start in "
                    + s.getDefaultGameType().getName() + " mode, PvP " + (s.isPvpAllowed() ? "on" : "off") + ".");
            Qol.ok(c, "Whitelist " + (pl.isUsingWhitelist() ? "on" : "off") + " with " + pl.getWhiteListNames().length + " names, "
                    + pl.getBans().getEntries().size() + " bans, " + pl.getOps().getEntries().size() + " operators"
                    + (maintenance ? ", MAINTENANCE MODE" : "") + ".");
            Qol.ok(c, "Day lasts " + (Store.dayFactor() == 1.0 && Store.nightFactor() == 1.0 ? "the normal time" : "a custom time (see /daylength)")
                    + ", fire at " + Store.firePct() + " percent, daylight cycle " + RuleCmds.get(s, "doDaylightCycle")
                    + ", mob griefing " + RuleCmds.get(s, "mobGriefing") + ", keep inventory " + RuleCmds.get(s, "keepInventory")
                    + ", random tick " + RuleCmds.get(s, "randomTickSpeed") + ".");
            if (stopAt >= 0L) {
                Qol.ok(c, "A stop is set in " + span(Math.max(0L, stopAt - s.getTickCount()) * 50L) + ". /stopcancel cancels it.");
            }
            return 1;
        });

        Qol.custom(C, "stopin", 4, "<time> [reason]", "Stop the server after a countdown (30s, 5m, 1h)",
                Qol.lit("stopin", 4).then(Commands.argument("time", StringArgumentType.word()).suggests(Qol.options("30s", "1m", "5m", "10m", "1h"))
                        .executes(Qol.guard(c -> startStop(c.getSource(), StringArgumentType.getString(c, "time"), "")))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(Qol.guard(c -> startStop(c.getSource(), StringArgumentType.getString(c, "time"), StringArgumentType.getString(c, "reason")))))));

        Qol.none(C, "stopcancel", 4, "Cancel a stop countdown", c -> {
            if (stopAt < 0L) {
                throw new Bad("No stop is set.");
            }
            stopAt = -1L;
            Qol.say(c.getSource().getServer(), "The server stop was cancelled.");
            return 1;
        });

        Qol.custom(C, "wl", 3, "add|remove <name> | all | list | on | off | reload", "Whitelist helper",
                Qol.lit("wl", 3)
                        .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word()).suggests(KNOWN)
                                .executes(Qol.guard(c -> {
                                    GameProfile gp = profile(c.getSource(), StringArgumentType.getString(c, "name"));
                                    UserWhiteList wl = c.getSource().getServer().getPlayerList().getWhiteList();
                                    if (wl.isWhiteListed(gp)) {
                                        throw new Bad(gp.getName() + " is already on the whitelist.");
                                    }
                                    wl.add(new UserWhiteListEntry(gp));
                                    return Qol.ok(c, "Added " + gp.getName() + ".");
                                }))))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerList().getWhiteListNames(), b))
                                .executes(Qol.guard(c -> {
                                    GameProfile gp = profile(c.getSource(), StringArgumentType.getString(c, "name"));
                                    UserWhiteList wl = c.getSource().getServer().getPlayerList().getWhiteList();
                                    if (!wl.isWhiteListed(gp)) {
                                        throw new Bad(gp.getName() + " is not on the whitelist.");
                                    }
                                    wl.remove(gp);
                                    return Qol.ok(c, "Removed " + gp.getName() + ".");
                                }))))
                        .then(Commands.literal("all").executes(Qol.guard(c -> {
                            UserWhiteList wl = c.getSource().getServer().getPlayerList().getWhiteList();
                            int n = 0;
                            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                                if (!wl.isWhiteListed(p.getGameProfile())) {
                                    wl.add(new UserWhiteListEntry(p.getGameProfile()));
                                    n++;
                                }
                            }
                            return Qol.ok(c, "Added " + n + " online player" + (n == 1 ? "" : "s") + ".");
                        })))
                        .then(Commands.literal("list").executes(Qol.guard(c -> {
                            String[] names = c.getSource().getServer().getPlayerList().getWhiteListNames();
                            return Qol.ok(c, names.length == 0 ? "The whitelist is empty." : names.length + " on the whitelist: " + String.join(", ", names));
                        })))
                        .then(Commands.literal("on").executes(Qol.guard(c -> {
                            c.getSource().getServer().getPlayerList().setUsingWhiteList(true);
                            return Qol.ok(c, "Whitelist on.");
                        })))
                        .then(Commands.literal("off").executes(Qol.guard(c -> {
                            c.getSource().getServer().getPlayerList().setUsingWhiteList(false);
                            return Qol.ok(c, "Whitelist off.");
                        })))
                        .then(Commands.literal("reload").executes(Qol.guard(c -> {
                            c.getSource().getServer().getPlayerList().reloadWhiteList();
                            return Qol.ok(c, "Whitelist reloaded.");
                        }))));

        Qol.custom(C, "tempban", 3, "<player> <time> [reason]", "Ban a player for a while (30m, 2h, 7d)",
                Qol.lit("tempban", 3).then(Commands.argument("name", StringArgumentType.word()).suggests(KNOWN)
                        .then(Commands.argument("time", StringArgumentType.word()).suggests(Qol.options("30m", "2h", "1d", "7d"))
                                .executes(Qol.guard(c -> tempBan(c.getSource(), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "time"), "Banned for a while.")))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(Qol.guard(c -> tempBan(c.getSource(), StringArgumentType.getString(c, "name"),
                                                StringArgumentType.getString(c, "time"), StringArgumentType.getString(c, "reason"))))))));

        Qol.none(C, "bans", 3, "List banned players with their reason and how long is left", c -> {
            UserBanList bl = c.getSource().getServer().getPlayerList().getBans();
            var entries = bl.getEntries();
            if (entries.isEmpty()) {
                return Qol.ok(c, "Nobody is banned.");
            }
            String[] banNames = bl.getUserList();
            int idx = 0;
            for (UserBanListEntry e : entries) {
                String who = banNames.length == entries.size() ? banNames[idx] : "player " + (idx + 1);
                idx++;                Date ex = e.getExpires();
                Qol.ok(c, who + ": " + (e.getReason() == null ? "no reason" : e.getReason())
                        + (ex == null ? ", for good" : ", " + span(Math.max(0L, ex.getTime() - System.currentTimeMillis())) + " left"));
            }
            return entries.size();
        });

        Qol.word(C, "maintenance", 3, "<on|off>", "Close the server for upkeep: whitelist on, a new message of the day, non-operators kicked", false,
                Qol.options("on", "off"), (c, w) -> {
                    MinecraftServer s = c.getSource().getServer();
                    if (w.equalsIgnoreCase("on")) {
                        if (!maintenance) {
                            oldMotd = s.getMotd();
                        }
                        maintenance = true;
                        s.getPlayerList().setUsingWhiteList(true);
                        s.setMotd("Closed for maintenance. Back soon.");
                        int n = 0;
                        for (ServerPlayer p : new ArrayList<>(s.getPlayerList().getPlayers())) {
                            if (!p.hasPermissions(2)) {
                                p.connection.disconnect(Component.literal("The server is closed for maintenance. Back soon."));
                                n++;
                            }
                        }
                        return Qol.ok(c, "Maintenance on. Kicked " + n + ". Operators can still join only if they are whitelisted: /wl add <name>.");
                    }
                    if (w.equalsIgnoreCase("off")) {
                        maintenance = false;
                        s.getPlayerList().setUsingWhiteList(false);
                        if (oldMotd != null) {
                            s.setMotd(oldMotd);
                        }
                        return Qol.ok(c, "Maintenance off. The whitelist is off and the message of the day is back.");
                    }
                    throw new Bad("Say on or off.");
                });

        Qol.custom(C, "autosave", 3, "<minutes|off|now>", "Save the world every few minutes",
                Qol.lit("autosave", 3)
                        .then(Commands.literal("off").executes(Qol.guard(c -> {
                            if (autosaveId >= 0) {
                                Timers.cancel(SERVER, autosaveId);
                                autosaveId = -1;
                            }
                            return Qol.ok(c, "Extra saving is off.");
                        })))
                        .then(Commands.literal("now").executes(Qol.guard(c -> {
                            c.getSource().getServer().saveEverything(true, false, true);
                            return Qol.ok(c, "Saved.");
                        })))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 120))
                                .executes(Qol.guard(c -> {
                                    int m = IntegerArgumentType.getInteger(c, "minutes");
                                    MinecraftServer s = c.getSource().getServer();
                                    if (autosaveId >= 0) {
                                        Timers.cancel(SERVER, autosaveId);
                                    }
                                    autosaveId = Timers.add(SERVER, "autosave", m * 1200L, m * 1200L, () -> s.saveEverything(true, false, true));
                                    return Qol.ok(c, "The world will be saved every " + m + " minute" + (m == 1 ? "" : "s") + ".");
                                }))));

        Qol.none(C, "saveoff", 3, "Stop the world saving to disk (for a backup). Use /saveon after", c -> {
            int n = 0;
            for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
                l.noSave = true;
                n++;
            }
            return Qol.ok(c, "Saving is off in " + n + " dimensions. /saveon turns it back on.");
        });

        Qol.none(C, "saveon", 3, "Let the world save to disk again", c -> {
            for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
                l.noSave = false;
            }
            return Qol.ok(c, "Saving is on.");
        });

        Qol.word(C, "defaultgm", 3, "<survival|creative|adventure|spectator>", "The game mode new players start in", false,
                Qol.options("survival", "creative", "adventure", "spectator"), (c, w) -> {
                    GameType t = switch (w.toLowerCase(Locale.ROOT)) {
                        case "survival" -> GameType.SURVIVAL;
                        case "creative" -> GameType.CREATIVE;
                        case "adventure" -> GameType.ADVENTURE;
                        case "spectator" -> GameType.SPECTATOR;
                        default -> throw new Bad("Pick survival, creative, adventure or spectator.");
                    };
                    c.getSource().getServer().setDefaultGameType(t);
                    return Qol.ok(c, "New players now start in " + t.getName() + " mode.");
                });

        Qol.wordText(C, "sayas", 2, "<name> <text>", "Put a chat line in the name of anyone", KNOWN, (c, who, text) -> {
            say(c.getSource().getServer(), Component.literal("<" + who + "> " + text));
            return 1;
        });

        Qol.word("Fun", "fakejoin", 2, "<name>", "Show a made up join message", false, KNOWN, (c, w) -> {
            say(c.getSource().getServer(), Component.translatable("multiplayer.player.joined", w).withStyle(ChatFormatting.YELLOW));
            return 1;
        });

        Qol.word("Fun", "fakeleave", 2, "<name>", "Show a made up leave message", false, KNOWN, (c, w) -> {
            say(c.getSource().getServer(), Component.translatable("multiplayer.player.left", w).withStyle(ChatFormatting.YELLOW));
            return 1;
        });

        Qol.wordText("Fun", "fakedeath", 2, "<name> <fall|lava|drown|explosion|anvil|fire|void|cactus|starve|magic|lightning|wither|freeze|generic>",
                "Show a made up death message", Qol.options("Steve", "Alex"), (c, who, how) -> {
                    String key = how.trim().toLowerCase(Locale.ROOT);
                    for (String[] d : DEATHS) {
                        if (d[0].equals(key)) {
                            say(c.getSource().getServer(), Component.translatable(d[1], who));
                            return 1;
                        }
                    }
                    throw new Bad("Pick one of: fall, lava, drown, explosion, anvil, fire, void, cactus, starve, magic, lightning, wither, freeze, generic.");
                });

        Qol.wordText("Fun", "fakeadvancement", 2, "<name> <title>", "Show a made up advancement message", KNOWN, (c, who, title) -> {
            say(c.getSource().getServer(), Component.translatable("chat.type.advancement.task", who,
                    Component.literal("[" + title + "]").withStyle(ChatFormatting.GREEN)));
            return 1;
        });
    }

    private static int startStop(CommandSourceStack src, String time, String reason) {
        long ms = millis(time);
        if (ms < 1000L || ms > 86_400_000L) {
            throw new Bad("Pick a time between 1 second and 1 day.");
        }
        MinecraftServer s = src.getServer();
        stopAt = s.getTickCount() + ms / 50L;
        stopReason = reason;
        lastCall = Long.MAX_VALUE;
        Qol.say(s, "The server stops in " + span(ms) + (reason.isEmpty() ? "." : ": " + reason));
        src.sendSuccess(() -> Component.literal("Stop set. /stopcancel cancels it."), false);
        return 1;
    }

    private static int tempBan(CommandSourceStack src, String name, String time, String reason) {
        GameProfile gp = profile(src, name);
        MinecraftServer s = src.getServer();
        long ms = millis(time);
        if (s.getPlayerList().getOps().get(gp) != null && s.getPlayerList().getOps().get(gp).getLevel() >= 2) {
            throw new Bad(gp.getName() + " is an operator. Remove them first (/oplevel " + gp.getName() + " 0).");
        }
        Date now = new Date();
        UserBanListEntry e = new UserBanListEntry(gp, now, src.getTextName(), new Date(now.getTime() + ms), reason);
        s.getPlayerList().getBans().add(e);
        ServerPlayer p = s.getPlayerList().getPlayer(gp.getId());
        if (p != null) {
            p.connection.disconnect(Component.literal("You are banned for " + span(ms) + ": " + reason));
        }
        src.sendSuccess(() -> Component.literal("Banned " + gp.getName() + " for " + span(ms) + "."), true);
        return 1;
    }
}
