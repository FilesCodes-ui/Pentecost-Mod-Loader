package khi.pentecost.forgeglue;

import khi.pentecost.control.Settings;
import khi.pentecost.core.ModOwners;
import khi.pentecost.messenger.Messenger;
import khi.pentecost.messenger.MobHooks;
import khi.pentecost.messenger.ModThreads;
import khi.pentecost.messenger.TickSplit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class ModHandlers implements TickSplit.Impl {
    private static final String TICK = "net.minecraftforge.event.TickEvent";
    private static final String SERVER_TICK = TICK + "$ServerTickEvent";
    private static final String LEVEL_TICK = TICK + "$LevelTickEvent";
    private static final String LISTENER = "net.minecraftforge.eventbus.api.IEventListener";

    private static final Set<String> SENT_HOME = TickSplit.SENT_HOME;
    private static volatile boolean toldFirst;
    private static volatile boolean toldNone;
    private static volatile String lastWhy;

    private final Map<Object, Integer> threadOf = new IdentityHashMap<>();
    private final Map<Object, String> modOf = new IdentityHashMap<>();
    private static volatile Field readable;
    private static volatile Method invoke;
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    public static void install() {
        TickSplit.impl = new ModHandlers();
    }

    @Override
    public Object[] split(Object[] listeners, Object event) {
        if (event == null) {
            return listeners;
        }
        String kind = tickKind(event.getClass());
        if (kind == null) {
            return listeners;
        }
        Object side = field(event, "side");
        if (side == null || !"SERVER".equals(side.toString())) {
            return listeners;
        }
        ModThreads mt = ModThreads.shared;
        ModOwners owners = ModThreads.owners;
        if (mt == null || owners == null || !Settings.on(Settings.MOD_TICKS) || !Messenger.get().on()) {
            String why = mt == null ? "no mod threads yet" : owners == null ? "no mod owner list yet"
                    : !Settings.on(Settings.MOD_TICKS) ? "switch off" : "messenger off";
            if (!why.equals(lastWhy)) {
                lastWhy = why;
                khi.pentecost.boot.Log.info("Mod tick threads waiting: " + why);
            }
            return listeners;
        }
        if (lastWhy != null) {
            lastWhy = null;
            khi.pentecost.boot.Log.info("Mod tick threads ready");
        }
        Map<Integer, List<Object>> groups = new TreeMap<>();
        List<Object> rest = new ArrayList<>(listeners.length);
        for (Object l : listeners) {
            int t = threadFor(l, owners, mt);
            if (t == ModThreads.HOME) {
                rest.add(l);
            } else {
                groups.computeIfAbsent(t, k -> new ArrayList<>()).add(l);
            }
        }
        if (groups.isEmpty()) {
            if (!toldNone) {
                toldNone = true;
                khi.pentecost.boot.Log.info("Mod tick threads: no tick handler in " + listeners.length
                        + " belongs to a mod with a thread, they stay on the server thread");
            }
            return listeners;
        }
        Method call = invokeMethod(groups.values().iterator().next().get(0));
        if (call == null) {
            return listeners;
        }
        Runnable idle = idleFor(kind, event);
        mt.setHome(Thread.currentThread());
        Map<Integer, Runnable> batches = new TreeMap<>();
        List<Object> again = java.util.Collections.synchronizedList(new ArrayList<>());
        int count = 0;
        for (Map.Entry<Integer, List<Object>> g : groups.entrySet()) {
            List<Object> list = g.getValue();
            count += list.size();
            batches.put(g.getKey(), () -> {
                for (Object l : list) {
                    String mod = modOf.get(l);
                    try {
                        call.invoke(l, event);
                    } catch (java.lang.reflect.InvocationTargetException x) {
                        Throwable cause = x.getCause() == null ? x : x.getCause();
                        if (khi.pentecost.messenger.HomeOnly.isSendHome(cause)) {
                            sendHome(mod, l, cause);
                            again.add(l);
                        } else {
                            fail(mod, l, cause);
                        }
                    } catch (Throwable x) {
                        fail(mod, l, x);
                    }
                }
            });
        }
        long started = System.nanoTime();
        MobHooks.phase = true;
        try {
            mt.runAll(batches, idle, 250);
        } finally {
            MobHooks.phase = false;
        }
        for (int t : groups.keySet()) {
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
                    Messenger.get().note("A queued world change from a mod tick handler failed: " + x);
                }
            }
        }
        if (!again.isEmpty()) {
            for (Object l : listeners) {
                if (again.contains(l)) {
                    try {
                        call.invoke(l, event);
                    } catch (java.lang.reflect.InvocationTargetException x) {
                        Throwable cause = x.getCause() == null ? x : x.getCause();
                        if (cause instanceof RuntimeException r) {
                            throw r;
                        }
                        if (cause instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(cause);
                    } catch (IllegalAccessException x) {
                        throw new RuntimeException(x);
                    }
                }
            }
        }
        TickSplit.lastHandlers = count;
        TickSplit.lastGroups = groups.size();
        TickSplit.lastMs = (System.nanoTime() - started) / 1e6;
        if (!toldFirst) {
            toldFirst = true;
            khi.pentecost.boot.Log.info("Mod tick threads: " + count + " handlers moved to " + groups.size() + " threads");
        }
        Object[] out = Arrays.copyOf(listeners, rest.size());
        for (int i = 0; i < rest.size(); i++) {
            out[i] = rest.get(i);
        }
        return out;
    }

    static String tickKind(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals(SERVER_TICK)) {
                return SERVER_TICK;
            }
            if (n.equals(LEVEL_TICK)) {
                return LEVEL_TICK;
            }
            if (n.equals(TICK)) {
                return null;
            }
        }
        return null;
    }

    private static Runnable idleFor(String kind, Object event) {
        if (kind.equals(LEVEL_TICK)) {
            Object level = field(event, "level");
            return level == null ? null : () -> pollTask(level);
        }
        Object server = call(event, "getServer");
        if (server == null) {
            return null;
        }
        return () -> {
            Object all = call(server, "getAllLevels");
            if (all instanceof Iterable<?> it) {
                for (Object level : it) {
                    pollTask(level);
                }
            }
        };
    }

    private static void pollTask(Object level) {
        Object source = call(level, "getChunkSource");
        if (source != null && source.getClass().getName().equals("net.minecraft.server.level.ServerChunkCache")) {
            call(source, "pollTask");
        }
    }

    private static Object field(Object o, String name) {
        String key = o.getClass().getName() + "#" + name;
        try {
            Field f = FIELDS.get(key);
            if (f == null) {
                for (Class<?> k = o.getClass(); k != null && f == null; k = k.getSuperclass()) {
                    try {
                        f = k.getDeclaredField(name);
                    } catch (NoSuchFieldException e) {
                    }
                }
                if (f == null) {
                    return null;
                }
                f.setAccessible(true);
                FIELDS.put(key, f);
            }
            return f.get(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object call(Object o, String name) {
        String key = o.getClass().getName() + "#" + name;
        try {
            Method m = METHODS.get(key);
            if (m == null) {
                for (Class<?> k = o.getClass(); k != null && m == null; k = k.getSuperclass()) {
                    try {
                        m = k.getDeclaredMethod(name);
                    } catch (NoSuchMethodException e) {
                    }
                }
                if (m == null) {
                    return null;
                }
                m.setAccessible(true);
                METHODS.put(key, m);
            }
            return m.invoke(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method invokeMethod(Object listener) {
        Method m = invoke;
        if (m != null) {
            return m;
        }
        for (Class<?> k = listener.getClass(); k != null && m == null; k = k.getSuperclass()) {
            m = findInvoke(k);
        }
        if (m == null) {
            khi.pentecost.boot.Log.warn("Mod tick threads: could not find the listener call, nothing moves");
            return null;
        }
        invoke = m;
        return m;
    }

    private static Method findInvoke(Class<?> k) {
        for (Class<?> i : k.getInterfaces()) {
            if (i.getName().equals(LISTENER)) {
                for (Method m : i.getMethods()) {
                    if (m.getName().equals("invoke") && m.getParameterCount() == 1) {
                        return m;
                    }
                }
            }
            Method deeper = findInvoke(i);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private int threadFor(Object listener, ModOwners owners, ModThreads mt) {
        Integer known = threadOf.get(listener);
        if (known != null) {
            String mod = modOf.get(listener);
            return mod != null && SENT_HOME.contains(mod) ? ModThreads.HOME : known;
        }
        if (threadOf.size() > 4096) {
            threadOf.clear();
            modOf.clear();
        }
        String cls = ownerClass(listener);
        String mod = cls == null ? null : owners.ownerOfClass(cls);
        int t = mod == null || "minecraft".equals(mod) || "forge".equals(mod) || "pentecost".equals(mod)
                || movesBetweenWorlds(mod) ? ModThreads.HOME : mt.threadOf(mod);
        threadOf.put(listener, t);
        if (mod != null) {
            modOf.put(listener, mod);
        }
        return t;
    }

    static String ownerClass(Object listener) {
        if (listener instanceof Enum<?>) {
            return null;
        }
        String c = listener.getClass().getName();
        if (c.equals("net.minecraftforge.eventbus.ASMEventHandler")) {
            String r = readableOf(listener);
            if (r == null) {
                return null;
            }
            if (r.startsWith("ASM: ")) {
                r = r.substring(5);
            }
            if (r.startsWith("class ")) {
                r = r.substring(6);
            }
            int end = 0;
            while (end < r.length() && r.charAt(end) != ' ' && r.charAt(end) != '@') {
                end++;
            }
            return r.substring(0, end);
        }
        for (Class<?> k = listener.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(listener);
                    if (v != null && !v.getClass().getName().startsWith("net.minecraftforge.eventbus.")
                            && !v.getClass().getName().startsWith("java.")) {
                        return lambdaHost(v.getClass().getName());
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
        return c.startsWith("net.minecraftforge.eventbus.") ? null : lambdaHost(c);
    }

    private static String lambdaHost(String className) {
        int lambda = className.indexOf("$$Lambda");
        return lambda > 0 ? className.substring(0, lambda) : className;
    }

    private static String readableOf(Object handler) {
        try {
            Field f = readable;
            if (f == null) {
                f = handler.getClass().getDeclaredField("readable");
                f.setAccessible(true);
                readable = f;
            }
            return (String) f.get(handler);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return handler.toString();
        }
    }

    private static final Map<String, Boolean> MOVERS = new ConcurrentHashMap<>();

    static boolean movesBetweenWorlds(String mod) {
        return MOVERS.computeIfAbsent(mod, ModHandlers::scanForMoves);
    }

    private static final String[] MOVE_MARKS = {
            "changeDimension", "addDuringTeleport", "addDuringCommandTeleport", "addDuringPortalTeleport",
            "removePlayerImmediately", "addRespawnedPlayer", "addNewPlayer",
            "m_5489_", "m_8999_", "m_264318_", "m_8817_", "m_143334_", "m_8622_", "m_8834_", "m_8845_"};

    private static final byte[] TELEPORT = "teleportTo".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] TO_LEVEL = "(Lnet/minecraft/server/level/ServerLevel;DDD".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static boolean scanForMoves(String mod) {
        khi.pentecost.mods.ModInfo info = null;
        for (khi.pentecost.mods.ModInfo m : khi.pentecost.core.PentecostRuntime.mods) {
            if (m.id().equals(mod)) {
                info = m;
                break;
            }
        }
        if (info == null || info.jar() == null || !java.nio.file.Files.isRegularFile(info.jar())) {
            return true;
        }
        byte[][] marks = new byte[MOVE_MARKS.length][];
        for (int i = 0; i < marks.length; i++) {
            marks[i] = MOVE_MARKS[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(info.jar().toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (java.io.InputStream in = jar.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                if (contains(bytes, TELEPORT) && contains(bytes, TO_LEVEL)) {
                    khi.pentecost.boot.Log.info("Mod tick threads: " + mod + " moves players between worlds (teleportTo in "
                            + e.getName() + "), its tick handlers stay on the server thread");
                    return true;
                }
                for (int i = 0; i < marks.length; i++) {
                    if (contains(bytes, marks[i])) {
                        khi.pentecost.boot.Log.info("Mod tick threads: " + mod + " moves things between worlds ("
                                + MOVE_MARKS[i] + " in " + e.getName() + "), its tick handlers stay on the server thread");
                        return true;
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return true;
        }
        return false;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void sendHome(String mod, Object listener, Throwable x) {
        String who = mod == null ? String.valueOf(listener) : mod;
        if (SENT_HOME.add(who)) {
            Messenger.get().note("A tick handler of " + who + " tried to " + x.getMessage()
                    + " from a mod thread. It ran again on the server thread, and " + who
                    + "'s handlers stay there until restart.");
        }
    }

    private static void fail(String mod, Object listener, Throwable x) {
        String who = mod == null ? String.valueOf(listener) : mod;
        if (SENT_HOME.add(who)) {
            StringWriter sw = new StringWriter();
            x.printStackTrace(new PrintWriter(sw));
            String[] lines = sw.toString().split("\\R");
            StringBuilder b = new StringBuilder("A tick handler of " + who + " failed on a mod thread. Its handlers run on the server thread until restart.\n");
            for (int i = 0; i < Math.min(lines.length, 25); i++) {
                b.append("    ").append(lines[i]).append('\n');
            }
            Messenger.get().note(b.toString());
        }
    }
}
