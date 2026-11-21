package khi.pentecost.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GenWatch {
    private static final long REPORT_NS = 30_000_000_000L;
    private static Thread watcher;
    private static final Map<String, Integer> leaf = new HashMap<>();
    private static final Map<String, Integer> steps = new HashMap<>();
    private static int total;
    private static long lastReport = System.nanoTime();
    private static Thread[] workers = new Thread[0];
    private static long workersFound;

    private GenWatch() {}

    public static void ensureRunning() {
        if (watcher != null || System.getenv("PENTECOST_NO_GEN_WATCH") != null) {
            return;
        }
        synchronized (GenWatch.class) {
            if (watcher == null) {
                watcher = new Thread(GenWatch::loop, "pentecost-gen-watch");
                watcher.setDaemon(true);
                watcher.setPriority(Thread.MIN_PRIORITY);
                watcher.start();
            }
        }
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(25);
                if (!PartProbe.on || PartProbe.perSecond(PartProbe.NOISE) <= 0) {
                    continue;
                }
                long now = System.nanoTime();
                if (now - workersFound > 10_000_000_000L) {
                    workersFound = now;
                    java.util.List<Thread> found = new java.util.ArrayList<>();
                    for (Thread t : Thread.getAllStackTraces().keySet()) {
                        if (t.getName().startsWith("Worker-Main")) {
                            found.add(t);
                        }
                    }
                    workers = found.toArray(new Thread[0]);
                }
                for (Thread t : workers) {
                    if (t.isAlive()) {
                        sample(t.getStackTrace());
                    }
                }
                if (System.nanoTime() - lastReport > REPORT_NS) {
                    report();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
            }
        }
    }

    private static void sample(StackTraceElement[] stack) {
        if (stack.length == 0) {
            return;
        }
        String top = stack[0].getClassName();
        if (top.startsWith("jdk.internal.misc.Unsafe") || top.startsWith("java.util.concurrent.ForkJoinPool")
                || top.startsWith("java.util.concurrent.locks")) {
            return;
        }
        String work = null;
        for (StackTraceElement f : stack) {
            String c = f.getClassName();
            if (!c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("it.unimi.") && !c.startsWith("com.google.")) {
                work = shortName(c) + "." + f.getMethodName();
                break;
            }
        }
        String step = "other";
        for (StackTraceElement f : stack) {
            String m = f.getMethodName();
            String c = f.getClassName();
            if (m.equals("doFill")) {
                step = "terrain shape";
            } else if (m.equals("buildSurface")) {
                step = "surface";
            } else if (m.equals("applyCarvers")) {
                step = "caves";
            } else if (m.equals("applyBiomeDecoration")) {
                step = "trees, ores, plants";
            } else if (m.equals("createBiomes") || m.equals("doCreateBiomes")) {
                step = "biomes";
            } else if (m.equals("createStructures") || m.equals("createReferences")) {
                step = "structures";
            } else if (c.contains("Light")) {
                step = "light";
            } else if (c.endsWith("ChunkSerializer") || m.equals("read")) {
                step = "loading from disk";
            } else {
                continue;
            }
            break;
        }
        synchronized (leaf) {
            if (work != null) {
                leaf.merge(step + "  |  " + work, 1, Integer::sum);
            }
            steps.merge(step, 1, Integer::sum);
            total++;
        }
    }

    private static String shortName(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot < 0 ? cls : cls.substring(dot + 1);
    }

    private static void report() {
        lastReport = System.nanoTime();
        List<Map.Entry<String, Integer>> l;
        List<Map.Entry<String, Integer>> s;
        int all;
        synchronized (leaf) {
            if (total == 0) {
                return;
            }
            l = new ArrayList<>(leaf.entrySet());
            s = new ArrayList<>(steps.entrySet());
            all = total;
            leaf.clear();
            steps.clear();
            total = 0;
        }
        l.sort((a, b) -> b.getValue() - a.getValue());
        s.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder out = new StringBuilder();
        out.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .append("  World generation, ").append(all).append(" looks at busy threads\n  By step:\n");
        for (Map.Entry<String, Integer> e : s) {
            out.append(String.format("    %3d%%  %s%n", e.getValue() * 100 / all, e.getKey()));
        }
        out.append("  By method doing the work (step | method):\n");
        for (int i = 0; i < Math.min(30, l.size()); i++) {
            Map.Entry<String, Integer> e = l.get(i);
            out.append(String.format("    %3d%%  %s%n", e.getValue() * 100 / all, e.getKey()));
        }
        out.append('\n');
        try {
            Path file = khi.pentecost.boot.Dirs.run().resolve("logs").resolve("pentecost-gen-profile.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
        }
    }
}
