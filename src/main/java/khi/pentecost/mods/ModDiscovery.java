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

/** Reads quilt.mod.json, fabric.mod.json, and mods.toml into one mod list. Unpacks jars nested inside mods. */
public final class ModDiscovery {
    private static final String FORGE_MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

    private record Found(List<ModInfo> mods, List<String> nested) {}

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
        // Top-level jars first, then the jars nested inside them. The first copy of a mod id wins.
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
        return mods;
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
            // A jar with several metadata files: Quilt first, then Fabric, then Forge.
            // Fabric jars often carry a stub mods.toml, so Forge comes last.
            List<String> jarjar = new ArrayList<>();
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                    jarjar.add(n);
                }
            }
            Found found;
            JarEntry quilt = jf.getJarEntry("quilt.mod.json");
            JarEntry fabric = jf.getJarEntry("fabric.mod.json");
            JarEntry forge = jf.getJarEntry("META-INF/mods.toml");
            if (quilt != null) {
                found = readQuilt(jar, jf, quilt);
            } else if (fabric != null) {
                found = readFabric(jar, jf, fabric);
            } else if (forge != null) {
                found = readForge(jar, jf, forge);
            } else {
                found = new Found(List.of(), List.of());
            }
            if (jarjar.isEmpty()) {
                return found;
            }
            List<String> nested = new ArrayList<>(found.nested());
            nested.addAll(jarjar);
            return new Found(found.mods(), List.copyOf(nested));
        }
    }

    // ---------------------------------------------------------------- Forge

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
            out.add(new ModInfo(id, name, version, LoaderKind.FORGE, jar, eps, List.copyOf(depends),
                    first ? List.copyOf(mixins) : List.of(), List.of(), null));
            first = false;
        }
        return new Found(out, List.of());
    }

    /** Finds classes carrying Forge's @Mod annotation. Returns mod id to class name. */
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
                // A class file newer than this ASM reads. Not a mod class we can use.
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- Fabric

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

    // ---------------------------------------------------------------- Quilt

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
                // Quilt names its entrypoints init and client_init. Store them under the shared keys.
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

    // ---------------------------------------------------------------- helpers

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

    /** An entrypoint is a string, an object with "value", or an array of those. */
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
