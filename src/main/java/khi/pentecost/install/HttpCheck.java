package khi.pentecost.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Headless check for Http's address rules and pinned hashes. It makes no network calls. */
public final class HttpCheck {
    private static int failures;

    private HttpCheck() {}

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static boolean accepted(String url) {
        try {
            Http.checkUrl(url);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        String[] good = {
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "https://piston-data.mojang.com/v1/objects/abc/client.jar",
                "https://libraries.minecraft.net/com/x/y.jar",
                "https://resources.download.minecraft.net/ab/abcdef",
                "https://api.modrinth.com/v2/project/x/version",
                "https://cdn.modrinth.com/data/x/versions/y/file.jar",
                "https://maven.fabricmc.net/a/b.jar",
                "https://maven.minecraftforge.net/a/b.zip",
                "https://maven.neoforged.net/releases/a.jar"};
        for (String u : good) {
            expect(accepted(u), "accepted " + u);
        }
        String[] bad = {
                "http://piston-meta.mojang.com/x",                  // not https
                "ftp://maven.fabricmc.net/x",
                "https://evil.example.com/x.jar",
                "https://mojang.com.evil.example.com/x.jar",        // trusted name used as a prefix
                "https://evilmojang.com/x.jar",                     // trusted name glued to another
                "https://notminecraft.net/x.jar",
                "https://localhost/x.jar",
                "https://127.0.0.1/x.jar",
                "https://192.168.1.5/x.jar",
                "file:///C:/Windows/System32/x.dll",
                "https:///x.jar",
                "not a url"};
        for (String u : bad) {
            expect(!accepted(u), "refused " + u);
        }

        // The pinned files: a pin exists, and the copies in the cache (if present) still match it.
        String intermediary = "https://maven.fabricmc.net/net/fabricmc/intermediary/1.20.1/intermediary-1.20.1-v2.jar";
        String mcp = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip";
        expect(Http.pinned(intermediary) != null, "intermediary is pinned");
        expect(Http.pinned(mcp) != null, "mcp_config is pinned");
        expect(Http.pinned("https://maven.fabricmc.net/other.jar") == null, "other files are not pinned");
        Path cache = Path.of("cache", "mappings");
        Path i = cache.resolve("intermediary-1.20.1-v2.jar");
        Path m = cache.resolve("mcp_config-1.20.1.zip");
        if (Files.isRegularFile(i)) {
            expect(Http.sha1(i).equals(Http.pinned(intermediary)), "cached intermediary matches its pin");
        }
        if (Files.isRegularFile(m)) {
            expect(Http.sha1(m).equals(Http.pinned(mcp)), "cached mcp_config matches its pin");
        }

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("HttpCheck passed");
    }
}
