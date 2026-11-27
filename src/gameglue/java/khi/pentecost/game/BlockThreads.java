package khi.pentecost.game;

import khi.pentecost.control.Settings;
import khi.pentecost.messenger.BlockHooks;
import khi.pentecost.messenger.Islands;
import khi.pentecost.messenger.Messenger;
import khi.pentecost.messenger.MobHooks;
import khi.pentecost.messenger.ModThreads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class BlockThreads implements BlockHooks.Impl {
    public static final BlockThreads INSTANCE = new BlockThreads();
    public static final LongAdder MOVED = new LongAdder();
    public static final Set<String> SENT_HOME = ConcurrentHashMap.newKeySet();
    public static volatile int lastMachines;
    public static volatile double lastMs;

    private record Pending(TickingBlockEntity ticker, BlockPos pos, String mod) {}

    private final List<Pending> pending = new ArrayList<>();

    private BlockThreads() {}

    public static void install() {
        MobThreads.install();
        BlockHooks.impl = INSTANCE;
    }

    private static String modOf(TickingBlockEntity t) {
        String type = t.getType();
        int c = type == null ? -1 : type.indexOf(':');
        return c > 0 ? type.substring(0, c) : "minecraft";
    }

    @Override
    public boolean take(Object level, Object ticker) {
        if (!(level instanceof ServerLevel) || !(ticker instanceof TickingBlockEntity t)) return false;
        ModThreads mt = ModThreads.shared;
        if (mt == null || !Settings.on(Settings.BLOCK_THREADS) || !Messenger.get().on()) return false;
        String mod = modOf(t);
        if ("minecraft".equals(mod) || SENT_HOME.contains(mod) || mt.threadOf(mod) == ModThreads.HOME) return false;
        if (!(((ServerLevel) level).getProfiler() instanceof net.minecraft.util.profiling.InactiveProfiler)) return false;
        BlockPos pos = t.getPos();
        if (pos == null) return false;
        synchronized (pending) {
            pending.add(new Pending(t, pos.immutable(), mod));
        }
        return true;
    }

    @Override
    public void tickNow(Object ticker) {
        ((TickingBlockEntity) ticker).tick();
    }

    @Override
    public void flush(Object level) {
        List<Pending> all;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            all = new ArrayList<>(pending);
            pending.clear();
        }
        all.removeIf(java.util.Objects::isNull);
        if (all.isEmpty()) return;
        ModThreads mt = ModThreads.shared;
        if (mt == null || !(level instanceof ServerLevel sl)) {
            all.forEach(p -> p.ticker().tick());
            return;
        }
        int[] cx = new int[all.size()];
        int[] cz = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            cx[i] = Islands.cell(all.get(i).pos().getX());
            cz[i] = Islands.cell(all.get(i).pos().getZ());
        }
        int[] family = new int[all.size()];
        Map<Integer, Integer> famIndex = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            int planThread = mt.threadOf(all.get(i).mod());
            family[i] = famIndex.computeIfAbsent(planThread, k -> famIndex.size());
        }
        int[] thread = Islands.assignByFamily(cx, cz, family, famIndex.size(), mt.threadCount());
        Map<Integer, List<Pending>> work = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            work.computeIfAbsent(thread[i], k -> new ArrayList<>()).add(all.get(i));
        }
        SharedSets.makeSafe(sl);
        mt.setHome(Thread.currentThread());
        Map<Integer, Runnable> batches = new HashMap<>();
        for (Map.Entry<Integer, List<Pending>> e : work.entrySet()) {
            List<Pending> list = e.getValue();
            batches.put(e.getKey(), () -> {
                for (Pending p : list) {
                    if (SENT_HOME.contains(p.mod())) {
                        MobHooks.defer(() -> p.ticker().tick());
                        continue;
                    }
                    try {
                        p.ticker().tick();
                    } catch (Throwable x) {
                        fail(p, x);
                    }
                }
            });
        }
        ServerChunkCache chunks = sl.getChunkSource();
        long started = System.nanoTime();
        MobHooks.phase = true;
        try {
            mt.runAll(batches, () -> {
                if (System.nanoTime() - MobHooks.chunkWantedAt < 50_000_000L) {
                    chunks.pollTask();
                }
            }, 250);
        } finally {
            MobHooks.phase = false;
        }
        MOVED.add(all.size());
        for (int t : work.keySet()) {
            ModThreads.Worker w = mt.workerAt(t);
            if (w == null) continue;
            List<Runnable> changes = new ArrayList<>(w.deferred);
            w.deferred.clear();
            for (Runnable r : changes) {
                try {
                    r.run();
                } catch (Throwable x) {
                    Messenger.get().note("A queued world change from a machine on mod thread " + (t + 1) + " failed: " + x);
                }
            }
            MobThreads.CHANGES.add(changes.size());
        }
        lastMachines = all.size();
        lastMs = (System.nanoTime() - started) / 1e6;
    }

    private static void fail(Pending p, Throwable x) {
        if (SENT_HOME.add(p.mod())) {
            StringWriter sw = new StringWriter();
            x.printStackTrace(new PrintWriter(sw));
            String[] lines = sw.toString().split("\\R");
            StringBuilder b = new StringBuilder("Machine " + p.ticker().getType() + " at " + p.pos().toShortString() + " from "
                    + p.mod() + " failed on a mod thread. Its mod's machines tick on the server thread until restart.\n");
            for (int i = 0; i < Math.min(lines.length, 25); i++) b.append("    ").append(lines[i]).append('\n');
            Messenger.get().note(b.toString());
        }
    }
}
