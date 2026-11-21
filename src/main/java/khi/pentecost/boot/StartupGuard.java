package khi.pentecost.boot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StartupGuard {
    public enum Decision { RETRY, TURN_OFF, GIVE_UP }

    static final int MAX_STREAK = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern FORGE_FAIL = Pattern.compile("\\(([a-z0-9_.\\-]+)\\) encountered an error");
    private static final Pattern FABRIC_FAIL = Pattern.compile("provided by '([a-z0-9_.\\-]+)'");
    private static final Set<String> CORE = Set.of("minecraft", "forge", "fml", "java", "fabricloader", "quilt_loader",
            "fabric", "fabric-api", "pentecost");

    private static volatile String[] mainArgs = new String[0];
    private static volatile boolean menu;
    private static volatile boolean acted;
    private static volatile Set<String> offCache;

    private StartupGuard() {}

    public static void args(String[] args) {
        mainArgs = args == null ? new String[0] : args.clone();
    }

    static Path file() {
        return Dirs.mk(Dirs.config().resolve("pentecost")).resolve("startup-guard.json");
    }

    static synchronized JsonObject load() {
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                JsonElement e = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonElement.class);
                if (e != null && e.isJsonObject()) {
                    return e.getAsJsonObject();
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("Startup guard file could not be read, starting fresh: " + e);
        }
        return new JsonObject();
    }

    static synchronized void save(JsonObject state) {
        try {
            Path f = file();
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(state), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Log.warn("Startup guard file could not be saved: " + e);
        }
        offCache = null;
    }

    private static JsonObject off(JsonObject state) {
        if (!state.has("off") || !state.get("off").isJsonObject()) {
            state.add("off", new JsonObject());
        }
        return state.getAsJsonObject("off");
    }

    public static Set<String> offIds() {
        Set<String> c = offCache;
        if (c != null) {
            return c;
        }
        Set<String> out = new LinkedHashSet<>();
        JsonObject off = off(load());
        for (String id : off.keySet()) {
            out.add(id);
            JsonObject entry = off.getAsJsonObject(id);
            if (entry != null && entry.has("with")) {
                for (JsonElement w : entry.getAsJsonArray("with")) {
                    out.add(w.getAsString());
                }
            }
        }
        offCache = Set.copyOf(out);
        return offCache;
    }

    public static List<String[]> offList() {
        List<String[]> rows = new ArrayList<>();
        JsonObject off = off(load());
        for (String id : off.keySet()) {
            JsonObject e = off.getAsJsonObject(id);
            List<String> with = new ArrayList<>();
            if (e.has("with")) {
                for (JsonElement w : e.getAsJsonArray("with")) {
                    with.add(w.getAsString());
                }
            }
            rows.add(new String[]{id, e.has("name") ? e.get("name").getAsString() : id, String.join(", ", with)});
        }
        return rows;
    }

    public static void allowAll() {
        JsonObject s = load();
        s.add("off", new JsonObject());
        s.addProperty("lastMod", "");
        s.addProperty("lastCount", 0);
        save(s);
        Log.info("Startup guard: every mod turned off by Pentecost is allowed again from the next launch");
    }

    public static boolean menuReached() {
        return menu;
    }

    public static void markMenu() {
        if (menu) {
            return;
        }
        menu = true;
        JsonObject s = load();
        if (s.has("streak") && s.get("streak").getAsInt() != 0 || s.has("lastCount") && s.get("lastCount").getAsInt() != 0) {
            s.addProperty("streak", 0);
            s.addProperty("lastMod", "");
            s.addProperty("lastCount", 0);
            save(s);
        }
    }

    static Decision decide(int streakBefore, String lastMod, int lastCount, String mod) {
        if (streakBefore + 1 > MAX_STREAK) {
            return Decision.GIVE_UP;
        }
        if (mod != null && mod.equals(lastMod) && lastCount >= 1) {
            return Decision.TURN_OFF;
        }
        return Decision.RETRY;
    }

    static List<String> withDependents(String id, List<ModInfo> mods) {
        Set<String> gone = new LinkedHashSet<>();
        gone.add(id);
        Path jar = null;
        for (ModInfo m : mods) {
            if (m.id().equals(id)) {
                jar = m.jar();
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ModInfo m : mods) {
                if (gone.contains(m.id()) || CORE.contains(m.id()) || m.loader() == LoaderKind.LIBRARY) {
                    continue;
                }
                boolean sameJar = jar != null && jar.equals(m.jar());
                boolean needs = m.depends() != null && m.depends().stream().anyMatch(gone::contains);
                if (sameJar || needs) {
                    gone.add(m.id());
                    grew = true;
                }
            }
        }
        gone.remove(id);
        return new ArrayList<>(gone);
    }

    static boolean isStartupCrash(String text) {
        return text != null && (text.contains("Description: Initializing game")
                || text.contains("Description: Startup guard test"));
    }

    static String failingMod(String text, String blamed) {
        if (text != null) {
            Matcher m = FORGE_FAIL.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
            m = FABRIC_FAIL.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return blamed == null || CORE.contains(blamed) ? null : blamed;
    }

    public static String onCrash(String text, String blamed, List<ModInfo> mods) {
        if (menu || acted || System.getenv("PENTECOST_NO_STARTUP_GUARD") != null || !isStartupCrash(text)) {
            return null;
        }
        acted = true;
        try {
            String mod = failingMod(text, blamed);
            JsonObject s = load();
            int streak = s.has("streak") ? s.get("streak").getAsInt() : 0;
            String lastMod = s.has("lastMod") ? s.get("lastMod").getAsString() : "";
            int lastCount = s.has("lastCount") ? s.get("lastCount").getAsInt() : 0;
            Decision d = decide(streak, lastMod, lastCount, mod);
            String name = mod;
            for (ModInfo m : mods) {
                if (m.id().equals(mod)) {
                    name = m.name();
                }
            }
            String note;
            switch (d) {
                case GIVE_UP -> {
                    s.addProperty("streak", 0);
                    s.addProperty("lastMod", "");
                    s.addProperty("lastCount", 0);
                    save(s);
                    note = "STARTUP GUARD: the game failed to start " + MAX_STREAK
                            + " times in a row, so Pentecost stopped restarting it. Launch again to retry.";
                    Log.error(note);
                    return note;
                }
                case TURN_OFF -> {
                    List<String> with = withDependents(mod, mods);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", name);
                    entry.addProperty("at", System.currentTimeMillis());
                    entry.addProperty("reason", firstLine(text));
                    JsonArray arr = new JsonArray();
                    with.forEach(arr::add);
                    entry.add("with", arr);
                    off(s).add(mod, entry);
                    s.addProperty("lastMod", "");
                    s.addProperty("lastCount", 0);
                    s.addProperty("warn", true);
                    note = "STARTUP GUARD: " + name + " broke startup twice in a row. Pentecost turned it off"
                            + (with.isEmpty() ? "" : ", plus " + with.size() + " mods that need it (" + String.join(", ", with) + ")")
                            + ", and is restarting the game.";
                }
                default -> {
                    s.addProperty("lastMod", mod == null ? "" : mod);
                    s.addProperty("lastCount", mod != null && mod.equals(lastMod) ? lastCount + 1 : 1);
                    note = "STARTUP GUARD: the game crashed while starting" + (mod == null ? "" : ", in " + name)
                            + ". Pentecost is restarting it once with the same mods.";
                }
            }
            s.addProperty("streak", streak + 1);
            save(s);
            Log.error(note);
            relaunchOnExit();
            return note;
        } catch (RuntimeException e) {
            Log.warn("Startup guard failed and did nothing: " + e);
            return null;
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = FORGE_FAIL.matcher(text);
        if (m.find()) {
            int start = text.lastIndexOf('\n', m.start()) + 1;
            int end = text.indexOf('\n', m.end());
            String line = text.substring(start, end < 0 ? text.length() : end).replaceAll("§.", "").strip();
            return line.length() > 300 ? line.substring(0, 300) : line;
        }
        return "";
    }

    static List<String> relaunchCommand() {
        List<String> cmd = new ArrayList<>();
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        cmd.add(Path.of(System.getProperty("java.home"), "bin", win ? "javaw.exe" : "java").toString());
        List<String> flags = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String a : flags) {
            if (a.startsWith("-Djava.class.path") || a.equals("-cp") || a.equals("-classpath")
                    || a.startsWith("-agentlib:jdwp") || a.startsWith("-Dpentecost.guardRelaunch")) {
                continue;
            }
            cmd.add(a);
        }
        cmd.add("-Dpentecost.guardRelaunch=true");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        String main = System.getProperty("sun.java.command", "");
        String mainClass = main.isBlank() ? "" : main.split(" ")[0];
        if (mainClass.isEmpty() || mainClass.endsWith(".jar")) {
            mainClass = "khi.pentecost.boot.PentecostMain";
        }
        cmd.add(mainClass);
        cmd.addAll(List.of(mainArgs));
        return cmd;
    }

    private static void relaunchOnExit() {
        List<String> cmd = relaunchCommand();
        Path out = Dirs.mk(Dirs.run().resolve("logs")).resolve("startup-guard-relaunch.txt");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new ProcessBuilder(cmd)
                        .directory(Path.of("").toAbsolutePath().toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(out.toFile())
                        .start();
            } catch (IOException | RuntimeException e) {
                System.err.println("[Pentecost] Startup guard could not restart the game: " + e);
            }
        }, "pentecost-startup-guard"));
    }
}
