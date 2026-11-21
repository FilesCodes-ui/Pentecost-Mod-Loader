package khi.pentecost.install;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MinecraftInstall {
    public record GameFiles(String version, Path clientJar, Path clientMappings, List<Path> libraries,
                            Path assetsDir, String assetIndex, String mainClass) {}

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";

    private MinecraftInstall() {}

    public static GameFiles install(String version, boolean needAssets) throws IOException {
        Path base = Dirs.mk(Dirs.cache().resolve("minecraft").resolve(version));
        Path versionJson = base.resolve(version + ".json");
        if (!Files.isRegularFile(versionJson)) {
            Log.info("Fetching version list from Mojang");
            JsonObject manifest = JsonParser.parseString(Http.text(MANIFEST)).getAsJsonObject();
            String url = null;
            String sha = null;
            for (JsonElement e : manifest.getAsJsonArray("versions")) {
                JsonObject v = e.getAsJsonObject();
                if (version.equals(v.get("id").getAsString())) {
                    url = v.get("url").getAsString();
                    sha = v.get("sha1").getAsString();
                }
            }
            if (url == null) {
                throw new IOException("Unknown Minecraft version " + version);
            }
            Http.download(url, versionJson, sha);
        }
        JsonObject meta = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
        JsonObject downloads = meta.getAsJsonObject("downloads");

        Path clientJar = fetch(downloads.getAsJsonObject("client"), base.resolve("client.jar"));
        Path mappings = fetch(downloads.getAsJsonObject("client_mappings"), base.resolve("client.txt"));

        Path libDir = Dirs.mk(Dirs.cache().resolve("libraries"));
        List<Path> libraries = new ArrayList<>();
        List<Callable<Path>> jobs = new ArrayList<>();
        for (JsonElement e : meta.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            if (!allowed(lib)) {
                continue;
            }
            JsonObject dl = lib.getAsJsonObject("downloads");
            if (dl == null || !dl.has("artifact")) {
                continue;
            }
            JsonObject artifact = dl.getAsJsonObject("artifact");
            Path dest = libDir.resolve(artifact.get("path").getAsString());
            libraries.add(dest);
            jobs.add(() -> Http.download(artifact.get("url").getAsString(), dest, artifact.get("sha1").getAsString()));
        }
        runAll(jobs, 12, "libraries");

        JsonObject assetIndex = meta.getAsJsonObject("assetIndex");
        String indexId = assetIndex.get("id").getAsString();
        Path assetsDir = null;
        if (needAssets) {
            assetsDir = findExistingAssets(indexId);
            if (assetsDir != null) {
                Log.info("Using existing assets at " + assetsDir);
            } else {
                assetsDir = downloadAssets(assetIndex, indexId);
            }
        }
        return new GameFiles(version, clientJar, mappings, List.copyOf(libraries), assetsDir, indexId,
                meta.get("mainClass").getAsString());
    }

    private static Path fetch(JsonObject entry, Path dest) throws IOException {
        if (!Files.isRegularFile(dest)) {
            Log.info("Downloading " + dest.getFileName());
        }
        return Http.download(entry.get("url").getAsString(), dest, entry.get("sha1").getAsString());
    }

    static boolean allowed(JsonObject lib) {
        if (!lib.has("rules")) {
            return true;
        }
        boolean allow = false;
        for (JsonElement e : lib.getAsJsonArray("rules")) {
            JsonObject rule = e.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals(osName())) {
                    matches = false;
                }
                if (os.has("arch") && os.get("arch").getAsString().equals("x86") && is64Bit()) {
                    matches = false;
                }
            }
            if (matches) {
                allow = rule.get("action").getAsString().equals("allow");
            }
        }
        return allow;
    }

    static String osName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "osx";
        }
        return "linux";
    }

    private static boolean is64Bit() {
        return System.getProperty("os.arch").contains("64");
    }

    private static Path findExistingAssets(String indexId) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Dirs.cache().resolve("assets"));
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            candidates.add(Path.of(appData, "ModrinthApp", "meta", "assets"));
            candidates.add(Path.of(appData, "com.modrinth.theseus", "meta", "assets"));
            candidates.add(Path.of(appData, ".minecraft", "assets"));
        }
        for (Path dir : candidates) {
            if (Files.isRegularFile(dir.resolve("indexes").resolve(indexId + ".json"))
                    && Files.isDirectory(dir.resolve("objects"))) {
                return dir;
            }
        }
        return null;
    }

    private static Path downloadAssets(JsonObject assetIndex, String indexId) throws IOException {
        Path assetsDir = Dirs.mk(Dirs.cache().resolve("assets"));
        Path indexFile = assetsDir.resolve("indexes").resolve(indexId + ".json");
        Http.download(assetIndex.get("url").getAsString(), indexFile, assetIndex.get("sha1").getAsString());
        JsonObject objects = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject().getAsJsonObject("objects");
        List<Callable<Path>> jobs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String hash = o.get("hash").getAsString();
            long size = o.get("size").getAsLong();
            String rel = hash.substring(0, 2) + "/" + hash;
            Path dest = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            jobs.add(() -> {
                if (Files.isRegularFile(dest) && Files.size(dest) == size) {
                    return dest;
                }
                return Http.download(RESOURCES + rel, dest, hash);
            });
        }
        Log.info("Downloading " + jobs.size() + " asset files");
        runAll(jobs, 16, "assets");
        return assetsDir;
    }

    private static void runAll(List<Callable<Path>> jobs, int threads, String what) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Path>> futures = new ArrayList<>();
            for (Callable<Path> job : jobs) {
                futures.add(pool.submit(job));
            }
            for (Future<Path> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw new IOException("Failed to download " + what + ": " + e.getCause().getMessage(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading " + what, e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
