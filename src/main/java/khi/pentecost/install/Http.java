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

/** Downloads with retries and SHA-1 checks. */
public final class Http {
    public static final String USER_AGENT =
            "KHI-Pentecost/0.1.0 (Minecraft mod loader compatibility tooling)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private Http() {}

    public static String text(String url) throws IOException {
        return send(url, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Downloads url to dest. Skips the download when dest exists and matches sha1 (or sha1 is null). */
    public static Path download(String url, Path dest, String sha1) throws IOException {
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
