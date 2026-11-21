package khi.pentecost.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.update.ReleaseInfo;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DevFeed {
    private DevFeed() {}

    private static final String SERVER = "https://api.pentecost-modloader.workers.dev";
    private static final byte[] TAG = "pentecost-feed-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private static volatile JsonObject current = new JsonObject();
    private static volatile boolean loaded;
    private static volatile Set<String> kill = Set.of();
    private static volatile PublicKey backup;
    private static volatile boolean backupTried;
    private static final Object LOCK = new Object();
    private static volatile boolean busy;

    private static Path dir() {
        return Dirs.mk(Dirs.config().resolve("pentecost"));
    }

    private static void ensure() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            loaded = true;
            try {
                Path j = dir().resolve("feed.json");
                Path s = dir().resolve("feed.sig");
                if (Files.isRegularFile(j) && Files.isRegularFile(s)) {
                    JsonObject f = check(Files.readAllBytes(j), Files.readString(s, StandardCharsets.UTF_8), false);
                    if (f != null) {
                        apply(f);
                    }
                }
            } catch (Exception e) {
                Log.warn("Feed cache: " + e);
            }
        }
    }

    private static void apply(JsonObject f) {
        current = f;
        Set<String> k = new HashSet<>();
        if (f.has("kill") && f.get("kill").isJsonArray()) {
            for (JsonElement e : f.getAsJsonArray("kill")) {
                if (e.isJsonPrimitive()) {
                    k.add(e.getAsString());
                }
            }
        }
        kill = k;
    }

    private static PublicKey backupKey() {
        if (!backupTried) {
            backupTried = true;
            try (InputStream in = DevFeed.class.getResourceAsStream("/pentecost/dev-key-backup.cer")) {
                if (in != null) {
                    backup = CertificateFactory.getInstance("X.509").generateCertificate(in).getPublicKey();
                }
            } catch (Exception e) {
                Log.warn("Feed: backup key unreadable: " + e);
            }
        }
        return backup;
    }

    private static JsonObject state() {
        try {
            Path f = dir().resolve("feed-state.json");
            if (Files.isRegularFile(f)) {
                return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return new JsonObject();
    }

    private static void saveState(JsonObject o) {
        try {
            Files.writeString(dir().resolve("feed-state.json"), o.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static JsonObject check(byte[] json, String sigText, boolean record) {
        try {
            JsonObject sig = JsonParser.parseString(sigText).getAsJsonObject();
            String which = sig.has("key") ? sig.get("key").getAsString() : "";
            JsonObject st = state();
            boolean retired = st.has("primaryRetired") && st.get("primaryRetired").getAsBoolean();
            PublicKey key = which.equals("backup") ? backupKey() : which.equals("primary") && !retired ? DevKey.publicKey() : null;
            if (key == null) {
                return null;
            }
            byte[] sigBytes = Base64.getDecoder().decode(sig.get("sig").getAsString());
            byte[] msg = new byte[TAG.length + json.length];
            System.arraycopy(TAG, 0, msg, 0, TAG.length);
            System.arraycopy(json, 0, msg, TAG.length, json.length);
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(key);
            v.update(msg);
            if (!v.verify(sigBytes)) {
                Log.warn("Feed: signature does not match, ignored");
                return null;
            }
            JsonObject feed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            long seq = feed.has("seq") ? feed.get("seq").getAsLong() : 0;
            long top = st.has("seq") ? st.get("seq").getAsLong() : 0;
            if (seq < top) {
                Log.warn("Feed: older feed refused");
                return null;
            }
            if (record) {
                st.addProperty("seq", seq);
                if (which.equals("backup") && feed.has("retirePrimary") && feed.get("retirePrimary").getAsBoolean()) {
                    st.addProperty("primaryRetired", true);
                }
                saveState(st);
            }
            return feed;
        } catch (Exception e) {
            return null;
        }
    }

    public static void refresh() {
        if (busy) {
            return;
        }
        busy = true;
        try {
            ensure();
            long stamp = System.currentTimeMillis();
            byte[] json = get(SERVER + "/feed/feed.json?t=" + stamp);
            byte[] sig = get(SERVER + "/feed/feed.sig?t=" + stamp);
            if (json == null || sig == null || json.length > 4_000_000) {
                return;
            }
            String sigText = new String(sig, StandardCharsets.UTF_8);
            JsonObject f = check(json, sigText, true);
            if (f == null) {
                return;
            }
            apply(f);
            Files.write(dir().resolve("feed.json"), json);
            Files.writeString(dir().resolve("feed.sig"), sigText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Feed refresh: " + e.getMessage());
        } finally {
            busy = false;
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return r.statusCode() == 200 ? r.body() : null;
    }

    public static boolean killed(String key) {
        ensure();
        return kill.contains(key);
    }

    public static List<String> killList() {
        ensure();
        return new ArrayList<>(kill);
    }

    public static String gate() {
        ensure();
        JsonObject g = current.has("gate") && current.get("gate").isJsonObject() ? current.getAsJsonObject("gate") : null;
        if (g == null || !g.has("minVersion")) {
            return null;
        }
        String min = g.get("minVersion").getAsString();
        String cur = khi.pentecost.boot.PentecostMain.VERSION;
        if (min.isEmpty() || cur == null || cur.isEmpty() || !ReleaseInfo.newer(min, cur)) {
            return null;
        }
        String text = g.has("text") ? g.get("text").getAsString() : "";
        return text.isEmpty() ? "Pentecost " + cur + " has a problem that makes it unsafe. Update to " + min + " or newer to play." : text;
    }

    public record Post(String id, String kind, String title, String body) {}

    public record SkinPick(String name, String texture) {}

    public static List<SkinPick> skinPicks() {
        ensure();
        List<SkinPick> out = new ArrayList<>();
        if (!current.has("skins") || !current.get("skins").isJsonArray()) {
            return out;
        }
        for (JsonElement e : current.getAsJsonArray("skins")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            String tex = str(s, "texture");
            if (tex.matches("[0-9a-f]{20,80}")) {
                out.add(new SkinPick(str(s, "name").isEmpty() ? "Pick" : str(s, "name"), tex));
            }
        }
        return out;
    }

    public static Set<String> capes() {
        ensure();
        Set<String> out = new HashSet<>();
        if (!current.has("capes") || !current.get("capes").isJsonArray()) {
            return out;
        }
        for (JsonElement e : current.getAsJsonArray("capes")) {
            if (e.isJsonPrimitive()) {
                String id = e.getAsString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
                if (id.matches("[0-9a-f]{32}")) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    public static List<Post> livePosts() {
        ensure();
        List<Post> out = new ArrayList<>();
        if (!current.has("posts") || !current.get("posts").isJsonArray()) {
            return out;
        }
        long now = System.currentTimeMillis();
        String cur = khi.pentecost.boot.PentecostMain.VERSION;
        for (JsonElement e : current.getAsJsonArray("posts")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject p = e.getAsJsonObject();
            String kind = str(p, "kind");
            if (!kind.equals("announcement") && !kind.equals("whatsnew")) {
                continue;
            }
            long at = p.has("at") && p.get("at").isJsonPrimitive() ? p.get("at").getAsLong() : 0;
            long until = p.has("until") && p.get("until").isJsonPrimitive() ? p.get("until").getAsLong() : 0;
            if ((at > 0 && now < at) || (until > 0 && now > until)) {
                continue;
            }
            if (p.has("target") && p.get("target").isJsonObject()) {
                JsonObject t = p.getAsJsonObject("target");
                String min = str(t, "minVersion");
                String max = str(t, "maxVersion");
                if (!min.isEmpty() && ReleaseInfo.newer(min, cur)) {
                    continue;
                }
                if (!max.isEmpty() && ReleaseInfo.newer(cur, max)) {
                    continue;
                }
                if (!str(t, "mod").isEmpty()) {
                    continue;
                }
            }
            String id = str(p, "id");
            if (!id.isEmpty()) {
                out.add(new Post(id, kind, str(p, "title"), str(p, "body")));
            }
        }
        return out;
    }

    public static List<Post> unseenPosts() {
        JsonObject st = state();
        Set<String> seen = new HashSet<>();
        if (st.has("seenPosts") && st.get("seenPosts").isJsonArray()) {
            for (JsonElement e : st.getAsJsonArray("seenPosts")) {
                seen.add(e.getAsString());
            }
        }
        List<Post> out = new ArrayList<>();
        for (Post p : livePosts()) {
            if (!seen.contains(p.id())) {
                out.add(p);
            }
        }
        return out;
    }

    public static void markSeen(List<Post> posts) {
        if (posts.isEmpty()) {
            return;
        }
        JsonObject st = state();
        JsonArray arr = st.has("seenPosts") && st.get("seenPosts").isJsonArray() ? st.getAsJsonArray("seenPosts") : new JsonArray();
        Set<String> have = new HashSet<>();
        for (JsonElement e : arr) {
            have.add(e.getAsString());
        }
        for (Post p : posts) {
            if (have.add(p.id())) {
                arr.add(p.id());
            }
        }
        while (arr.size() > 100) {
            arr.remove(0);
        }
        st.add("seenPosts", arr);
        saveState(st);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
