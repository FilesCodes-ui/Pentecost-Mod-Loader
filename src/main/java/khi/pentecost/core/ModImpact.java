package khi.pentecost.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Measures what each mod costs the game while the player plays.
 *
 * Java's Flight Recorder samples the render thread and the server thread every PERIOD_MS without stopping the game.
 * Each sample is charged to the first mod found from the top of the stack: the mod's own classes, or Mixin code a mod
 * merged into a game class. Library mods (Kotlin and the like) pass the charge on to the mod that called them.
 * Samples times the period, divided by frames (or ticks), gives milliseconds per frame (or per tick) for each mod.
 * Code no mod owns is charged to "minecraft" (the game and Forge).
 *
 * A mod that measured as costly after enough play gets a flag, saved per mod and version, which the Mods screen shows.
 * Mods that draw the world in place of the game (Embeddium, Oculus) are expected to cost frame time and never get one.
 */
public final class ModImpact {
    public interface Owners {
        /** The mod that owns this code, or null for the game and the JDK. */
        String owner(String className, String method, String descriptor);

        /** True for libraries that should pass their cost on to the mod that called them. */
        boolean passThrough(String mod);
    }

    public record Row(String mod, double msPerFrame, double msPerTick, boolean renderer, boolean flagged) {}

    public record Flag(String version, double msPerFrame, double msPerTick, long at) {}

    public static final long PERIOD_MS = 10;
    public static final String GAME = "minecraft";
    /** Mods that replace how the world is drawn. Their frame time is the drawing itself. */
    static final Set<String> RENDERERS = Set.of("embeddium", "rubidium", "sodium", "oculus", "iris", "flywheel");
    /** Enough play to trust the numbers. */
    static final double MIN_SECONDS = 30;
    static final long MIN_FRAMES = 300;
    static final double FLAG_MS_FRAME = 3.0;
    static final double FLAG_FRAME_SHARE = 0.10;
    static final double FLAG_MS_TICK = 8.0;

    private static final class Session {
        final Map<String, LongAdder> frame = new ConcurrentHashMap<>();
        final Map<String, LongAdder> tick = new ConcurrentHashMap<>();
        final LongAdder frames = new LongAdder();
        final LongAdder ticks = new LongAdder();
        /** Per mod: its hottest own methods, and the game code that called into it. Keys are "F mod" or "T mod". */
        final Map<String, Map<String, LongAdder>> where = new ConcurrentHashMap<>();
        final Map<String, Map<String, LongAdder>> via = new ConcurrentHashMap<>();
        final long started = System.nanoTime();
        final String renderThread;
        final String serverThread;
        volatile Owners owners;
        volatile RecordingStream stream;
        volatile String error = "";

        Session(String renderThread, String serverThread) {
            this.renderThread = renderThread;
            this.serverThread = serverThread;
        }
    }

    private static volatile Session session;
    private static volatile Session last;
    private static Map<String, Flag> flags;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModImpact() {}

    public static boolean running() {
        return session != null;
    }

    /** True while the mod index is still being read, before sampling starts. */
    public static boolean preparing() {
        Session s = session;
        return s != null && s.stream == null && s.error.isEmpty();
    }

    public static String error() {
        Session s = session != null ? session : last;
        return s == null ? "" : s.error;
    }

    public static double seconds() {
        Session s = session != null ? session : last;
        return s == null ? 0 : (System.nanoTime() - s.started) / 1e9;
    }

    public static long frames() {
        Session s = session != null ? session : last;
        return s == null ? 0 : s.frames.sum();
    }

    /** Starts measuring. The owner index is built on a background thread first. */
    public static synchronized void start(Supplier<Owners> owners) {
        start(owners, "Render thread", "Server thread");
    }

    static synchronized void start(Supplier<Owners> owners, String renderThread, String serverThread) {
        if (session != null) {
            return;
        }
        Session s = new Session(renderThread, serverThread);
        session = s;
        Thread t = new Thread(() -> {
            try {
                s.owners = owners.get();
                RecordingStream rs = new RecordingStream();
                rs.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(PERIOD_MS));
                rs.onEvent("jdk.ExecutionSample", e -> sample(s, e));
                rs.setReuse(true);
                rs.setOrdered(false);
                if (session != s) {
                    rs.close();
                    return;
                }
                s.stream = rs;
                rs.startAsync();
            } catch (Throwable e) {
                s.error = "The Flight Recorder could not start: " + e;
                khi.pentecost.boot.Log.error("Mod impact: " + s.error);
            }
        }, "pentecost-impact-start");
        t.setDaemon(true);
        t.start();
    }

    /** Stops measuring and returns the measurement. loaded: mod id to version, for the flags. */
    public static synchronized List<Row> stop(Map<String, String> loaded, Path flagFile) {
        Session s = session;
        if (s == null) {
            return List.of();
        }
        session = null;
        last = s;
        RecordingStream rs = s.stream;
        if (rs != null) {
            try {
                rs.close();
            } catch (RuntimeException ignored) {
                // Closing is best effort.
            }
        }
        List<Row> rows = rows(s);
        if (flagFile != null) {
            try {
                Files.createDirectories(flagFile.getParent());
                Files.writeString(flagFile.resolveSibling("mod-impact-detail.txt"), detailReport(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                khi.pentecost.boot.Log.warn("Mod impact: could not save the detail: " + e);
            }
        }
        if (flagFile != null && enough(s)) {
            Map<String, Flag> all = new HashMap<>(loadFlags(flagFile));
            long now = System.currentTimeMillis();
            for (Map.Entry<String, String> m : loaded.entrySet()) {
                all.remove(m.getKey());
            }
            for (Row r : rows) {
                if (r.flagged() && loaded.containsKey(r.mod())) {
                    all.put(r.mod(), new Flag(loaded.get(r.mod()), r.msPerFrame(), r.msPerTick(), now));
                }
            }
            saveFlags(flagFile, all);
        }
        return rows;
    }

    public static void frame() {
        Session s = session;
        if (s != null) {
            s.frames.increment();
        }
    }

    public static void tick() {
        Session s = session;
        if (s != null) {
            s.ticks.increment();
        }
    }

    private static void sample(Session s, RecordedEvent e) {
        Owners owners = s.owners;
        if (owners == null) {
            return;
        }
        RecordedThread t = e.getThread("sampledThread");
        if (t == null) {
            return;
        }
        String name = t.getJavaName();
        boolean render = s.renderThread.equals(name);
        if (!render && !s.serverThread.equals(name)) {
            return;
        }
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) {
            return;
        }
        List<String[]> frames = new ArrayList<>();
        for (RecordedFrame f : st.getFrames()) {
            if (f.isJavaFrame() && f.getMethod() != null && f.getMethod().getType() != null) {
                frames.add(new String[]{f.getMethod().getType().getName(), f.getMethod().getName(), f.getMethod().getDescriptor()});
            }
        }
        String mod = charge(frames, owners);
        (render ? s.frame : s.tick).computeIfAbsent(mod, k -> new LongAdder()).increment();
        if (!GAME.equals(mod)) {
            String[] d = detail(frames, owners, mod);
            String key = (render ? "F " : "T ") + mod;
            s.where.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).computeIfAbsent(d[0], k -> new LongAdder()).increment();
            s.via.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).computeIfAbsent(d[1], k -> new LongAdder()).increment();
        }
    }

    /**
     * Where a sample landed inside a mod: [0] the mod's first method from the top (where the time is spent),
     * [1] the code just below the mod's lowest method (what called into the mod: mob AI, an event, a block entity).
     */
    static String[] detail(List<String[]> frames, Owners owners, String mod) {
        String top = null;
        int deepest = -1;
        for (int i = 0; i < frames.size(); i++) {
            String[] f = frames.get(i);
            if (mod.equals(owners.owner(f[0], f[1], f[2]))) {
                if (top == null) {
                    top = shortName(f);
                }
                deepest = i;
            }
        }
        if (top == null) {
            top = frames.isEmpty() ? "(unknown)" : shortName(frames.get(0));
        }
        String caller = deepest >= 0 && deepest + 1 < frames.size() ? shortName(frames.get(deepest + 1)) : "(thread start)";
        return new String[]{top, caller};
    }

    private static String shortName(String[] f) {
        String c = f[0];
        int dot = c.lastIndexOf('.');
        return (dot >= 0 ? c.substring(dot + 1) : c) + "." + f[1];
    }

    /** A text breakdown of the costliest mods: their hottest methods and what called them. */
    public static String detailReport() {
        Session s = session != null ? session : last;
        if (s == null) {
            return "No measurement yet.";
        }
        StringBuilder b = new StringBuilder();
        b.append("Mod impact detail, ").append(String.format("%.0f", seconds())).append(" s, ")
                .append(s.frames.sum()).append(" frames, ").append(s.ticks.sum()).append(" ticks\n");
        for (String side : List.of("T", "F")) {
            long count = Math.max(1, side.equals("T") ? s.ticks.sum() : s.frames.sum());
            Map<String, LongAdder> totals = side.equals("T") ? s.tick : s.frame;
            List<Map.Entry<String, LongAdder>> mods = new ArrayList<>(totals.entrySet());
            mods.removeIf(e -> GAME.equals(e.getKey()));
            mods.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
            b.append("\n== ").append(side.equals("T") ? "Per tick (server)" : "Per frame (client)").append(" ==\n");
            for (Map.Entry<String, LongAdder> m : mods.subList(0, Math.min(6, mods.size()))) {
                String key = side + " " + m.getKey();
                b.append("\n").append(m.getKey()).append(String.format("  %.2f ms", m.getValue().sum() * (double) PERIOD_MS / count)).append("\n");
                b.append("  called from:\n");
                top(b, s.via.get(key), count);
                b.append("  time spent in:\n");
                top(b, s.where.get(key), count);
            }
        }
        return b.toString();
    }

    private static void top(StringBuilder b, Map<String, LongAdder> m, long count) {
        if (m == null) {
            return;
        }
        List<Map.Entry<String, LongAdder>> l = new ArrayList<>(m.entrySet());
        l.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        for (Map.Entry<String, LongAdder> e : l.subList(0, Math.min(8, l.size()))) {
            b.append(String.format("    %6.2f ms  %s%n", e.getValue().sum() * (double) PERIOD_MS / count, e.getKey()));
        }
    }

    /** The mod a stack is charged to: the first owner from the top, passing over libraries. */
    static String charge(List<String[]> frames, Owners owners) {
        String library = null;
        for (String[] f : frames) {
            String mod = owners.owner(f[0], f[1], f[2]);
            if (mod == null) {
                continue;
            }
            if (owners.passThrough(mod)) {
                if (library == null) {
                    library = mod;
                }
                continue;
            }
            return mod;
        }
        return library != null ? library : GAME;
    }

    public static List<Row> rows() {
        Session s = session != null ? session : last;
        return s == null ? List.of() : rows(s);
    }

    private static boolean enough(Session s) {
        return (System.nanoTime() - s.started) / 1e9 >= MIN_SECONDS && s.frames.sum() >= MIN_FRAMES;
    }

    static List<Row> rows(Session s) {
        long frames = Math.max(1, s.frames.sum());
        long ticks = Math.max(1, s.ticks.sum());
        double frameMs = (System.nanoTime() - s.started) / 1e6 / frames;
        boolean trust = enough(s);
        Set<String> mods = new java.util.HashSet<>(s.frame.keySet());
        mods.addAll(s.tick.keySet());
        List<Row> out = new ArrayList<>();
        for (String m : mods) {
            double f = s.frame.getOrDefault(m, new LongAdder()).sum() * (double) PERIOD_MS / frames;
            double t = s.tick.getOrDefault(m, new LongAdder()).sum() * (double) PERIOD_MS / ticks;
            if (s.ticks.sum() == 0) {
                t = 0;
            }
            boolean renderer = RENDERERS.contains(m);
            boolean flagged = trust && !renderer && !GAME.equals(m) && !"pentecost".equals(m)
                    && ((f >= FLAG_MS_FRAME && f >= frameMs * FLAG_FRAME_SHARE) || t >= FLAG_MS_TICK);
            out.add(new Row(m, f, t, renderer, flagged));
        }
        out.sort(Comparator.comparingDouble((Row r) -> r.msPerFrame() + r.msPerTick()).reversed());
        return out;
    }

    // ------------------------------------------------------------------ flags on disk

    public static synchronized Map<String, Flag> loadFlags(Path file) {
        if (flags != null) {
            return flags;
        }
        Map<String, Flag> read = new HashMap<>();
        try {
            if (Files.isRegularFile(file)) {
                Map<String, Flag> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Flag>>() {}.getType());
                if (m != null) {
                    m.forEach((k, v) -> {
                        if (k != null && v != null && v.version() != null) {
                            read.put(k, v);
                        }
                    });
                }
            }
        } catch (IOException | RuntimeException e) {
            // A damaged file means no flags, never a crash.
        }
        flags = Map.copyOf(read);
        return flags;
    }

    private static synchronized void saveFlags(Path file, Map<String, Flag> all) {
        flags = Map.copyOf(all);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(all), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            khi.pentecost.boot.Log.warn("Mod impact: could not save " + file + ": " + e);
        }
    }

    /** The flag for this mod and version, or null. A flag for another version of the mod does not count. */
    public static Flag flag(Path file, String mod, String version) {
        Flag f = loadFlags(file).get(mod);
        return f != null && f.version().equals(version) ? f : null;
    }

    /** For tests: forget cached flags. */
    static synchronized void forgetFlags() {
        flags = null;
    }
}
