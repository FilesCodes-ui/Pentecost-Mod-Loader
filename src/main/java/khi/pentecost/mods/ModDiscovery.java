package khi.pentecost.mods;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;
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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Reads mods.toml, quilt.mod.json, and fabric.mod.json into one mod list. */
public final class ModDiscovery {
    private static final String FORGE_MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

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
        for (Path jar : jars) {
            try {
                List<ModInfo> found = read(jar);
                if (found.isEmpty()) {
                    Log.warn(jar.getFileName() + " has no mods.toml, quilt.mod.json, or fabric.mod.json. Skipped.");
                }
                mods.addAll(found);
            } catch (Exception e) {
                Log.error("Cannot read " + jar.getFileName() + ": " + e);
            }
        }
        return mods;
    }

    public static List<ModInfo> read(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry forge = jf.getJarEntry("META-INF/mods.toml");
            if (forge != null) {
                return readForge(jar, jf, forge);
            }
            JarEntry quilt = jf.getJarEntry("quilt.mod.json");
            if (quilt != null) {
                return List.of(readQuilt(jar, jf, quilt));
            }
            JarEntry fabric = jf.getJarEntry("fabric.mod.json");
            if (fabric != null) {
                return List.of(readFabric(jar, jf, fabric));
            }
            return List.of();
        }
    }

    // ---------------------------------------------------------------- Forge

    private static List<ModInfo> readForge(Path jar, JarFile jf, JarEntry entry) throws IOException {
        Config cfg;
        try (Reader r = new InputStreamReader(jf.getInputStream(entry), StandardCharsets.UTF_8)) {
            cfg = new TomlParser().parse(r);
        }
        Map<String, String> modClasses = findForgeModClasses(jf);
        String jarVersion = null;
        Manifest mf = jf.getManifest();
        if (mf != null) {
            jarVersion = mf.getMainAttributes().getValue("Implementation-Version");
        }
        List<ModInfo> out = new ArrayList<>();
        List<Config> mods = cfg.get("mods");
        if (mods == null) {
            return out;
        }
        Config allDeps = cfg.get("dependencies");
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
                        Object mandatory = d.get("mandatory");
                        if (Boolean.TRUE.equals(mandatory)) {
                            depends.add(d.get("modId"));
                        }
                    }
                }
            }
            String cls = modClasses.get(id);
            out.add(new ModInfo(id, name, version, LoaderKind.FORGE, jar,
                    cls == null ? List.of() : List.of(cls), List.of(), List.copyOf(depends)));
        }
        return out;
    }

    /** Finds classes carrying Forge's @Mod annotation. Returns mod id to class name. */
    private static Map<String, String> findForgeModClasses(JarFile jf) throws IOException {
        Map<String, String> found = new java.util.HashMap<>();
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

    private static ModInfo readFabric(Path jar, JarFile jf, JarEntry entry) throws IOException {
        JsonObject o = json(jf, entry);
        String id = o.get("id").getAsString();
        String version = o.has("version") ? o.get("version").getAsString() : "unknown";
        String name = o.has("name") ? o.get("name").getAsString() : id;
        List<String> main = new ArrayList<>();
        List<String> client = new ArrayList<>();
        if (o.has("entrypoints")) {
            JsonObject eps = o.getAsJsonObject("entrypoints");
            entrypoints(eps.get("main"), main);
            entrypoints(eps.get("client"), client);
        }
        List<String> depends = new ArrayList<>();
        if (o.has("depends") && o.get("depends").isJsonObject()) {
            depends.addAll(o.getAsJsonObject("depends").keySet());
        }
        return new ModInfo(id, name, version, LoaderKind.FABRIC, jar,
                List.copyOf(main), List.copyOf(client), List.copyOf(depends));
    }

    // ---------------------------------------------------------------- Quilt

    private static ModInfo readQuilt(Path jar, JarFile jf, JarEntry entry) throws IOException {
        JsonObject loader = json(jf, entry).getAsJsonObject("quilt_loader");
        String id = loader.get("id").getAsString();
        String version = loader.has("version") ? loader.get("version").getAsString() : "unknown";
        String name = id;
        if (loader.has("metadata") && loader.getAsJsonObject("metadata").has("name")) {
            name = loader.getAsJsonObject("metadata").get("name").getAsString();
        }
        List<String> main = new ArrayList<>();
        List<String> client = new ArrayList<>();
        if (loader.has("entrypoints")) {
            JsonObject eps = loader.getAsJsonObject("entrypoints");
            entrypoints(eps.get("init"), main);
            entrypoints(eps.get("main"), main);
            entrypoints(eps.get("client_init"), client);
            entrypoints(eps.get("client"), client);
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
        return new ModInfo(id, name, version, LoaderKind.QUILT, jar,
                List.copyOf(main), List.copyOf(client), List.copyOf(depends));
    }

    // ---------------------------------------------------------------- helpers

    private static JsonObject json(JarFile jf, JarEntry entry) throws IOException {
        try (Reader r = new InputStreamReader(jf.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
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
