package khi.pentecost.core;

import khi.pentecost.boot.Dirs;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A 15 second profile of the render thread and the server thread, started by a key in the game.
 * Every few milliseconds it looks at where each thread is. A thread that waits is counted as idle.
 * It writes run\\profile.txt with, for each thread: where the time went by method, by game package and by mod.
 */
public final class RunProfiler {
    private static final int SECONDS = 15;
    private static final int EVERY_MS = 4;
    private static volatile boolean running;

    private RunProfiler() {}

    public static boolean running() {
        return running;
    }

    private static final class Stats {
        int samples;
        int idle;
        final Map<String, Integer> self = new HashMap<>();
        final Map<String, Integer> inclusive = new HashMap<>();
        final Map<String, Integer> packages = new HashMap<>();
        final Map<String, Integer> mods = new HashMap<>();
    }

    public static void start(Consumer<String> say) {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(() -> {
            try {
                run(say);
            } catch (Throwable e) {
                say.accept("The profile failed: " + e);
            } finally {
                running = false;
            }
        }, "Pentecost run profiler");
        t.setDaemon(true);
        t.start();
    }

    private static Thread find(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name)) {
                return t;
            }
        }
        return null;
    }

    private static void run(Consumer<String> say) throws Exception {
        Map<String, Thread> targets = new HashMap<>();
        for (String name : List.of("Render thread", "Server thread")) {
            Thread t = find(name);
            if (t != null) {
                targets.put(name, t);
            }
        }
        if (targets.isEmpty()) {
            say.accept("No game threads found to profile.");
            return;
        }
        say.accept("Profiling for " + SECONDS + " seconds. Keep doing what lags.");
        Map<String, Stats> all = new HashMap<>();
        long end = System.currentTimeMillis() + SECONDS * 1000L;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(EVERY_MS);
            for (Map.Entry<String, Thread> e : targets.entrySet()) {
                Thread t = e.getValue();
                Thread.State state = t.getState();
                StackTraceElement[] stack = t.getStackTrace();
                Stats s = all.computeIfAbsent(e.getKey(), k -> new Stats());
                s.samples++;
                if (stack.length == 0 || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                        || state == Thread.State.BLOCKED) {
                    s.idle++;
                    continue;
                }
                record(s, stack);
            }
        }
        StringBuilder out = new StringBuilder("Profile of " + SECONDS + " seconds, a look every " + EVERY_MS + " ms.\n"
                + "Percentages are of the thread's busy samples. A waiting thread counts as idle.\n");
        for (Map.Entry<String, Stats> e : all.entrySet()) {
            Stats s = e.getValue();
            int busy = Math.max(1, s.samples - s.idle);
            out.append(String.format(Locale.ROOT, "%n######## %s: %d samples, %d%% idle%n", e.getKey(), s.samples,
                    100 * s.idle / Math.max(1, s.samples)));
            section(out, "Where the time was spent (the method on top)", s.self, busy, 25);
            section(out, "Inside which methods (a method counts once per look)", s.inclusive, busy, 30);
            section(out, "Inside which game packages", s.packages, busy, 15);
            section(out, "Inside which mods", s.mods, busy, 15);
        }
        Files.writeString(Dirs.run().resolve("profile.txt"), out.toString());
        say.accept("Profile saved as profile.txt in your game folder. Settings, General, Game folder opens it");
    }

    private static boolean own(String c) {
        return c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("khi.pentecost.");
    }

    private static void record(Stats s, StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            if (!own(f.getClassName())) {
                s.self.merge(f.getClassName() + "." + f.getMethodName(), 1, Integer::sum);
                break;
            }
        }
        Set<String> methods = new HashSet<>();
        Set<String> packages = new HashSet<>();
        Set<String> mods = new HashSet<>();
        for (StackTraceElement f : stack) {
            String c = f.getClassName();
            if (own(c)) {
                continue;
            }
            methods.add(c + "." + f.getMethodName());
            packages.add(pkg(c));
            if (PentecostRuntime.loader != null) {
                String mod = PentecostRuntime.loader.modOf(c);
                if (mod != null) {
                    mods.add(mod);
                }
            }
        }
        methods.forEach(m -> s.inclusive.merge(m, 1, Integer::sum));
        packages.forEach(m -> s.packages.merge(m, 1, Integer::sum));
        mods.forEach(m -> s.mods.merge(m, 1, Integer::sum));
    }

    private static String pkg(String c) {
        int a = c.indexOf('.');
        int b = a < 0 ? -1 : c.indexOf('.', a + 1);
        int d = b < 0 ? -1 : c.indexOf('.', b + 1);
        int e = d < 0 ? -1 : c.indexOf('.', d + 1);
        return c.startsWith("net.minecraft.") && e > 0 ? c.substring(0, e) : d > 0 ? c.substring(0, d) : c;
    }

    private static void section(StringBuilder out, String title, Map<String, Integer> map, int busy, int limit) {
        out.append("\n== ").append(title).append('\n');
        List<Map.Entry<String, Integer>> top = new ArrayList<>(map.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(limit, top.size()); i++) {
            out.append(String.format(Locale.ROOT, "  %3d%%  %s%n", 100 * top.get(i).getValue() / busy, top.get(i).getKey()));
        }
    }
}
