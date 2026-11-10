package khi.pentecost.remap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Name tables. The vanilla jar ships with scrambled names. Mojang's official mapping file gives
 * the readable names, and Pentecost runs the game in those readable names.
 * Fabric mods use "intermediary" names and Forge mods use "SRG" names. Both convert to readable names here.
 */
public final class Mappings {
    /** scrambled class -> readable class (internal names with slashes). */
    public final Map<String, String> classes = new HashMap<>();
    /** scrambled owner -> (scrambled name + descriptor -> readable name). */
    public final Map<String, Map<String, String>> methods = new HashMap<>();
    /** scrambled owner -> (scrambled name + ":" + descriptor -> readable name). */
    public final Map<String, Map<String, String>> fields = new HashMap<>();
    public final Map<String, Map<String, String>> methodsByName = new HashMap<>();
    public final Map<String, Map<String, String>> fieldsByName = new HashMap<>();

    /** A name-only table for mods: every intermediary or SRG name is unique, so no owner is needed. */
    public record NameTable(Map<String, String> classes, Map<String, String> methods,
                            Map<String, String> fields, int conflicts) {}

    // ------------------------------------------------------------ Mojang (ProGuard format)

    public static Mappings parseProguard(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        Mappings m = new Mappings();
        Map<String, String> namedToObf = new HashMap<>();
        for (String line : lines) {
            if (isClassLine(line)) {
                int a = line.indexOf(" -> ");
                String named = line.substring(0, a).replace('.', '/');
                String obf = line.substring(a + 4, line.length() - 1).replace('.', '/');
                m.classes.put(obf, named);
                namedToObf.put(named, obf);
            }
        }
        String cur = null;
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (isClassLine(line)) {
                int a = line.indexOf(" -> ");
                cur = line.substring(a + 4, line.length() - 1).replace('.', '/');
                continue;
            }
            String s = line.trim();
            int a = s.lastIndexOf(" -> ");
            if (a < 0 || cur == null) {
                continue;
            }
            String obf = s.substring(a + 4);
            String left = s.substring(0, a);
            while (!left.isEmpty() && Character.isDigit(left.charAt(0))) {
                left = left.substring(left.indexOf(':') + 1);
            }
            int sp = left.indexOf(' ');
            if (sp < 0) {
                continue;
            }
            String type = left.substring(0, sp);
            String rest = left.substring(sp + 1);
            int p = rest.indexOf('(');
            if (p >= 0) {
                String name = rest.substring(0, p);
                String args = rest.substring(p + 1, rest.indexOf(')'));
                StringBuilder d = new StringBuilder("(");
                if (!args.isEmpty()) {
                    for (String x : args.split(",")) {
                        d.append(desc(x, namedToObf));
                    }
                }
                d.append(')').append(desc(type, namedToObf));
                m.methods.computeIfAbsent(cur, k -> new HashMap<>()).put(obf + d, name);
                m.methodsByName.computeIfAbsent(cur, k -> new HashMap<>()).putIfAbsent(obf, name);
            } else {
                m.fields.computeIfAbsent(cur, k -> new HashMap<>()).put(obf + ":" + desc(type, namedToObf), rest);
                m.fieldsByName.computeIfAbsent(cur, k -> new HashMap<>()).putIfAbsent(obf, rest);
            }
        }
        return m;
    }

    private static boolean isClassLine(String line) {
        return !line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '#' && line.endsWith(":");
    }

    private static String desc(String type, Map<String, String> namedToObf) {
        String t = type.trim();
        int dims = 0;
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2);
            dims++;
        }
        String base = switch (t) {
            case "int" -> "I";
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> {
                String internal = t.replace('.', '/');
                yield "L" + namedToObf.getOrDefault(internal, internal) + ";";
            }
        };
        return "[".repeat(dims) + base;
    }

    // ------------------------------------------------------------ Fabric intermediary (tiny v2)

    public NameTable intermediary(List<String> tinyLines) {
        Map<String, String> cls = new HashMap<>();
        Map<String, String> mth = new HashMap<>();
        Map<String, String> fld = new HashMap<>();
        int conflicts = 0;
        String cur = null;
        for (String line : tinyLines) {
            String[] p = line.split("\t", -1);
            if (p.length >= 3 && p[0].equals("c")) {
                cur = p[1];
                String named = classes.getOrDefault(cur, cur);
                if (!p[2].equals(named)) {
                    cls.put(p[2], named);
                }
            } else if (p.length >= 5 && p[0].isEmpty() && cur != null) {
                String desc = p[2];
                String obf = p[3];
                String inter = p[4];
                if (inter.equals(obf)) {
                    continue;
                }
                if (p[1].equals("m")) {
                    Map<String, String> own = methods.get(cur);
                    String named = own == null ? null : own.get(obf + desc);
                    if (named == null) {
                        named = obf;
                    }
                    String old = mth.put(inter, named);
                    if (old != null && !old.equals(named)) {
                        conflicts++;
                        mth.put(inter, old);
                    }
                } else if (p[1].equals("f")) {
                    Map<String, String> own = fields.get(cur);
                    String named = own == null ? null : own.get(obf + ":" + desc);
                    if (named == null) {
                        named = obf;
                    }
                    String old = fld.put(inter, named);
                    if (old != null && !old.equals(named)) {
                        conflicts++;
                        fld.put(inter, old);
                    }
                }
            }
        }
        return new NameTable(cls, mth, fld, conflicts);
    }

    // ------------------------------------------------------------ Forge SRG (tsrg2)

    public NameTable srg(List<String> tsrgLines) {
        Map<String, String> mth = new HashMap<>();
        Map<String, String> fld = new HashMap<>();
        int conflicts = 0;
        String cur = null;
        for (String line : tsrgLines) {
            if (line.isEmpty() || line.startsWith("tsrg2") || line.startsWith("\t\t")) {
                continue;
            }
            if (line.charAt(0) != '\t') {
                cur = line.split(" ")[0];
                continue;
            }
            String[] p = line.trim().split(" ");
            if (cur == null || p.length < 2) {
                continue;
            }
            if (p.length >= 3 && p[1].startsWith("(")) {
                String srg = p[2];
                if (!srg.startsWith("m_")) {
                    continue;
                }
                Map<String, String> own = methods.get(cur);
                String named = own == null ? null : own.get(p[0] + p[1]);
                if (named == null) {
                    named = p[0];
                }
                String old = mth.put(srg, named);
                if (old != null && !old.equals(named)) {
                    conflicts++;
                    mth.put(srg, old);
                }
            } else {
                String srg = p[1];
                if (!srg.startsWith("f_")) {
                    continue;
                }
                Map<String, String> own = fieldsByName.get(cur);
                String named = own == null ? null : own.get(p[0]);
                if (named == null) {
                    named = p[0];
                }
                String old = fld.put(srg, named);
                if (old != null && !old.equals(named)) {
                    conflicts++;
                    fld.put(srg, old);
                }
            }
        }
        // Forge mods already use Mojang class names, so the class table stays empty.
        return new NameTable(Map.of(), mth, fld, conflicts);
    }
}
