package khi.pentecost.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Friends {
    public record Friend(String name, String code) {}

    public record Status(boolean hosting, String address, long checkedAt) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Status> STATUS = new ConcurrentHashMap<>();
    private static final int MAX_FRIENDS = 64;
    private static Path file;
    private static String myCode;
    private static final List<Friend> FRIENDS = new ArrayList<>();
    private static boolean loaded;

    private Friends() {}

    public static synchronized void use(Path configFile) {
        if (configFile.equals(file)) {
            return;
        }
        file = configFile;
        loaded = false;
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        FRIENDS.clear();
        myCode = null;
        if (file == null) {
            return;
        }
        try {
            if (Files.isRegularFile(file)) {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("myCode")) {
                    String c = Wwls.cleanCode(o.get("myCode").getAsString());
                    myCode = c.length() == Wwls.CODE_LENGTH ? c : null;
                }
                if (o.has("friends") && o.get("friends").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("friends")) {
                        JsonObject f = e.getAsJsonObject();
                        String code = f.has("code") ? Wwls.cleanCode(f.get("code").getAsString()) : "";
                        String name = f.has("name") ? cleanName(f.get("name").getAsString()) : "";
                        if (code.length() == Wwls.CODE_LENGTH && FRIENDS.size() < MAX_FRIENDS) {
                            FRIENDS.add(new Friend(name.isEmpty() ? "Friend" : name, code));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("Cannot read the friends list. Starting a new one: " + e);
        }
        if (myCode == null) {
            myCode = Wwls.newCode(new SecureRandom().nextInt(Wwls.BACKENDS.length), Wwls.BACKENDS.length);
            save();
        }
    }

    private static synchronized void save() {
        if (file == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("myCode", myCode);
        JsonArray a = new JsonArray();
        for (Friend f : FRIENDS) {
            JsonObject x = new JsonObject();
            x.addProperty("name", f.name());
            x.addProperty("code", f.code());
            a.add(x);
        }
        o.add("friends", a);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("Cannot save the friends list: " + e);
        }
    }

    static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_ ]", "").trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    public static synchronized String myCode() {
        load();
        return myCode;
    }

    public static String shareText(String myName) {
        String n = cleanName(myName);
        return (n.isEmpty() ? "" : n + ":") + myCode();
    }

    public static synchronized List<Friend> list() {
        load();
        return List.copyOf(FRIENDS);
    }

    public static synchronized String add(String shared) {
        load();
        String raw = shared == null ? "" : shared.trim();
        String name = "";
        String code = raw;
        int colon = raw.lastIndexOf(':');
        if (colon > 0) {
            name = cleanName(raw.substring(0, colon));
            code = raw.substring(colon + 1);
        }
        code = Wwls.cleanCode(code);
        if (code.length() != Wwls.CODE_LENGTH) {
            return "A friend code has " + Wwls.CODE_LENGTH + " letters and numbers.";
        }
        if (code.equals(myCode)) {
            return "That is your own code.";
        }
        for (Friend f : FRIENDS) {
            if (f.code().equals(code)) {
                return f.name() + " is already on your list.";
            }
        }
        if (FRIENDS.size() >= MAX_FRIENDS) {
            return "Your list is full.";
        }
        Friend f = new Friend(name.isEmpty() ? "Friend " + (FRIENDS.size() + 1) : name, code);
        FRIENDS.add(f);
        save();
        return f.name() + " was added.";
    }

    public static synchronized void remove(String code) {
        load();
        FRIENDS.removeIf(f -> f.code().equals(code));
        STATUS.remove(code);
        save();
    }

    public static Status status(String code) {
        return STATUS.get(code);
    }

    public static void check() {
        for (Friend f : list()) {
            long now = System.currentTimeMillis();
            try {
                String address = Wwls.find(f.code());
                STATUS.put(f.code(), new Status(true, address, now));
            } catch (Wwls.WwlsException e) {
                STATUS.put(f.code(), new Status(false, "", now));
            }
        }
    }

    static synchronized void forget() {
        loaded = false;
        file = null;
        FRIENDS.clear();
        STATUS.clear();
        myCode = null;
    }
}
