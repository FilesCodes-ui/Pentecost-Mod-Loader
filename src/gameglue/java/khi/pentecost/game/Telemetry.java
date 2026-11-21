package khi.pentecost.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.management.GarbageCollectionNotificationInfo;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class Telemetry {
    private static final long MAX_BYTES = 60L * 1024 * 1024;
    private static final int STACK_DEPTH = 40;
    private static final String[] SKIP = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.lwjgl.", "com.mojang.",
            "net.minecraft.", "it.unimi.", "com.google.", "org.apache.", "io.netty.", "org.objectweb.",
            "org.spongepowered.", "net.minecraftforge.", "cpw.mods.", "org.slf4j.", "org.apache.logging."};

    private static boolean inited;
    private static volatile boolean enabled;
    private static final float[] frames = new float[4000];
    private static int count;
    private static double workSum;
    private static long windowStart;
    private static long seq;
    private static double lastCostUs;
    private static int failures;
    private static Path dir;
    private static String gpu;
    private static boolean threadsStarted;
    private static String prevDimension;
    private static String prevScreen;
    private static String prevPack;
    private static boolean prevFocused = true;
    private static boolean prevLevel;
    private static long lastGameTime;

    private static volatile Thread renderThread;
    private static volatile long frameStart;
    private static volatile long serverId;
    private static volatile long[] extraIds = new long[0];
    private static volatile JsonObject systemInfo;
    private static volatile JsonObject pendingInfo;

    private static final Object LOCK = new Object();
    private static final Map<String, Integer> samples = new HashMap<>();
    private static final Map<String, Integer> states = new HashMap<>();
    private static final Map<String, Integer> hot = new HashMap<>();
    private static final Map<String, Integer> owners = new HashMap<>();
    private static final List<String> slow = new ArrayList<>();
    private static final ConcurrentLinkedQueue<String> gcEvents = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean busy = new AtomicBoolean();
    private static ExecutorService pool;

    private static boolean started;
    private static long written;
    private static final Map<Long, Long> lastCpu = new HashMap<>();
    private static final Map<Long, Long> lastAlloc = new HashMap<>();
    private static final Map<String, long[]> lastGc = new HashMap<>();
    private static long lastJitMs = -1;
    private static long lastClasses = -1;

    private Telemetry() {}

    static boolean on() {
        if (!inited) {
            inited = true;
            enabled = wanted();
        }
        return enabled;
    }

    private static boolean wanted() {
        return System.getenv("PENTECOST_NO_TELEMETRY") == null && Settings.on("debug.telemetry") && failures < 5;
    }

    static void frame(Minecraft mc, double instantMs, double workMs) {
        if (!on()) {
            return;
        }
        long now = System.nanoTime();
        renderThread = Thread.currentThread();
        frameStart = now;
        if (windowStart == 0) {
            windowStart = now;
            return;
        }
        if (count < frames.length) {
            frames[count] = (float) instantMs;
        }
        count++;
        workSum += workMs;
        if (now - windowStart < 1_000_000_000L) {
            return;
        }
        try {
            snapshot(mc, now);
        } catch (RuntimeException | LinkageError e) {
            failures++;
            Log.warn("Telemetry snapshot failed (" + failures + "): " + e);
            enabled = wanted();
            count = 0;
            workSum = 0;
            windowStart = now;
        }
    }

    private static void snapshot(Minecraft mc, long now) {
        long t0 = System.nanoTime();
        double seconds = (now - windowStart) / 1e9;
        int total = count;
        int n = Math.min(total, frames.length);
        float[] copy = Arrays.copyOf(frames, n);
        double work = total == 0 ? 0 : workSum / total;
        count = 0;
        workSum = 0;
        windowStart = now;
        seq++;
        enabled = wanted();
        if (dir == null) {
            dir = mc.gameDirectory.toPath().resolve("pentecost-telemetry");
        }
        if (!threadsStarted) {
            threadsStarted = true;
            startThreads();
            pendingInfo = staticInfo(mc);
        }

        JsonObject root = new JsonObject();
        root.addProperty("t", System.currentTimeMillis());
        root.addProperty("seq", seq);
        root.addProperty("windowS", round(seconds, 3));
        root.addProperty("telemetryCostUs", Math.round(lastCostUs));

        JsonObject f = new JsonObject();
        f.addProperty("count", total);
        f.addProperty("fps", round(total / seconds, 1));
        f.addProperty("workMs", round(work, 2));
        if (n > 0) {
            float[] sorted = copy.clone();
            Arrays.sort(sorted);
            int o12 = 0;
            int o16 = 0;
            int o25 = 0;
            for (float v : copy) {
                if (v > 12) {
                    o12++;
                }
                if (v > 16.7f) {
                    o16++;
                }
                if (v > 25) {
                    o25++;
                }
            }
            f.addProperty("avgMs", round(seconds * 1000 / total, 2));
            f.addProperty("minMs", round(sorted[0], 1));
            f.addProperty("p50Ms", round(sorted[n / 2], 1));
            f.addProperty("p95Ms", round(sorted[Math.min(n - 1, (int) (n * 0.95))], 1));
            f.addProperty("p99Ms", round(sorted[Math.min(n - 1, (int) (n * 0.99))], 1));
            f.addProperty("maxMs", round(sorted[n - 1], 1));
            f.addProperty("over12ms", o12);
            f.addProperty("over16ms", o16);
            f.addProperty("over25ms", o25);
            JsonArray ms = new JsonArray();
            for (float v : copy) {
                ms.add(round(v, 1));
            }
            f.add("ms", ms);
        }
        root.add("frames", f);

        PerfDips.drainSecond(root);
        JsonArray events = new JsonArray();
        root.add("game", gameInfo(mc, events));
        root.add("events", events);
        root.add("sampler", drainSampler());

        JsonObject hud = new JsonObject();
        JsonObject cur = null;
        String title = null;
        for (String[] r : PerfHud.telemetryRows(mc)) {
            if (!r[0].equals(title)) {
                title = r[0];
                cur = new JsonObject();
                hud.add(title, cur);
            }
            cur.addProperty(r[1], r[2]);
        }
        root.add("hud", hud);

        lastCostUs = (System.nanoTime() - t0) / 1000.0;

        if (!busy.compareAndSet(false, true)) {
            return;
        }
        Path out = dir;
        try {
            pool().execute(() -> {
                try {
                    finish(root, out);
                } catch (IOException | RuntimeException | LinkageError e) {
                    failures++;
                    Log.warn("Telemetry write failed (" + failures + "): " + e);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false);
        }
    }

    private static JsonObject gameInfo(Minecraft mc, JsonArray events) {
        JsonObject g = new JsonObject();
        if (gpu == null) {
            try {
                gpu = com.mojang.blaze3d.platform.GlUtil.getRenderer() + " | "
                        + com.mojang.blaze3d.platform.GlUtil.getVendor() + " | "
                        + com.mojang.blaze3d.platform.GlUtil.getOpenGLVersion();
            } catch (RuntimeException | LinkageError e) {
                gpu = "unknown";
            }
        }
        g.addProperty("gpu", gpu);
        g.addProperty("java", System.getProperty("java.version"));
        g.addProperty("cores", Runtime.getRuntime().availableProcessors());
        g.addProperty("renderDistance", mc.options.renderDistance().get());
        g.addProperty("simulationDistance", mc.options.simulationDistance().get());
        g.addProperty("fpsLimit", mc.options.framerateLimit().get());
        g.addProperty("vsync", mc.options.enableVsync().get());
        try {
            g.addProperty("graphics", String.valueOf(mc.options.graphicsMode().get()));
            g.addProperty("clouds", String.valueOf(mc.options.cloudStatus().get()));
            g.addProperty("ambientOcclusion", String.valueOf(mc.options.ambientOcclusion().get()));
            g.addProperty("biomeBlend", String.valueOf(mc.options.biomeBlendRadius().get()));
            g.addProperty("entityDistanceScaling", String.valueOf(mc.options.entityDistanceScaling().get()));
            g.addProperty("particleSetting", String.valueOf(mc.options.particles().get()));
            g.addProperty("mipmaps", String.valueOf(mc.options.mipmapLevels().get()));
        } catch (RuntimeException | LinkageError e) {
            g.addProperty("optionsError", String.valueOf(e));
        }
        String pack = "none";
        try {
            pack = ExternalOptions.shadersOn() ? String.valueOf(ExternalOptions.currentPack()) : "off";
        } catch (RuntimeException | LinkageError e) {
            pack = "unknown";
        }
        g.addProperty("shaderPack", pack);
        if (prevPack != null && !prevPack.equals(pack)) {
            events.add("shader pack " + prevPack + " -> " + pack);
        }
        prevPack = pack;

        boolean focused = mc.isWindowActive();
        g.addProperty("window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        g.addProperty("focused", focused);
        if (focused != prevFocused) {
            events.add(focused ? "window gained focus" : "window lost focus");
        }
        prevFocused = focused;
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        g.addProperty("screen", mc.screen == null ? "none" : mc.screen.getClass().getName());
        if (prevScreen != null && !prevScreen.equals(screen)) {
            events.add("screen " + prevScreen + " -> " + screen);
        }
        prevScreen = screen;
        g.addProperty("singleplayer", mc.getSingleplayerServer() != null);
        boolean hasLevel = mc.level != null && mc.player != null;
        if (hasLevel != prevLevel) {
            events.add(hasLevel ? "level loaded" : "level unloaded");
        }
        prevLevel = hasLevel;
        if (hasLevel) {
            String dim = mc.level.dimension().location().toString();
            g.addProperty("dimension", dim);
            if (prevDimension != null && !prevDimension.equals(dim)) {
                events.add("dimension " + prevDimension + " -> " + dim);
            }
            prevDimension = dim;
            g.addProperty("x", round(mc.player.getX(), 1));
            g.addProperty("y", round(mc.player.getY(), 1));
            g.addProperty("z", round(mc.player.getZ(), 1));
            g.addProperty("yaw", round(mc.player.getYRot(), 0));
            g.addProperty("pitch", round(mc.player.getXRot(), 0));
            g.addProperty("dayTime", mc.level.getDayTime() % 24000);
            long gameTime = mc.level.getGameTime();
            g.addProperty("gameTime", gameTime);
            g.addProperty("ticksThisSecond", lastGameTime == 0 ? 0 : gameTime - lastGameTime);
            lastGameTime = gameTime;
            g.addProperty("raining", mc.level.isRaining());
            int entities = 0;
            for (Entity e : mc.level.entitiesForRendering()) {
                entities++;
            }
            g.addProperty("entities", entities);
            g.addProperty("loadedChunks", mc.level.getChunkSource().getLoadedChunksCount());
            g.addProperty("particles", mc.particleEngine.countParticles());
        }
        return g;
    }

    private static JsonObject staticInfo(Minecraft mc) {
        JsonObject info = new JsonObject();
        info.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        info.addProperty("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version") + " "
                + System.getProperty("java.vm.name"));
        info.addProperty("processors", Runtime.getRuntime().availableProcessors());
        info.addProperty("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        JsonArray args = new JsonArray();
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            args.add(a);
        }
        info.add("jvmArguments", args);
        JsonArray gcNames = new JsonArray();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcNames.add(b.getName());
        }
        info.add("collectors", gcNames);
        JsonObject env = new JsonObject();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith("PENTECOST")) {
                env.addProperty(e.getKey(), e.getValue());
            }
        }
        info.add("pentecostEnv", env);
        JsonArray mods = new JsonArray();
        try {
            for (ModInfo m : PentecostRuntime.mods) {
                mods.add(m.id() + " (" + m.loader() + ")");
            }
        } catch (RuntimeException | LinkageError e) {
            mods.add("error " + e);
        }
        info.add("mods", mods);
        info.addProperty("startedAt", System.currentTimeMillis());
        return info;
    }

    private static ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Pentecost telemetry");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        return pool;
    }

    private static void startThreads() {
        try {
            for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (b instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener((notification, handback) -> {
                        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                                .equals(notification.getType()) || gcEvents.size() > 60) {
                            return;
                        }
                        GarbageCollectionNotificationInfo gi =
                                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                        long before = 0;
                        for (MemoryUsage u : gi.getGcInfo().getMemoryUsageBeforeGc().values()) {
                            before += u.getUsed();
                        }
                        long after = 0;
                        for (MemoryUsage u : gi.getGcInfo().getMemoryUsageAfterGc().values()) {
                            after += u.getUsed();
                        }
                        gcEvents.add(gi.getGcName() + " / " + gi.getGcAction() + " / " + gi.getGcCause() + ": "
                                + gi.getGcInfo().getDuration() + " ms, heap " + before / (1024 * 1024) + " -> "
                                + after / (1024 * 1024) + " MB");
                    }, null, null);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Telemetry could not watch GC: " + e);
        }
        if (Settings.on("debug.telemetry.stacks")) {
            Thread s = new Thread(Telemetry::samplerLoop, "Pentecost sampler");
            s.setDaemon(true);
            s.setPriority(Thread.MIN_PRIORITY);
            s.start();
        }
        Thread sys = new Thread(Telemetry::systemLoop, "Pentecost system sampler");
        sys.setDaemon(true);
        sys.setPriority(Thread.MIN_PRIORITY);
        sys.start();
    }

    private static void samplerLoop() {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        int tick = 0;
        while (true) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
            Thread rt = renderThread;
            if (!enabled || rt == null) {
                continue;
            }
            try {
                long inFrame = System.nanoTime() - frameStart;
                take("render", tm.getThreadInfo(rt.getId(), STACK_DEPTH), inFrame);
                long sid = serverId;
                if (sid != 0) {
                    take("server", tm.getThreadInfo(sid, STACK_DEPTH), 0);
                }
                long[] ids = extraIds;
                if (++tick % 5 == 0 && ids.length > 0) {
                    for (ThreadInfo ti : tm.getThreadInfo(ids, STACK_DEPTH)) {
                        if (ti != null && ti.getThreadState() == Thread.State.RUNNABLE) {
                            take(groupName(ti.getThreadName()), ti, 0);
                        }
                    }
                }
            } catch (RuntimeException e) {
            }
        }
    }

    private static void take(String who, ThreadInfo info, long inFrameNanos) {
        if (info == null) {
            return;
        }
        StackTraceElement[] st = info.getStackTrace();
        if (st.length == 0) {
            return;
        }
        String top = st[0].getClassName() + "." + st[0].getMethodName();
        String owner = ownerOf(st);
        synchronized (LOCK) {
            samples.merge(who, 1, Integer::sum);
            states.merge(who + " " + info.getThreadState(), 1, Integer::sum);
            hot.merge(who + " " + top, 1, Integer::sum);
            owners.merge(who + " " + owner, 1, Integer::sum);
            if (inFrameNanos > 18_000_000L && who.equals("render") && slow.size() < 8) {
                StringBuilder sb = new StringBuilder(String.format("%.0f ms into the frame, %s: ", inFrameNanos / 1e6,
                        info.getThreadState()));
                for (int i = 0; i < Math.min(st.length, 26); i++) {
                    if (i > 0) {
                        sb.append(" < ");
                    }
                    String cn = st[i].getClassName();
                    sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName())
                            .append(':').append(st[i].getLineNumber());
                }
                slow.add(sb.toString());
            }
        }
    }

    private static String ownerOf(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String m = e.getMethodName();
            if (m.startsWith("handler$") || m.startsWith("redirect$") || m.startsWith("wrapOperation$")
                    || m.startsWith("wrapMethod$") || m.startsWith("modify") && m.contains("$")) {
                String[] p = m.split("\\$");
                if (p.length >= 3) {
                    return "mixin:" + p[2];
                }
            }
            String c = e.getClassName();
            boolean skip = false;
            for (String prefix : SKIP) {
                if (c.startsWith(prefix)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                String[] parts = c.split("\\.");
                return parts.length >= 3 ? parts[0] + "." + parts[1] + "." + parts[2] : c;
            }
        }
        return "vanilla/jdk";
    }

    private static JsonObject drainSampler() {
        JsonObject o = new JsonObject();
        synchronized (LOCK) {
            JsonObject s = new JsonObject();
            for (Map.Entry<String, Integer> e : samples.entrySet()) {
                s.addProperty(e.getKey(), e.getValue());
            }
            o.add("samples", s);
            JsonObject st = new JsonObject();
            for (Map.Entry<String, Integer> e : states.entrySet()) {
                st.addProperty(e.getKey(), e.getValue());
            }
            o.add("states", st);
            o.add("hot", top(hot, 16));
            o.add("owners", top(owners, 16));
            JsonArray sl = new JsonArray();
            for (String x : slow) {
                sl.add(x);
            }
            o.add("slowStacks", sl);
            samples.clear();
            states.clear();
            hot.clear();
            owners.clear();
            slow.clear();
        }
        return o;
    }

    private static JsonArray top(Map<String, Integer> map, int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            JsonObject one = new JsonObject();
            one.addProperty("what", list.get(i).getKey());
            one.addProperty("samples", list.get(i).getValue());
            out.add(one);
        }
        return out;
    }

    private static void systemLoop() {
        Map<Long, Long> last = new HashMap<>();
        long lastT = 0;
        long self = ProcessHandle.current().pid();
        boolean smi = true;
        boolean smiFull = true;
        boolean typeperf = true;
        while (true) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            if (!enabled) {
                continue;
            }
            JsonObject s = new JsonObject();
            try {
                long t = System.nanoTime();
                Map<Long, Long> seen = new HashMap<>();
                Map<String, double[]> byName = new HashMap<>();
                final Map<Long, Long> prev = last;
                ProcessHandle.allProcesses().forEach(p -> {
                    ProcessHandle.Info info = p.info();
                    Optional<Duration> d = info.totalCpuDuration();
                    if (d.isEmpty()) {
                        return;
                    }
                    long ns = d.get().toNanos();
                    seen.put(p.pid(), ns);
                    Long before = prev.get(p.pid());
                    if (before == null || p.pid() == self) {
                        return;
                    }
                    String name = info.command().map(Telemetry::base).orElse("pid " + p.pid());
                    double[] acc = byName.computeIfAbsent(name, k -> new double[2]);
                    acc[0] += (ns - before) / 1e6;
                    acc[1]++;
                });
                if (lastT != 0) {
                    double windowMs = (t - lastT) / 1e6;
                    List<Map.Entry<String, double[]>> list = new ArrayList<>(byName.entrySet());
                    list.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
                    JsonArray procs = new JsonArray();
                    for (int i = 0; i < Math.min(8, list.size()); i++) {
                        JsonObject one = new JsonObject();
                        one.addProperty("name", list.get(i).getKey());
                        one.addProperty("processes", (int) list.get(i).getValue()[1]);
                        one.addProperty("percentOfOneCore", round(list.get(i).getValue()[0] / windowMs * 100, 1));
                        procs.add(one);
                    }
                    s.add("otherProcesses", procs);
                }
                last = seen;
                lastT = t;
            } catch (RuntimeException e) {
                s.addProperty("processError", String.valueOf(e));
            }
            if (smi) {
                try {
                    List<String> fields = smiFull
                            ? List.of("utilization.gpu", "utilization.memory", "memory.used", "memory.total",
                                    "temperature.gpu", "power.draw", "clocks.gr", "clocks.max.gr", "clocks.mem",
                                    "pstate", "clocks_throttle_reasons.active")
                            : List.of("utilization.gpu", "utilization.memory", "memory.used", "memory.total",
                                    "temperature.gpu", "power.draw", "clocks.gr", "clocks.mem", "pstate");
                    List<String> out = run(List.of("nvidia-smi", "--query-gpu=" + String.join(",", fields),
                            "--format=csv,noheader,nounits"), 4);
                    if (out.get(0).equals("exit=0") && out.size() > 1) {
                        String[] v = out.get(1).split(",\\s*");
                        JsonObject gp = new JsonObject();
                        for (int i = 0; i < fields.size() && i < v.length; i++) {
                            gp.addProperty(fields.get(i), v[i].trim());
                        }
                        s.add("gpu", gp);
                    } else if (smiFull) {
                        smiFull = false;
                    } else {
                        smi = false;
                        s.addProperty("gpuError", String.join(" | ", out));
                    }
                } catch (IOException | RuntimeException e) {
                    smi = false;
                    s.addProperty("gpuError", String.valueOf(e));
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (typeperf) {
                try {
                    List<String> out = run(List.of("typeperf", "\\Processor Information(_Total)\\% Processor Performance",
                            "\\Processor Information(_Total)\\Processor Frequency", "-sc", "1"), 6);
                    JsonObject cp = new JsonObject();
                    for (String line : out) {
                        if (line.startsWith("\"") && !line.contains("PDH-CSV")) {
                            String[] v = line.split("\",\"");
                            if (v.length >= 3) {
                                cp.addProperty("performancePercent", v[1].replace("\"", "").trim());
                                cp.addProperty("frequencyMHz", v[2].replace("\"", "").trim());
                            }
                        }
                    }
                    if (cp.size() == 0) {
                        typeperf = false;
                        s.addProperty("cpuSpeedError", String.join(" | ", out));
                    } else {
                        s.add("cpuSpeed", cp);
                    }
                } catch (IOException | RuntimeException e) {
                    typeperf = false;
                    s.addProperty("cpuSpeedError", String.valueOf(e));
                } catch (InterruptedException e) {
                    return;
                }
            }
            s.addProperty("sampledAt", System.currentTimeMillis());
            systemInfo = s;
        }
    }

    private static String base(String path) {
        int i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static List<String> run(List<String> cmd, long timeoutSeconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                lines.add(l);
            }
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            lines.add(0, "exit=timeout");
        } else {
            lines.add(0, "exit=" + p.exitValue());
        }
        return lines;
    }

    private static String groupName(String name) {
        return name.replaceAll("[\\s#_-]*\\d+$", "");
    }

    private static void finish(JsonObject root, Path out) throws IOException {
        double windowS = Math.max(0.1, root.get("windowS").getAsDouble());

        JsonObject jvm = new JsonObject();
        Runtime rt = Runtime.getRuntime();
        jvm.addProperty("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        jvm.addProperty("heapCommittedMb", rt.totalMemory() / (1024 * 1024));
        jvm.addProperty("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        JsonObject gcs = new JsonObject();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = b.getCollectionCount();
            long ms = b.getCollectionTime();
            long[] prev = lastGc.put(b.getName(), new long[]{c, ms});
            if (prev != null) {
                JsonObject one = new JsonObject();
                one.addProperty("count", c - prev[0]);
                one.addProperty("ms", ms - prev[1]);
                gcs.add(b.getName(), one);
            }
        }
        jvm.add("gc", gcs);
        JsonArray events = new JsonArray();
        String ev;
        while ((ev = gcEvents.poll()) != null) {
            events.add(ev);
        }
        jvm.add("gcEvents", events);
        JsonObject pools = new JsonObject();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = p.getUsage();
            if (u != null) {
                pools.addProperty(p.getName(), u.getUsed() / (1024 * 1024));
            }
        }
        jvm.add("poolsUsedMb", pools);
        JsonObject buffers = new JsonObject();
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            buffers.addProperty(b.getName(), b.getMemoryUsed() / (1024 * 1024));
        }
        jvm.add("bufferPoolsMb", buffers);
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        if (jit != null && jit.isCompilationTimeMonitoringSupported()) {
            long j = jit.getTotalCompilationTime();
            jvm.addProperty("jitMsThisSecond", lastJitMs < 0 ? 0 : j - lastJitMs);
            lastJitMs = j;
        }
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        long classes = cl.getTotalLoadedClassCount();
        jvm.addProperty("classesLoaded", cl.getLoadedClassCount());
        jvm.addProperty("classesLoadedThisSecond", lastClasses < 0 ? 0 : classes - lastClasses);
        lastClasses = classes;
        root.add("jvm", jvm);

        JsonObject cpu = new JsonObject();
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            cpu.addProperty("processPercent", round(os.getProcessCpuLoad() * 100, 1));
            cpu.addProperty("systemPercent", round(os.getCpuLoad() * 100, 1));
            cpu.addProperty("physicalMemoryTotalMb", os.getTotalMemorySize() / (1024 * 1024));
            cpu.addProperty("physicalMemoryFreeMb", os.getFreeMemorySize() / (1024 * 1024));
            cpu.addProperty("committedVirtualMb", os.getCommittedVirtualMemorySize() / (1024 * 1024));
            cpu.addProperty("swapFreeMb", os.getFreeSwapSpaceSize() / (1024 * 1024));
        } catch (RuntimeException | LinkageError e) {
            cpu.addProperty("error", String.valueOf(e));
        }
        root.add("cpu", cpu);
        JsonObject sys = systemInfo;
        if (sys != null) {
            root.add("system", sys.deepCopy());
        }

        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean stm = tm instanceof com.sun.management.ThreadMXBean x ? x : null;
        JsonArray threads = new JsonArray();
        JsonArray groupsOut = new JsonArray();
        JsonArray allocOut = new JsonArray();
        try {
            long[] ids = tm.getAllThreadIds();
            ThreadInfo[] infos = tm.getThreadInfo(ids);
            long[] alloc = stm == null ? null : stm.getThreadAllocatedBytes(ids);
            List<Object[]> rows = new ArrayList<>();
            Map<String, double[]> groups = new HashMap<>();
            Map<Long, Long> seenCpu = new HashMap<>();
            Map<Long, Long> seenAlloc = new HashMap<>();
            JsonObject stateCounts = new JsonObject();
            Map<String, Integer> sc = new HashMap<>();
            double allocTotal = 0;
            List<Long> watch = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) {
                if (infos[i] == null) {
                    continue;
                }
                String name = infos[i].getThreadName();
                if ((name.startsWith("pentecost-") || name.startsWith("Worker-Main") || name.startsWith("Sound engine")
                        || name.startsWith("Chunk Render Task")) && watch.size() < 28) {
                    watch.add(ids[i]);
                }
                sc.merge(infos[i].getThreadState().name(), 1, Integer::sum);
                if ("Server thread".equals(name)) {
                    serverId = ids[i];
                }
                double cpuMs = 0;
                long cpuNs = tm.getThreadCpuTime(ids[i]);
                if (cpuNs >= 0) {
                    seenCpu.put(ids[i], cpuNs);
                    Long before = lastCpu.get(ids[i]);
                    if (before != null) {
                        cpuMs = (cpuNs - before) / 1e6 / windowS;
                    }
                }
                double allocMb = 0;
                if (alloc != null && alloc[i] >= 0) {
                    seenAlloc.put(ids[i], alloc[i]);
                    Long before = lastAlloc.get(ids[i]);
                    if (before != null) {
                        allocMb = (alloc[i] - before) / (1024.0 * 1024.0) / windowS;
                    }
                }
                allocTotal += allocMb;
                rows.add(new Object[]{name, cpuMs, allocMb});
                double[] g = groups.computeIfAbsent(groupName(name), k -> new double[3]);
                g[0]++;
                g[1] += cpuMs;
                g[2] += allocMb;
            }
            extraIds = watch.stream().mapToLong(Long::longValue).toArray();
            lastCpu.clear();
            lastCpu.putAll(seenCpu);
            lastAlloc.clear();
            lastAlloc.putAll(seenAlloc);
            for (Map.Entry<String, Integer> e : sc.entrySet()) {
                stateCounts.addProperty(e.getKey(), e.getValue());
            }
            root.add("threadStates", stateCounts);
            root.addProperty("threadCount", ids.length);
            root.addProperty("allocMbPerSecond", round(allocTotal, 1));

            rows.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            for (int i = 0; i < Math.min(15, rows.size()); i++) {
                JsonObject one = new JsonObject();
                one.addProperty("name", (String) rows.get(i)[0]);
                one.addProperty("cpuMsPerSecond", round((Double) rows.get(i)[1], 1));
                one.addProperty("allocMbPerSecond", round((Double) rows.get(i)[2], 1));
                threads.add(one);
            }
            List<Map.Entry<String, double[]>> gl = new ArrayList<>(groups.entrySet());
            gl.sort((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]));
            for (int i = 0; i < Math.min(16, gl.size()); i++) {
                JsonObject one = new JsonObject();
                one.addProperty("name", gl.get(i).getKey());
                one.addProperty("threads", (int) gl.get(i).getValue()[0]);
                one.addProperty("cpuMsPerSecond", round(gl.get(i).getValue()[1], 1));
                one.addProperty("allocMbPerSecond", round(gl.get(i).getValue()[2], 1));
                groupsOut.add(one);
            }
            gl.sort((a, b) -> Double.compare(b.getValue()[2], a.getValue()[2]));
            for (int i = 0; i < Math.min(8, gl.size()); i++) {
                if (gl.get(i).getValue()[2] < 0.5) {
                    break;
                }
                JsonObject one = new JsonObject();
                one.addProperty("name", gl.get(i).getKey());
                one.addProperty("threads", (int) gl.get(i).getValue()[0]);
                one.addProperty("allocMbPerSecond", round(gl.get(i).getValue()[2], 1));
                allocOut.add(one);
            }
        } catch (RuntimeException e) {
            root.addProperty("threadError", String.valueOf(e));
        }
        root.add("topThreads", threads);
        root.add("threadGroups", groupsOut);
        root.add("topAllocators", allocOut);

        Files.createDirectories(out);
        JsonObject info = pendingInfo;
        if (info != null) {
            pendingInfo = null;
            Files.writeString(out.resolve("info.json"), info.toString(), StandardCharsets.UTF_8);
        }
        Path history = out.resolve("history.jsonl");
        if (!started) {
            started = true;
            if (Files.exists(history)) {
                Files.move(history, out.resolve("history.prev.jsonl"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String full = root.toString();
        Path tmp = out.resolve("now.json.tmp");
        Files.writeString(tmp, full, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, out.resolve("now.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, out.resolve("now.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
        }

        String line = full;
        if (root.get("seq").getAsLong() % 10 != 0) {
            JsonElement removed = root.remove("hud");
            line = removed == null ? full : root.toString();
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(history, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        written += bytes.length;
        if (written > MAX_BYTES) {
            Files.move(history, out.resolve("history.old.jsonl"), StandardCopyOption.REPLACE_EXISTING);
            written = 0;
        }
    }

    private static double round(double v, int places) {
        double m = Math.pow(10, places);
        return Math.round(v * m) / m;
    }
}
