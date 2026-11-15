package khi.pentecost.game.pregen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Makes the chunks of one dimension ahead of time, as fast as the machine allows without hurting the players on it.
 * Works on any server: a single player world, a world opened to friends (WWLS) and a dedicated server all run this
 * same code, because it only talks to the server's own chunk system.
 * <p>
 * How a chunk is made: the server thread puts a ticket on it (distance 0, so it is a full chunk but is not ticked,
 * which means no mob thinks, no block updates, nothing sent to players) and asks for it. The game's own worker
 * threads make it. When it is done the ticket is taken off, and the game saves and unloads it the normal way.
 * <p>
 * How many are being made at once (the window) is the whole speed control:
 * - nobody on the server: a big window, the machine's cores all busy,
 * - players on the server: a small window that shrinks whenever the server tick gets slow and grows back when it
 *   does not, so friends on a WWLS world are not made to lag,
 * - setting pregen.speed = full keeps the big window even with players on.
 * <p>
 * Threads: ticket work has to happen on the server thread, and it does. A virtual thread per run does the
 * waiting-and-writing part (the progress file and the speed report), which is plain blocking code.
 */
final class ChunkPregen {
    enum State { RUNNING, PAUSED, DONE, STOPPED }

    /** Never times out by itself. Taken off by hand when the chunk is finished. */
    static final TicketType<ChunkPos> TICKET = TicketType.create("pentecost_pregen",
            (a, b) -> Long.compare(a.toLong(), b.toLong()));

    private static final int ADMIT_PER_TICK = 64;
    private static final int MIN_WINDOW = 2;
    private static final float TICK_LIMIT_MS = 40.0F;
    /** A tick this slow means the server is in trouble whatever the window is: ask for nothing new until it recovers. */
    private static final float PANIC_MS = 250.0F;
    private static final long REPORT_EVERY_MS = 5000;
    /** How often, in ticks, to look for chunks that never finish. */
    private static final int SWEEP_EVERY_TICKS = 100;

    private final MinecraftServer server;
    private final ServerLevel level;
    private final ServerChunkCache cache;
    private final Path file;
    final PregenPlan plan;
    final int centerX;
    final int centerZ;
    final int radius;
    final String dimension;

    /** The chunks being made right now: where the progress file may resume from, and which ones to give up on. */
    private final PregenLedger ledger = new PregenLedger();
    private int sinceSweep;
    /** True while a flying player is being followed: their chunks come first, so this run asks for nothing new. */
    private volatile boolean yielding;
    private volatile long next;
    private final PregenWindow window;

    private volatile State state = State.RUNNING;
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile double rate;
    private final long elapsedBeforeMs;
    private long runningSince = System.nanoTime();
    private long activeNanos;
    private int logged;

    ChunkPregen(MinecraftServer server, ServerLevel level, int centerX, int centerZ, int radius, Path file,
                long resumeFrom, long doneBefore, long failedBefore, long elapsedBeforeMs) {
        this.server = server;
        this.level = level;
        this.cache = level.getChunkSource();
        this.file = file;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.dimension = level.dimension().location().toString();
        this.plan = new PregenPlan(centerX, centerZ, radius);
        this.next = Math.max(0, Math.min(resumeFrom, plan.total()));
        this.done.set(doneBefore);
        this.failed.set(failedBefore);
        this.elapsedBeforeMs = elapsedBeforeMs;
        this.window = new PregenWindow(gentleWindow());
        Thread.ofVirtual().name("Pentecost pregen " + dimension).start(this::supervise);
    }

    void setYielding(boolean yielding) {
        this.yielding = yielding;
    }

    State state() {
        return state;
    }

    ServerLevel level() {
        return level;
    }

    // ---- Speed ----

    private static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static int fullWindow() {
        return Math.max(MIN_WINDOW, Settings.num("pregen.window", Math.max(16, Math.min(256, cores() * 8))));
    }

    private static int gentleWindow() {
        return Math.max(MIN_WINDOW, Settings.num("pregen.windowGentle", Math.max(4, cores())));
    }

    private void adjustWindow(boolean players) {
        window.adjust(players, "full".equals(Settings.get("pregen.speed")), fullWindow(), gentleWindow(),
                server.getAverageTickTime(), TICK_LIMIT_MS);
    }

    // ---- Server thread ----

    /** Called once per server tick, on the server thread. */
    void tick() {
        if (state != State.RUNNING) {
            return;
        }
        adjustWindow(server.getPlayerCount() > 0);
        if (++sinceSweep >= SWEEP_EVERY_TICKS) {
            sinceSweep = 0;
            abandonStuck();
        }
        int target = window.size();
        long total = plan.total();
        if (!yielding && server.getAverageTickTime() < PANIC_MS) {
            int admitted = 0;
            while (next < total && ledger.size() < target && admitted < ADMIT_PER_TICK) {
                admit(next);
                next = next + 1;
                admitted++;
            }
        }
        if (next >= total && ledger.size() == 0) {
            finish();
        }
    }

    private void admit(long index) {
        long packed = plan.chunkAt(index);
        ChunkPos pos = new ChunkPos(PregenPlan.x(packed), PregenPlan.z(packed));
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            done.incrementAndGet(); // outside this dimension's world border: nothing to make, and nothing should be
            return;
        }
        ledger.admit(index, System.nanoTime());
        cache.addRegionTicket(TICKET, pos, 0, pos);
        cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true).whenComplete((result, error) -> {
            boolean ok = error == null && result != null && result.left().isPresent();
            Runnable finished = () -> complete(index, pos, ok, error);
            if (server.isSameThread()) {
                finished.run();
            } else {
                server.execute(finished);
            }
        });
    }

    private void complete(long index, ChunkPos pos, boolean ok, Throwable error) {
        if (!ledger.complete(index)) {
            return; // already given up on (see abandonStuck), so this late answer no longer counts
        }
        cache.removeRegionTicket(TICKET, pos, 0, pos);
        if (ok) {
            done.incrementAndGet();
        } else {
            failed.incrementAndGet();
            if (logged++ < 5) {
                Log.error("Pregen " + dimension + ": chunk " + pos.x + "," + pos.z + " failed"
                        + (error != null ? ": " + error : ""));
            }
        }
    }

    /**
     * Other mods' world generation can hang or crash on a rare chunk. Without this, every such chunk would keep its
     * slot in the window for good and the run would slowly choke. After a long wait the chunk is given up on: its
     * ticket comes off, it counts as failed, and the run carries on. Server thread only.
     */
    private void abandonStuck() {
        long limit = Settings.num("pregen.stuckSeconds", 180) * 1_000_000_000L;
        long now = System.nanoTime();
        for (long index : ledger.takeOverdue(now, limit)) {
            long packed = plan.chunkAt(index);
            ChunkPos pos = new ChunkPos(PregenPlan.x(packed), PregenPlan.z(packed));
            cache.removeRegionTicket(TICKET, pos, 0, pos);
            failed.incrementAndGet();
            Log.error("Pregen " + dimension + ": gave up on chunk " + pos.x + "," + pos.z
                    + " after " + (limit / 1_000_000_000L) + " s without it finishing");
        }
    }

    private void finish() {
        stopClock();
        state = State.DONE;
        save();
        Log.info("Pregen " + dimension + " finished: " + done.get() + " chunks, " + failed.get() + " failed, in "
                + (elapsedMs() / 1000) + " s of running time");
    }

    void pause() {
        if (state == State.RUNNING) {
            stopClock();
            state = State.PAUSED;
            save();
        }
    }

    void resume() {
        if (state == State.PAUSED) {
            runningSince = System.nanoTime();
            state = State.RUNNING;
            save();
        }
    }

    /** Stops asking for new chunks. The ones already being made finish and give their tickets back by themselves. */
    void stop() {
        if (state == State.RUNNING || state == State.PAUSED) {
            stopClock();
            state = State.STOPPED;
            save();
        }
    }

    private void stopClock() {
        if (state == State.RUNNING) {
            activeNanos += System.nanoTime() - runningSince;
        }
    }

    private long elapsedMs() {
        long active = activeNanos + (state == State.RUNNING ? System.nanoTime() - runningSince : 0);
        return elapsedBeforeMs + active / 1_000_000L;
    }

    /** Lowest chunk number that may not be on disk yet. Carrying on from here makes at most a few chunks twice. */
    private long safeIndex() {
        return ledger.safeIndex(next);
    }

    // ---- Virtual thread: speed report and progress file ----

    private void supervise() {
        long lastDone = done.get();
        long lastNanos = System.nanoTime();
        while (state == State.RUNNING || state == State.PAUSED) {
            try {
                Thread.sleep(REPORT_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            long d = done.get();
            double seconds = Math.max(0.001, (now - lastNanos) / 1e9);
            double r = (d - lastDone) / seconds;
            rate = rate == 0 ? r : rate * 0.6 + r * 0.4;
            lastDone = d;
            lastNanos = now;
            save();
            if (state == State.RUNNING) {
                Log.info("Pregen " + dimension + ": " + status());
            }
        }
    }

    String status() {
        long total = plan.total();
        long d = done.get();
        String pct = total == 0 ? "0" : String.format("%.1f", 100.0 * d / total);
        long left = (long) Math.max(0, total - d);
        String eta = rate > 1 ? ", about " + formatSeconds((long) (left / rate)) + " left" : "";
        return state.name().toLowerCase() + ", " + d + "/" + total + " chunks (" + pct + "%)"
                + (failed.get() > 0 ? ", " + failed.get() + " failed" : "")
                + ", " + String.format("%.0f", rate) + " chunks/s" + eta
                + ", window " + window.size() + ", " + ledger.size() + " in flight";
    }

    private static String formatSeconds(long s) {
        if (s < 90) {
            return s + " s";
        }
        if (s < 5400) {
            return (s / 60) + " min";
        }
        return String.format("%.1f h", s / 3600.0);
    }

    // ---- Progress file ----

    private synchronized void save() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", 1);
            m.put("dimension", dimension);
            m.put("centerChunkX", centerX);
            m.put("centerChunkZ", centerZ);
            m.put("radiusChunks", radius);
            m.put("next", safeIndex());
            m.put("done", done.get());
            m.put("failed", failed.get());
            m.put("elapsedMs", elapsedMs());
            m.put("state", state == State.RUNNING ? "running" : state.name().toLowerCase());
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new Gson().toJson(m));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Log.error("Pregen " + dimension + ": could not write the progress file: " + e);
        }
    }

    /** What the progress file says, or null when there is none or it cannot be read. */
    record Saved(String dimension, int centerX, int centerZ, int radius, long next, long done, long failed,
                 long elapsedMs, String state) {}

    static Saved load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return new Saved(o.get("dimension").getAsString(), o.get("centerChunkX").getAsInt(),
                    o.get("centerChunkZ").getAsInt(), o.get("radiusChunks").getAsInt(), o.get("next").getAsLong(),
                    o.get("done").getAsLong(), o.get("failed").getAsLong(), o.get("elapsedMs").getAsLong(),
                    o.get("state").getAsString());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
