package khi.pentecost.core;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where launch time goes. Each named point records the seconds since the Java process started.
 * The speed goals (launch, world load) are measured against these points, and the HUD shows them.
 */
public final class Timeline {
    private static final long START = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final Map<String, Double> MARKS = new LinkedHashMap<>();

    private Timeline() {}

    /** Records a point once. Later calls with the same name are ignored. */
    public static synchronized void mark(String name) {
        MARKS.putIfAbsent(name, (System.currentTimeMillis() - START) / 1000.0);
    }

    public static synchronized Map<String, Double> marks() {
        return new LinkedHashMap<>(MARKS);
    }
}
