package khi.pentecost.translator;

import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Shadow {
    public record Parent(String id, int classes, boolean declared) {}

    public record Gap(String missing, String usedBy, String candidate, String candidateMod, int score, String decision) {}

    public record ModReport(String id, LoaderKind loader, int classes, List<Parent> parents, List<Gap> gaps, List<String> mixinTargets) {}

    public record Result(List<ModReport> mods, int indexed, long millis) {}

    private static final Pattern DESC = Pattern.compile("L([A-Za-z_$][\\w$]*(?:/[A-Za-z_$][\\w$]*)+);");
    private static final Pattern DOTTED = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){2,}\\.[A-Z][\\w$]*");

    private Shadow() {}

    public static Result analyze(List<ModInfo> mods, Predicate<String> elsewhere) {
        long t0 = System.currentTimeMillis();
        Map<String, String> owner = new HashMap<>();
        Map<String, List<String>> bySimple = new HashMap<>();
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
                            bySimple.computeIfAbsent(simple(c), k -> new ArrayList<>()).add(c);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
            classesOf.put(m.id(), names);
        }

        List<ModReport> out = new ArrayList<>();
        for (ModInfo m : mods) {
            List<String> mine = classesOf.get(m.id());
            if (mine == null || mine.isEmpty()) {
                continue;
            }
            Map<String, Set<String>> usedFrom = new TreeMap<>();
            Map<String, String> missing = new TreeMap<>();
            Set<String> mixinTargets = new HashSet<>();
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
                    Set<String> refs;
                    boolean mixin;
                    try {
                        ClassReader r = new ClassReader(bytes);
                        refs = references(r);
                        mixin = refs.contains("org/spongepowered/asm/mixin/Mixin");
                    } catch (RuntimeException bad) {
                        continue;
                    }
                    for (String ref : refs) {
                        if (ref.startsWith("java/") || ref.startsWith("javax/") || ref.startsWith("jdk/") || ref.startsWith("sun/")) {
                            continue;
                        }
                        String o = owner.get(ref);
                        if (o != null) {
                            if (!o.equals(m.id())) {
                                usedFrom.computeIfAbsent(o, k -> new HashSet<>()).add(ref);
                                if (mixin) {
                                    mixinTargets.add(ref.replace('/', '.') + " (" + o + ")");
                                }
                            }
                        } else if (!elsewhere.test(ref) && looksLikeClass(ref)) {
                            missing.putIfAbsent(ref, c);
                        }
                    }
                }
            } catch (IOException e) {
                continue;
            }
            Set<String> declared = new HashSet<>();
            for (String d : m.depends()) {
                declared.add(d.toLowerCase(Locale.ROOT));
            }
            List<Parent> parents = new ArrayList<>();
            usedFrom.forEach((id, set) -> parents.add(new Parent(id, set.size(), declared.contains(id.toLowerCase(Locale.ROOT)))));
            parents.sort(Comparator.comparingInt(Parent::classes).reversed());
            List<Gap> gaps = new ArrayList<>();
            Set<String> parentIds = new HashSet<>(usedFrom.keySet());
            missing.forEach((ref, usedBy) -> gaps.add(gap(ref, usedBy, bySimple, owner, parentIds, m.id())));
            out.add(new ModReport(m.id(), m.loader(), mine.size(), parents, gaps, List.copyOf(new java.util.TreeSet<>(mixinTargets))));
        }
        return new Result(out, owner.size(), System.currentTimeMillis() - t0);
    }

    static Set<String> references(ClassReader r) {
        Set<String> out = new HashSet<>();
        char[] buf = new char[r.getMaxStringLength() + 1];
        for (int i = 1; i < r.getItemCount(); i++) {
            int at = r.getItem(i);
            if (at == 0) {
                continue;
            }
            int tag = r.readByte(at - 1);
            if (tag == 7) {
                String c = r.readUTF8(at, buf);
                if (c != null && !c.startsWith("[")) {
                    out.add(c);
                } else if (c != null) {
                    var mm = DESC.matcher(c);
                    while (mm.find()) {
                        out.add(mm.group(1));
                    }
                }
            } else if (tag == 1) {
                String s = readUtf(r, at);
                if (s == null) {
                    continue;
                }
                var mm = DESC.matcher(s);
                while (mm.find()) {
                    out.add(mm.group(1));
                }
                if (s.indexOf('.') > 0 && s.length() < 200) {
                    var dm = DOTTED.matcher(s);
                    while (dm.find()) {
                        out.add(dm.group().replace('.', '/'));
                    }
                }
            }
        }
        out.remove(r.getClassName());
        return out;
    }

    private static String readUtf(ClassReader r, int at) {
        int len = r.readUnsignedShort(at);
        if (len == 0 || len > 4000) {
            return null;
        }
        byte[] b = new byte[len];
        for (int k = 0; k < len; k++) {
            b[k] = (byte) r.readByte(at + 2 + k);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static boolean looksLikeClass(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash > 0 && slash < ref.length() - 1 && Character.isJavaIdentifierStart(ref.charAt(slash + 1));
    }

    private static String simple(String c) {
        return c.substring(c.lastIndexOf('/') + 1);
    }

    static Gap gap(String ref, String usedBy, Map<String, List<String>> bySimple, Map<String, String> owner, Set<String> parents, String self) {
        List<String> same = bySimple.getOrDefault(simple(ref), List.of());
        String best = null;
        int bestScore = 0;
        String[] want = ref.split("/");
        for (String c : same) {
            String o = owner.get(c);
            if (o == null || o.equals(self)) {
                continue;
            }
            String[] have = c.split("/");
            int prefix = 0;
            while (prefix < want.length - 1 && prefix < have.length - 1 && want[prefix].equals(have[prefix])) {
                prefix++;
            }
            int score = 30 + prefix * 15 + (parents.contains(o) ? 20 : 0);
            Set<String> wantParts = new HashSet<>(List.of(want).subList(0, want.length - 1));
            for (int k = 0; k < have.length - 1; k++) {
                if (wantParts.contains(have[k]) && k >= prefix) {
                    score += 5;
                }
            }
            score = Math.min(100, score);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        String decision;
        if (best == null) {
            decision = "no match, would shim and report";
        } else if (bestScore >= 80) {
            decision = "would link, then check signatures";
        } else if (bestScore >= 55) {
            decision = "would link on a guess, watched";
        } else {
            decision = "too weak, would shim and report";
        }
        return new Gap(ref.replace('/', '.'), usedBy.replace('/', '.'), best == null ? "" : best.replace('/', '.'),
                best == null ? "" : owner.get(best), bestScore, decision);
    }

    public static String text(Result r) {
        StringBuilder b = new StringBuilder();
        b.append("Pentecost translator, shadow mode\n");
        b.append("Written ").append(LocalDateTime.now()).append(". Nothing here was changed in the game: this is what the translator WOULD do.\n");
        b.append(r.indexed()).append(" classes indexed from ").append(r.mods().size()).append(" mods in ").append(r.millis()).append(" ms.\n\n");
        int gaps = 0;
        int undeclared = 0;
        for (ModReport m : r.mods()) {
            gaps += m.gaps().size();
            undeclared += (int) m.parents().stream().filter(p -> !p.declared()).count();
        }
        b.append("Summary: ").append(gaps).append(" missing classes, ").append(undeclared).append(" parents used but not declared.\n\n");
        for (ModReport m : r.mods()) {
            if (m.parents().isEmpty() && m.gaps().isEmpty()) {
                continue;
            }
            b.append("== ").append(m.id()).append(" (").append(m.loader().label).append(", ").append(m.classes()).append(" classes)\n");
            for (Parent p : m.parents()) {
                b.append("   uses ").append(p.id()).append(": ").append(p.classes()).append(" classes")
                        .append(p.declared() ? "" : "   NOT DECLARED, likely optional").append('\n');
            }
            if (!m.mixinTargets().isEmpty()) {
                b.append("   mixins into other mods: ").append(m.mixinTargets().size()).append('\n');
            }
            int shown = 0;
            for (Gap g : m.gaps()) {
                if (shown++ >= 40) {
                    b.append("   ... ").append(m.gaps().size() - 40).append(" more missing\n");
                    break;
                }
                b.append("   missing ").append(g.missing()).append("  (used by ").append(g.usedBy()).append(")\n");
                if (!g.candidate().isEmpty()) {
                    b.append("      best match ").append(g.candidate()).append(" in ").append(g.candidateMod())
                            .append(", score ").append(g.score()).append(": ").append(g.decision()).append('\n');
                } else {
                    b.append("      ").append(g.decision()).append('\n');
                }
            }
            b.append('\n');
        }
        return b.toString();
    }
}
