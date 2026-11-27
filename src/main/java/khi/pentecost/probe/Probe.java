package khi.pentecost.probe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.ThreadInfo;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Live debugging probe. A loopback-only HTTP server that lets an outside tool look inside
 * the running game. Read-only by design: it reports, it never changes game state.
 *
 * Safety: binds 127.0.0.1 only, every request needs the token from run/pentecost-probe.json, and the
 * Host header must be a loopback name (blocks DNS rebinding from web pages). It only starts
 * on a machine that holds the developer key (DevKey.held()), so it never runs for anyone else.
 */
public final class Probe {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long START = System.currentTimeMillis();

    private static volatile List<ModInfo> mods = List.of();
    private static volatile ClassLoader loader;
    private static volatile HttpServer server;
    private static volatile String token;
    private static volatile int port;
    private static volatile boolean gated;

    /** Endpoints that need the Minecraft side. Registered by the game glue; run on the render thread. */
    public static final Map<String, Supplier<Object>> GAME = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> RENDER_QUEUE = new ConcurrentLinkedQueue<>();

    /** Recent probe requests, so you can see what was asked. */
    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();

    private Probe() {}

    public static synchronized void attach(ClassLoader cl, List<ModInfo> list) {
        loader = cl;
        mods = List.copyOf(list);
        if (server != null || gated) return;
        gated = true;
        // Owner only: checked off the main thread because the key lookup reads the Windows certificate store.
        Thread t = new Thread(() -> {
            try {
                if (!khi.pentecost.auth.DevKey.held()) return;
                synchronized (Probe.class) {
                    if (server == null) start();
                }
            } catch (Throwable x) {
                Log.warn("Probe: could not start: " + x);
            }
        }, "Pentecost-Probe-Gate");
        t.setDaemon(true);
        t.start();
    }

    /** Called once per rendered frame on the render thread. Runs queued game-thread requests. */
    public static void frame() {
        Runnable r;
        int n = 0;
        while (n++ < 8 && (r = RENDER_QUEUE.poll()) != null) {
            try { r.run(); } catch (Throwable t) { Log.warn("Probe: queued task failed: " + t); }
        }
    }

    /** Runs on the render thread at its next frame and waits for the answer. */
    public static <T> T onRenderThread(Supplier<T> s, long timeoutMs) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        RENDER_QUEUE.add(() -> {
            try { f.complete(s.get()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static void start() throws IOException {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        HttpServer s = null;
        for (int p = 7878; p < 7898 && s == null; p++) {
            try {
                s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 16);
                port = p;
            } catch (IOException busy) { /* try next */ }
        }
        if (s == null) throw new IOException("no free port in 7878-7897");
        s.createContext("/", Probe::handle);
        s.setExecutor(Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "Pentecost-Probe");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server = s;
        JsonObject info = new JsonObject();
        info.addProperty("url", "http://127.0.0.1:" + port + "/");
        info.addProperty("token", token);
        info.addProperty("pid", ProcessHandle.current().pid());
        Files.writeString(Dirs.run().resolve("pentecost-probe.json"), GSON.toJson(info));
        Log.info("Probe: listening on 127.0.0.1:" + port + " (token in run/pentecost-probe.json)");
    }

    // ---------------------------------------------------------------- request handling

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            String h = host == null ? "" : host.replaceAll(":\\d+$", "");
            if (!(h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]"))) {
                send(ex, 403, "{\"error\":\"bad host\"}");
                return;
            }
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String given = q.getOrDefault("token", ex.getRequestHeaders().getFirst("X-Probe-Token"));
            if (given == null || !java.security.MessageDigest.isEqual(
                    given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, "{\"error\":\"token required: ?token=... (see run/pentecost-probe.json)\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            synchronized (RECENT) {
                RECENT.addLast(((System.currentTimeMillis() - START) / 1000) + "s " + path);
                while (RECENT.size() > 50) RECENT.removeFirst();
            }
            Object out = route(path, q);
            if (out instanceof String s) send(ex, 200, s, "text/plain; charset=utf-8");
            else send(ex, 200, GSON.toJson(out));
        } catch (Throwable t) {
            JsonObject e = new JsonObject();
            e.addProperty("error", t.toString());
            send(ex, 500, GSON.toJson(e));
        }
    }

    private static Object route(String path, Map<String, String> q) throws Exception {
        switch (path) {
            case "/": return index();
            case "/status": return status();
            case "/threads": return threads(q);
            case "/sample": return sample(num(q, "ms", 3000), num(q, "hz", 100), q.get("thread"));
            case "/mods": return modList();
            case "/mod": return modDetail(q.get("id"));
            case "/class": return classInfo(q.get("name"));
            case "/modclasses": return modClasses();
            case "/memory": return memory();
            case "/gc": return gc();
            case "/props": return props();
            case "/env": return env();
            case "/logs": return logs();
            case "/log": return logTail(q.get("file"), (int) num(q, "lines", 200), q.get("grep"));
            case "/files": return files(q.get("dir"));
            case "/config": return fileText(Dirs.config(), q.get("file"), (int) num(q, "max", 60000));
            case "/mixins": return mixins();
            case "/heal": return fileText(Dirs.run(), "pentecost-heal.properties", 60000);
            case "/refused": return refused();
            case "/guards": return khi.pentecost.compat.PassGuard.describe();
            case "/recent": synchronized (RECENT) { return new ArrayList<>(RECENT); }
            default:
                if (path.startsWith("/game/")) return game(path.substring(6));
                JsonObject e = new JsonObject();
                e.addProperty("error", "unknown endpoint " + path);
                e.add("try", GSON.toJsonTree(index().get("endpoints")));
                return e;
        }
    }

    // ---------------------------------------------------------------- endpoints

    private static JsonObject index() {
        JsonObject o = new JsonObject();
        o.addProperty("about", "Pentecost live probe. Read-only. Add ?token=... to every request.");
        Map<String, String> ep = new java.util.LinkedHashMap<>();
        ep.put("/status", "pid, uptime, JVM, CPU, load, thread/class counts");
        ep.put("/threads?state=&name=&stacks=1", "every thread: state, CPU time, locks held/awaited, stack (stacks=0 to omit)");
        ep.put("/sample?ms=3000&hz=100&thread=Render", "sampling profiler: hottest methods, per mod, per thread, hottest stacks");
        ep.put("/mods", "all loaded mods with loader kind, jar, entrypoints, mixins, depends");
        ep.put("/mod?id=lambdynlights", "one mod in full, including raw fabric.mod.json / mods.toml data");
        ep.put("/class?name=a.b.C", "is a class loaded, by which loader, from which jar/mod, super types");
        ep.put("/modclasses", "loaded class count per mod (spot who loads the most)");
        ep.put("/memory", "heap, non-heap, every memory pool, buffer pools");
        ep.put("/gc", "collector counts and total pause time");
        ep.put("/props", "JVM system properties");
        ep.put("/env", "environment variables (secrets redacted)");
        ep.put("/logs", "log files in run/logs, run/crash-reports and build reports, newest first");
        ep.put("/log?file=latest.log&lines=200&grep=Lamb", "tail of a log, optionally filtered");
        ep.put("/files?dir=run/config", "list files under the Pentecost home");
        ep.put("/config?file=lambdynlights.toml", "read a file from run/config");
        ep.put("/mixins", "Mixin configs and the classes they were applied to (if Mixin is reachable)");
        ep.put("/heal", "self-healing memory file");
        ep.put("/guards", "portal-pass guard rules, whether each patched a method, and how many calls each has skipped");
        ep.put("/refused", "mods Pentecost refused to load, with reasons");
        ep.put("/recent", "last probe requests");
        for (String k : GAME.keySet()) ep.put("/game/" + k, "game-side data (render thread)");
        o.add("endpoints", GSON.toJsonTree(ep));
        return o;
    }

    private static JsonObject status() {
        var rt = ManagementFactory.getRuntimeMXBean();
        var os = ManagementFactory.getOperatingSystemMXBean();
        var th = ManagementFactory.getThreadMXBean();
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        JsonObject o = new JsonObject();
        o.addProperty("pid", ProcessHandle.current().pid());
        o.addProperty("uptimeSeconds", rt.getUptime() / 1000.0);
        o.addProperty("probeAgeSeconds", (System.currentTimeMillis() - START) / 1000.0);
        o.addProperty("java", rt.getVmName() + " " + rt.getVmVersion());
        o.addProperty("os", os.getName() + " " + os.getVersion() + " " + os.getArch());
        o.addProperty("cpus", os.getAvailableProcessors());
        o.addProperty("systemLoad", os.getSystemLoadAverage());
        if (os instanceof com.sun.management.OperatingSystemMXBean x) {
            o.addProperty("processCpuLoad", x.getProcessCpuLoad());
            o.addProperty("systemCpuLoad", x.getCpuLoad());
            o.addProperty("physicalMemoryMB", x.getTotalMemorySize() >> 20);
            o.addProperty("freePhysicalMemoryMB", x.getFreeMemorySize() >> 20);
        }
        o.addProperty("threads", th.getThreadCount());
        o.addProperty("peakThreads", th.getPeakThreadCount());
        o.addProperty("loadedClasses", cl.getLoadedClassCount());
        o.addProperty("totalLoadedClasses", cl.getTotalLoadedClassCount());
        o.addProperty("unloadedClasses", cl.getUnloadedClassCount());
        o.addProperty("modsLoaded", mods.size());
        o.addProperty("heapUsedMB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
        o.addProperty("heapMaxMB", Runtime.getRuntime().maxMemory() >> 20);
        o.add("jvmArgs", GSON.toJsonTree(rt.getInputArguments()));
        o.addProperty("home", Dirs.home().toString());
        return o;
    }

    private static Object threads(Map<String, String> q) {
        var th = ManagementFactory.getThreadMXBean();
        boolean stacks = !"0".equals(q.get("stacks"));
        String state = q.get("state"), name = q.get("name");
        ThreadInfo[] infos = th.dumpAllThreads(true, true, 64);
        JsonArray arr = new JsonArray();
        Map<String, Integer> byState = new HashMap<>();
        for (ThreadInfo ti : infos) {
            if (ti == null) continue;
            byState.merge(ti.getThreadState().name(), 1, Integer::sum);
            if (state != null && !ti.getThreadState().name().equalsIgnoreCase(state)) continue;
            if (name != null && !ti.getThreadName().toLowerCase().contains(name.toLowerCase())) continue;
            JsonObject t = new JsonObject();
            t.addProperty("id", ti.getThreadId());
            t.addProperty("name", ti.getThreadName());
            t.addProperty("state", ti.getThreadState().name());
            t.addProperty("daemon", ti.isDaemon());
            t.addProperty("cpuMs", th.isThreadCpuTimeSupported() ? th.getThreadCpuTime(ti.getThreadId()) / 1_000_000 : -1);
            t.addProperty("blockedCount", ti.getBlockedCount());
            t.addProperty("blockedMs", ti.getBlockedTime());
            if (ti.getLockName() != null) t.addProperty("waitingOn", ti.getLockName());
            if (ti.getLockOwnerName() != null) t.addProperty("lockOwner", ti.getLockOwnerName());
            JsonArray held = new JsonArray();
            for (var m : ti.getLockedMonitors()) held.add(m.toString());
            for (var l : ti.getLockedSynchronizers()) held.add(l.toString());
            if (held.size() > 0) t.add("holds", held);
            if (stacks) t.add("stack", stack(ti.getStackTrace(), 48));
            arr.add(t);
        }
        long[] dead = th.findDeadlockedThreads();
        JsonObject o = new JsonObject();
        o.add("deadlockedThreadIds", GSON.toJsonTree(dead == null ? new long[0] : dead));
        o.add("countsByState", GSON.toJsonTree(byState));
        o.add("threads", arr);
        return o;
    }

    private static JsonArray stack(StackTraceElement[] st, int max) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < st.length && i < max; i++) {
            String mod = modOfClass(st[i].getClassName());
            a.add(st[i] + (mod == null ? "" : "   [" + mod + "]"));
        }
        if (st.length > max) a.add("... " + (st.length - max) + " more");
        return a;
    }

    /** Sampling profiler. Samples every live thread, attributes each sample to the mod owning the top frames. */
    private static Object sample(long ms, long hz, String threadFilter) throws InterruptedException {
        ms = Math.min(Math.max(ms, 200), 30_000);
        hz = Math.min(Math.max(hz, 5), 250);
        long gap = 1000 / hz;
        Map<String, int[]> self = new HashMap<>();
        Map<String, int[]> perMod = new HashMap<>();
        Map<String, int[]> perThread = new HashMap<>();
        Map<String, int[]> stacksTop = new HashMap<>();
        Map<String, Map<String, int[]>> modByThread = new HashMap<>();
        int samples = 0;
        long end = System.nanoTime() + ms * 1_000_000;
        while (System.nanoTime() < end) {
            for (var e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (t.getName().startsWith("Pentecost-Probe")) continue;
                if (t.getState() != Thread.State.RUNNABLE) continue;
                if (threadFilter != null && !t.getName().toLowerCase().contains(threadFilter.toLowerCase())) continue;
                StackTraceElement[] st = e.getValue();
                if (st.length == 0) continue;
                // A thread parked in native I/O waits is not burning CPU; skip well-known idle frames.
                String top = st[0].getClassName() + "." + st[0].getMethodName();
                if (isIdle(st[0])) continue;
                samples++;
                self.computeIfAbsent(top, k -> new int[1])[0]++;
                perThread.computeIfAbsent(t.getName(), k -> new int[1])[0]++;
                String owner = "game/jdk";
                for (int i = 0; i < st.length && i < 40; i++) {
                    String m = modOfClass(st[i].getClassName());
                    if (m != null) { owner = m; break; }
                }
                perMod.computeIfAbsent(owner, k -> new int[1])[0]++;
                modByThread.computeIfAbsent(t.getName(), k -> new HashMap<>())
                        .computeIfAbsent(owner, k -> new int[1])[0]++;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < st.length && i < 8; i++) sb.append(i == 0 ? "" : " <- ").append(st[i].getClassName())
                        .append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
                stacksTop.computeIfAbsent(sb.toString(), k -> new int[1])[0]++;
            }
            Thread.sleep(gap);
        }
        final int total = Math.max(samples, 1);
        JsonObject o = new JsonObject();
        o.addProperty("durationMs", ms);
        o.addProperty("hz", hz);
        o.addProperty("busySamples", samples);
        o.addProperty("note", "Counts samples of RUNNABLE, non-idle threads. Percent is of all busy samples. 'mod' is the first mod found walking down the stack.");
        o.add("perMod", ranked(perMod, total, 25));
        o.add("perThread", ranked(perThread, total, 25));
        o.add("hottestMethods", ranked(self, total, 40));
        o.add("hottestStacks", ranked(stacksTop, total, 15));
        JsonObject mbt = new JsonObject();
        perThread.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(8)
                .forEach(e -> mbt.add(e.getKey(), ranked(modByThread.get(e.getKey()), total, 8)));
        o.add("modsByBusiestThread", mbt);
        return o;
    }

    private static boolean isIdle(StackTraceElement top) {
        String c = top.getClassName(), m = top.getMethodName();
        return (c.equals("sun.nio.ch.EPoll") || c.equals("sun.nio.ch.WindowsSelectorImpl$SubSelector")
                || c.startsWith("sun.nio.ch.") && m.startsWith("poll")
                || c.equals("java.net.SocketInputStream") || c.equals("sun.nio.ch.SocketDispatcher")
                || c.equals("java.lang.ProcessHandleImpl") || c.equals("java.io.FileInputStream") && m.equals("readBytes")
                || c.equals("org.lwjgl.glfw.GLFW") && m.startsWith("glfwWaitEvents"));
    }

    private static JsonArray ranked(Map<String, int[]> m, int total, int limit) {
        JsonArray a = new JsonArray();
        if (m == null) return a;
        m.entrySet().stream().sorted((x, y) -> y.getValue()[0] - x.getValue()[0]).limit(limit).forEach(e -> {
            JsonObject r = new JsonObject();
            r.addProperty("name", e.getKey());
            r.addProperty("samples", e.getValue()[0]);
            r.addProperty("percent", Math.round(1000.0 * e.getValue()[0] / total) / 10.0);
            a.add(r);
        });
        return a;
    }

    private static String modOfClass(String className) {
        ClassLoader cl = loader;
        if (cl instanceof GameClassLoader g) {
            try { return g.modOf(className); } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object modList() {
        JsonArray a = new JsonArray();
        for (ModInfo m : mods) a.add(summary(m));
        JsonObject o = new JsonObject();
        o.addProperty("count", mods.size());
        o.add("mods", a);
        return o;
    }

    private static JsonObject summary(ModInfo m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id());
        o.addProperty("name", m.name());
        o.addProperty("version", m.version());
        o.addProperty("loader", m.loader().label);
        o.addProperty("jar", m.jar() == null ? null : m.jar().getFileName().toString());
        o.add("entrypoints", GSON.toJsonTree(m.entrypoints()));
        o.add("depends", GSON.toJsonTree(m.depends()));
        o.add("mixins", GSON.toJsonTree(m.mixins()));
        return o;
    }

    private static Object modDetail(String id) {
        for (ModInfo m : mods) {
            if (m.id().equalsIgnoreCase(String.valueOf(id))) {
                JsonObject o = summary(m);
                o.addProperty("jarPath", String.valueOf(m.jar()));
                o.add("accessWideners", GSON.toJsonTree(m.accessWideners()));
                o.add("raw", m.raw());
                return o;
            }
        }
        JsonObject e = new JsonObject();
        e.addProperty("error", "no mod with id " + id);
        return e;
    }

    private static Object classInfo(String name) {
        JsonObject o = new JsonObject();
        if (name == null) { o.addProperty("error", "name= required"); return o; }
        o.addProperty("name", name);
        o.addProperty("mod", modOfClass(name));
        try {
            java.lang.reflect.Method m = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            m.setAccessible(true);
            Object c = m.invoke(loader, name);
            o.addProperty("loadedByGameLoader", c != null);
            if (c instanceof Class<?> k) {
                o.addProperty("classLoader", String.valueOf(k.getClassLoader()));
                var cs = k.getProtectionDomain().getCodeSource();
                o.addProperty("source", cs == null ? null : String.valueOf(cs.getLocation()));
                o.addProperty("super", k.getSuperclass() == null ? null : k.getSuperclass().getName());
                JsonArray ifs = new JsonArray();
                for (Class<?> i : k.getInterfaces()) ifs.add(i.getName());
                o.add("interfaces", ifs);
                o.addProperty("modifiers", java.lang.reflect.Modifier.toString(k.getModifiers()));
                JsonArray methods = new JsonArray();
                for (var me : k.getDeclaredMethods()) methods.add(me.toString());
                o.add("methods", methods);
            }
        } catch (Throwable t) {
            o.addProperty("inspectError", t.toString());
        }
        String res = name.replace('.', '/') + ".class";
        var url = loader == null ? null : loader.getResource(res);
        o.addProperty("resolvesTo", url == null ? null : url.toString());
        return o;
    }

    private static Object modClasses() {
        Map<String, Integer> counts = new HashMap<>();
        try {
            Class<?> k = ClassLoader.class;
            java.lang.reflect.Field f = k.getDeclaredField("classes");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Class<?>> all = new ArrayList<>((java.util.Collection<Class<?>>) f.get(loader));
            for (Class<?> c : all) counts.merge(String.valueOf(modOfClass(c.getName())), 1, Integer::sum);
        } catch (Throwable t) {
            JsonObject e = new JsonObject();
            e.addProperty("error", "cannot read loaded classes (needs --add-opens java.base/java.lang=ALL-UNNAMED): " + t);
            return e;
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        JsonObject o = new JsonObject();
        for (var e : list) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    private static Object memory() {
        var mem = ManagementFactory.getMemoryMXBean();
        JsonObject o = new JsonObject();
        o.add("heap", usage(mem.getHeapMemoryUsage()));
        o.add("nonHeap", usage(mem.getNonHeapMemoryUsage()));
        JsonArray pools = new JsonArray();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            JsonObject po = usage(p.getUsage());
            po.addProperty("name", p.getName());
            if (p.getCollectionUsage() != null) po.add("afterLastGc", usage(p.getCollectionUsage()));
            pools.add(po);
        }
        o.add("pools", pools);
        JsonArray bufs = new JsonArray();
        for (var b : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            JsonObject bo = new JsonObject();
            bo.addProperty("name", b.getName());
            bo.addProperty("count", b.getCount());
            bo.addProperty("usedMB", b.getMemoryUsed() >> 20);
            bo.addProperty("capacityMB", b.getTotalCapacity() >> 20);
            bufs.add(bo);
        }
        o.add("buffers", bufs);
        return o;
    }

    private static JsonObject usage(java.lang.management.MemoryUsage u) {
        JsonObject o = new JsonObject();
        o.addProperty("usedMB", u.getUsed() >> 20);
        o.addProperty("committedMB", u.getCommitted() >> 20);
        o.addProperty("maxMB", u.getMax() < 0 ? -1 : u.getMax() >> 20);
        return o;
    }

    private static Object gc() {
        JsonArray a = new JsonArray();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", g.getName());
            o.addProperty("collections", g.getCollectionCount());
            o.addProperty("totalPauseMs", g.getCollectionTime());
            a.add(o);
        }
        return a;
    }

    private static Object props() {
        Map<String, String> m = new java.util.TreeMap<>();
        System.getProperties().forEach((k, v) -> m.put(String.valueOf(k), redact(String.valueOf(k), String.valueOf(v))));
        return m;
    }

    private static Object env() {
        Map<String, String> m = new java.util.TreeMap<>();
        System.getenv().forEach((k, v) -> m.put(k, redact(k, v)));
        return m;
    }

    private static String redact(String key, String value) {
        String k = key.toLowerCase();
        for (String s : new String[]{"token", "secret", "password", "passwd", "key", "auth", "session", "cookie", "credential"}) {
            if (k.contains(s)) return "<redacted>";
        }
        return value;
    }

    private static Object logs() throws IOException {
        JsonArray a = new JsonArray();
        for (Path dir : new Path[]{Dirs.run().resolve("logs"), Dirs.crashReports(), Dirs.reports(), Dirs.run()}) {
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".log") || p.toString().endsWith(".txt")
                                || p.toString().endsWith(".json") || p.toString().endsWith(".properties"))
                        .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .limit(15).forEach(p -> {
                            JsonObject o = new JsonObject();
                            o.addProperty("path", Dirs.home().relativize(p.toAbsolutePath()).toString());
                            o.addProperty("bytes", p.toFile().length());
                            o.addProperty("modified", java.time.Instant.ofEpochMilli(p.toFile().lastModified()).toString());
                            a.add(o);
                        });
            }
        }
        return a;
    }

    private static Path safe(Path base, String rel) {
        Path p = base.resolve(rel == null ? "" : rel).normalize().toAbsolutePath();
        if (!p.startsWith(base.toAbsolutePath().normalize())) throw new IllegalArgumentException("outside the Pentecost folder");
        return p;
    }

    private static Object logTail(String file, int lines, String grep) throws IOException {
        if (file == null) return "file= required (see /logs)";
        Path p = safe(Dirs.home(), file);
        if (p.toString().toLowerCase().contains("account") || p.getFileName().toString().toLowerCase().contains("token"))
            return "refused: looks like it may hold credentials";
        List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String l : all) if (grep == null || l.toLowerCase().contains(grep.toLowerCase())) out.add(l);
        int from = Math.max(0, out.size() - Math.max(1, Math.min(lines, 5000)));
        return String.join("\n", out.subList(from, out.size()));
    }

    private static Object files(String dir) throws IOException {
        Path p = safe(Dirs.home(), dir == null ? "run" : dir);
        JsonArray a = new JsonArray();
        try (var s = Files.list(p)) {
            s.sorted().limit(500).forEach(f -> {
                JsonObject o = new JsonObject();
                o.addProperty("name", f.getFileName().toString());
                o.addProperty("dir", Files.isDirectory(f));
                o.addProperty("bytes", f.toFile().length());
                o.addProperty("modified", java.time.Instant.ofEpochMilli(f.toFile().lastModified()).toString());
                a.add(o);
            });
        }
        return a;
    }

    private static Object fileText(Path base, String file, int max) throws IOException {
        if (file == null) return "file= required";
        Path p = safe(base, file);
        if (!Files.exists(p)) return "no such file: " + base.relativize(p);
        String s = Files.readString(p, StandardCharsets.UTF_8);
        return s.length() > max ? s.substring(0, max) + "\n... truncated" : s;
    }

    private static Object mixins() {
        JsonObject o = new JsonObject();
        try {
            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins");
            Object configs = mixins.getMethod("getConfigs").invoke(null);
            JsonArray a = new JsonArray();
            for (Object c : (java.util.Collection<?>) configs) {
                Object cfg = c.getClass().getMethod("getConfig").invoke(c);
                JsonObject co = new JsonObject();
                co.addProperty("name", String.valueOf(c.getClass().getMethod("getName").invoke(c)));
                co.addProperty("config", String.valueOf(cfg));
                a.add(co);
            }
            o.add("configs", a);
        } catch (Throwable t) {
            o.addProperty("error", "Mixin not reachable from the probe: " + t);
        }
        return o;
    }

    private static Object refused() {
        JsonArray a = new JsonArray();
        for (Object r : khi.pentecost.mods.ModDiscovery.REFUSED) a.add(String.valueOf(r));
        return a;
    }

    private static Object game(String name) throws Exception {
        Supplier<Object> s = GAME.get(name);
        if (s == null) {
            JsonObject e = new JsonObject();
            e.addProperty("error", "no game endpoint " + name);
            e.add("available", GSON.toJsonTree(GAME.keySet()));
            return e;
        }
        try {
            return onRenderThread(s, 5000);
        } catch (java.util.concurrent.TimeoutException t) {
            JsonObject e = new JsonObject();
            e.addProperty("error", "render thread did not run the request within 5 s. It may be frozen or not rendering; check /threads?name=Render");
            return e;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static long num(Map<String, String> q, String k, long def) {
        try { return Long.parseLong(q.get(k)); } catch (Exception e) { return def; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? part : part.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "application/json; charset=utf-8");
    }

    private static void send(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
