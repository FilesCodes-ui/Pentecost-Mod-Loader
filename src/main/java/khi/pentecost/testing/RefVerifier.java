package khi.pentecost.testing;

import khi.pentecost.mods.ModInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks every call a converted mod makes.
 * Calls into Minecraft must resolve against the readable-name game jar. That proves the remapper.
 * Calls into Forge, Fabric, or Quilt loader classes are counted as provided or missing. That is the API tracker.
 */
public final class RefVerifier {
    private record ClassInfo(String superName, String[] interfaces, Set<String> methods, Set<String> fields) {}

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "getClass",
            "clone", "finalize", "wait", "notify", "notifyAll");
    private static final String[] LOADER_PREFIXES = {"net/minecraftforge/", "net/fabricmc/", "org/quiltmc/",
            "cpw/mods/", "org/spongepowered/asm/", "net/neoforged/"};

    public static final class ModResult {
        public final ModInfo mod;
        public int gameRefs;
        public int gameMissing;
        public final Set<String> missingExamples = new HashSet<>();
        public final Set<String> loaderHave = new HashSet<>();
        public final Set<String> loaderMissing = new HashSet<>();

        ModResult(ModInfo mod) {
            this.mod = mod;
        }
    }

    private final Map<String, ClassInfo> game = new HashMap<>();

    public RefVerifier(Path namedGameJar) throws IOException {
        try (ZipFile z = new ZipFile(namedGameJar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    Set<String> methods = new HashSet<>();
                    Set<String> fields = new HashSet<>();
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                            methods.add(name + desc);
                            return null;
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                            fields.add(name);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    game.put(cr.getClassName(), new ClassInfo(cr.getSuperName(), cr.getInterfaces(), methods, fields));
                }
            }
        }
    }

    public int gameClassCount() {
        return game.size();
    }

    public ModResult verify(ModInfo mod) throws IOException {
        ModResult r = new ModResult(mod);
        try (ZipFile z = new ZipFile(mod.jar().toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(int v, int access, String name, String sig, String sup, String[] itf) {
                            type(r, sup);
                            if (itf != null) {
                                for (String i : itf) {
                                    type(r, i);
                                }
                            }
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                                    member(r, owner, n, d, true);
                                }

                                @Override
                                public void visitFieldInsn(int op, String owner, String n, String d) {
                                    member(r, owner, n, d, false);
                                }

                                @Override
                                public void visitTypeInsn(int op, String t) {
                                    type(r, t);
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (RuntimeException ex) {
                    // Unreadable class file. Skip it.
                }
            }
        }
        return r;
    }

    private void type(ModResult r, String t) {
        if (t == null || t.startsWith("[")) {
            return;
        }
        if (isLoader(t)) {
            loader(r, t);
        } else if (isGame(t)) {
            r.gameRefs++;
            if (!game.containsKey(t)) {
                r.gameMissing++;
                r.missingExamples.add("class " + t);
            }
        }
    }

    private void member(ModResult r, String owner, String name, String desc, boolean method) {
        if (owner.startsWith("[")) {
            return;
        }
        if (isLoader(owner)) {
            loader(r, owner);
            return;
        }
        if (!isGame(owner)) {
            return;
        }
        r.gameRefs++;
        if (!resolves(owner, name, desc, method)) {
            r.gameMissing++;
            r.missingExamples.add(owner + "." + name + (method ? desc : ""));
        }
    }

    private boolean resolves(String owner, String name, String desc, boolean method) {
        if (!game.containsKey(owner)) {
            return false;
        }
        boolean leftGame = false;
        Deque<String> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(owner);
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (c == null || !seen.add(c)) {
                continue;
            }
            ClassInfo info = game.get(c);
            if (info == null) {
                if (!c.equals("java/lang/Object")) {
                    leftGame = true;
                }
                continue;
            }
            if (method ? info.methods.contains(name + desc) : info.fields.contains(name)) {
                return true;
            }
            todo.add(info.superName);
            for (String i : info.interfaces) {
                todo.add(i);
            }
        }
        // A parent outside the game jar (a Java or library class) may declare the member.
        return leftGame || (method && OBJECT_METHODS.contains(name));
    }

    private void loader(ModResult r, String cls) {
        boolean have = RefVerifier.class.getClassLoader().getResource(cls + ".class") != null;
        (have ? r.loaderHave : r.loaderMissing).add(cls);
    }

    private boolean isGame(String t) {
        return game.containsKey(t) || (t.startsWith("net/minecraft/") && !t.startsWith("net/minecraftforge/"));
    }

    private static boolean isLoader(String t) {
        for (String p : LOADER_PREFIXES) {
            if (t.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public static String report(List<ModResult> results) {
        StringBuilder sb = new StringBuilder("PENTECOST VERIFY REPORT\n\n");
        long refs = 0;
        long missing = 0;
        Map<String, Integer> wanted = new HashMap<>();
        Set<String> have = new HashSet<>();
        sb.append("PER MOD\n");
        for (ModResult r : results) {
            refs += r.gameRefs;
            missing += r.gameMissing;
            have.addAll(r.loaderHave);
            for (String c : r.loaderMissing) {
                wanted.merge(c, 1, Integer::sum);
            }
            double pct = r.gameRefs == 0 ? 100.0 : 100.0 * (r.gameRefs - r.gameMissing) / r.gameRefs;
            sb.append(String.format("  %-34s %-6s game calls %6d, resolved %6.2f%%, loader classes missing %4d%n",
                    cut(r.mod.name(), 34), r.mod.loader().label, r.gameRefs, pct, r.loaderMissing.size()));
        }
        double pct = refs == 0 ? 100.0 : 100.0 * (refs - missing) / refs;
        sb.append(String.format("%nTOTAL  mods %d, game calls %d, resolved %.2f%%, unresolved %d%n",
                results.size(), refs, pct, missing));
        sb.append(String.format("LOADER API  classes Pentecost provides %d, classes still missing %d%n",
                have.size(), wanted.size()));

        sb.append("\nMOST WANTED LOADER CLASSES (number of mods that use each)\n");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(wanted.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        for (int i = 0; i < Math.min(40, top.size()); i++) {
            sb.append(String.format("  %3d  %s%n", top.get(i).getValue(), top.get(i).getKey()));
        }

        sb.append("\nUNRESOLVED GAME CALLS (up to 8 per mod)\n");
        for (ModResult r : results) {
            if (r.missingExamples.isEmpty()) {
                continue;
            }
            sb.append("  ").append(r.mod.name()).append(" (").append(r.mod.loader().label).append(")\n");
            r.missingExamples.stream().sorted().limit(8).forEach(x -> sb.append("      ").append(x).append('\n'));
        }

        Map<String, Integer> byPackage = new TreeMap<>();
        for (String c : wanted.keySet()) {
            String[] p = c.split("/");
            byPackage.merge(String.join("/", java.util.Arrays.copyOf(p, Math.min(4, p.length - 1))), 1, Integer::sum);
        }
        sb.append("\nMISSING LOADER CLASSES BY PACKAGE\n");
        byPackage.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(25).forEach(e -> sb.append(String.format("  %4d  %s%n", e.getValue(), e.getKey())));
        return sb.toString();
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + ".";
    }
}
