package khi.pentecost.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Downloads with retries and SHA-1 checks.
 *
 * Every download must use https and come from a host Pentecost expects (Mojang, Minecraft, Modrinth, Fabric,
 * Forge, NeoForged). Files that no caller gave a hash for are checked against the pinned hashes below.
 */
public final class Http {
    public static final String USER_AGENT =
            "KHI-Pentecost/0.1.0 (Minecraft mod loader compatibility tooling)";

    /** Domains downloads may come from, with their subdomains. */
    private static final String[] TRUSTED = {
            "mojang.com", "minecraft.net", "modrinth.com", "fabricmc.net", "minecraftforge.net", "neoforged.net"};

    /**
     * Files that are fetched by a fixed address and must always be the same bytes.
     * Verified against the checksums published next to them on the Fabric and Forge Maven servers.
     */
    private static final Map<String, String> PINNED = Map.of(
            "https://maven.fabricmc.net/net/fabricmc/intermediary/1.20.1/intermediary-1.20.1-v2.jar",
            "20ba9399cb5a23aa06ea901eff0a84e648d866db",
            "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip",
            "c7d29380ddb38becad7c0819b5b325e43bca23f0");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private Http() {}

    /** Throws unless the address is https and on a trusted domain. */
    static void checkUrl(String url) throws IOException {
        URI u;
        try {
            u = URI.create(url.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Refusing a download with a malformed address");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) {
            throw new IOException("Refusing a download that is not https: " + u.getHost());
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        for (String domain : TRUSTED) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return;
            }
        }
        throw new IOException("Refusing a download from an unexpected host: " + host);
    }

    /** The pinned hash for an address, or null when there is none. */
    static String pinned(String url) {
        return PINNED.get(url);
    }

    public static String text(String url) throws IOException {
        return send(url, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Downloads url to dest. Skips the download when dest exists and matches sha1 (or sha1 is null). */
    public static Path download(String url, Path dest, String sha1) throws IOException {
        if (sha1 == null) {
            sha1 = pinned(url);
        }
        if (Files.isRegularFile(dest) && (sha1 == null || sha1.equalsIgnoreCase(sha1(dest)))) {
            return dest;
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part" + Thread.currentThread().threadId());
        try {
            send(url, HttpResponse.BodyHandlers.ofFile(tmp));
            if (sha1 != null) {
                String got = sha1(tmp);
                if (!sha1.equalsIgnoreCase(got)) {
                    throw new IOException("Hash mismatch for " + url + ": expected " + sha1 + ", got " + got);
                }
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return dest;
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler) throws IOException {
        checkUrl(url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.replace(" ", "%20")))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<T> r = CLIENT.send(req, handler);
                if (r.statusCode() / 100 == 2) {
                    return r;
                }
                last = new IOException("HTTP " + r.statusCode() + " for " + url);
                if (r.statusCode() == 404 || r.statusCode() == 403) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching " + url, e);
            } catch (IOException e) {
                last = new IOException("Failed to fetch " + url + ": " + e.getMessage(), e);
            }
        }
        throw last;
    }
}
