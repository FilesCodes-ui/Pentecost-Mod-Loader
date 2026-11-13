package khi.pentecost.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Microsoft sign in for Minecraft, by device code: the game shows a short code, the player types it
 * at microsoft.com/link, and the game gets a Minecraft session. Only a refresh token is saved to disk.
 * The app registration (client ID) comes from PENTECOST_MS_CLIENT_ID or the file pentecost-client-id.txt in the game folder.
 */
public final class Account {
    public enum State { OUT, WAITING, WORKING, IN, FAILED }

    private static final String DEVICE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String JSON = "application/json";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private static volatile State state = State.OUT;
    private static volatile String message = "";
    private static volatile String userCode = "";
    private static volatile String verifyUrl = "";
    private static volatile String name = "";
    private static volatile String uuid = "";
    private static volatile boolean cancelled;
    private static boolean started;
    private static String refresh;

    private Account() {}

    public static State state() { return state; }
    public static String message() { return message; }
    public static String userCode() { return userCode; }
    public static String verifyUrl() { return verifyUrl; }
    public static String name() { return name; }
    public static String uuid() { return uuid; }
    public static boolean signedIn() { return state == State.IN; }

    /** Screens that talk to Mojang's servers or services. They need a signed in account. */
    public static boolean gated(String screenClass) {
        return screenClass.equals("net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen")
                || screenClass.equals("net.minecraft.client.gui.screens.ConnectScreen")
                || screenClass.equals("com.mojang.realmsclient.RealmsMainScreen");
    }

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    public static Path clientIdFile() {
        return dir().resolve("pentecost-client-id.txt");
    }

    public static String clientId() {
        String env = System.getenv("PENTECOST_MS_CLIENT_ID");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        try {
            Path f = clientIdFile();
            if (Files.exists(f)) {
                String s = Files.readString(f).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (IOException e) {
            Log.error("Cannot read the client ID file: " + e);
        }
        return null;
    }

    /** Called every frame. Restores the saved sign in once, on the first frame. */
    public static void frame() {
        if (started || Minecraft.getInstance().gameDirectory == null) {
            return;
        }
        started = true;
        try {
            Path f = dir().resolve("pentecost-account.json");
            String id = clientId();
            if (!Files.exists(f) || id == null) {
                return;
            }
            JsonObject saved = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            refresh = saved.get("refresh").getAsString();
            state = State.WORKING;
            message = "Signing in as " + saved.get("name").getAsString();
            run(() -> {
                JsonObject t = post(TOKEN, form("grant_type", "refresh_token", "client_id", id, "refresh_token", refresh,
                        "scope", SCOPE), FORM);
                if (!t.has("access_token")) {
                    throw new IOException("Your saved sign in ended. Sign in again.");
                }
                finish(t);
            });
        } catch (IOException | RuntimeException e) {
            Log.error("Cannot restore the account: " + e);
            state = State.OUT;
        }
    }

    public static void startLogin() {
        String id = clientId();
        if (id == null) {
            fail("There is no client ID yet.");
            return;
        }
        cancelled = false;
        state = State.WORKING;
        message = "Asking Microsoft for a code";
        run(() -> {
            JsonObject d = post(DEVICE, form("client_id", id, "scope", SCOPE), FORM);
            if (!d.has("device_code")) {
                throw new IOException(describe(d));
            }
            String deviceCode = d.get("device_code").getAsString();
            userCode = d.get("user_code").getAsString();
            verifyUrl = d.get("verification_uri").getAsString();
            int interval = d.has("interval") ? d.get("interval").getAsInt() : 5;
            long deadline = System.currentTimeMillis() + 1000L * (d.has("expires_in") ? d.get("expires_in").getAsInt() : 900);
            state = State.WAITING;
            while (!cancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval * 1000L);
                if (cancelled) {
                    return;
                }
                JsonObject t = post(TOKEN, form("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id", id, "device_code", deviceCode), FORM);
                if (t.has("access_token")) {
                    state = State.WORKING;
                    message = "Signing in to Minecraft";
                    finish(t);
                    return;
                }
                String err = t.has("error") ? t.get("error").getAsString() : "";
                if (err.equals("slow_down")) {
                    interval += 5;
                } else if (!err.equals("authorization_pending")) {
                    throw new IOException(describe(t));
                }
            }
            if (!cancelled) {
                throw new IOException("The code ran out of time.");
            }
        });
    }

    public static void cancel() {
        cancelled = true;
        if (state == State.WAITING || state == State.WORKING) {
            state = State.OUT;
        }
    }

    public static void signOut() {
        cancelled = true;
        refresh = null;
        name = "";
        uuid = "";
        state = State.OUT;
        try {
            Files.deleteIfExists(dir().resolve("pentecost-account.json"));
        } catch (IOException e) {
            Log.error("Cannot delete the saved account: " + e);
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> setUser(new User("Dev", "00000000000000000000000000000000", "0", Optional.empty(), Optional.empty(),
                User.Type.LEGACY)));
        AccountSkin.clear();
    }

    // ------------------------------------------------------------------ the sign in chain

    private interface Job { void run() throws Exception; }

    private static void run(Job job) {
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.error("Sign in failed: " + e);
                fail(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, "pentecost-sign-in");
        t.setDaemon(true);
        t.start();
    }

    private static void fail(String why) {
        message = why;
        state = State.FAILED;
    }

    private static void finish(JsonObject microsoft) throws Exception {
        String access = microsoft.get("access_token").getAsString();
        if (microsoft.has("refresh_token")) {
            refresh = microsoft.get("refresh_token").getAsString();
        }
        message = "Signing in to Xbox";
        JsonObject xbl = post("https://user.auth.xboxlive.com/user/authenticate", "{\"Properties\":{\"AuthMethod\":\"RPS\","
                + "\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + access + "\"},"
                + "\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}", JSON);
        if (!xbl.has("Token")) {
            throw new IOException("Xbox sign in failed. " + describe(xbl));
        }
        String xblToken = xbl.get("Token").getAsString();
        String hash = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        JsonObject xsts = post("https://xsts.auth.xboxlive.com/xsts/authorize", "{\"Properties\":{\"SandboxId\":\"RETAIL\","
                + "\"UserTokens\":[\"" + xblToken + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\","
                + "\"TokenType\":\"JWT\"}", JSON);
        if (!xsts.has("Token")) {
            throw new IOException(xstsReason(xsts));
        }
        message = "Signing in to Minecraft";
        JsonObject mc = post("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":\"XBL3.0 x=" + hash + ";" + xsts.get("Token").getAsString() + "\"}", JSON);
        if (!mc.has("access_token")) {
            throw new IOException("Minecraft refused the sign in. " + describe(mc));
        }
        String token = mc.get("access_token").getAsString();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException("This Microsoft account does not own Minecraft: Java Edition.");
        }
        JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!profile.has("id")) {
            throw new IOException("Cannot read the Minecraft profile. " + describe(profile));
        }
        String id = profile.get("id").getAsString();
        String playerName = profile.get("name").getAsString();
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> setUser(new User(playerName, id, token, Optional.empty(), Optional.empty(), User.Type.MSA)));
        name = playerName;
        uuid = id;
        save(playerName, id);
        loadSkin(profile);
        state = State.IN;
        message = "";
        PentecostDev.accountChanged();
        Log.info("Signed in as " + playerName);
    }

    private static void loadSkin(JsonObject profile) {
        try {
            JsonArray skins = profile.has("skins") ? profile.getAsJsonArray("skins") : new JsonArray();
            for (JsonElement e : skins) {
                JsonObject s = e.getAsJsonObject();
                if (s.has("state") && s.get("state").getAsString().equals("ACTIVE") && s.has("url")) {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(s.get("url").getAsString()))
                            .timeout(Duration.ofSeconds(30)).GET().build();
                    HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() == 200) {
                        AccountSkin.set(r.body());
                    }
                    return;
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            Log.error("Cannot download the skin: " + e);
        }
    }

    private static void save(String playerName, String id) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("refresh", refresh);
            o.addProperty("name", playerName);
            o.addProperty("uuid", id);
            Files.writeString(dir().resolve("pentecost-account.json"), o.toString());
        } catch (IOException e) {
            Log.error("Cannot save the account: " + e);
        }
    }

    private static void setUser(User user) {
        try {
            java.lang.reflect.Field f = Minecraft.class.getDeclaredField("user");
            f.setAccessible(true);
            f.set(Minecraft.getInstance(), user);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Cannot set the game's user: " + e);
        }
    }

    // ------------------------------------------------------------------ http

    private static String form(String... pairs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (b.length() > 0) {
                b.append('&');
            }
            b.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    private static JsonObject post(String url, String body, String type) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type).header("Accept", JSON)
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        try {
            return r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Unexpected answer (" + r.statusCode() + ")");
        }
    }

    private static String describe(JsonObject o) {
        if (o.has("error_description")) {
            String s = o.get("error_description").getAsString();
            int line = s.indexOf('\n');
            return line > 0 ? s.substring(0, line) : s;
        }
        if (o.has("error")) {
            return o.get("error").getAsString();
        }
        if (o.has("errorMessage")) {
            return o.get("errorMessage").getAsString();
        }
        return "No details.";
    }

    private static String xstsReason(JsonObject o) {
        long code = o.has("XErr") ? o.get("XErr").getAsLong() : 0;
        if (code == 2148916233L) {
            return "This Microsoft account has no Xbox profile. Open xbox.com once and make one.";
        }
        if (code == 2148916235L) {
            return "Xbox sign in is not allowed in your country.";
        }
        if (code == 2148916236L || code == 2148916237L) {
            return "This account needs adult verification on xbox.com.";
        }
        if (code == 2148916238L) {
            return "This is a child account. An adult must add it to a Microsoft family first.";
        }
        return "Xbox sign in failed. " + describe(o);
    }
}
