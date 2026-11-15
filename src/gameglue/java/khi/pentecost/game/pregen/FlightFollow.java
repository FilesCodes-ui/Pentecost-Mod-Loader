package khi.pentecost.game.pregen;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Follow mode: while a player flies fast and high, makes the chunks in front of them before they get there.
 * <p>
 * Off unless switched on (setting pregen.follow, or /pentecost pregen follow on), so a shared server never starts
 * background work nobody asked for. Each player is followed by a {@link FlightPath}; the chunks to make come from a
 * {@link FlightCone}, spine first. The chunks are made the same way as in a pregen run: a ticket that gives a full
 * chunk but does not tick it, nothing sent to anyone, and the ticket taken off when the chunk is done so the game saves
 * and unloads it normally. When the player gets there it is a read from disk instead of a generation.
 * <p>
 * It shares the server with the players, so it uses the same rules as a run with players on: a window that shrinks
 * when the server tick gets slow, and nothing new asked for while a tick is very slow. While anyone is being followed,
 * running pregen runs hold back, so the chunks that matter in a few seconds come first.
 * <p>
 * Server thread only, apart from the chunk answers, which are handed back to the server thread.
 * Settings: pregen.follow.speed (blocks per second, default 12), pregen.follow.height (blocks above ground, default 8),
 * pregen.follow.lookahead (seconds of flight to cover, default 30), pregen.follow.window (chunks at once).
 */
final class FlightFollow {
    /** A different ticket from the pregen runs, so taking one off never takes the other off the same chunk. */
    static final TicketType<ChunkPos> TICKET = TicketType.create("pentecost_follow",
            (a, b) -> Long.compare(a.toLong(), b.toLong()));

    private static final int RECOMPUTE_TICKS = 10;
    private static final int SWEEP_TICKS = 100;
    private static final int ADMIT_PER_TICK = 64;
    private static final int MAX_CONE = 4000;
    /** A chunk asked for lately is not asked for again, so it is not read back from disk over and over. */
    private static final int REPEAT_TICKS = 1200;
    private static final float TICK_LIMIT_MS = 40.0F;
    private static final float PANIC_MS = 250.0F;
    private static final long[] NONE = new long[0];

    private static final class LevelState {
        final ServerLevel level;
        final PregenLedger ledger = new PregenLedger();
        final PregenWindow window = new PregenWindow(window());
        final Map<Long, Integer> lastAsk = new HashMap<>();
        int adjustedAt = -1;

        LevelState(ServerLevel level) {
            this.level = level;
        }
    }

    private static final class PlayerState {
        final FlightPath path = new FlightPath();
        ServerLevel level;
        long[] cone = NONE;
        int cursor;
    }

    private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();
    private static final Map<ServerLevel, LevelState> LEVELS = new HashMap<>();
    private static volatile int flying;

    private FlightFollow() {}

    static boolean enabled() {
        return Boolean.parseBoolean(Settings.get("pregen.follow"));
    }

    /** Forget everything, for when a different server starts. */
    static void reset() {
        PLAYERS.clear();
        LEVELS.clear();
        flying = 0;
    }

    private static int window() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(PregenWindow.MIN, Settings.num("pregen.follow.window", Math.max(8, cores * 4)));
    }

    /** Called once per server tick. True while any player is being followed. */
    static boolean tick(MinecraftServer server) {
        if (!enabled()) {
            if (!PLAYERS.isEmpty()) {
                PLAYERS.clear();
            }
            flying = 0;
            return false;
        }
        int now = server.getTickCount();
        double enter = Settings.num("pregen.follow.speed", 12);
        double minHeight = Settings.num("pregen.follow.height", 8);
        double lookahead = Settings.num("pregen.follow.lookahead", 30);
        float average = server.getAverageTickTime();
        int view = server.getPlayerList().getViewDistance();
        Set<UUID> online = new HashSet<>();
        int active = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.add(p.getUUID());
            ServerLevel level = p.serverLevel();
            PlayerState ps = PLAYERS.computeIfAbsent(p.getUUID(), k -> new PlayerState());
            if (ps.level != level) {
                ps.level = level;
                ps.path.reset();
                ps.cone = NONE;
            }
            ps.path.update(p.getX(), p.getZ(), heightAbove(level, p), enter, minHeight);
            if (!ps.path.active()) {
                ps.cone = NONE;
                continue;
            }
            active++;
            LevelState st = LEVELS.computeIfAbsent(level, LevelState::new);
            if (st.adjustedAt != now) {
                st.adjustedAt = now;
                st.window.adjust(true, false, window(), window(), average, TICK_LIMIT_MS);
            }
            if (ps.cone.length == 0 || now % RECOMPUTE_TICKS == 0) {
                ps.cone = FlightCone.ordered(p.getX(), p.getZ(), ps.path.dirX(), ps.path.dirZ(), ps.path.speedBps(),
                        FlightCone.halfAngle(ps.path.turnRadPerSec()), view, lookahead, MAX_CONE);
                ps.cursor = 0;
            }
            if (average < PANIC_MS) {
                admit(server, st, ps, now);
            }
        }
        PLAYERS.keySet().retainAll(online);
        flying = active;
        if (now % SWEEP_TICKS == 0) {
            for (LevelState st : LEVELS.values()) {
                sweep(st, now);
            }
        }
        return active > 0;
    }

    private static void admit(MinecraftServer server, LevelState st, PlayerState ps, int now) {
        int target = st.window.size();
        int admitted = 0;
        while (ps.cursor < ps.cone.length && st.ledger.size() < target && admitted < ADMIT_PER_TICK) {
            long packed = ps.cone[ps.cursor++];
            Integer last = st.lastAsk.get(packed);
            if (last != null && now - last < REPEAT_TICKS) {
                continue;
            }
            ChunkPos pos = new ChunkPos(PregenPlan.x(packed), PregenPlan.z(packed));
            if (!st.level.getWorldBorder().isWithinBounds(pos)) {
                continue;
            }
            st.lastAsk.put(packed, now);
            request(server, st, pos, packed);
            admitted++;
        }
    }

    private static void request(MinecraftServer server, LevelState st, ChunkPos pos, long key) {
        ServerChunkCache cache = st.level.getChunkSource();
        st.ledger.admit(key, System.nanoTime());
        cache.addRegionTicket(TICKET, pos, 0, pos);
        cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true).whenComplete((result, error) -> {
            Runnable finished = () -> {
                if (st.ledger.complete(key)) {
                    cache.removeRegionTicket(TICKET, pos, 0, pos);
                }
            };
            if (server.isSameThread()) {
                finished.run();
            } else {
                server.execute(finished);
            }
        });
    }

    /** Gives up on chunks that never finish and forgets old requests. Server thread only. */
    private static void sweep(LevelState st, int now) {
        long limit = Settings.num("pregen.stuckSeconds", 180) * 1_000_000_000L;
        ServerChunkCache cache = st.level.getChunkSource();
        for (long key : st.ledger.takeOverdue(System.nanoTime(), limit)) {
            ChunkPos pos = new ChunkPos(PregenPlan.x(key), PregenPlan.z(key));
            cache.removeRegionTicket(TICKET, pos, 0, pos);
            Log.error("Pregen follow: gave up on chunk " + pos.x + "," + pos.z + " after "
                    + (limit / 1_000_000_000L) + " s without it finishing");
        }
        st.lastAsk.values().removeIf(t -> now - t > REPEAT_TICKS * 2);
    }

    /** Blocks between the player and the highest block under them. A chunk that is not loaded counts as very high. */
    private static double heightAbove(ServerLevel level, ServerPlayer p) {
        int bx = Mth.floor(p.getX());
        int bz = Mth.floor(p.getZ());
        LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        if (chunk == null) {
            return 1000.0;
        }
        return p.getY() - chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
    }

    static String status() {
        int inFlight = 0;
        for (LevelState st : LEVELS.values()) {
            inFlight += st.ledger.size();
        }
        return "follow " + (enabled() ? "on" : "off") + ", " + flying + " flying, " + inFlight + " chunks in flight";
    }
}
