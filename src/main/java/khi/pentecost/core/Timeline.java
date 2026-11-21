package khi.pentecost.core;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Timeline {
    private static final long START = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final Map<String, Double> MARKS = new LinkedHashMap<>();

    private Timeline() {}

    public static synchronized void mark(String name) {
        if (MARKS.putIfAbsent(name, (System.currentTimeMillis() - START) / 1000.0) == null) {
            khi.pentecost.loader.ClassStats.report(name);
        }
    }

    private static double lastStep;

    public static long startMillis() {
        return START;
    }

    public static synchronized void step(String name) {
        double now = (System.currentTimeMillis() - START) / 1000.0;
        khi.pentecost.boot.Log.info(String.format(java.util.Locale.ROOT,
                "Startup step: %s at %.1f s (+%.1f s)", name, now, now - lastStep));
        lastStep = now;
    }

    public static synchronized Map<String, Double> marks() {
        return new LinkedHashMap<>(MARKS);
    }
}
