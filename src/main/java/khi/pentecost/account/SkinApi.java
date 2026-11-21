package khi.pentecost.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SkinApi {
    public record Textures(String skinUrl, boolean slim, String capeUrl) {}

    private static final String SERVICES = "https://api.minecraftservices.com/minecraft/profile";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern ID = Pattern.compile("^[0-9a-fA-F]{32}$");
    public static final int MAX_BYTES = 1 << 20;

    private SkinApi() {}

    public static boolean isName(String s) {
        return s != null && NAME.matcher(s.trim()).matches();
    }

    public static String parseId(String json) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("id")) {
                String id = o.get("id").getAsString();
                if (ID.matcher(id).matches()) {
                    return id.toLowerCase(Locale.ROOT);
                }
            }
        } catch (RuntimeException e) {
        }
        throw new IOException("Mojang's answer has no player id");
    }

    public static Textures parseTextures(String profileJson) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(profileJson).getAsJsonObject();
            for (var e : o.getAsJsonArray("properties")) {
                JsonObject p = e.getAsJsonObject();
                if (!"textures".equals(p.get("name").getAsString())) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(p.get("value").getAsString()), StandardCharsets.UTF_8);
                JsonObject tex = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
                String skin = "";
                boolean slim = false;
                String cape = "";
                if (tex != null && tex.has("SKIN")) {
                    JsonObject s = tex.getAsJsonObject("SKIN");
                    skin = s.get("url").getAsString();
                    slim = s.has("metadata") && s.getAsJsonObject("metadata").has("model")
                            && "slim".equals(s.getAsJsonObject("metadata").get("model").getAsString());
                }
                if (tex != null && tex.has("CAPE")) {
                    cape = tex.getAsJsonObject("CAPE").get("url").getAsString();
                }
                return new Textures(skin, slim, cape);
            }
        } catch (RuntimeException e) {
            throw new IOException("The profile could not be read");
        }
        throw new IOException("This player has no skin data");
    }

    public static int[] pngSize(byte[] png) {
        if (png == null || png.length < 24) {
            return null;
        }
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < sig.length; i++) {
            if (png[i] != sig[i]) {
                return null;
            }
        }
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[] {w, h};
    }

    public static void checkSkin(byte[] png) throws IOException {
        int[] size = pngSize(png);
        if (size == null) {
            throw new IOException("This is not a PNG image");
        }
        if (size[0] != 64 || (size[1] != 64 && size[1] != 32)) {
            throw new IOException("A skin is 64 by 64 pixels (or the old 64 by 32). This image is " + size[0] + " by " + size[1]);
        }
    }

    public static byte[] multipart(String boundary, boolean slim, byte[] png) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 400);
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + (slim ? "slim" : "classic") + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(png);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static String describe(int status, String body) {
        if (status == 401 || status == 403) {
            return "Minecraft refused the request. Your session may have ended. Restart from your launcher.";
        }
        if (status == 429) {
            return "Mojang says too many changes. Wait a minute and try again.";
        }
        if (status == 400) {
            String why = "";
            try {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                if (o.has("errorMessage")) {
                    why = o.get("errorMessage").getAsString();
                }
            } catch (RuntimeException e) {
            }
            return why.isEmpty() ? "Mojang did not accept this image" : "Mojang did not accept this: " + why;
        }
        return "Mojang answered " + status;
    }

    public static String idOf(String name) throws IOException {
        if (!isName(name)) {
            throw new IOException("A Minecraft name is 3 to 16 letters, numbers or underscores");
        }
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name.trim()))
                .timeout(Duration.ofSeconds(15)).GET().build());
        if (r.statusCode() == 404 || r.statusCode() == 204) {
            throw new IOException("There is no player called " + name.trim());
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException(r.statusCode() == 429 ? "Mojang says too many lookups. Wait a minute." : "Mojang answered " + r.statusCode());
        }
        return parseId(r.body());
    }

    public static Textures texturesOf(String id) throws IOException {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                + id.replace("-", "")))
                .timeout(Duration.ofSeconds(15)).GET().build());
        if (r.statusCode() == 429) {
            throw new IOException("Mojang says too many lookups. Wait a minute.");
        }
        if (r.statusCode() / 100 != 2 || r.body().isBlank()) {
            throw new IOException("Mojang has no skin data for this player");
        }
        return parseTextures(r.body());
    }

    public static byte[] download(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("This is not a web address");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only web addresses starting with https:// work");
        }
        try {
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(uri).header("User-Agent", "Pentecost")
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("The download answered " + r.statusCode());
            }
            try (InputStream in = r.body()) {
                byte[] data = in.readNBytes(MAX_BYTES + 1);
                if (data.length > MAX_BYTES) {
                    throw new IOException("This file is too big to be a skin");
                }
                return data;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped");
        }
    }

    public static void upload(String token, byte[] png, boolean slim) throws IOException {
        checkSkin(png);
        String boundary = "pentecost" + Long.toHexString(System.nanoTime());
        HttpRequest req = HttpRequest.newBuilder(URI.create(SERVICES + "/skins"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, slim, png))).build();
        HttpResponse<String> r = send(req);
        if (r.statusCode() / 100 != 2) {
            throw new IOException(describe(r.statusCode(), r.body()));
        }
    }

    public static void resetSkin(String token) throws IOException {
        call(HttpRequest.newBuilder(URI.create(SERVICES + "/skins/active")).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30)).DELETE().build());
    }

    public static void setCape(String token, String capeId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("capeId", capeId);
        call(HttpRequest.newBuilder(URI.create(SERVICES + "/capes/active")).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    public static void hideCape(String token) throws IOException {
        call(HttpRequest.newBuilder(URI.create(SERVICES + "/capes/active")).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30)).DELETE().build());
    }

    private static void call(HttpRequest req) throws IOException {
        HttpResponse<String> r = send(req);
        if (r.statusCode() / 100 != 2) {
            throw new IOException(describe(r.statusCode(), r.body()));
        }
    }

    private static HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped");
        }
    }
}
