package khi.pentecost.game;

import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who carries the [dev] tag. The list is the dev file in the game folder, plus the offline "Dev" player,
 * plus anyone another Pentecost client announces once WWLS can carry that message.
 */
public final class PentecostDev {
    private static final String OFFLINE = "00000000000000000000000000000000";
    private static final Set<String> IDS = ConcurrentHashMap.newKeySet();
    private static boolean loaded;

    private PentecostDev() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("pentecost-dev.txt");
    }

    private static String plain(UUID id) {
        return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) {
                    String s = line.trim().replace("-", "").toLowerCase(Locale.ROOT);
                    if (!s.isEmpty() && !s.startsWith("#")) {
                        IDS.add(s);
                    }
                }
            }
        } catch (IOException e) {
            Log.error("Cannot read the dev file: " + e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), String.join("\n", IDS) + "\n");
        } catch (IOException e) {
            Log.error("Cannot save the dev file: " + e);
        }
    }

    public static boolean isDev(UUID id) {
        if (id == null || !Settings_on()) {
            return false;
        }
        load();
        String p = plain(id);
        return p.equals(OFFLINE) || IDS.contains(p);
    }

    /** Another Pentecost client said this player is a dev. */
    public static void announced(UUID id) {
        IDS.add(plain(id));
    }

    private static boolean Settings_on() {
        return !"off".equals(System.getProperty("pentecost.devtag"));
    }

    public static boolean ownIsDev() {
        load();
        String id = Minecraft.getInstance().getUser().getProfileId().toString();
        return isDev(UUID.fromString(id));
    }

    public static boolean ownIsListed() {
        load();
        return IDS.contains(plain(Minecraft.getInstance().getUser().getProfileId()));
    }

    public static void toggleOwn() {
        load();
        String id = plain(Minecraft.getInstance().getUser().getProfileId());
        if (!IDS.remove(id)) {
            IDS.add(id);
        }
        save();
    }

    public static void accountChanged() {
        // nothing is cached per account yet
    }
}
