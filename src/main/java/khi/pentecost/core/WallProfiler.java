package khi.pentecost.core;

import khi.pentecost.boot.Dirs;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class WallProfiler {
    private static final int EVERY_MS = 50;
    private static final int SLICE_S = 5;
    private static final int LIMIT_S = 150;
    private static final double[] DUMPS = {7, 12, 17, 22, 32, 52, 60};
    private static int nextDump;
    private static final StringBuilder DUMP_TEXT = new StringBuilder();

    private WallProfiler() {}

    public static void startIfAsked(Thread target) {
        if (System.getenv("PENTECOST_WALL") == null) {
            return;
        }
        Thread t = new Thread(() -> run(target), "Pentecost wall profiler");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Thread target) {
        Map<Integer, Map<String, Integer>> slices = new TreeMap<>();
        long start = System.currentTimeMillis();
        long lastWrite = start;
        try {
            while (System.currentTimeMillis() - start < LIMIT_S * 1000L) {
                Thread.sleep(EVERY_MS);
                StackTraceElement[] stack = target.getStackTrace();
                String key = target.getState() + "  " + where(stack);
                double seen = (System.currentTimeMillis() - Timeline.startMillis()) / 1000.0;
                slices.computeIfAbsent((int) (seen / SLICE_S), k -> new HashMap<>()).merge(key, 1, Integer::sum);
                if (nextDump < DUMPS.length && seen >= DUMPS[nextDump]) {
                    dumpThreads(DUMPS[nextDump++]);
                }
                if (System.currentTimeMillis() - lastWrite > 10_000) {
                    lastWrite = System.currentTimeMillis();
                    write(slices);
                }
            }
            write(slices);
        } catch (Throwable ignored) {
        }
    }

    private static void dumpThreads(double at) throws Exception {
        DUMP_TEXT.append(String.format(java.util.Locale.ROOT, "%n######## At about %.0f s%n", at));
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread th = e.getKey();
            StackTraceElement[] st = e.getValue();
            if (th.getName().equals("Pentecost wall profiler") || st.length == 0) {
                continue;
            }
            boolean parked = st[0].getClassName().equals("jdk.internal.misc.Unsafe") && st[0].getMethodName().startsWith("park");
            if (parked && th != null && !th.getName().equals("main") && !th.getName().equals("Render thread")) {
                continue;
            }
            DUMP_TEXT.append("\n").append(th.getName()).append("  ").append(th.getState()).append('\n');
            for (int i = 0; i < Math.min(8, st.length); i++) {
                DUMP_TEXT.append("    ").append(st[i]).append('\n');
            }
        }
        Files.writeString(Dirs.run().resolve("wallclock-threads.txt"), DUMP_TEXT.toString());
    }

    private static String where(StackTraceElement[] stack) {
        if (stack.length == 0) {
            return "(no stack)";
        }
        for (int i = 0; i < stack.length; i++) {
            String c = stack[i].getClassName();
            if (!c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("sun.")) {
                String top = stack[0].getClassName() + "." + stack[0].getMethodName();
                return c + "." + stack[i].getMethodName() + (i == 0 ? "" : "  (in " + top + ")");
            }
        }
        return stack[0].getClassName() + "." + stack[0].getMethodName();
    }

    private static void write(Map<Integer, Map<String, Integer>> slices) throws Exception {
        StringBuilder out = new StringBuilder("Where the main thread stood, sampled every " + EVERY_MS + " ms\n");
        for (Map.Entry<Integer, Map<String, Integer>> e : new TreeMap<>(slices).entrySet()) {
            Map<String, Integer> m = e.getValue();
            int total = m.values().stream().mapToInt(Integer::intValue).sum();
            out.append(String.format(java.util.Locale.ROOT, "%n== %d to %d s (%d samples)%n",
                    e.getKey() * SLICE_S, (e.getKey() + 1) * SLICE_S, total));
            List<Map.Entry<String, Integer>> top = new ArrayList<>(m.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                out.append(String.format(java.util.Locale.ROOT, "  %3d%%  %s%n",
                        100 * top.get(i).getValue() / total, top.get(i).getKey()));
            }
        }
        Files.writeString(Dirs.run().resolve("wallclock.txt"), out.toString());
    }
}
