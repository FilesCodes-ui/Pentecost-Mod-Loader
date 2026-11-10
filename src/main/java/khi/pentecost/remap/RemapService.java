package khi.pentecost.remap;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.install.Http;
import khi.pentecost.install.MinecraftInstall.GameFiles;
import khi.pentecost.mods.ModInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Converts the game and every mod to readable names, once. Results sit in the cache folder,
 * so later launches skip all of this work. Mapping files load only when something needs converting.
 */
public final class RemapService {
    private static final String INTERMEDIARY =
            "https://maven.fabricmc.net/net/fabricmc/intermediary/%s/intermediary-%s-v2.jar";
    private static final String MCP_CONFIG_1_20_1 =
            "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip";

    private final GameFiles vanilla;
    private Mappings mojang;
    private Map<String, String[]> hierarchy;
    private Mappings.NameTable intermediary;
    private Mappings.NameTable srg;

    public RemapService(GameFiles vanilla) {
        this.vanilla = vanilla;
    }

    /** Returns the game files with the client jar swapped for the readable-name copy. */
    public GameFiles game() throws IOException {
        Path out = Dirs.cache().resolve("remapped").resolve("client-" + vanilla.version() + "-named.jar");
        if (!Files.isRegularFile(out)) {
            long t = System.nanoTime();
            Log.info("Converting Minecraft to readable names (first launch only)");
            JarRemapper.remap(vanilla.clientJar(), out, new GameRemapper(mojang(), hierarchy()));
            Log.info(String.format("Converted the game in %.1f s", (System.nanoTime() - t) / 1e9));
        }
        return new GameFiles(vanilla.version(), out, vanilla.clientMappings(), vanilla.libraries(),
                vanilla.assetsDir(), vanilla.assetIndex(), vanilla.mainClass());
    }

    /** Returns the mod list with each jar swapped for its readable-name copy. */
    public List<ModInfo> mods(List<ModInfo> mods) throws IOException {
        List<ModInfo> out = new ArrayList<>();
        Path dir = Dirs.mk(Dirs.cache().resolve("mods-v2"));
        for (ModInfo m : mods) {
            String hash = Http.sha1(m.jar()).substring(0, 12);
            Path dest = dir.resolve(hash + "-" + m.jar().getFileName());
            if (!Files.isRegularFile(dest)) {
                long t = System.nanoTime();
                JarRemapper.remap(m.jar(), dest, new NameRemapper(modTable()));
                Log.info(String.format("Converted %s in %.2f s", m.jar().getFileName(), (System.nanoTime() - t) / 1e9));
            }
            out.add(new ModInfo(m.id(), m.name(), m.version(), m.loader(), dest,
                    m.mainEntrypoints(), m.clientEntrypoints(), m.depends()));
        }
        return out;
    }

    private Mappings.NameTable merged;

    private Mappings.NameTable modTable() throws IOException {
        if (merged == null) {
            Mappings.NameTable a = intermediary();
            Mappings.NameTable b = srg();
            Map<String, String> methods = new java.util.HashMap<>(a.methods());
            methods.putAll(b.methods());
            Map<String, String> fields = new java.util.HashMap<>(a.fields());
            fields.putAll(b.fields());
            merged = new Mappings.NameTable(a.classes(), methods, fields, a.conflicts() + b.conflicts());
        }
        return merged;
    }

    private Mappings mojang() throws IOException {
        if (mojang == null) {
            mojang = Mappings.parseProguard(vanilla.clientMappings());
        }
        return mojang;
    }

    private Map<String, String[]> hierarchy() throws IOException {
        if (hierarchy == null) {
            hierarchy = GameRemapper.readHierarchy(vanilla.clientJar());
        }
        return hierarchy;
    }

    private Mappings.NameTable intermediary() throws IOException {
        if (intermediary == null) {
            String v = vanilla.version();
            Path jar = Http.download(String.format(INTERMEDIARY, v, v),
                    Dirs.cache().resolve("mappings").resolve("intermediary-" + v + "-v2.jar"), null);
            intermediary = mojang().intermediary(readEntry(jar, "mappings/mappings.tiny"));
            Log.info("Fabric names loaded: " + intermediary.classes().size() + " classes, "
                    + intermediary.methods().size() + " methods, " + intermediary.fields().size()
                    + " fields, " + intermediary.conflicts() + " conflicts");
        }
        return intermediary;
    }

    private Mappings.NameTable srg() throws IOException {
        if (srg == null) {
            if (!vanilla.version().equals("1.20.1")) {
                throw new IOException("No Forge name table is configured for Minecraft " + vanilla.version());
            }
            Path zip = Http.download(MCP_CONFIG_1_20_1,
                    Dirs.cache().resolve("mappings").resolve("mcp_config-1.20.1.zip"), null);
            srg = mojang().srg(readEntry(zip, "config/joined.tsrg"));
            Log.info("Forge names loaded: " + srg.methods().size() + " methods, " + srg.fields().size()
                    + " fields, " + srg.conflicts() + " conflicts");
        }
        return srg;
    }

    private static List<String> readEntry(Path zip, String name) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(name);
            if (e == null) {
                throw new IOException(name + " is missing from " + zip.getFileName());
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8))) {
                return r.lines().toList();
            }
        }
    }
}
