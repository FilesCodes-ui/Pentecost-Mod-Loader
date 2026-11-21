package khi.pentecost.core;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class Runaways {
    private Runaways() {}

    private static final long RUNAWAY_NANOS = 20_000_000_000L;
    private static final int MAX_PLACES = 2;
    private static final int MIN_SAMPLES = 6;
    private static final long LOOP_LIMIT_NANOS = 5_000_000_000L;
    private static final long LOOP_GAP_NANOS = 50_000_000L;

    public static volatile Consumer<String> notice;

    private static final List<String> guarded = new ArrayList<>();
    private static final Map<String, List<String>> methodsByClass = new HashMap<>();
    private static final Set<String> known = new HashSet<>();

    private static Path file() {
        return Dirs.config().resolve("pentecost").resolve("runaways-v3.txt");
    }

    public static synchronized void load() {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String s = line.trim();
                int hash = s.indexOf('#');
                if (s.isEmpty() || s.startsWith("//") || hash <= 0 || !known.add(s)) continue;
                guarded.add(s);
                methodsByClass.computeIfAbsent(s.substring(0, hash), k -> new ArrayList<>()).add(s.substring(hash + 1));
            }
            if (!guarded.isEmpty()) Log.info("Runaway guard: " + guarded.size() + " method(s) get a loop time limit");
        } catch (Exception e) {
            Log.warn("Runaway guard: could not read " + file() + ": " + e);
        }
    }

    private static final ThreadLocal<Map<Integer, long[]>> RUNS = ThreadLocal.withInitial(HashMap::new);

    public static final class Stopped extends RuntimeException {
        Stopped(String what) {
            super("Pentecost stopped a loop in " + what + " that ran for over 5 seconds without a pause");
        }
    }

    public static void loop(int id) {
        long now = System.nanoTime();
        Map<Integer, long[]> runs = RUNS.get();
        long[] r = runs.get(id);
        if (r == null || now - r[1] > LOOP_GAP_NANOS) {
            runs.put(id, new long[]{now, now});
            return;
        }
        r[1] = now;
        if (now - r[0] > LOOP_LIMIT_NANOS) {
            runs.remove(id);
            String what = id >= 0 && id < guarded.size() ? guarded.get(id) : "a mod";
            Log.warn("Runaway guard: stopped a loop in " + what);
            throw new Stopped(what);
        }
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            List<String> methods;
            synchronized (Runaways.class) {
                methods = methodsByClass.get(name);
            }
            if (methods == null || System.getenv("PENTECOST_NO_RUNAWAY_GUARD") != null) return bytes;
            return guard(name, bytes, methods);
        };
    }

    static byte[] guard(String className, byte[] bytes, List<String> methods) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            int[] count = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!methods.contains(n)) return mv;
                    int id;
                    synchronized (Runaways.class) {
                        id = guarded.indexOf(className + "#" + n);
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        private final Set<Label> seen = new HashSet<>();

                        @Override
                        public void visitLabel(Label label) {
                            seen.add(label);
                            super.visitLabel(label);
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            if (seen.contains(label)) {
                                super.visitLdcInsn(id);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Runaways", "loop", "(I)V", false);
                                count[0]++;
                            }
                            super.visitJumpInsn(opcode, label);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 1, maxLocals);
                        }
                    };
                }
            }, 0);
            if (count[0] == 0) return bytes;
            Log.info("Runaway guard: " + count[0] + " loop check(s) in " + className);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            Log.warn("Runaway guard skipped " + className + ": " + e);
            return bytes;
        }
    }

    private record Watch(String task, long sinceNanos, long cpuAtStart, List<StackTraceElement[]> samples) {}

    private static volatile Thread watchdog;
    private static final Set<Long> reported = new HashSet<>();
    private static final Set<String> noticed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static synchronized void start() {
        if (watchdog != null || System.getenv("PENTECOST_NO_RUNAWAY_GUARD") != null) return;
        Thread t = new Thread(Runaways::watch, "pentecost-watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        watchdog = t;
        t.start();
    }

    private static void watch() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        boolean cpu = mx.isThreadCpuTimeSupported();
        if (cpu && !mx.isThreadCpuTimeEnabled()) mx.setThreadCpuTimeEnabled(true);
        Map<Long, Watch> watching = new HashMap<>();
        while (true) {
            try {
                Thread.sleep(2000);
                long now = System.nanoTime();
                Map<Long, Thread> live = new HashMap<>();
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    live.put(t.threadId(), t);
                    yieldToGame(t);
                }
                Set<Long> still = new HashSet<>();
                for (ThreadInfo info : mx.dumpAllThreads(false, false)) {
                    if (info == null || info.getThreadState() != Thread.State.RUNNABLE) continue;
                    StackTraceElement[] st = info.getStackTrace();
                    if (!shared(info.getThreadName()) || !inPool(st)) continue;
                    String task = taskOf(st);
                    if (task == null) continue;
                    long id = info.getThreadId();
                    still.add(id);
                    long cpuNow = cpu ? mx.getThreadCpuTime(id) : 0;
                    Watch w = watching.get(id);
                    if (w == null || !w.task.equals(task)) {
                        watching.put(id, new Watch(task, now, cpuNow, new ArrayList<>(List.<StackTraceElement[]>of(st))));
                        continue;
                    }
                    if (w.samples.size() < 12) w.samples.add(st);
                    long ran = now - w.sinceNanos;
                    if (ran < RUNAWAY_NANOS || reported.contains(id) || w.samples.size() < MIN_SAMPLES) continue;
                    double busy = cpu ? (cpuNow - w.cpuAtStart) / (double) ran : 1.0;
                    if (busy < 0.8) continue;
                    if (places(w.samples) > MAX_PLACES) continue;
                    reported.add(id);
                    caught(live.get(id), info.getThreadName(), w);
                }
                watching.keySet().retainAll(still);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                Log.warn("Runaway watchdog: " + e);
            }
        }
    }

    static boolean shared(String thread) {
        return thread.startsWith("Worker-Main") || thread.startsWith("IO-Worker") || thread.startsWith("ForkJoinPool.commonPool")
                || thread.startsWith("Download-");
    }

    static int places(List<StackTraceElement[]> samples) {
        Set<String> seen = new HashSet<>();
        for (StackTraceElement[] s : samples) {
            StringBuilder k = new StringBuilder();
            for (int i = 0; i < s.length; i++) {
                k.append(s[i].getClassName()).append('.').append(s[i].getMethodName()).append(':').append(s[i].getLineNumber()).append('|');
            }
            seen.add(k.toString());
        }
        return seen.size();
    }

    private static final Set<Long> yielded = new HashSet<>();

    private static void yieldToGame(Thread t) {
        String n = t.getName();
        if (n.equals("Render thread") || n.equals("Server thread")) {
            int want = n.equals("Render thread") ? Thread.MAX_PRIORITY : Thread.NORM_PRIORITY + 2;
            if (t.getPriority() != want && !"false".equals(khi.pentecost.control.Settings.get("threads.boostGame"))) {
                try {
                    t.setPriority(want);
                } catch (SecurityException ignored) {
                }
            }
            return;
        }
        if (yielded.contains(t.threadId()) || t.getPriority() != Thread.NORM_PRIORITY) return;
        if (n.equals("Render thread") || n.equals("Server thread") || n.equals("main") || n.startsWith("pentecost")
                || n.startsWith("Worker-Main") || n.startsWith("IO-Worker") || n.startsWith("Netty") || n.startsWith("Sound")
                || n.startsWith("Chunk Render") || n.startsWith("ForkJoinPool") || n.startsWith("Timer")
                || n.startsWith("Reference Handler") || n.startsWith("Finalizer") || n.startsWith("Signal")
                || n.startsWith("Common-Cleaner") || n.startsWith("Attach") || n.startsWith("DestroyJavaVM")) {
            return;
        }
        String c = creator(t);
        if (c == null) return;
        try {
            t.setPriority(Thread.NORM_PRIORITY - 1);
            yielded.add(t.threadId());
        } catch (SecurityException ignored) {
        }
    }

    private static String creator(Thread t) {
        for (StackTraceElement e : t.getStackTrace()) {
            String c = e.getClassName();
            if (jdk(c) || c.startsWith("net.minecraft.") || c.startsWith("com.mojang.") || c.startsWith("khi.pentecost.")
                    || c.startsWith("net.minecraftforge.") || c.startsWith("org.lwjgl.")) continue;
            return c;
        }
        return null;
    }

    private static boolean inPool(StackTraceElement[] st) {
        for (int i = st.length - 1; i >= Math.max(0, st.length - 8); i--) {
            String c = st[i].getClassName();
            if (c.startsWith("java.util.concurrent.ForkJoinWorkerThread") || c.startsWith("java.util.concurrent.ThreadPoolExecutor")) {
                return true;
            }
        }
        return false;
    }

    private static boolean jdk(String c) {
        return c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.sun.");
    }

    private static String taskOf(StackTraceElement[] st) {
        for (int i = st.length - 1; i >= 0; i--) {
            if (!jdk(st[i].getClassName())) return st[i].getClassName() + "#" + st[i].getMethodName();
        }
        return null;
    }

    static List<String> loopMethods(List<StackTraceElement[]> samples) {
        StackTraceElement[] first = samples.get(0);
        int common = first.length;
        for (StackTraceElement[] s : samples) {
            int n = 0;
            while (n < Math.min(common, s.length)) {
                StackTraceElement a = first[first.length - 1 - n], b = s[s.length - 1 - n];
                if (!a.getClassName().equals(b.getClassName()) || !a.getMethodName().equals(b.getMethodName())) break;
                n++;
            }
            common = n;
        }
        List<String> out = new ArrayList<>();
        for (int k = 0; k < common; k++) {
            StackTraceElement f = first[first.length - 1 - k];
            String c = f.getClassName();
            if (jdk(c) || c.startsWith("khi.pentecost.") || f.getMethodName().startsWith("<") || c.contains("$$Lambda")) continue;
            String m = c + "#" + f.getMethodName();
            if (!out.contains(m)) out.add(m);
        }
        return out;
    }

    static StackTraceElement loopFrame(List<StackTraceElement[]> samples) {
        StackTraceElement[] first = samples.get(0);
        int common = first.length;
        for (StackTraceElement[] s : samples) {
            int n = 0;
            while (n < Math.min(common, s.length)) {
                StackTraceElement a = first[first.length - 1 - n], b = s[s.length - 1 - n];
                if (!a.getClassName().equals(b.getClassName()) || !a.getMethodName().equals(b.getMethodName())) break;
                n++;
            }
            common = n;
        }
        for (int k = common - 1; k >= 0; k--) {
            StackTraceElement f = first[first.length - 1 - k];
            if (!jdk(f.getClassName())) return f;
        }
        return null;
    }

    private static void caught(Thread t, String name, Watch w) {
        if (t != null) {
            try {
                t.setPriority(Thread.MIN_PRIORITY);
                t.interrupt();
            } catch (SecurityException ignored) {
            }
        }
        StackTraceElement f = loopFrame(w.samples);
        String where = f == null ? w.task : f.getClassName() + "#" + f.getMethodName();
        String mod = modOf(f == null ? w.task : f.getClassName(), w.samples.get(0));
        StringBuilder sb = new StringBuilder("Runaway: " + name + " has looped in " + where + " for over 20 seconds ("
                + mod + "). It now runs at the lowest priority; from the next start that loop gets a time limit.\n");
        for (StackTraceElement e : w.samples.get(0)) sb.append("    at ").append(e).append('\n');
        Log.warn(sb.toString());
        for (String m : loopMethods(w.samples)) remember(m);
        Consumer<String> n = notice;
        if (n != null && noticed.add(mod)) {
            try {
                n.accept(mod + " got stuck in a loop and was slowed down. Fixed from the next start.");
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String modOf(String fallback, StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (jdk(c) || c.startsWith("net.minecraft.") || c.startsWith("com.mojang.") || c.startsWith("khi.pentecost.")) continue;
            String[] p = c.split("\\.");
            return p.length >= 3 ? p[1] + "." + p[2] : c;
        }
        return fallback;
    }

    private static synchronized void remember(String method) {
        if (!known.add(method)) return;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = Files.isRegularFile(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8))
                    : new ArrayList<>(List.of("// Methods that looped forever. Pentecost limits their loops. Delete a line to undo."));
            Set<String> have = new LinkedHashSet<>(lines);
            have.add(method);
            Files.write(f, have, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Runaway guard: could not save " + method + ": " + e);
        }
    }
}
