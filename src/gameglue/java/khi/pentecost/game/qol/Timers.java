package khi.pentecost.game.qol;

import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Timers {
    static final class Task {
        final int id;
        final UUID owner;
        final String label;
        final long every;
        final Runnable run;
        long due;

        Task(int id, UUID owner, String label, long due, long every, Runnable run) {
            this.id = id;
            this.owner = owner;
            this.label = label;
            this.due = due;
            this.every = every;
            this.run = run;
        }
    }

    static final int MAX_PER_PLAYER = 20;
    private static final List<Task> TASKS = new ArrayList<>();
    private static int next = 1;
    private static long now;

    private Timers() {}

    static synchronized void clear() {
        TASKS.clear();
    }

    static synchronized void tick(MinecraftServer server) {
        now = server.getTickCount();
        for (Task t : new ArrayList<>(TASKS)) {
            if (now >= t.due) {
                if (t.every > 0) {
                    t.due = now + t.every;
                } else {
                    TASKS.remove(t);
                }
                try {
                    t.run.run();
                } catch (RuntimeException e) {
                    TASKS.remove(t);
                    khi.pentecost.boot.Log.warn("QOL timer stopped: " + e);
                }
            }
        }
    }

    static synchronized int add(UUID owner, String label, long delayTicks, long everyTicks, Runnable run) {
        int mine = 0;
        for (Task t : TASKS) {
            if (t.owner.equals(owner)) {
                mine++;
            }
        }
        if (mine >= MAX_PER_PLAYER) {
            throw new Bad("You already have " + MAX_PER_PLAYER + " timers. Use /canceltimers first.");
        }
        int id = next++;
        TASKS.add(new Task(id, owner, label, now + Math.max(1, delayTicks), everyTicks, run));
        return id;
    }

    static synchronized List<Task> of(UUID owner) {
        List<Task> out = new ArrayList<>();
        for (Task t : TASKS) {
            if (t.owner.equals(owner)) {
                out.add(t);
            }
        }
        return out;
    }

    static synchronized int cancel(UUID owner, int id) {
        int n = 0;
        for (Task t : new ArrayList<>(TASKS)) {
            if (t.owner.equals(owner) && (id < 0 || t.id == id)) {
                TASKS.remove(t);
                n++;
            }
        }
        return n;
    }

    static synchronized long now() {
        return now;
    }
}

final class Tracker {
    private static final Map<UUID, String> TRACKED = new HashMap<>();

    private Tracker() {}

    static synchronized void clear() {
        TRACKED.clear();
    }

    static synchronized void set(UUID who, String where) {
        TRACKED.put(who, where);
    }

    static synchronized boolean stop(UUID who) {
        return TRACKED.remove(who) != null;
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) {
            return;
        }
        Map<UUID, String> copy;
        synchronized (Tracker.class) {
            if (TRACKED.isEmpty()) {
                return;
            }
            copy = new HashMap<>(TRACKED);
        }
        for (Map.Entry<UUID, String> e : copy.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                stop(e.getKey());
                continue;
            }
            try {
                Where.Spot s = Where.resolve(p.createCommandSourceStack(), e.getValue());
                p.displayClientMessage(Component.literal("Tracking " + e.getValue() + ": " + relative(p, s)), true);
            } catch (Bad | com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
                stop(e.getKey());
                p.displayClientMessage(Component.literal("Stopped tracking " + e.getValue() + ": it can no longer be found."), true);
            }
        }
    }

    private static final String[] SECTORS = {"ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"};

    static String relative(ServerPlayer p, Where.Spot s) {
        if (s.level() != p.level()) {
            return "in " + Where.dimension(s.level());
        }
        Vec3 d = s.pos().subtract(p.position());
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        StringBuilder sb = new StringBuilder();
        sb.append(Math.round(d.length())).append(" blocks");
        if (flat > 1.5) {
            float want = (float) (Mth.atan2(d.z, d.x) * 57.29577951308232) - 90.0F;
            float diff = Mth.wrapDegrees(want - p.getYRot());
            int sector = Math.floorMod(Math.round(diff / 45.0F), 8);
            sb.append(", ").append(SECTORS[sector]);
        }
        if (d.y > 3.0) {
            sb.append(", above");
        } else if (d.y < -3.0) {
            sb.append(", below");
        }
        return sb.toString();
    }
}
