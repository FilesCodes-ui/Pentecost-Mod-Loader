package khi.pentecost.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class MemoryPlan {
    public record Plan(int targetGb, int reserveGb, int youngGb, String gc) {
        public int ceilingGb() {
            return targetGb + reserveGb;
        }
    }

    public record Effective(long startMb, long maxMb, long youngMb, long softMb, String gc) {}

    private MemoryPlan() {}

    public static int[] tier(int machineGb) {
        int target;
        int reserve;
        if (machineGb >= 24) {
            target = 8;
            reserve = 2;
        } else if (machineGb >= 16) {
            target = 6;
            reserve = 2;
        } else if (machineGb >= 12) {
            target = 5;
            reserve = 2;
        } else if (machineGb >= 8) {
            target = 4;
            reserve = 1;
        } else {
            target = 3;
            reserve = 0;
        }
        return new int[]{target, fitReserve(machineGb, target, reserve)};
    }

    public static int cap(int machineGb) {
        return Math.max(4, Math.min(32, machineGb - 2));
    }

    public static int fitReserve(int machineGb, int targetGb, int reserveGb) {
        return Math.max(0, Math.min(Math.min(16, reserveGb), machineGb - 2 - targetGb));
    }

    public static int halfOf(int targetGb) {
        return Math.max(1, targetGb / 2);
    }

    public static int machineGb() {
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean big) {
                return (int) (big.getTotalMemorySize() >> 30);
            }
        } catch (RuntimeException | LinkageError e) {
        }
        return 16;
    }

    public static boolean anySet(Function<String, String> get) {
        return !get.apply(Settings.JAVA_MEMORY).isEmpty() || !get.apply(Settings.JAVA_RESERVE).isEmpty()
                || !get.apply(Settings.JAVA_YOUNG).isEmpty() || !get.apply(Settings.JAVA_GC).isEmpty();
    }

    public static Plan plan(int machineGb, List<String> running, Function<String, String> get) {
        Effective now = effective(running);
        int[] tier = tier(machineGb);
        boolean memorySet = !get.apply(Settings.JAVA_MEMORY).isEmpty();
        boolean reserveSet = !get.apply(Settings.JAVA_RESERVE).isEmpty();
        boolean youngSet = !get.apply(Settings.JAVA_YOUNG).isEmpty();
        boolean gcSet = !get.apply(Settings.JAVA_GC).isEmpty();

        long runningStart = now.startMb() > 0 ? now.startMb() : now.maxMb();
        int target;
        if (memorySet) {
            target = Math.max(2, Math.min(cap(machineGb), number(get.apply(Settings.JAVA_MEMORY), tier[0])));
        } else if (runningStart > 0) {
            target = Math.max(2, (int) Math.round(runningStart / 1024.0));
        } else {
            target = tier[0];
        }

        int reserve;
        if (reserveSet) {
            reserve = fitReserve(machineGb, target, Math.max(0, number(get.apply(Settings.JAVA_RESERVE), tier[1])));
        } else if (memorySet || now.maxMb() == 0) {
            reserve = fitReserve(machineGb, target, tier[1]);
        } else {
            reserve = Math.max(0, (int) Math.round(now.maxMb() / 1024.0) - target);
        }

        int young = youngSet ? number(get.apply(Settings.JAVA_YOUNG), 0) : 0;
        young = young > 0 ? Math.max(1, Math.min(young, target - 1)) : halfOf(target);

        String gc = gcSet ? normalGc(get.apply(Settings.JAVA_GC)) : now.gc();
        return new Plan(target, reserve, young, gc);
    }

    public static List<String> flags(Plan p) {
        List<String> f = new ArrayList<>();
        f.add("-Xms" + p.targetGb() + "G");
        f.add("-Xmx" + p.ceilingGb() + "G");
        switch (p.gc()) {
            case "zgc" -> {
                f.add("-XX:+UseZGC");
                f.add("-XX:+ZGenerational");
                if (p.reserveGb() > 0) {
                    f.add("-XX:SoftMaxHeapSize=" + p.targetGb() + "G");
                }
            }
            case "parallel" -> {
                f.add("-Xmn" + p.youngGb() + "G");
                f.add("-XX:+UseParallelGC");
            }
            default -> f.add("-Xmn" + p.youngGb() + "G");
        }
        return f;
    }

    public static List<String> apply(List<String> running, Plan plan, List<String> extra) {
        List<String> out = new ArrayList<>();
        for (String a : running) {
            if (!isMemoryFlag(a)) {
                out.add(a);
            }
        }
        out.addAll(flags(plan));
        for (String e : extra) {
            if (!out.contains(e) || isMemoryFlag(e)) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean isMemoryFlag(String a) {
        return a.startsWith("-Xms") || a.startsWith("-Xmx") || a.startsWith("-Xmn") || a.startsWith("-XX:SoftMaxHeapSize=")
                || a.equals("-XX:+UseZGC") || a.equals("-XX:+ZGenerational") || a.equals("-XX:+UseParallelGC")
                || a.equals("-XX:+UseG1GC") || a.equals("-XX:-ZGenerational");
    }

    public static Effective effective(List<String> args) {
        long start = 0;
        long max = 0;
        long young = 0;
        long soft = 0;
        String gc = "g1";
        for (String a : args) {
            if (a.startsWith("-Xms")) {
                start = megabytes(a.substring(4));
            } else if (a.startsWith("-Xmx")) {
                max = megabytes(a.substring(4));
            } else if (a.startsWith("-Xmn")) {
                young = megabytes(a.substring(4));
            } else if (a.startsWith("-XX:SoftMaxHeapSize=")) {
                soft = megabytes(a.substring("-XX:SoftMaxHeapSize=".length()));
            } else if (a.equals("-XX:+UseZGC")) {
                gc = "zgc";
            } else if (a.equals("-XX:+UseParallelGC")) {
                gc = "parallel";
            } else if (a.equals("-XX:+UseG1GC")) {
                gc = "g1";
            }
        }
        return new Effective(start, max, young, soft, gc);
    }

    public static long megabytes(String size) {
        String v = size.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return 0;
        }
        try {
            char unit = v.charAt(v.length() - 1);
            if (Character.isDigit(unit)) {
                return Long.parseLong(v) / (1024 * 1024);
            }
            long n = Long.parseLong(v.substring(0, v.length() - 1));
            return switch (unit) {
                case 'g' -> n * 1024;
                case 'm' -> n;
                case 'k' -> n / 1024;
                default -> 0;
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static List<String> extraFlags(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    private static String normalGc(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("zgc") || v.equals("parallel") ? v : "g1";
    }

    private static int number(String raw, int def) {
        try {
            return raw.isBlank() ? def : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
