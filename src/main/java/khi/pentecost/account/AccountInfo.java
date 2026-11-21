package khi.pentecost.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class AccountInfo {
    public record Cape(String id, String alias, boolean active, String url) {}

    public String name = "";
    public String id = "";
    public String skinVariant = "";
    public String skinUrl = "";
    public final List<Cape> capes = new ArrayList<>();

    public Instant createdAt;
    public Instant nameChangedAt;
    public Boolean nameChangeAllowed;

    public Boolean ownsJava;
    public Boolean onlineChat;
    public Boolean multiplayer;
    public Boolean realms;
    public Boolean telemetry;
    public Boolean profanityFilter;
    public boolean banned;

    public Instant tokenExpires;

    public final List<String> problems = new ArrayList<>();

    public Cape activeCape() {
        for (Cape c : capes) {
            if (c.active()) {
                return c;
            }
        }
        return null;
    }

    public static AccountInfo parse(String profile, String nameChange, String entitlements, String attributes, String token) {
        AccountInfo a = new AccountInfo();
        a.tokenExpires = jwtExpiry(token);
        JsonObject p = obj(profile);
        if (p == null) {
            a.problems.add("The profile could not be read.");
        } else {
            a.name = str(p, "name");
            a.id = str(p, "id");
            JsonArray skins = arr(p, "skins");
            for (JsonElement e : skins) {
                JsonObject s = e.getAsJsonObject();
                if ("ACTIVE".equals(str(s, "state"))) {
                    a.skinVariant = str(s, "variant");
                    a.skinUrl = str(s, "url");
                    break;
                }
            }
            for (JsonElement e : arr(p, "capes")) {
                JsonObject c = e.getAsJsonObject();
                a.capes.add(new Cape(str(c, "id"), str(c, "alias"), "ACTIVE".equals(str(c, "state")), str(c, "url")));
            }
        }
        JsonObject n = obj(nameChange);
        if (n == null) {
            a.problems.add("The name change details could not be read.");
        } else {
            a.createdAt = time(str(n, "createdAt"));
            a.nameChangedAt = time(str(n, "changedAt"));
            if (n.has("nameChangeAllowed") && n.get("nameChangeAllowed").isJsonPrimitive()) {
                a.nameChangeAllowed = n.get("nameChangeAllowed").getAsBoolean();
            }
        }
        JsonObject ent = obj(entitlements);
        if (ent == null) {
            a.problems.add("The game ownership could not be read.");
        } else {
            boolean java = false;
            for (JsonElement e : arr(ent, "items")) {
                String item = str(e.getAsJsonObject(), "name");
                if (item.equals("game_minecraft") || item.equals("product_minecraft")) {
                    java = true;
                }
            }
            a.ownsJava = java;
        }
        JsonObject attr = obj(attributes);
        if (attr == null) {
            a.problems.add("The account permissions could not be read.");
        } else {
            JsonObject priv = attr.has("privileges") && attr.get("privileges").isJsonObject() ? attr.getAsJsonObject("privileges") : null;
            if (priv != null) {
                a.onlineChat = enabled(priv, "onlineChat");
                a.multiplayer = enabled(priv, "multiplayerServer");
                a.realms = enabled(priv, "multiplayerRealms");
                a.telemetry = enabled(priv, "telemetry");
            }
            if (attr.has("profanityFilterPreferences") && attr.get("profanityFilterPreferences").isJsonObject()) {
                JsonObject f = attr.getAsJsonObject("profanityFilterPreferences");
                if (f.has("profanityFilterOn") && f.get("profanityFilterOn").isJsonPrimitive()) {
                    a.profanityFilter = f.get("profanityFilterOn").getAsBoolean();
                }
            }
            if (attr.has("banStatus") && attr.get("banStatus").isJsonObject()) {
                JsonObject b = attr.getAsJsonObject("banStatus");
                if (b.has("bannedScopes") && b.get("bannedScopes").isJsonObject()) {
                    a.banned = !b.getAsJsonObject("bannedScopes").entrySet().isEmpty();
                }
            }
        }
        return a;
    }

    private static Boolean enabled(JsonObject priv, String key) {
        if (priv.has(key) && priv.get(key).isJsonObject()) {
            JsonObject o = priv.getAsJsonObject(key);
            if (o.has("enabled") && o.get("enabled").isJsonPrimitive()) {
                return o.get("enabled").getAsBoolean();
            }
        }
        return null;
    }

    private static JsonObject obj(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return null;
            }
            JsonObject o = e.getAsJsonObject();
            return o.has("error") && o.size() <= 4 && !o.has("id") && !o.has("items") && !o.has("privileges") ? null : o;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static Instant time(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Instant jwtExpiry(String jwt) {
        if (jwt == null) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("exp") && o.get("exp").isJsonPrimitive()) {
                return Instant.ofEpochSecond(o.get("exp").getAsLong());
            }
        } catch (RuntimeException e) {
        }
        return null;
    }

    public static String dashed(String id) {
        if (id == null || id.length() != 32) {
            return id == null ? "" : id;
        }
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
    }

    public static String age(Instant from, Instant now) {
        if (from == null) {
            return "";
        }
        long days = ChronoUnit.DAYS.between(from, now);
        if (days < 1) {
            return "today";
        }
        if (days < 60) {
            return days + (days == 1 ? " day" : " days");
        }
        long months = days / 30;
        if (months < 24) {
            return months + " months";
        }
        long years = days / 365;
        long restMonths = (days - years * 365) / 30;
        return years + (years == 1 ? " year" : " years") + (restMonths > 0 ? ", " + restMonths + (restMonths == 1 ? " month" : " months") : "");
    }

    public static String until(Instant to, Instant now) {
        if (to == null) {
            return "unknown";
        }
        long minutes = ChronoUnit.MINUTES.between(now, to);
        if (minutes <= 0) {
            return "ended";
        }
        if (minutes < 60) {
            return "in " + minutes + " min";
        }
        return "in " + (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final String BASE = "https://api.minecraftservices.com";

    public static AccountInfo fetch(String token) {
        return parse(get(BASE + "/minecraft/profile", token), get(BASE + "/minecraft/profile/namechange", token),
                get(BASE + "/entitlements/mcstore", token), get(BASE + "/player/attributes", token), token);
    }

    private static String get(String url, String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json").timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
