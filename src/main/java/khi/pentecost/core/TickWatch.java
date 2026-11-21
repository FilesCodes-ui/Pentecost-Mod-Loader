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

public final class TickWatch {
    private static final long SLOW_NS = 60_000_000L;
    private static final long REPORT_NS = 20_000_000_000L;
    private static final int FRAMES = 10;

    private static volatile Thread server;
    private static volatile long tickStart;
    private static Thread watcher;
    private static final Map<String, Integer> counts = new HashMap<>();
    private static int total;
    private static long lastReport = System.nanoTime();

    private TickWatch() {}

    public static void tickStart() {
        server = Thread.currentThread();
        tickStart = System.nanoTime();
        if (watcher == null && System.getenv("PENTECOST_NO_TICK_WATCH") == null) {
            synchronized (TickWatch.class) {
                if (watcher == null) {
                    watcher = new Thread(TickWatch::loop, "pentecost-tick-watch");
                    watcher.setDaemon(true);
                    watcher.setPriority(Thread.MIN_PRIORITY);
                    watcher.start();
                }
            }
        }
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(10);
                Thread s = server;
                long start = tickStart;
                if (s != null && start != 0 && System.nanoTime() - start > SLOW_NS && s.isAlive()) {
                    sample(s.getStackTrace());
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
        String top = stack[0].getClassName() + "." + stack[0].getMethodName();
        if (top.startsWith("jdk.internal.misc.Unsafe.park") || top.startsWith("java.lang.Thread.sleep")
                || top.startsWith("java.lang.Thread.yield")) {
            boolean waitingForTick = false;
            for (StackTraceElement f : stack) {
                if (f.getMethodName().equals("waitUntilNextTick") || f.getMethodName().equals("managedBlock")
                        && f.getClassName().endsWith("MinecraftServer")) {
                    waitingForTick = true;
                    break;
                }
            }
            if (waitingForTick) {
                return;
            }
        }
        StringBuilder key = new StringBuilder();
        int kept = 0;
        for (StackTraceElement f : stack) {
            String c = f.getClassName();
            if (kept == 0 && (c.startsWith("java.") || c.startsWith("jdk."))) {
                key.append("    ").append(c).append('.').append(f.getMethodName()).append('\n');
                continue;
            }
            key.append("    ").append(c).append('.').append(f.getMethodName());
            if (f.getLineNumber() > 0) {
                key.append(':').append(f.getLineNumber());
            }
            key.append('\n');
            if (++kept >= FRAMES) {
                break;
            }
        }
        synchronized (counts) {
            counts.merge(key.toString(), 1, Integer::sum);
            total++;
        }
    }

    private static void report() {
        lastReport = System.nanoTime();
        List<Map.Entry<String, Integer>> top;
        int all;
        synchronized (counts) {
            if (total == 0) {
                return;
            }
            top = new ArrayList<>(counts.entrySet());
            all = total;
            counts.clear();
            total = 0;
        }
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder out = new StringBuilder();
        out.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .append("  Slow server ticks: about ").append(all * 10).append(" ms of slow time in the last 20 s.")
                .append(" Where the server thread was, most first:\n");
        for (int i = 0; i < Math.min(12, top.size()); i++) {
            Map.Entry<String, Integer> e = top.get(i);
            out.append(String.format("  %d%% of the slow time:%n", e.getValue() * 100 / all)).append(e.getKey());
        }
        out.append('\n');
        try {
            Path file = khi.pentecost.boot.Dirs.run().resolve("logs").resolve("pentecost-slow-ticks.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
        }
    }
}
