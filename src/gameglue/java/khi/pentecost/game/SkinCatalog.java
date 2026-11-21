package khi.pentecost.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.account.SkinApi;
import khi.pentecost.auth.DevFeed;
import khi.pentecost.boot.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SkinCatalog {
    record Entry(String name, String texture) {}

    private static final String LIST = "https://api.mineskin.org/v2/skins";
    private static final String TEXTURES = "https://textures.minecraft.net/texture/";
    private static final int PAGE = 48;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "pentecost-skin-catalog");
        t.setDaemon(true);
        return t;
    });

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final Map<String, byte[]> PNG = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static volatile String after = "";
    private static volatile String filter = "";
    private static volatile boolean busy;
    private static volatile boolean end;
    private static volatile String error = "";
    private static volatile int generation;

    private SkinCatalog() {}

    static List<Entry> entries() {
        return ENTRIES;
    }

    static boolean busy() {
        return busy;
    }

    static boolean end() {
        return end;
    }

    static String error() {
        return error;
    }

    static String filter() {
        return filter;
    }

    static List<Entry> picks() {
        return DevFeed.skinPicks().stream().map(p -> new Entry(p.name(), p.texture())).toList();
    }

    static void reset(String text) {
        generation++;
        filter = text == null ? "" : text.strip();
        ENTRIES.clear();
        after = "";
        end = false;
        error = "";
        busy = false;
        more();
    }

    static void more() {
        if (busy || end) {
            return;
        }
        busy = true;
        int gen = generation;
        String from = after;
        String f = filter;
        POOL.execute(() -> {
            try {
                StringBuilder url = new StringBuilder(LIST).append("?size=").append(PAGE);
                if (!from.isEmpty()) {
                    url.append("&after=").append(URLEncoder.encode(from, StandardCharsets.UTF_8));
                }
                if (!f.isEmpty()) {
                    url.append("&filter=").append(URLEncoder.encode(f, StandardCharsets.UTF_8));
                }
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url.toString()))
                        .header("User-Agent", "Pentecost/" + khi.pentecost.boot.PentecostMain.VERSION)
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (gen != generation) {
                    return;
                }
                if (r.statusCode() == 429) {
                    error = "Too many requests. Wait a moment.";
                    return;
                }
                if (r.statusCode() / 100 != 2) {
                    error = "The skin list answered " + r.statusCode() + ".";
                    return;
                }
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                int added = 0;
                if (o.has("skins") && o.get("skins").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("skins")) {
                        JsonObject s = e.getAsJsonObject();
                        String tex = s.has("texture") ? s.get("texture").getAsString() : "";
                        if (!tex.matches("[0-9a-f]{20,80}")) {
                            continue;
                        }
                        String name = s.has("name") && !s.get("name").isJsonNull() ? s.get("name").getAsString() : "";
                        ENTRIES.add(new Entry(name.isBlank() ? "Unnamed" : name, tex));
                        added++;
                    }
                }
                String next = "";
                if (o.has("pagination") && o.getAsJsonObject("pagination").has("next")) {
                    JsonObject n = o.getAsJsonObject("pagination").getAsJsonObject("next");
                    next = n.has("after") ? n.get("after").getAsString() : "";
                }
                after = next;
                end = next.isEmpty() || added == 0;
                error = "";
            } catch (Exception e) {
                if (gen == generation) {
                    error = "Could not load skins. Check your connection.";
                    Log.warn("Skin catalog: " + e);
                }
            } finally {
                if (gen == generation) {
                    busy = false;
                }
            }
        });
    }

    static byte[] png(String texture) {
        byte[] b = PNG.get(texture);
        if (b != null) {
            return b.length == 0 ? null : b;
        }
        if (LOADING.add(texture)) {
            POOL.execute(() -> {
                try {
                    PNG.put(texture, SkinApi.download(TEXTURES + texture));
                } catch (Exception e) {
                    PNG.put(texture, new byte[0]);
                }
            });
        }
        return null;
    }

    static boolean slim(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            return img != null && img.getWidth() == 64 && img.getHeight() == 64 && (img.getRGB(54, 20) >>> 24) == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
