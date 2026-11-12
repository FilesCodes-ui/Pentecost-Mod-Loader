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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a setup into one zip to share or back up: the list of mods, the player's choices,
 * and every config file. Mod jars stay out. The list names each file and its checksum,
 * so the same jars can be fetched again.
 */
public final class PackExport {
    private static final long MAX_CONFIG_FILE = 4L * 1024 * 1024;

    private PackExport() {}

    /** Writes the zip and returns where it went. */
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
            // Jars nested inside another mod come with that mod. Only files the player installed are listed.
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

        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
            put(zip, "pentecost-pack.json",
                    new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8));
            put(zip, "README.txt", ("Pentecost pack, made " + stamp + "\n\n"
                    + "pentecost-pack.json lists every mod with its file name and checksum.\n"
                    + "config/ holds the config files, including the player's choices in config/pentecost/choices.json.\n"
                    + "To use it: put the listed mod jars in Pentecost's mods folder, copy config/ into the run folder, start the game.\n")
                    .getBytes(StandardCharsets.UTF_8));
            Path run = Dirs.run();
            Path options = run.resolve("options.txt");
            if (Files.isRegularFile(options)) {
                put(zip, "options.txt", Files.readAllBytes(options));
            }
            Path config = run.resolve("config");
            if (Files.isDirectory(config)) {
                try (Stream<Path> walk = Files.walk(config)) {
                    for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                        if (Files.size(p) <= MAX_CONFIG_FILE) {
                            put(zip, "config/" + config.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
