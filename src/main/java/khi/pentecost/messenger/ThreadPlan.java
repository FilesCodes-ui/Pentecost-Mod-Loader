package khi.pentecost.messenger;

import khi.pentecost.mods.ModInfo;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Step 1 of mod threads: decides which mods would share a thread. It moves nothing yet.
 *
 * Every mod jar is read. Each spot where one mod's code calls, reads or creates something from another mod is
 * counted, and so is a mixin that edits another mod. Then:
 * - A library (a mod three or more other mods use, or a known library) belongs to no thread. It runs on the thread
 *   of whoever called it.
 * - A mod that edits another mod, calls into it a lot, or declares it as a dependency joins it: an addon goes with its
 *   parent, so the two never wait on each other.
 * - The joined groups are spread over the threads, heaviest first onto the lightest thread.
 * Calls that still cross between threads are the ones the messenger will carry in step 2.
 */
public final class ThreadPlan {
    /** What one mod's code does with another mod: call sites (calls, field reads, new objects) and mixin edits. */
    public record Edge(String from, String to, int calls, int edits) {}

    /** Mods that share one thread because they are tied together. */
    public record Group(int thread, List<String> mods, long cost) {}

    public record Plan(int threads, List<Group> groups, Map<String, Integer> threadOf, Set<String> libraries,
                       List<Edge> edges, long crossCalls, long sameCalls, long millis) {}

    /** A mod used by this many other mods is a library. */
    static final int LIBRARY_USERS = 3;
    /** This many call sites into a mod ties the caller to it. */
    static final int JOIN_CALLS = 20;
    /** Never owned by a thread, whatever the counts say. */
    static final Set<String> KNOWN_LIBRARIES = Set.of("minecraft", "forge", "fabricloader", "fabric_api", "java",
            "geckolib", "architectury", "cloth_config", "kotlinforforge", "fabric_language_kotlin", "balm", "curios",
            "puzzleslib", "resourcefullib", "bookshelf", "moonlight", "citadel", "pentecost", "yet_another_config_lib_v3",
            "mezz_config", "jamlib", "kirin", "mixinextras", "creativecore");

    /**
     * Libraries packed inside other jars and Fabric API modules: named like lib_x.jar, org_x, com_x, net_x, io_x,
     * fabric_x or fabric-x, or anything Kotlin. They hold shared code, never game logic of their own.
     */
    static boolean libraryName(String id) {
        return id.startsWith("lib_") || id.startsWith("org_") || id.startsWith("com_") || id.startsWith("net_")
                || id.startsWith("io_") || id.startsWith("fabric_") || id.startsWith("fabric-") || id.endsWith(".jar")
                || id.contains("kotlin");
    }

    private ThreadPlan() {}

    /** Threads for mods: 4 on 8 or more cores, 3 below. */
    public static int defaultThreads() {
        return Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 3;
    }

    /** Reads the jars and makes the plan. Cost per mod is its class count until a measured cost is given. */
    public static Plan build(List<ModInfo> mods, int threads, Map<String, Double> measuredMs) {
        long t0 = System.currentTimeMillis();
        Map<String, String> owner = new HashMap<>();
        Map<String, List<String>> classesOf = new LinkedHashMap<>();
        for (ModInfo m : mods) {
            List<String> names = new ArrayList<>();
            if (m.jar() != null && Files.isRegularFile(m.jar())) {
                try (ZipFile z = new ZipFile(m.jar().toFile())) {
                    Enumeration<? extends ZipEntry> e = z.entries();
                    while (e.hasMoreElements()) {
                        String n = e.nextElement().getName();
                        if (n.endsWith(".class") && !n.startsWith("META-INF/") && !n.endsWith("module-info.class")) {
                            String c = n.substring(0, n.length() - 6);
                            names.add(c);
                            owner.putIfAbsent(c, m.id());
                        }
                    }
                } catch (IOException ignored) {
                    // An unreadable jar counts as empty.
                }
            }
            classesOf.put(m.id(), names);
        }

        Map<String, Long> cost = new LinkedHashMap<>();
        Map<String, Set<String>> declared = new HashMap<>();
        Map<String, int[]> counts = new TreeMap<>();
        for (ModInfo m : mods) {
            List<String> mine = classesOf.get(m.id());
            Double ms = measuredMs == null ? null : measuredMs.get(m.id());
            cost.put(m.id(), ms != null ? Math.max(1, Math.round(ms * 1000)) : Math.max(1, mine.size()));
            Set<String> d = new HashSet<>();
            for (String s : m.depends()) {
                d.add(s.toLowerCase(Locale.ROOT));
            }
            declared.put(m.id(), d);
            if (mine.isEmpty()) {
                continue;
            }
            try (ZipFile z = new ZipFile(m.jar().toFile())) {
                for (String c : mine) {
                    ZipEntry entry = z.getEntry(c + ".class");
                    if (entry == null || entry.getSize() > 4_000_000) {
                        continue;
                    }
                    byte[] bytes;
                    try (InputStream in = z.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    try {
                        scan(bytes, m.id(), owner, counts);
                    } catch (RuntimeException bad) {
                        // A class ASM cannot read adds nothing.
                    }
                }
            } catch (IOException ignored) {
                // Skipped, as above.
            }
        }
        List<Edge> edges = new ArrayList<>();
        counts.forEach((k, v) -> {
            int bar = k.indexOf('|');
            edges.add(new Edge(k.substring(0, bar), k.substring(bar + 1), v[0], v[1]));
        });
        return plan(cost, declared, edges, threads, t0);
    }

    /** Counts what one class uses from other mods. counts key: "from|to", value: {calls, edits}. */
    static void scan(byte[] bytes, String self, Map<String, String> owner, Map<String, int[]> counts) {
        boolean[] mixin = new boolean[1];
        Map<String, Integer> local = new HashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) {
                    mixin[0] = true;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            return this;
                        }

                        @Override
                        public void visit(String name, Object value) {
                            String t = value instanceof org.objectweb.asm.Type ty ? ty.getInternalName()
                                    : value instanceof String s ? s.replace('.', '/') : null;
                            String o = t == null ? null : owner.get(t);
                            if (o != null && !o.equals(self)) {
                                counts.computeIfAbsent(self + "|" + o, k -> new int[2])[1]++;
                            }
                        }
                    };
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private void use(String cls) {
                        if (cls == null || cls.startsWith("[")) {
                            return;
                        }
                        String o = owner.get(cls);
                        if (o != null && !o.equals(self)) {
                            local.merge(o, 1, Integer::sum);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int op, String ownerName, String n, String d, boolean itf) {
                        use(ownerName);
                    }

                    @Override
                    public void visitFieldInsn(int op, String ownerName, String n, String d) {
                        use(ownerName);
                    }

                    @Override
                    public void visitTypeInsn(int op, String type) {
                        if (op == Opcodes.NEW) {
                            use(type);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        local.forEach((o, n) -> counts.computeIfAbsent(self + "|" + o, k -> new int[2])[0] += n);
    }

    /** The plan from costs, declared dependencies and edges. Pure, so it can be tested without jars. */
    static Plan plan(Map<String, Long> cost, Map<String, Set<String>> declared, List<Edge> edges, int threads, long t0) {
        threads = Math.max(1, threads);
        Set<String> mods = new TreeSet<>(cost.keySet());
        Map<String, Set<String>> users = new HashMap<>();
        for (Edge e : edges) {
            if (e.calls() > 0 || e.edits() > 0) {
                users.computeIfAbsent(e.to(), k -> new HashSet<>()).add(e.from());
            }
        }
        declared.forEach((from, set) -> set.forEach(to -> {
            if (mods.contains(to) && !to.equals(from)) {
                users.computeIfAbsent(to, k -> new HashSet<>()).add(from);
            }
        }));
        Set<String> libraries = new TreeSet<>();
        for (String m : mods) {
            if (KNOWN_LIBRARIES.contains(m) || libraryName(m) || users.getOrDefault(m, Set.of()).size() >= LIBRARY_USERS) {
                libraries.add(m);
            }
        }

        Map<String, String> parent = new HashMap<>();
        for (String m : mods) {
            if (!libraries.contains(m)) {
                parent.put(m, m);
            }
        }
        for (Edge e : edges) {
            if (parent.containsKey(e.from()) && parent.containsKey(e.to())
                    && (e.edits() > 0 || e.calls() >= JOIN_CALLS || declared.getOrDefault(e.from(), Set.of()).contains(e.to()))) {
                union(parent, e.from(), e.to());
            }
        }
        declared.forEach((from, set) -> set.forEach(to -> {
            if (parent.containsKey(from) && parent.containsKey(to)) {
                union(parent, from, to);
            }
        }));

        Map<String, List<String>> clusters = new TreeMap<>();
        for (String m : parent.keySet()) {
            clusters.computeIfAbsent(find(parent, m), k -> new ArrayList<>()).add(m);
        }
        List<List<String>> ordered = new ArrayList<>(clusters.values());
        ordered.forEach(l -> l.sort(null));
        ordered.sort(Comparator.comparingLong((List<String> l) -> sum(cost, l)).reversed().thenComparing(l -> l.get(0)));

        long[] load = new long[threads];
        Map<String, Integer> threadOf = new TreeMap<>();
        List<Group> groups = new ArrayList<>();
        for (List<String> l : ordered) {
            int best = 0;
            for (int t = 1; t < threads; t++) {
                if (load[t] < load[best]) {
                    best = t;
                }
            }
            long c = sum(cost, l);
            load[best] += c;
            for (String m : l) {
                threadOf.put(m, best);
            }
            groups.add(new Group(best, List.copyOf(l), c));
        }

        long cross = 0;
        long same = 0;
        for (Edge e : edges) {
            Integer a = threadOf.get(e.from());
            Integer b = threadOf.get(e.to());
            if (a == null || b == null) {
                continue;
            }
            if (a.equals(b)) {
                same += e.calls();
            } else {
                cross += e.calls();
            }
        }
        return new Plan(threads, groups, threadOf, libraries, edges, cross, same, System.currentTimeMillis() - t0);
    }

    private static long sum(Map<String, Long> cost, List<String> l) {
        long s = 0;
        for (String m : l) {
            s += cost.getOrDefault(m, 1L);
        }
        return s;
    }

    private static String find(Map<String, String> p, String x) {
        while (!p.get(x).equals(x)) {
            p.put(x, p.get(p.get(x)));
            x = p.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> p, String a, String b) {
        String ra = find(p, a);
        String rb = find(p, b);
        if (!ra.equals(rb)) {
            if (ra.compareTo(rb) < 0) {
                p.put(rb, ra);
            } else {
                p.put(ra, rb);
            }
        }
    }

    /** The plan as text, for a person. */
    public static String text(Plan p) {
        StringBuilder b = new StringBuilder();
        b.append("Pentecost mod threads, step 1 (plan only, nothing was moved)\n");
        b.append("Written ").append(LocalDateTime.now()).append(". Made in ").append(p.millis()).append(" ms.\n\n");
        long[] load = new long[p.threads()];
        for (Group g : p.groups()) {
            load[g.thread()] += g.cost();
        }
        for (int t = 0; t < p.threads(); t++) {
            b.append("== Thread ").append(t + 1).append("  (load ").append(load[t]).append(")\n");
            for (Group g : p.groups()) {
                if (g.thread() == t) {
                    b.append("   ").append(String.join(" + ", g.mods())).append("   (").append(g.cost()).append(")\n");
                }
            }
            b.append('\n');
        }
        b.append("== Shared libraries (run on the caller's thread): ").append(p.libraries().size()).append("\n   ")
                .append(String.join(", ", p.libraries())).append("\n\n");
        b.append("Call sites between mods on the same thread: ").append(p.sameCalls()).append('\n');
        b.append("Call sites the messenger would carry between threads: ").append(p.crossCalls()).append("\n\n");
        b.append("== Biggest links that cross threads\n");
        List<Edge> crossing = new ArrayList<>();
        for (Edge e : p.edges()) {
            Integer a = p.threadOf().get(e.from());
            Integer c = p.threadOf().get(e.to());
            if (a != null && c != null && !a.equals(c)) {
                crossing.add(e);
            }
        }
        crossing.sort(Comparator.comparingInt((Edge e) -> e.calls() + e.edits() * 50).reversed());
        for (Edge e : crossing.subList(0, Math.min(25, crossing.size()))) {
            b.append("   ").append(e.from()).append(" -> ").append(e.to()).append(": ").append(e.calls()).append(" calls");
            if (e.edits() > 0) {
                b.append(", ").append(e.edits()).append(" mixin edits");
            }
            b.append('\n');
        }
        if (crossing.isEmpty()) {
            b.append("   none\n");
        }
        return b.toString();
    }
}
