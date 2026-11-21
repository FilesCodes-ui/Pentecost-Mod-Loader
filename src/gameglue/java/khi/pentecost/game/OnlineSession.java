package khi.pentecost.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.platform.win32.Crypt32Util;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;

public final class OnlineSession {
    public static final String SERVER = "https://api.pentecost-modloader.workers.dev";
    private static final long RENEW_MS = 7L * 86400_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static volatile boolean started;

    private OnlineSession() {}

    public static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("pentecost-online.json");
    }

    public static void install() {
        if (started || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                int fails = 0;
                while (true) {
                    boolean done = false;
                    try {
                        done = check();
                        fails = 0;
                    } catch (Exception e) {
                        fails++;
                        Log.warn("Pentecost online sign-in: " + e.getMessage());
                    }
                    Thread.sleep(done ? 10_000L : fails > 0 ? Math.min(5 * 60_000L, 15_000L << Math.min(fails, 4)) : 10_000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pentecost-online");
        t.setDaemon(true);
        t.start();
    }

    static String token() {
        try {
            if (!Account.signedIn()) {
                return null;
            }
            Path f = file();
            if (!Files.isRegularFile(f)) {
                return null;
            }
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("token") || !o.has("expires") || !o.has("uuid") || o.get("expires").getAsLong() < System.currentTimeMillis()) {
                return null;
            }
            if (!o.get("uuid").getAsString().equals(Account.uuid().replace("-", ""))) {
                return null;
            }
            byte[] raw = Base64.getDecoder().decode(o.get("token").getAsString());
            return new String(Crypt32Util.cryptUnprotectData(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean check() throws Exception {
        if (!Account.signedIn() || Account.restoring()) {
            return false;
        }
        String uuid = Account.uuid().replace("-", "");
        String name = Account.name();
        String access = Minecraft.getInstance().getUser().getAccessToken();
        if (uuid.length() != 32 || name.isBlank() || access == null || access.isBlank() || access.equals("0")) {
            return false;
        }
        Path f = file();
        if (Files.isRegularFile(f)) {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("uuid") && o.get("uuid").getAsString().equals(uuid) && o.has("expires")
                    && o.get("expires").getAsLong() - System.currentTimeMillis() > RENEW_MS) {
                return true;
            }
        }
        JsonObject c = get("/auth/challenge");
        String serverId = c.get("serverId").getAsString();
        JsonObject join = new JsonObject();
        join.addProperty("accessToken", access);
        join.addProperty("selectedProfile", uuid);
        join.addProperty("serverId", serverId);
        HttpResponse<String> j = HTTP.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(join.toString())).build(), HttpResponse.BodyHandlers.ofString());
        if (j.statusCode() / 100 != 2) {
            throw new IllegalStateException("Mojang refused the join (" + j.statusCode() + ")");
        }
        JsonObject login = new JsonObject();
        login.addProperty("name", name);
        login.addProperty("nonce", c.get("nonce").getAsString());
        login.addProperty("ts", c.get("ts").getAsLong());
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(SERVER + "/auth/login"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(login.toString())).build(), HttpResponse.BodyHandlers.ofString());
        JsonObject res = JsonParser.parseString(r.body()).getAsJsonObject();
        if (r.statusCode() != 200 || !res.has("token")) {
            throw new IllegalStateException(res.has("error") ? res.get("error").getAsString() : "status " + r.statusCode());
        }
        JsonObject out = new JsonObject();
        out.addProperty("v", 1);
        out.addProperty("uuid", res.get("uuid").getAsString());
        out.addProperty("name", res.get("name").getAsString());
        out.addProperty("expires", res.get("expires").getAsLong());
        out.addProperty("protection", "dpapi");
        out.addProperty("token", Base64.getEncoder().encodeToString(
                Crypt32Util.cryptProtectData(res.get("token").getAsString().getBytes(StandardCharsets.UTF_8))));
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        Log.info("Signed in to Pentecost online as " + res.get("name").getAsString());
        return true;
    }

    private static JsonObject get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(SERVER + path)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("server answered " + r.statusCode());
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }
}
