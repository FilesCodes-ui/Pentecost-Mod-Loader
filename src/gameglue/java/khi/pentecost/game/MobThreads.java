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

/**
 * The game side of mob threads (see MobHooks). Decides which mobs go to a mod thread, ticks them there, and runs the
 * world changes they queued once the server thread has them back.
 *
 * A mob goes to a mod thread only when: the switch is on, the messenger is on, it is a Mob from a mod (not Minecraft),
 * it rides nothing and carries nothing, and its mod never failed this session.
 *
 * Mobs are split by area, not by mod: groups of mobs too far apart to touch each other (see Islands) run on
 * different threads at the same time, so even one mod's mobs use every thread. A mob may change only itself and the
 * mobs in its own group right away. Anything else waits for the server thread.
 */
public final class MobThreads implements MobHooks.Impl {
    public static final MobThreads INSTANCE = new MobThreads();
    public static final LongAdder MOVED = new LongAdder();
    public static final LongAdder CHANGES = new LongAdder();
    /** Mods whose mobs failed on a mod thread. They tick on the server thread until the game restarts. */
    public static final Set<String> SENT_HOME = ConcurrentHashMap.newKeySet();
    public static volatile int threadsUsed;
    /** The last tick: mobs ticked on mod threads, world changes they queued, and time the server waited for them. */
    public static volatile int lastMobs;
    public static volatile int lastChanges;
    public static volatile double lastMs;

    private record Pending(ServerLevel level, Consumer<Object> tick, Mob mob, String mod) {}

    private final List<Pending> pending = new ArrayList<>();
    /** The mobs the current mod thread is ticking now. Only these may be changed right away. */
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
        if (mt == null || !(level instanceof ServerLevel sl) || !(entity instanceof Mob mob)
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

    /**
     * Minecraft mobs that change data shared across the whole world while they think: village points (beds, job
     * blocks), raids, and trades. Two threads changing that data at once could break it, so these stay home.
     */
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
        // The queued world changes, thread by thread, in the order each thread made them.
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
    public Object chunkNow(Object cache, int x, int z, Object status) {
        if (status != ChunkStatus.FULL || !(cache instanceof ServerChunkCache c)) {
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
            return h == null ? null : h.getFullChunk();
        } catch (ReflectiveOperationException | RuntimeException e) {
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
