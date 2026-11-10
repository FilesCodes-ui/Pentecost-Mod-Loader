package khi.pentecost.boot;

/** Small console logger with a time stamp since launch. */
public final class Log {
    private static final long START = System.nanoTime();

    private Log() {}

    public static void info(String msg) { System.out.println(prefix("INFO") + msg); }
    public static void warn(String msg) { System.out.println(prefix("WARN") + msg); }
    public static void error(String msg) { System.out.println(prefix("ERROR") + msg); }

    public static double seconds() {
        return (System.nanoTime() - START) / 1_000_000_000.0;
    }

    private static String prefix(String level) {
        return String.format("[Pentecost %7.2fs %s] ", seconds(), level);
    }
}
