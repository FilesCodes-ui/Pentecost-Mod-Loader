package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.messenger.Lane;
import khi.pentecost.messenger.Messenger;
import khi.pentecost.messenger.PathHooks;
import khi.pentecost.messenger.Threads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncPaths implements PathHooks.Impl {
    private static final long TIMEOUT_MS = 500;
    private static final Map<EntityType<?>, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> PLAIN = new ConcurrentHashMap<>();

    private static Field maxNodes;
    private static Method settled;

    public static void install() throws ReflectiveOperationException {
        maxNodes = PathFinder.class.getDeclaredField("maxVisitedNodes");
        maxNodes.setAccessible(true);
        settled = PathNavigation.class.getMethod("pentecost$settled");
        PathHooks.impl = new AsyncPaths();
        Log.info("Async pathfinding installed. It runs while the messenger is on.");
    }

    private static String name(Mob mob) {
        return NAMES.computeIfAbsent(mob.getType(), t -> {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(t);
            return "path:" + (key == null ? t.toString() : key.toString());
        });
    }

    @Override
    public Object findPath(Object finderO, Object regionO, Object mobO, Set<?> targetsO, float range, int accuracy,
                           float multiplier, Object navigationO) {
        PathFinder finder = (PathFinder) finderO;
        PathNavigationRegion region = (PathNavigationRegion) regionO;
        Mob mob = (Mob) mobO;
        @SuppressWarnings("unchecked")
        Set<BlockPos> targets = (Set<BlockPos>) targetsO;
        PathNavigation nav = (PathNavigation) navigationO;

        NodeEvaluator evaluator = nav.getNodeEvaluator();
        if (!Threads.paths() || targets.size() != 1 || finder.getClass() != PathFinder.class || evaluator == null
                || !(evaluator.getClass() == WalkNodeEvaluator.class || evaluator.getClass() == FlyNodeEvaluator.class)
                || !plainHooks(mob.getClass()) || !onServerThread(mob)) {
            PathHooks.onServer.increment();
            return finder.findPath(region, mob, targets, range, accuracy, multiplier);
        }
        String from = name(mob);
        Messenger m = Messenger.get();
        if (m.isQuarantined(from)) {
            PathHooks.onServer.increment();
            return finder.findPath(region, mob, targets, range, accuracy, multiplier);
        }
        String site = callerSite();
        if (!PathHooks.siteAsync(site)) {
            PathHooks.onServer.increment();
            return finder.findPath(region, mob, targets, range, accuracy, multiplier);
        }
        int nodes;
        try {
            nodes = maxNodes.getInt(finder);
        } catch (IllegalAccessException e) {
            PathHooks.onServer.increment();
            return finder.findPath(region, mob, targets, range, accuracy, multiplier);
        }
        boolean fly = evaluator.getClass() == FlyNodeEvaluator.class;
        boolean doors = evaluator.canPassDoors();
        boolean open = evaluator.canOpenDoors();
        boolean floats = evaluator.canFloat();
        boolean fences = evaluator.canWalkOverFences();
        Set<BlockPos> wanted = Set.copyOf(targets);

        java.util.function.Supplier<Path> search = () -> {
            NodeEvaluator fresh = fly ? new FlyNodeEvaluator() : new WalkNodeEvaluator();
            fresh.setCanPassDoors(doors);
            fresh.setCanOpenDoors(open);
            fresh.setCanFloat(floats);
            fresh.setCanWalkOverFences(fences);
            return new PathFinder(fresh, nodes).findPath(region, mob, wanted, range, accuracy, multiplier);
        };
        AsyncPath path = new AsyncPath(wanted.iterator().next(), search, site);
        PathHooks.onWorkers.increment();
        PathHooks.tried(site);
        m.ask(from, "pathfind", Lane.WORKER, () -> {
            long t0 = System.nanoTime();
            try {
                return search.get();
            } finally {
                PathHooks.workerNanos.add(System.nanoTime() - t0);
            }
        }, Lane.SERVER, result -> apply(nav, mob, path, result), () -> apply(nav, mob, path, search.get()), TIMEOUT_MS);
        return path;
    }

    private static boolean plainHooks(Class<?> type) {
        return PLAIN.computeIfAbsent(type, t -> {
            try {
                return t.getMethod("onPathfindingStart").getDeclaringClass() == Mob.class
                        && t.getMethod("onPathfindingDone").getDeclaringClass() == Mob.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
    }

    private static final StackWalker WALK = StackWalker.getInstance();

    static String callerSite() {
        return WALK.walk(s -> s.map(f -> f.getClassName() + "." + f.getMethodName())
                .filter(n -> !n.startsWith("khi.pentecost.") && !n.startsWith("net.minecraft.world.entity.ai.navigation.")
                        && !n.startsWith("net.minecraft.world.level.pathfinder."))
                .findFirst().orElse("unknown"));
    }

    private static boolean onServerThread(Mob mob) {
        if (mob.level().isClientSide()) {
            return false;
        }
        MinecraftServer server = mob.level().getServer();
        return server != null && server.isSameThread() && mob.level().getProfiler() == InactiveProfiler.INSTANCE;
    }

    private static void apply(PathNavigation nav, Mob mob, AsyncPath path, Path result) {
        if (!path.settle(result)) {
            return;
        }
        if (mob.isRemoved() || nav.getPath() != path) {
            return;
        }
        try {
            settled.invoke(nav);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not finish the path", e.getCause() != null ? e.getCause() : e);
        }
    }
}
