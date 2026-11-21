package khi.pentecost.mods;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.install.Http;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

public final class ModDiscovery {
    private static final String FORGE_MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

    private record Found(List<ModInfo> mods, List<String> nested) {}

    public record TwoSided(String key, String file, List<String> sides, String chosen, boolean auto) {}

    public record Refused(String name, String id, String reason) {}

    public static final List<Refused> REFUSED = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final Map<String, String> CANNOT_RUN = Map.of(
            "sodium", "Embeddium is the Forge version",
            "iris", "Oculus is the Forge version",
            "indium", "Embeddium has it built in",
            "sodium-extra", "Embeddium Extra is the Forge version",
            "reeses-sodium-options", "Embeddium has its own options",
            "sodiumextras", "Embeddium Extra is the Forge version");

    public static final Map<String, String> PORTS = Map.of(
            "sodium", "embeddium",
            "iris", "oculus",
            "indium", "embeddium");

    public static boolean met(String dep, Set<String> present) {
        String d = dep.trim().toLowerCase(java.util.Locale.ROOT);
        if (present.contains(d) || present.contains(d.replace('-', '_')) || present.contains(d.replace('_', '-'))) {
            return true;
        }
        String port = PORTS.get(d);
        if (port != null && present.contains(port)) {
            return true;
        }
        boolean fabricApi = present.stream().anyMatch(p -> p.startsWith("fabric_") || p.startsWith("fabric-"));
        if (fabricApi && (d.equals("fabric-api") || d.equals("fabric") || d.equals("fabric_api")
                || d.equals("quilted_fabric_api") || d.equals("qsl") || d.startsWith("quilt_"))) {
            return true;
        }
        return false;
    }

    private static boolean preferForge;

    public static final java.util.Map<String, TwoSided> TWO_SIDED = new java.util.concurrent.ConcurrentSkipListMap<>();

    public static String sideSetting(String key) {
        return "mods.side." + key;
    }

    public static String sideKey(String fileName) {
        String n = fileName.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jar")) {
            n = n.substring(0, n.length() - 4);
        }
        n = n.replaceAll("[-_+ .]?(mc)?\\d.*$", "");
        n = n.replaceAll("[-_+ .](forge|fabric|quilt|neoforge)$", "");
        return n.isEmpty() ? fileName.toLowerCase(java.util.Locale.ROOT) : n;
    }

    private ModDiscovery() {}

    public static List<ModInfo> scan(Path modsDir) throws IOException {
        List<ModInfo> mods = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return mods;
        }
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        preferForge = false;
        REFUSED.clear();
        for (Path j : jars) {
            try (JarFile jf = new JarFile(j.toFile())) {
                if (jf.getJarEntry("META-INF/mods.toml") != null && jf.getJarEntry("fabric.mod.json") == null
                        && jf.getJarEntry("quilt.mod.json") == null) {
                    preferForge = true;
                    break;
                }
            } catch (IOException ignored) {
            }
        }

        Deque<Path> todo = new ArrayDeque<>(jars);
        Set<String> topLevel = new HashSet<>();
        for (Path j : jars) {
            topLevel.add(j.toString());
        }
        Set<String> ids = new HashSet<>();
        while (!todo.isEmpty()) {
            Path jar = todo.poll();
            boolean top = topLevel.contains(jar.toString());
            try {
                Found found = read(jar);
                if (top && !khi.pentecost.boot.StartupGuard.offIds().isEmpty()
                        && found.mods.stream().anyMatch(m -> khi.pentecost.boot.StartupGuard.offIds().contains(m.id()))) {
                    Log.warn(jar.getFileName() + " is turned off by the startup guard because it broke startup. Skipped.");
                    continue;
                }
                if (found.mods.isEmpty()) {
                    if (top && found.nested.isEmpty()) {
                        Log.warn(jar.getFileName() + " has no quilt.mod.json, fabric.mod.json, or mods.toml. Skipped.");
                        continue;
                    }
                    String id = "lib_" + jar.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]", "_");
                    if (ids.add(id)) {
                        mods.add(new ModInfo(id, jar.getFileName().toString(), "", LoaderKind.LIBRARY, jar,
                                Map.of(), List.of(), List.of(), List.of(), null));
                    }
                }
                for (ModInfo m : found.mods) {
                    if (ids.add(m.id())) {
                        mods.add(m);
                    }
                }
                if (!found.nested.isEmpty()) {
                    todo.addAll(extractNested(jar, found.nested));
                }
            } catch (Exception e) {
                Log.error("Cannot read " + jar.getFileName() + ": " + e);
            }
        }
        if (preferForge) {
            leaveOutWhatCannotRun(mods);
        }
        return mods;
    }

    private static void leaveOutWhatCannotRun(List<ModInfo> mods) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (ModInfo m : mods) {
            if (m.loader() != LoaderKind.FORGE && CANNOT_RUN.containsKey(m.id())) {
                out.put(m.id(), CANNOT_RUN.get(m.id()));
            }
        }

        Set<String> present = new HashSet<>();
        for (ModInfo m : mods) {
            if (!out.containsKey(m.id())) {
                present.add(m.id());
            }
        }
        boolean more = true;
        while (more) {
            more = false;
            for (ModInfo m : mods) {
                if (out.containsKey(m.id()) || m.loader() == LoaderKind.FORGE) {
                    continue;
                }
                for (String d : m.depends()) {
                    String dep = d.trim().toLowerCase(java.util.Locale.ROOT);
                    if ((out.containsKey(dep) || CANNOT_RUN.containsKey(dep)) && !met(dep, present)) {
                        out.put(m.id(), "it needs " + dep + ", which cannot run with Forge mods, and the pack has no Forge version of it");
                        present.remove(m.id());
                        more = true;
                        break;
                    }
                }
            }
        }
        for (ModInfo m : mods) {
            for (String d : m.depends()) {
                String dep = d.trim().toLowerCase(java.util.Locale.ROOT);
                if (!out.containsKey(m.id()) && PORTS.containsKey(dep) && present.contains(PORTS.get(dep))) {
                    Log.info(m.name() + " needs " + dep + "; it runs against " + PORTS.get(dep) + ", the Forge version");
                }
            }
        }
        if (out.isEmpty()) {
            return;
        }
        mods.removeIf(m -> {
            String why = out.get(m.id());
            if (why == null) {
                return false;
            }
            REFUSED.add(new Refused(m.name(), m.id(), why));
            Log.warn(m.name() + " (" + m.id() + ") is not loaded: it cannot run in a pack with Forge mods. " + why + ".");
            return true;
        });
    }

    private static List<Path> extractNested(Path jar, List<String> entries) throws IOException {
        Path dir = Dirs.mk(Dirs.cache().resolve("nested").resolve(Http.sha1(jar).substring(0, 12)));
        List<Path> out = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (String name : entries) {
                JarEntry e = jf.getJarEntry(name);
                if (e == null) {
                    continue;
                }
                Path dest = dir.resolve(Path.of(name).getFileName().toString());
                if (!Files.isRegularFile(dest) || Files.size(dest) != e.getSize()) {
                    try (InputStream in = jf.getInputStream(e)) {
                        Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                out.add(dest);
            }
        }
        return out;
    }

    private static Found read(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            List<String> jarjar = new ArrayList<>();
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                    jarjar.add(n);
                }
            }

            JarEntry jarjarMeta = jf.getJarEntry("META-INF/jarjar/metadata.json");
            if (jarjarMeta != null) {
                try {
                    JsonElement listed = json(jf, jarjarMeta).get("jars");
                    if (listed != null && listed.isJsonArray()) {
                        for (JsonElement x : listed.getAsJsonArray()) {
                            if (x.isJsonObject() && x.getAsJsonObject().has("path")) {
                                String p = x.getAsJsonObject().get("path").getAsString();
                                if (p.endsWith(".jar") && !jarjar.contains(p)) {
                                    jarjar.add(p);
                                }
                            }
                        }
                    }
                } catch (RuntimeException | IOException badMetadata) {
                }
            }
            Found found;
            JarEntry quilt = jf.getJarEntry("quilt.mod.json");
            JarEntry fabric = jf.getJarEntry("fabric.mod.json");
            JarEntry forge = jf.getJarEntry("META-INF/mods.toml");
            List<String> sides = new ArrayList<>();
            if (quilt != null) {
                sides.add("quilt");
            }
            if (fabric != null) {
                sides.add("fabric");
            }
            if (forge != null) {
                sides.add("forge");
            }

            String chosen = sides.isEmpty() ? "" : sides.get(0);
            if (sides.size() > 1 && sides.contains("forge") && preferForge && !findForgeModClasses(jf).isEmpty()) {
                chosen = "forge";
            }
            if (sides.size() > 1) {
                String key = sideKey(jar.getFileName().toString());
                String want = "";
                try {
                    want = khi.pentecost.control.Settings.get(sideSetting(key)).trim().toLowerCase(java.util.Locale.ROOT);
                } catch (RuntimeException | LinkageError e) {
                }
                boolean auto = !sides.contains(want);
                if (!auto) {
                    chosen = want;
                    Log.info(jar.getFileName() + " has " + String.join(", ", sides) + " sides; loading " + chosen + " as chosen in settings");
                }
                TWO_SIDED.put(key, new TwoSided(key, jar.getFileName().toString(), List.copyOf(sides), chosen, auto));
            }
            switch (chosen) {
                case "quilt" -> found = readQuilt(jar, jf, quilt);
                case "fabric" -> found = readFabric(jar, jf, fabric);
                case "forge" -> found = readForge(jar, jf, forge);
                default -> found = new Found(List.of(), List.of());
            }
            if (jarjar.isEmpty()) {
                return found;
            }
            List<String> nested = new ArrayList<>(found.nested());
            nested.addAll(jarjar);
            return new Found(found.mods(), List.copyOf(nested));
        }
    }

    private static Found readForge(Path jar, JarFile jf, JarEntry entry) throws IOException {
        Config cfg;
        try (Reader r = new InputStreamReader(jf.getInputStream(entry), StandardCharsets.UTF_8)) {
            cfg = new TomlParser().parse(r);
        }
        Map<String, String> modClasses = findForgeModClasses(jf);
        String jarVersion = null;
        List<String> mixins = new ArrayList<>();
        Manifest mf = jf.getManifest();
        if (mf != null) {
            jarVersion = mf.getMainAttributes().getValue("Implementation-Version");
            String mc = mf.getMainAttributes().getValue("MixinConfigs");
            if (mc != null) {
                for (String s : mc.split(",")) {
                    if (!s.isBlank()) {
                        mixins.add(s.trim());
                    }
                }
            }
        }
        List<ModInfo> out = new ArrayList<>();
        List<Config> mods = cfg.get("mods");
        if (mods == null) {
            return new Found(out, List.of());
        }
        Config allDeps = cfg.get("dependencies");
        boolean first = true;
        for (Config m : mods) {
            String id = m.get("modId");
            String version = String.valueOf(m.<Object>getOrElse("version", "unknown"));
            if (version.contains("${")) {
                version = jarVersion != null ? jarVersion : "unknown";
            }
            String name = m.getOrElse("displayName", id);
            List<String> depends = new ArrayList<>();
            if (allDeps != null) {
                List<Config> deps = allDeps.get(List.of(id));
                if (deps != null) {
                    for (Config d : deps) {
                        if (Boolean.TRUE.equals(d.get("mandatory"))) {
                            depends.add(d.get("modId"));
                        }
                    }
                }
            }
            String cls = modClasses.get(id);
            Map<String, List<String>> eps = cls == null ? Map.of() : Map.of("main", List.of(cls));
            JsonObject raw = new JsonObject();
            if (m.get("description") != null) {
                raw.addProperty("description", String.valueOf(m.<Object>get("description")));
            }
            if (m.get("logoFile") != null) {
                raw.addProperty("logoFile", String.valueOf(m.<Object>get("logoFile")));
            } else if (cfg.get("logoFile") != null) {
                raw.addProperty("logoFile", String.valueOf(cfg.<Object>get("logoFile")));
            }
            Object provides = m.get("provides");
            if (provides instanceof List<?> list && !list.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (Object o : list) {
                    arr.add(String.valueOf(o));
                }
                raw.add("provides", arr);
            }
            out.add(new ModInfo(id, name, version, LoaderKind.FORGE, jar, eps, List.copyOf(depends),
                    first ? List.copyOf(mixins) : List.of(), List.of(), raw));
            first = false;
        }
        return new Found(out, List.of());
    }

    private static Map<String, String> findForgeModClasses(JarFile jf) throws IOException {
        Map<String, String> found = new HashMap<>();
        Enumeration<JarEntry> entries = jf.entries();
        while (entries.hasMoreElements()) {
            JarEntry e = entries.nextElement();
            if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) {
                continue;
            }
            try (InputStream in = jf.getInputStream(e)) {
                ClassReader cr = new ClassReader(in);
                String className = cr.getClassName().replace('/', '.');
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (!FORGE_MOD_ANNOTATION.equals(desc)) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if ("value".equals(name)) {
                                    found.put(String.valueOf(value), className);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (RuntimeException ex) {
            }
        }
        return found;
    }

    private static Found readFabric(Path jar, JarFile jf, JarEntry entry) throws IOException {
        JsonObject o = json(jf, entry);
        String id = o.get("id").getAsString();
        String version = o.has("version") ? o.get("version").getAsString() : "unknown";
        String name = o.has("name") ? o.get("name").getAsString() : id;
        Map<String, List<String>> eps = new LinkedHashMap<>();
        if (o.has("entrypoints") && o.get("entrypoints").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("entrypoints").entrySet()) {
                List<String> list = new ArrayList<>();
                entrypoints(e.getValue(), list);
                eps.put(e.getKey(), List.copyOf(list));
            }
        }
        List<String> depends = new ArrayList<>();
        if (o.has("depends") && o.get("depends").isJsonObject()) {
            depends.addAll(o.getAsJsonObject("depends").keySet());
        }
        List<String> mixins = new ArrayList<>();
        if (o.has("mixins") && o.get("mixins").isJsonArray()) {
            for (JsonElement m : o.getAsJsonArray("mixins")) {
                if (m.isJsonPrimitive()) {
                    mixins.add(m.getAsString());
                } else if (m.isJsonObject() && m.getAsJsonObject().has("config")) {
                    JsonObject mo = m.getAsJsonObject();
                    String env = mo.has("environment") ? mo.get("environment").getAsString() : "*";
                    if (!env.equals("server")) {
                        mixins.add(mo.get("config").getAsString());
                    }
                }
            }
        }
        List<String> aw = o.has("accessWidener") ? List.of(o.get("accessWidener").getAsString()) : List.of();
        List<String> nested = new ArrayList<>();
        if (o.has("jars") && o.get("jars").isJsonArray()) {
            for (JsonElement j : o.getAsJsonArray("jars")) {
                if (j.isJsonObject() && j.getAsJsonObject().has("file")) {
                    nested.add(j.getAsJsonObject().get("file").getAsString());
                }
            }
        }
        ModInfo mod = new ModInfo(id, name, version, LoaderKind.FABRIC, jar, eps, List.copyOf(depends),
                List.copyOf(mixins), aw, o);
        return new Found(List.of(mod), nested);
    }

    private static Found readQuilt(Path jar, JarFile jf, JarEntry entry) throws IOException {
        JsonObject root = json(jf, entry);
        JsonObject loader = root.getAsJsonObject("quilt_loader");
        String id = loader.get("id").getAsString();
        String version = loader.has("version") ? loader.get("version").getAsString() : "unknown";
        String name = id;
        if (loader.has("metadata") && loader.getAsJsonObject("metadata").has("name")) {
            name = loader.getAsJsonObject("metadata").get("name").getAsString();
        }
        Map<String, List<String>> eps = new LinkedHashMap<>();
        if (loader.has("entrypoints") && loader.get("entrypoints").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : loader.getAsJsonObject("entrypoints").entrySet()) {
                List<String> list = new ArrayList<>();
                entrypoints(e.getValue(), list);

                String key = switch (e.getKey()) {
                    case "init" -> "main";
                    case "client_init" -> "client";
                    case "server_init" -> "server";
                    case "pre_launch" -> "preLaunch";
                    default -> e.getKey();
                };
                List<String> merged = new ArrayList<>(eps.getOrDefault(key, List.of()));
                merged.addAll(list);
                eps.put(key, List.copyOf(merged));
            }
        }
        List<String> depends = new ArrayList<>();
        if (loader.has("depends") && loader.get("depends").isJsonArray()) {
            for (JsonElement d : loader.getAsJsonArray("depends")) {
                if (d.isJsonPrimitive()) {
                    depends.add(d.getAsString());
                } else if (d.isJsonObject() && d.getAsJsonObject().has("id")) {
                    JsonObject dep = d.getAsJsonObject();
                    boolean optional = dep.has("optional") && dep.get("optional").getAsBoolean();
                    if (!optional) {
                        depends.add(dep.get("id").getAsString());
                    }
                }
            }
        }
        List<String> mixins = new ArrayList<>();
        strings(root.get("mixin"), mixins);
        List<String> aw = new ArrayList<>();
        strings(root.get("access_widener"), aw);
        List<String> nested = new ArrayList<>();
        strings(loader.get("jars"), nested);
        ModInfo mod = new ModInfo(id, name, version, LoaderKind.QUILT, jar, eps, List.copyOf(depends),
                List.copyOf(mixins), List.copyOf(aw), root);
        return new Found(List.of(mod), nested);
    }

    private static JsonObject json(JarFile jf, JarEntry entry) throws IOException {
        try (Reader r = new InputStreamReader(jf.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static void strings(JsonElement e, List<String> out) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive()) {
                    out.add(x.getAsString());
                }
            }
        }
    }

    private static void entrypoints(JsonElement e, List<String> out) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
        } else if (e.isJsonObject() && e.getAsJsonObject().has("value")) {
            out.add(e.getAsJsonObject().get("value").getAsString());
        } else if (e.isJsonArray()) {
            JsonArray arr = e.getAsJsonArray();
            for (JsonElement x : arr) {
                entrypoints(x, out);
            }
        }
    }
}
