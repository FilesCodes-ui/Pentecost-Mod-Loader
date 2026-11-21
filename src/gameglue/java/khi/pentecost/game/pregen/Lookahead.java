package khi.pentecost.game.pregen;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.core.LookaheadPlan;
import khi.pentecost.messenger.Lane;
import khi.pentecost.messenger.Messenger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Lookahead {
    static final TicketType<ChunkPos> TICKET = TicketType.create("pentecost_lookahead",
            (a, b) -> Long.compare(a.toLong(), b.toLong()));

    private static final int HISTORY = 10;
    private static final int REPLAN_TICKS = 20;
    private static final int REPLAN_FAST_TICKS = 4;
    private static final double REPLAN_TURN = 0.12;
    private static final double TELEPORT_BLOCKS = 96;
    private static final int MAX_PLAN = 6000;
    private static final int ADMIT_PER_TICK = 64;
    private static final int READY_MEMORY = 400_000;
    private static final float SLOW_TICK_MS = 45f;
    private static final int GAME_QUEUE_LIMIT = 400;
    private static final int SAVE_TICKS = 1200;
    private static final double MAX_BPS = 150;
    private static final ChunkStatus TARGET = ChunkStatus.FEATURES;
    private static final int TARGET_LEVEL = ChunkLevel.byStatus(TARGET);
    private static java.lang.reflect.Field distanceField;

    private static final class PlayerState {
        ServerLevel level;
        final double[] hx = new double[HISTORY + 1];
        final double[] hz = new double[HISTORY + 1];
        final long[] ht = new long[HISTORY + 1];
        int samples;
        int at;
        double vx;
        double vz;
        double speed;
        double turn;
        double headingAtPlan = Double.NaN;
        int plannedAt = -1000;
        boolean active;
        volatile boolean planning;
        volatile LookaheadPlan.Result incoming;
        LookaheadPlan.Result plan = LookaheadPlan.Result.EMPTY;
        int cursor;
        double readySeconds;
        boolean viewComplete;
        boolean turnedSincePlan;

        void reset() {
            samples = 0;
            at = 0;
            speed = 0;
            active = false;
            plan = LookaheadPlan.Result.EMPTY;
            incoming = null;
            cursor = 0;
            headingAtPlan = Double.NaN;
            readySeconds = 0;
        }
    }

    private static final class LevelState {
        final ServerLevel level;
        final Path file;
        final Map<Long, Long> inFlight = new HashMap<>();
        final LinkedHashSet<Long> ready = new LinkedHashSet<>();
        boolean dirty;

        LevelState(ServerLevel level) {
            this.level = level;
            String dim = level.dimension().location().toString().replace(':', '_').replace('/', '_');
            this.file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("pentecost").resolve("lookahead")
                    .resolve(dim + ".bin");
            load();
        }

        void markReady(long key) {
            if (!ready.remove(key)) {
                dirty = true;
            }
            ready.add(key);
            if (ready.size() > READY_MEMORY) {
                Iterator<Long> it = ready.iterator();
                it.next();
                it.remove();
            }
        }

        private void load() {
            if (!Files.isRegularFile(file)) {
                return;
            }
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
                int version = in.readInt();
                int n = in.readInt();
                if (version != 1 || n < 0 || n > READY_MEMORY) {
                    return;
                }
                for (int i = 0; i < n; i++) {
                    ready.add(in.readLong());
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("Lookahead could not read " + file + ", it starts fresh: " + e);
                ready.clear();
            }
        }

        void save(boolean now) {
            if (!dirty) {
                return;
            }
            dirty = false;
            long[] keys = new long[ready.size()];
            int i = 0;
            for (long k : ready) {
                keys[i++] = k;
            }
            Path target = file;
            Runnable job = () -> {
                try {
                    Files.createDirectories(target.getParent());
                    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                    try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                        out.writeInt(1);
                        out.writeInt(keys.length);
                        for (long k : keys) {
                            out.writeLong(k);
                        }
                    }
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | RuntimeException e) {
                    Log.warn("Lookahead could not save " + target + ": " + e);
                }
            };
            Messenger m = Messenger.get();
            if (!now && m.on()) {
                m.send("pentecost:lookahead", "save", Lane.IO, job);
            } else {
                job.run();
            }
        }
    }

    private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();
    private static final Map<ServerLevel, LevelState> LEVELS = new HashMap<>();

    private static volatile boolean lastEnabled;
    private static volatile int following;
    private static volatile int inFlightTotal;
    private static volatile double madePerSecond;
    private static volatile double bestReadySeconds;
    private static volatile double fastestBps;
    private static volatile long cancelledTotal;
    private static int madeThisSecond;
    private static long secondStart;
    private static double rate = 16;

    private Lookahead() {}

    static boolean enabled() {
        return lastEnabled;
    }

    static boolean fastTravel() {
        return lastEnabled && following > 0;
    }

    private static boolean enabled(MinecraftServer server) {
        return Settings.on("pregen.follow") || (Settings.on(Settings.FOLLOW_SOLO) && !server.isDedicatedServer());
    }

    static double[] numbers() {
        return new double[]{lastEnabled ? 1 : 0, following, inFlightTotal, madePerSecond, bestReadySeconds, fastestBps,
                cancelledTotal};
    }

    static String status() {
        return "lookahead " + (lastEnabled ? "on" : "off") + ", " + following + " moving fast, " + inFlightTotal
                + " chunks being made, " + String.format("%.0f per second, %.0f s of path ready", madePerSecond, bestReadySeconds);
    }

    static void reset() {
        for (LevelState st : LEVELS.values()) {
            st.save(true);
        }
        PLAYERS.clear();
        LEVELS.clear();
        following = 0;
        inFlightTotal = 0;
    }

    static boolean tick(MinecraftServer server) {
        boolean on = enabled(server);
        if (on != lastEnabled && !on) {
            cancelAll();
        }
        lastEnabled = on;
        long nowNs = System.nanoTime();
        if (nowNs - secondStart >= 1_000_000_000L) {
            double seconds = secondStart == 0 ? 1 : (nowNs - secondStart) / 1e9;
            madePerSecond = madeThisSecond / seconds;
            if (madeThisSecond > 0) {
                rate = rate * 0.6 + madePerSecond * 0.4;
            }
            madeThisSecond = 0;
            secondStart = nowNs;
        }
        if (!on) {
            PLAYERS.clear();
            following = 0;
            return false;
        }
        int tick = server.getTickCount();
        double threshold = Math.max(3, Settings.num("lookahead.speed", 9));
        double maxSeconds = Math.max(5, Settings.num("lookahead.seconds", 30));
        int view = server.getPlayerList().getViewDistance();
        float avgTick = server.getAverageTickTime();
        Set<UUID> online = new HashSet<>();
        int active = 0;
        double fastest = 0;
        double best = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.add(p.getUUID());
            PlayerState ps = PLAYERS.computeIfAbsent(p.getUUID(), k -> new PlayerState());
            ServerLevel level = p.serverLevel();
            if (ps.level != level) {
                ps.level = level;
                ps.reset();
            }
            if (!track(ps, p.getX(), p.getZ(), System.nanoTime())) {
                continue;
            }
            LevelState st = LEVELS.computeIfAbsent(level, LevelState::new);
            if (tick % 5 == 0) {
                ps.viewComplete = viewComplete(level, p, view);
            }
            ps.active = ps.speed >= threshold || (ps.active && ps.speed >= threshold * 0.6);
            if (!ps.active) {
                ps.plan = LookaheadPlan.Result.EMPTY;
                ps.readySeconds = 0;
                continue;
            }
            active++;
            fastest = Math.max(fastest, ps.speed);
            LookaheadPlan.Result fresh = ps.incoming;
            if (fresh != null) {
                ps.incoming = null;
                ps.plan = fresh;
                ps.cursor = 0;
                if (ps.turnedSincePlan) {
                    takeBackOffPath(st);
                }
            }
            double heading = Math.atan2(ps.vz, ps.vx);
            boolean turned = !Double.isNaN(ps.headingAtPlan) && Math.abs(wrap(heading - ps.headingAtPlan)) > REPLAN_TURN;
            int since = tick - ps.plannedAt;
            if (!ps.planning && (since >= REPLAN_TICKS || (turned && since >= REPLAN_FAST_TICKS) || ps.plan.size() == 0)) {
                ps.plannedAt = tick;
                ps.turnedSincePlan = turned;
                ps.headingAtPlan = heading;
                plan(ps, p.getX(), p.getZ(), view, LookaheadPlan.horizon(ps.speed, maxSeconds));
            }
            if (tick % 10 == 0) {
                ps.readySeconds = readySeconds(st, ps);
            }
            best = Math.max(best, ps.readySeconds);
        }
        PLAYERS.keySet().retainAll(online);
        following = active;
        fastestBps = fastest;
        bestReadySeconds = best;

        int inFlight = 0;
        for (LevelState st : LEVELS.values()) {
            inFlight += st.inFlight.size();
        }
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        boolean admitted = false;
        if (active > 0) {
            int window = (int) Math.max(8, Math.min(cores * 4, rate * 2));
        window = (int) Math.max(8, window * (0.4 + 0.6 * khi.pentecost.core.LodBridge.budget()));
            if (avgTick > SLOW_TICK_MS) {
                window = 6;
            }
            admitted = admit(server, window - inFlight);
        }
        if (tick % SAVE_TICKS == 0) {
            for (LevelState st : LEVELS.values()) {
                st.save(false);
            }
        }
        int total = 0;
        for (LevelState st : LEVELS.values()) {
            total += st.inFlight.size();
        }
        inFlightTotal = total;
        return active > 0 || admitted || total > 0;
    }

    private static boolean admit(MinecraftServer server, int budget) {
        budget = Math.min(ADMIT_PER_TICK, budget);
        boolean any = false;
        boolean progress = true;
        while (budget > 0 && progress) {
            progress = false;
            for (PlayerState ps : PLAYERS.values()) {
                if (budget <= 0 || !ps.active) {
                    continue;
                }
                LevelState st = LEVELS.get(ps.level);
                if (st == null || ps.level.getChunkSource().getPendingTasksCount() > GAME_QUEUE_LIMIT) {
                    continue;
                }
                if (!ps.viewComplete && st.inFlight.size() >= 4) {
                    continue;
                }
                if (admitFrom(server, st, ps)) {
                    budget--;
                    progress = true;
                    any = true;
                }
            }
        }
        return any;
    }

    private static boolean track(PlayerState ps, double x, double z, long nanos) {
        if (ps.samples > 0) {
            int last = Math.floorMod(ps.at - 1, HISTORY + 1);
            if (Math.hypot(x - ps.hx[last], z - ps.hz[last]) > TELEPORT_BLOCKS) {
                ps.reset();
            }
        }
        ps.hx[ps.at] = x;
        ps.hz[ps.at] = z;
        ps.ht[ps.at] = nanos;
        ps.at = (ps.at + 1) % (HISTORY + 1);
        ps.samples++;
        if (ps.samples <= HISTORY) {
            return false;
        }
        int oldest = ps.at;
        int middle = (ps.at + HISTORY / 2) % (HISTORY + 1);
        double seconds = (nanos - ps.ht[oldest]) / 1e9;
        if (seconds < 0.25) {
            return true;
        }
        double vx = (x - ps.hx[oldest]) / seconds;
        double vz = (z - ps.hz[oldest]) / seconds;
        double h1 = Math.atan2(ps.hz[middle] - ps.hz[oldest], ps.hx[middle] - ps.hx[oldest]);
        double h2 = Math.atan2(z - ps.hz[middle], x - ps.hx[middle]);
        double turn = wrap(h2 - h1) / (seconds / 2);
        ps.vx = ps.vx * 0.5 + vx * 0.5;
        ps.vz = ps.vz * 0.5 + vz * 0.5;
        ps.speed = Math.hypot(ps.vx, ps.vz);
        if (ps.speed > MAX_BPS) {
            ps.vx *= MAX_BPS / ps.speed;
            ps.vz *= MAX_BPS / ps.speed;
            ps.speed = MAX_BPS;
        }
        ps.turn = ps.speed < 1 ? 0 : ps.turn * 0.5 + Math.max(-1.5, Math.min(1.5, turn)) * 0.5;
        return true;
    }

    private static void plan(PlayerState ps, double x, double z, int view, double horizon) {
        ps.planning = true;
        double vx = ps.vx;
        double vz = ps.vz;
        double turn = ps.turn;
        Runnable job = () -> {
            try {
                ps.incoming = LookaheadPlan.plan(x, z, vx, vz, turn, view, horizon, MAX_PLAN);
            } catch (RuntimeException e) {
                Log.warn("Lookahead could not plan a path: " + e);
            } finally {
                ps.planning = false;
            }
        };
        Messenger m = Messenger.get();
        if (m.on()) {
            m.send("pentecost:lookahead", "plan", Lane.WORKER, job);
        } else {
            job.run();
        }
    }

    private static boolean admitFrom(MinecraftServer server, LevelState st, PlayerState ps) {
        ServerChunkCache cache = st.level.getChunkSource();
        LookaheadPlan.Result plan = ps.plan;
        while (true) {
            if (ps.cursor >= plan.size()) {
                return false;
            }
            long key = plan.keys()[ps.cursor++];
            if (st.ready.contains(key) || st.inFlight.containsKey(key)) {
                continue;
            }
            int cx = LookaheadPlan.x(key);
            int cz = LookaheadPlan.z(key);
            ChunkPos pos = new ChunkPos(cx, cz);
            if (!st.level.getWorldBorder().isWithinBounds(pos)) {
                continue;
            }
            if (cache.getChunkNow(cx, cz) != null) {
                st.markReady(key);
                continue;
            }
            request(server, st, pos, key);
            return st.inFlight.containsKey(key);
        }
    }

    private static void request(MinecraftServer server, LevelState st, ChunkPos pos, long key) {
        ServerChunkCache cache = st.level.getChunkSource();
        DistanceManager dm = distance(cache);
        if (dm == null) {
            return;
        }
        st.inFlight.put(key, System.nanoTime());
        dm.addTicket(TICKET, pos, TARGET_LEVEL, pos);
        var future = futureNoWait(cache, pos);
        if (future == null) {
            st.inFlight.remove(key);
            dm.removeTicket(TICKET, pos, TARGET_LEVEL, pos);
            return;
        }
        if (future.isDone()) {
            st.inFlight.remove(key);
            dm.removeTicket(TICKET, pos, TARGET_LEVEL, pos);
            st.markReady(key);
            return;
        }
        future.whenComplete((result, error) -> {
            Runnable finished = () -> {
                if (st.inFlight.remove(key) == null) {
                    return;
                }
                dm.removeTicket(TICKET, pos, TARGET_LEVEL, pos);
                if (error == null && result != null && result.left().isPresent()) {
                    st.markReady(key);
                    madeThisSecond++;
                }
            };
            if (server.isSameThread()) {
                finished.run();
            } else {
                server.execute(finished);
            }
        });
    }

    private static void takeBackOffPath(LevelState st) {
        if (st.inFlight.isEmpty()) {
            return;
        }
        Set<Long> wanted = new HashSet<>();
        for (PlayerState ps : PLAYERS.values()) {
            if (ps.level == st.level) {
                for (long k : ps.plan.keys()) {
                    wanted.add(k);
                }
            }
        }
        long now = System.nanoTime();
        DistanceManager dm = distance(st.level.getChunkSource());
        Iterator<Map.Entry<Long, Long>> it = st.inFlight.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (!wanted.contains(e.getKey()) && now - e.getValue() > 500_000_000L) {
                ChunkPos pos = new ChunkPos(LookaheadPlan.x(e.getKey()), LookaheadPlan.z(e.getKey()));
                if (dm != null) {
                    dm.removeTicket(TICKET, pos, TARGET_LEVEL, pos);
                }
                it.remove();
                cancelledTotal++;
            }
        }
    }

    private static boolean viewComplete(ServerLevel level, ServerPlayer p, int view) {
        ServerChunkCache cache = level.getChunkSource();
        int cx = p.chunkPosition().x;
        int cz = p.chunkPosition().z;
        int edgeAllowance = 2 * (2 * view + 1);
        int edgeMissing = 0;
        for (int r = 0; r <= view; r++) {
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != r || cache.getChunkNow(x, z) != null) {
                        continue;
                    }
                    if (r < view - 1) {
                        return false;
                    }
                    if (++edgeMissing > edgeAllowance) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static double readySeconds(LevelState st, PlayerState ps) {
        LookaheadPlan.Result plan = ps.plan;
        ServerChunkCache cache = st.level.getChunkSource();
        for (int i = 0; i < plan.size(); i++) {
            long key = plan.keys()[i];
            if (st.ready.contains(key)) {
                continue;
            }
            if (cache.getChunkNow(LookaheadPlan.x(key), LookaheadPlan.z(key)) != null) {
                st.markReady(key);
                continue;
            }
            return plan.seconds()[i];
        }
        return plan.size() == 0 ? 0 : plan.seconds()[plan.size() - 1];
    }

    private static void cancelAll() {
        for (LevelState st : LEVELS.values()) {
            DistanceManager dm = distance(st.level.getChunkSource());
            for (long key : st.inFlight.keySet()) {
                ChunkPos pos = new ChunkPos(LookaheadPlan.x(key), LookaheadPlan.z(key));
                if (dm != null) {
                    dm.removeTicket(TICKET, pos, TARGET_LEVEL, pos);
                }
            }
            st.inFlight.clear();
            st.save(false);
        }
    }

    private static java.lang.reflect.Method futureMethod;

    @SuppressWarnings("unchecked")
    private static java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.ChunkAccess,
            net.minecraft.server.level.ChunkHolder.ChunkLoadingFailure>> futureNoWait(ServerChunkCache cache, ChunkPos pos) {
        try {
            java.lang.reflect.Method m = futureMethod;
            if (m == null) {
                m = ServerChunkCache.class.getDeclaredMethod("getChunkFutureMainThread", int.class, int.class,
                        ChunkStatus.class, boolean.class);
                m.setAccessible(true);
                futureMethod = m;
            }
            return (java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.ChunkAccess,
                    net.minecraft.server.level.ChunkHolder.ChunkLoadingFailure>>) m.invoke(cache, pos.x, pos.z, TARGET, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Lookahead cannot ask for chunks without waiting: " + e);
            return null;
        }
    }

    private static DistanceManager distance(ServerChunkCache cache) {
        try {
            java.lang.reflect.Field f = distanceField;
            if (f == null) {
                for (java.lang.reflect.Field g : ServerChunkCache.class.getDeclaredFields()) {
                    if (g.getType() == DistanceManager.class) {
                        g.setAccessible(true);
                        f = g;
                    }
                }
                distanceField = f;
            }
            return f == null ? null : (DistanceManager) f.get(cache);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Lookahead cannot reach the chunk tickets: " + e);
            return null;
        }
    }

    private static double wrap(double a) {
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }
}
