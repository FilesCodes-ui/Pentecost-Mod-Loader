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
 * Who carries the [dev] tag: only the owner's account, never anyone else. The owner can switch their own tag
 * on and off. Nothing else grants it: not the dev file, not the offline "Dev" player, not another client's message.
 */
public final class PentecostDev {
    /** The owner's Minecraft account id (Gabriel, The_Files), without dashes. */
    private static final String OWNER = "808323d1022140ccb69bf8bcd0d16112";
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
                    if (s.equals(OWNER)) {
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
        String p = plain(id);
        if (!p.equals(OWNER)) {
            return false;
        }
        // On the owner's own game the tag follows the owner's switch. On everyone else's game it is always shown:
        // Mojang has already checked the account id by the time the game knows it, so nobody else can carry it.
        if (ownIsOwner()) {
            load();
            return IDS.contains(p);
        }
        return true;
    }

    /** Another Pentecost client said this player is a dev. */
    public static void announced(UUID id) {
        // Ignored on purpose: a message from another client proves nothing, so it can never grant the tag.
    }

    private static boolean Settings_on() {
        return !"off".equals(System.getProperty("pentecost.devtag"));
    }

    public static boolean ownIsDev() {
        load();
        String id = Minecraft.getInstance().getUser().getProfileId().toString();
        return isDev(UUID.fromString(id));
    }

    /**
     * True only when the signed-in account is the owner's, with a real Microsoft session. The Developer tag button
     * is shown only then. An offline or cracked player using the owner's id does not count.
     */
    public static boolean ownIsOwner() {
        if (!Account.signedIn()) {
            return false;
        }
        return plain(Minecraft.getInstance().getUser().getProfileId()).equals(OWNER);
    }

    public static boolean ownIsListed() {
        load();
        return IDS.contains(plain(Minecraft.getInstance().getUser().getProfileId()));
    }

    public static void toggleOwn() {
        load();
        String id = plain(Minecraft.getInstance().getUser().getProfileId());
        if (!ownIsOwner()) {
            return;
        }
        if (!IDS.remove(id)) {
            IDS.add(id);
        }
        save();
    }

    public static void accountChanged() {
        // nothing is cached per account yet
    }
}
