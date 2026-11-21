package khi.pentecost.game.qol;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TimerCmds {
    private static final String C = "Timers";
    private static final Map<UUID, Long> WATCH = new HashMap<>();

    private TimerCmds() {}

    private static String span(long ticks) {
        long s = Math.max(0L, ticks) / 20L;
        return s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private static void exec(MinecraftServer server, UUID id, String cmd) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd);
        }
    }

    private static void nudge(MinecraftServer server, UUID id, String text) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            ChatCmds.title(p, "Reminder", text, 10, 80, 20);
            ChatCmds.tell(p, ChatFormatting.YELLOW, "Reminder: " + text);
            ChatCmds.sound(p, SoundEvents.PLAYER_LEVELUP, 1.0F);
        }
    }

    static void add() {
        Qol.numText(C, "after", 2, "<seconds> <command>", "Run a command once, later, as you", 1, 86400, (c, n, cmd) -> {
            MinecraftServer server = c.getSource().getServer();
            UUID me = Qol.me(c).getUUID();
            int id = Timers.add(me, "/" + cmd, n * 20L, 0L, () -> exec(server, me, cmd));
            return Qol.ok(c, "Timer #" + id + ": /" + cmd + " in " + span(n * 20L) + ".");
        });

        Qol.numText(C, "every", 2, "<seconds> <command>", "Run a command again and again, as you (/canceltimers stops it)", 1, 86400, (c, n, cmd) -> {
            MinecraftServer server = c.getSource().getServer();
            UUID me = Qol.me(c).getUUID();
            int id = Timers.add(me, "/" + cmd, n * 20L, n * 20L, () -> exec(server, me, cmd));
            return Qol.ok(c, "Timer #" + id + ": /" + cmd + " every " + span(n * 20L) + ".");
        });

        Qol.none(C, "timers", 0, "List your timers", c -> {
            List<Timers.Task> list = Timers.of(Qol.me(c).getUUID());
            if (list.isEmpty()) {
                return Qol.ok(c, "You have no timers.");
            }
            for (Timers.Task t : list) {
                Qol.ok(c, "  #" + t.id + " " + t.label + " in " + span(t.due - Timers.now()) + (t.every > 0 ? ", then every " + span(t.every) : ""));
            }
            return list.size();
        });

        Qol.integer(C, "canceltimer", 0, "<id>", "Cancel one of your timers", 1, Integer.MAX_VALUE, false, (c, n) -> {
            if (Timers.cancel(Qol.me(c).getUUID(), n) == 0) {
                throw new Qol.Bad("You have no timer #" + n + ".");
            }
            return Qol.ok(c, "Timer #" + n + " cancelled.");
        });

        Qol.none(C, "canceltimers", 0, "Cancel all your timers", c -> {
            int n = Timers.cancel(Qol.me(c).getUUID(), -1);
            return Qol.ok(c, "Cancelled " + n + " timer" + (n == 1 ? "" : "s") + ".");
        });

        Qol.integer(C, "countdown", 0, "[seconds]", "Count down on everyone's screen (default 5)", 1, 60, true, (c, n) -> {
            MinecraftServer server = c.getSource().getServer();
            UUID me = Qol.me(c).getUUID();
            int[] left = {n == null ? 5 : n};
            int[] timer = {0};
            timer[0] = Timers.add(me, "countdown", 1L, 20L, () -> {
                boolean go = left[0] <= 0;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (go) {
                        ChatCmds.title(p, "GO!", "", 0, 30, 10);
                        ChatCmds.sound(p, SoundEvents.PLAYER_LEVELUP, 1.0F);
                    } else {
                        ChatCmds.title(p, String.valueOf(left[0]), "", 0, 25, 5);
                        ChatCmds.sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F);
                    }
                }
                if (go) {
                    Timers.cancel(me, timer[0]);
                }
                left[0]--;
            });
            return 1;
        });

        Qol.none(C, "stopwatch", 0, "Start a stopwatch; use it again to see the time and stop it", c -> {
            UUID me = Qol.me(c).getUUID();
            Long start = WATCH.remove(me);
            if (start == null) {
                WATCH.put(me, System.currentTimeMillis());
                return Qol.ok(c, "Stopwatch started.");
            }
            long ms = System.currentTimeMillis() - start;
            return Qol.ok(c, String.format("Stopwatch: %d:%02d.%01d", ms / 60000, ms / 1000 % 60, ms / 100 % 10));
        });

        Qol.numText(C, "remind", 0, "<minutes> <text>", "Remind yourself of something in some minutes", 1, 1440, (c, n, text) -> {
            MinecraftServer server = c.getSource().getServer();
            UUID me = Qol.me(c).getUUID();
            int id = Timers.add(me, "reminder: " + text, n * 1200L, 0L, () -> nudge(server, me, text));
            return Qol.ok(c, "Reminder #" + id + " set for " + n + " minute" + (n == 1 ? "" : "s") + ".");
        });

        Qol.numText(C, "alarm", 0, "<seconds> <text>", "Remind yourself of something in some seconds", 1, 3600, (c, n, text) -> {
            MinecraftServer server = c.getSource().getServer();
            UUID me = Qol.me(c).getUUID();
            int id = Timers.add(me, "alarm: " + text, n * 20L, 0L, () -> nudge(server, me, text));
            return Qol.ok(c, "Alarm #" + id + " set for " + n + " second" + (n == 1 ? "" : "s") + ".");
        });

        Qol.none(C, "pomodoro", 0, "A 25 minute work block, then a 5 minute break, with a reminder at each", c -> {
            MinecraftServer server = c.getSource().getServer();
            UUID me = Qol.me(c).getUUID();
            Timers.add(me, "pomodoro: break", 25 * 1200L, 0L, () -> nudge(server, me, "Time for a 5 minute break."));
            Timers.add(me, "pomodoro: back to work", 30 * 1200L, 0L, () -> nudge(server, me, "Break is over."));
            return Qol.ok(c, "Work for 25 minutes. I will tell you when to rest.");
        });
    }
}
