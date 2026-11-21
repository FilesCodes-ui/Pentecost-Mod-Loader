package khi.pentecost.install;

import khi.pentecost.adapter.forge.ForgeFiles;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class ForgeSetup {
    private static final String VERSION = ForgeFiles.VERSION;

    private ForgeSetup() {}

    private static Path dir() {
        return Dirs.cache().resolve("forge-install");
    }

    public static boolean installed() {
        return new ForgeFiles().installed();
    }

    public static boolean ensure() throws IOException, InterruptedException {
        if (installed()) {
            return false;
        }
        for (Path broken : new ForgeFiles().brokenFiles()) {
            Log.info("A Forge file is damaged, setting it up again: " + broken.getFileName());
            Files.deleteIfExists(broken);
        }
        Log.info("First run: installing Forge " + VERSION + " from Forge's own servers. This takes a few minutes.");
        Path dir = Files.createDirectories(dir());
        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + VERSION + "/forge-" + VERSION + "-installer.jar";
        String published = Http.text(url + ".sha1").trim();
        String sha1 = published.split("\\s+")[0];
        if (!sha1.matches("[0-9a-fA-F]{40}")) {
            throw new IOException("Forge's published hash for the installer is not readable: " + published);
        }
        Path installer = Http.download(url, dir.resolve("forge-" + VERSION + "-installer.jar"), sha1);
        Path profiles = dir.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            Files.writeString(profiles, "{\"profiles\":{}}");
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        Process p = new ProcessBuilder(java, "-jar", installer.toString(), "--installClient", dir.toString())
                .directory(dir.toFile()).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.matches("(?i).*(downloading|processor|patching|success|error|building|extracting).*")) {
                    Log.info("Forge installer: " + (line.length() > 150 ? line.substring(0, 150) : line));
                }
            }
        }
        int code = p.waitFor();
        if (code != 0 || !installed()) {
            throw new IOException("The Forge installer did not finish (exit " + code + "). Run again; if it keeps failing, "
                    + "check your internet connection.");
        }
        Log.info("Forge installed");
        return true;
    }

    public static String secureJarHandlerClasspath() throws IOException {
        Path base = dir().resolve("libraries/cpw/mods/securejarhandler");
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (Stream<Path> s = Files.list(base)) {
            return s.filter(Files::isDirectory).sorted().reduce((a, b) -> b).map(d -> d.toAbsolutePath() + java.io.File.separator + "*")
                    .orElse(null);
        }
    }
}
