package khi.pentecost.control;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.PentecostMain;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.install.Http;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackExport {
    private static final long MAX_CONFIG_FILE = 4L * 1024 * 1024;

    private static final Pattern SECRET = Pattern.compile(
            "(?im)(token|secret|passw|api[_-]?key|webhook|credential|private[_-]?key|access[_-]?key|authorization)"
                    + "[\\w.-]*[ \\t]*[=:][ \\t]*[\"']?[^\\s\"',]{6,}");
    private static final Pattern LAST_SERVER = Pattern.compile("(?m)^lastServer:[ \\t]*\\S+");

    public static volatile List<String> lastFlagged = List.of();

    private PackExport() {}

    public static Path export() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path out = Dirs.mk(Dirs.home().resolve("exports")).resolve("pentecost-pack-" + stamp + ".zip");
        List<ModInfo> all = PentecostRuntime.allMods.isEmpty() ? PentecostRuntime.mods : PentecostRuntime.allMods;

        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("pentecost", PentecostMain.VERSION);
        root.addProperty("minecraft", PentecostMain.MINECRAFT);
        root.addProperty("forgeLayer", PentecostRuntime.forgeLayer);
        root.addProperty("created", stamp);
        JsonArray mods = new JsonArray();
        Set<Path> seen = new HashSet<>();
        for (ModInfo m : all) {
            Path jar = m.jar();
            if (m.loader() == LoaderKind.LIBRARY || jar.toString().contains("nested") || jar.toString().contains("bridge")) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id());
            o.addProperty("name", m.name());
            o.addProperty("version", m.version());
            o.addProperty("loader", m.loader().label);
            o.addProperty("file", jar.getFileName().toString());
            o.addProperty("enabled", !Choices.isModOff(m.id()));
            if (seen.add(jar) && Files.isRegularFile(jar)) {
                o.addProperty("sha1", Http.sha1(jar));
            }
            mods.add(o);
        }
        root.add("mods", mods);

        Path run = Dirs.run();
        Path options = run.resolve("options.txt");
        Path config = run.resolve("config");
        List<Path> configFiles = new ArrayList<>();
        if (Files.isDirectory(config)) {
            try (Stream<Path> walk = Files.walk(config)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    if (Files.size(p) <= MAX_CONFIG_FILE) {
                        configFiles.add(p);
                    }
                }
            }
        }
        List<String> flagged = new ArrayList<>();
        if (Files.isRegularFile(options) && matches(options, LAST_SERVER)) {
            flagged.add("options.txt (the address of the last server you joined)");
        }
        for (Path p : configFiles) {
            if (matches(p, SECRET)) {
                flagged.add("config/" + config.relativize(p).toString().replace('\\', '/'));
            }
        }
        lastFlagged = List.copyOf(flagged);

        StringBuilder readme = new StringBuilder();
        readme.append("Pentecost pack, made ").append(stamp).append("\n\n")
                .append("pentecost-pack.json lists every mod with its file name and checksum.\n")
                .append("config/ holds the config files, including the player's choices in config/pentecost/choices.json.\n")
                .append("To use it: put the listed mod jars in Pentecost's mods folder, copy config/ into the run folder, start the game.\n\n")
                .append("BEFORE YOU SHARE THIS ZIP\n")
                .append("Config files are copied exactly as they are. Some mods keep passwords, tokens, keys or webhook addresses in them, ")
                .append("and options.txt holds the address of the last server you joined.\n");
        if (flagged.isEmpty()) {
            readme.append("Pentecost found nothing that looks like a key or an address. Look through the files yourself anyway.\n");
        } else {
            readme.append("These files look like they hold a key, a password or an address:\n");
            for (String f : flagged) {
                readme.append("  - ").append(f).append('\n');
            }
            readme.append("Open them and remove those lines, or take the files out of the zip, before you give it to anyone.\n");
        }

        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "pentecost-pack.json",
                    new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8));
            put(zip, "README.txt", readme.toString().getBytes(StandardCharsets.UTF_8));
            if (Files.isRegularFile(options)) {
                put(zip, "options.txt", Files.readAllBytes(options));
            }
            for (Path p : configFiles) {
                put(zip, "config/" + config.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    private static boolean matches(Path file, Pattern pattern) {
        try {
            byte[] data = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(512, data.length); i++) {
                if (data[i] == 0) {
                    return false;
                }
            }
            return pattern.matcher(new String(data, StandardCharsets.UTF_8)).find();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
