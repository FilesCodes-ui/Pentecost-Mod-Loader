package khi.pentecost.game;

import khi.pentecost.control.Settings;
import khi.pentecost.messenger.Messenger;
import khi.pentecost.messenger.MobHooks;
import khi.pentecost.messenger.ModThreads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public final class MobThreads implements MobHooks.Impl {
    public static final MobThreads INSTANCE = new MobThreads();
    public static final LongAdder MOVED = new LongAdder();
    public static final LongAdder CHANGES = new LongAdder();
    public static final java.util.concurrent.atomic.AtomicLong CHUNK_MISSES = new java.util.concurrent.atomic.AtomicLong();
    public static final LongAdder CHUNK_HITS = new LongAdder();
    public static final LongAdder CHUNK_ERRORS = new LongAdder();
    private static volatile String chunkError;
    private static long diagAt;
    private static long diagMoved;
    private static long diagStuck;
    private static long diagPathing;
    private static int badWindows;
    private static final Map<String, long[]> TYPES = new HashMap<>();
    private static final List<String> SAMPLES = new ArrayList<>();
    public static volatile boolean selfOff;
    public static final Set<String> SENT_HOME = ConcurrentHashMap.newKeySet();
    public static volatile int threadsUsed;
    public static volatile int lastMobs;
    public static volatile int lastChanges;
    public static volatile double lastMs;

    private record Pending(ServerLevel level, Consumer<Object> tick, Mob mob, String mod) {}

    private final List<Pending> pending = new ArrayList<>();
    private static final ThreadLocal<Set<Entity>> MINE = ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    private static volatile Method visible;

    private MobThreads() {}

    public static void install() {
        MobHooks.impl = INSTANCE;
    }

    private static String modOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace();
    }

    @Override
    public boolean take(Object level, Consumer<Object> tick, Object entity) {
        ModThreads mt = ModThreads.shared;
        if (selfOff || mt == null || !(level instanceof ServerLevel sl) || !(entity instanceof Mob mob)
                || !Settings.on(Settings.MOB_THREADS) || !Messenger.get().on()) {
            return false;
        }
        if (mob.isPassenger() || mob.isVehicle()) {
            return false;
        }
        String mod = modOf(mob);
        if (SENT_HOME.contains(mod)) {
            return false;
        }
        if ("minecraft".equals(mod) && (!Settings.on(Settings.VANILLA_MOB_THREADS) || sharesWorldData(mob))) {
            return false;
        }
        pending.add(new Pending(sl, tick, mob, mod));
        return true;
    }

    private static boolean sharesWorldData(Mob mob) {
        return mob instanceof net.minecraft.world.entity.npc.AbstractVillager
                || mob instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin
                || mob instanceof net.minecraft.world.entity.monster.warden.Warden
                || mob instanceof net.minecraft.world.entity.animal.allay.Allay
                || mob instanceof net.minecraft.world.entity.raid.Raider r && r.hasActiveRaid()
                || mob instanceof net.minecraft.world.entity.animal.IronGolem;
    }

    @Override
    public void flush(Object level) {
        if (pending.isEmpty()) {
            return;
        }
        List<Pending> all = new ArrayList<>(pending);
        pending.clear();
        ModThreads mt = ModThreads.shared;
        if (mt == null) {
            all.forEach(p -> guarded(p.level(), p.tick(), p.mob()));
            return;
        }
        int[] cx = new int[all.size()];
        int[] cz = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Mob m = all.get(i).mob();
            cx[i] = khi.pentecost.messenger.Islands.cell(m.getX());
            cz[i] = khi.pentecost.messenger.Islands.cell(m.getZ());
        }
        int[] thread = khi.pentecost.messenger.Islands.assign(cx, cz, mt.threadCount());
        Map<Integer, List<Pending>> work = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            work.computeIfAbsent(thread[i], k -> new ArrayList<>()).add(all.get(i));
        }
        if (level instanceof net.minecraft.world.level.Level lv) {
            SharedSets.makeSafe(lv);
        }
        mt.setHome(Thread.currentThread());
        Map<Integer, Runnable> batches = new HashMap<>();
        int count = 0;
        for (Map.Entry<Integer, List<Pending>> e : work.entrySet()) {
            List<Pending> list = e.getValue();
            count += list.size();
            batches.put(e.getKey(), () -> {
                Set<Entity> mine = MINE.get();
                mine.clear();
                for (Pending p : list) {
                    mine.add(p.mob());
                }
                for (Pending p : list) {
                    if (p.mob().isRemoved() || SENT_HOME.contains(p.mod())) {
                        continue;
                    }
                    try {
                        guarded(p.level(), p.tick(), p.mob());
                    } catch (Throwable x) {
                        fail(p, x);
                    }
                }
                mine.clear();
            });
        }
        threadsUsed = batches.size();
        double[] before = new double[all.size() * 3];
        for (int i = 0; i < all.size(); i++) {
            Mob m = all.get(i).mob();
            before[i * 3] = m.getX();
            before[i * 3 + 1] = m.getY();
            before[i * 3 + 2] = m.getZ();
        }
        ServerChunkCache chunks = level instanceof ServerLevel sl ? sl.getChunkSource() : null;
        long started = System.nanoTime();
        int changed = 0;
        MobHooks.phase = true;
        try {
            mt.runAll(batches, chunks == null ? null : chunks::pollTask, 250);
        } finally {
            MobHooks.phase = false;
        }
        MOVED.add(count);
        for (int t : work.keySet()) {
            ModThreads.Worker w = mt.workerAt(t);
            if (w == null) {
                continue;
            }
            List<Runnable> changes = new ArrayList<>(w.deferred);
            w.deferred.clear();
            for (Runnable r : changes) {
                try {
                    r.run();
                } catch (Throwable x) {
                    Messenger.get().note("A queued world change from mod thread " + (t + 1) + " failed: " + x);
                }
            }
            CHANGES.add(changes.size());
            changed += changes.size();
        }
        for (int i = 0; i < all.size(); i++) {
            Mob m = all.get(i).mob();
            boolean moved = m.getX() != before[i * 3] || m.getY() != before[i * 3 + 1] || m.getZ() != before[i * 3 + 2];
            boolean pathing = m.getNavigation().isInProgress();
            if (moved) {
                diagMoved++;
            }
            if (pathing) {
                diagPathing++;
                if (!moved) {
                    diagStuck++;
                }
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
            long[] t = TYPES.computeIfAbsent(type, k -> new long[5]);
            t[0]++;
            if (moved) {
                t[1]++;
            }
            if (pathing) {
                t[2]++;
            }
            if (m.getTarget() != null) {
                t[3]++;
                if (!moved && !pathing) {
                    t[4]++;
                }
            }
            if (!moved && !pathing && SAMPLES.size() < 6 && m.getTarget() != null) {
                StringBuilder sb = new StringBuilder(type).append(" id ").append(m.getId())
                        .append(" onGround=").append(m.onGround()).append(" inWater=").append(m.isInWater())
                        .append(" noAi=").append(m.isNoAi()).append(" noActionTime=").append(m.getNoActionTime())
                        .append(" delta=").append(m.getDeltaMovement())
                        .append(" nav=").append(m.getNavigation().getClass().getSimpleName())
                        .append(" path=").append(m.getNavigation().getPath())
                        .append(" target=").append(m.getTarget() == null ? "none" : m.getTarget().getType().toShortString())
                        .append(" goals=[");
                try {
                    java.lang.reflect.Field gf = Mob.class.getDeclaredField("goalSelector"); gf.setAccessible(true); ((net.minecraft.world.entity.ai.goal.GoalSelector) gf.get(m)).getRunningGoals().forEach(g -> sb.append(g.getGoal().getClass().getSimpleName()).append(' '));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
                sb.append("] tickCount=").append(m.tickCount);
                SAMPLES.add(sb.toString());
            }
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - diagAt > 10_000) {
            if (diagAt != 0) {
                khi.pentecost.boot.Log.info("Mob threads check: " + diagMoved + " mob ticks moved, " + diagPathing
                        + " had a path, " + diagStuck + " had a path but did not move. Chunk reads: "
                        + CHUNK_HITS.sum() + " fast, " + CHUNK_MISSES.get() + " missed, " + CHUNK_ERRORS.sum()
                        + " failed" + (chunkError == null ? "" : " (" + chunkError + ")"));
            }
            if (diagAt != 0 && TYPES.values().stream().anyMatch(v -> v[4] > 20)) {
                StringBuilder tb = new StringBuilder("Mob threads by type (ticks, moved, had a path, had a target, target but no path and still):");
                TYPES.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(8)
                        .forEach(e -> tb.append("\n    ").append(e.getKey()).append(": ").append(e.getValue()[0]).append(", ")
                                .append(e.getValue()[1]).append(", ").append(e.getValue()[2]).append(", ")
                                .append(e.getValue()[3]).append(", ").append(e.getValue()[4]));
                khi.pentecost.boot.Log.info(tb.toString());
            }
            TYPES.clear();
            if (!SAMPLES.isEmpty()) {
                khi.pentecost.boot.Log.info("Mob threads still mobs:\n    " + String.join("\n    ", SAMPLES));
                SAMPLES.clear();
            }
            boolean bad = diagPathing >= 100 && diagStuck * 10 >= diagPathing * 8;
            badWindows = bad ? badWindows + 1 : 0;
            if (badWindows >= 2 && !selfOff) {
                selfOff = true;
                String why = "Mob threads turned themselves off: for 20 seconds most mobs with a path did not move ("
                        + diagStuck + " of " + diagPathing + "). Mobs tick on the server thread until restart.";
                khi.pentecost.boot.Log.warn(why);
                Messenger.get().note(why);
            }
            diagAt = nowMs;
            diagMoved = 0;
            diagStuck = 0;
            diagPathing = 0;
        }
        lastMobs = count;
        lastChanges = changed;
        lastMs = (System.nanoTime() - started) / 1e6;
    }

    private static void fail(Pending p, Throwable x) {
        if (SENT_HOME.add(p.mod())) {
            StringWriter sw = new StringWriter();
            x.printStackTrace(new PrintWriter(sw));
            String[] lines = sw.toString().split("\\R");
            StringBuilder b = new StringBuilder("Mob " + p.mob().getType().getDescriptionId() + " from " + p.mod()
                    + " failed on a mod thread. Its mod's mobs tick on the server thread until restart.\n");
            for (int i = 0; i < Math.min(lines.length, 25); i++) {
                b.append("    ").append(lines[i]).append('\n');
            }
            Messenger.get().note(b.toString());
        }
    }

    @Override
    public void tickNow(Object level, Consumer<Object> tick, Object entity) {
        guarded((Level) level, tick, (Entity) entity);
    }

    @SuppressWarnings("unchecked")
    private static void guarded(Level level, Consumer<Object> tick, Entity entity) {
        level.guardEntityTick((Consumer<Entity>) (Consumer<?>) tick, entity);
    }

    @Override
    public boolean foreign(Object entity) {
        ModThreads.Worker w = ModThreads.worker();
        if (w == null) {
            return false;
        }
        return !(entity instanceof Entity e) || !MINE.get().contains(e);
    }

    @Override
    public boolean hurtNow(Object entity, Object source, float amount) {
        return ((Entity) entity).hurt((DamageSource) source, amount);
    }

    @Override
    public Object threadRandom() {
        ModThreads.Worker w = ModThreads.worker();
        if (w == null) {
            return RandomSource.create();
        }
        if (w.random == null) {
            w.random = RandomSource.create();
        }
        return w.random;
    }

    @Override
    public Object loadedNow(Object cache, int x, int z) {
        Object c = chunkNow(cache, x, z, ChunkStatus.FULL, false);
        return c instanceof net.minecraft.world.level.chunk.LevelChunk ? c : null;
    }

    @Override
    public Object chunkNow(Object cache, int x, int z, Object statusObject, boolean load) {
        if (!(cache instanceof ServerChunkCache c) || !(statusObject instanceof ChunkStatus status)) {
            return null;
        }
        try {
            Method m = visible;
            if (m == null) {
                m = ChunkMap.class.getDeclaredMethod("getVisibleChunkIfPresent", long.class);
                m.setAccessible(true);
                visible = m;
            }
            ChunkHolder h = (ChunkHolder) m.invoke(c.chunkMap, ChunkPos.asLong(x, z));
            ChunkAccess found = null;
            if (h != null) {
                if (status == ChunkStatus.FULL) {
                    found = h.getFullChunk();
                    if (found == null && h.getLastAvailableStatus() == ChunkStatus.FULL) {
                        found = h.getLastAvailable();
                    }
                } else {
                    ChunkStatus have = h.getLastAvailableStatus();
                    if (have != null && have.isOrAfter(status)) {
                        found = h.getLastAvailable();
                    }
                }
            }
            if (found != null) {
                CHUNK_HITS.increment();
                return found;
            }
            if (load) {
                khi.pentecost.messenger.MobHooks.chunkWantedAt = System.nanoTime();
                return null;
            }
            long n = CHUNK_MISSES.incrementAndGet();
            if (n <= 5) {
                Messenger.get().note("Mod thread chunk read missed at " + x + ", " + z + " (" + status + ", holder "
                        + (h == null ? "none" : h.getLastAvailableStatus()) + "). Asked the server thread instead.");
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CHUNK_ERRORS.increment();
            if (chunkError == null) {
                chunkError = e.toString();
                Messenger.get().note("Mod thread chunk read failed: " + e);
            }
            return null;
        }
    }

    @Override
    public void setBlockNow(Object level, Object pos, Object state, int flags, int depth) {
        ((Level) level).setBlock((BlockPos) pos, (BlockState) state, flags, depth);
    }

    @Override
    public void addEntityNow(Object level, Object entity) {
        ((ServerLevel) level).addFreshEntity((Entity) entity);
    }

    @Override
    public void gameEventNow(Object level, Object event, Object pos, Object context) {
        ((ServerLevel) level).gameEvent((GameEvent) event, (Vec3) pos, (GameEvent.Context) context);
    }

    @Override
    public void onMoveNow(Object callback) {
        ((EntityInLevelCallback) callback).onMove();
    }

    @Override
    public void onRemoveNow(Object callback, Object reason) {
        ((EntityInLevelCallback) callback).onRemove((Entity.RemovalReason) reason);
    }
}
