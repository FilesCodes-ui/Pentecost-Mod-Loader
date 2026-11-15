package khi.pentecost.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.platform.win32.Crypt32Util;
import khi.pentecost.boot.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/**
 * Where the saved sign in lives on disk.
 *
 * The Microsoft refresh token lasts for months and lets anyone who holds it sign in as the player,
 * so it is never written as plain text.
 *  - Windows: encrypted with the player's Windows account (DPAPI). Another Windows user, another computer
 *    or a copied file cannot read it.
 *  - Other systems: the file is created readable by its owner only.
 * If Windows cannot protect the token, nothing is saved and the player signs in again next time.
 * The name and UUID are not secret and stay readable.
 */
final class TokenStore {
    record Saved(String refresh, String name, String uuid) {}

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private TokenStore() {}

    static void save(Path file, String name, String uuid, String refresh) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("v", 2);
        o.addProperty("name", name);
        o.addProperty("uuid", uuid);
        if (WINDOWS) {
            try {
                byte[] sealed = Crypt32Util.cryptProtectData(refresh.getBytes(StandardCharsets.UTF_8));
                o.addProperty("protection", "dpapi");
                o.addProperty("token", Base64.getEncoder().encodeToString(sealed));
            } catch (RuntimeException | LinkageError e) {
                throw new IOException("Windows could not protect the saved sign in, so it was not saved.");
            }
        } else {
            o.addProperty("protection", "owner-only");
            o.addProperty("token", refresh);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (POSIX) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Returns the saved sign in, or null when there is none that can be read. A plain text file from an older version is protected on the spot. */
    static Saved load(Path file) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        String name = o.get("name").getAsString();
        String uuid = o.has("uuid") ? o.get("uuid").getAsString() : "";

        if (o.has("refresh")) {
            String refresh = o.get("refresh").getAsString();
            try {
                save(file, name, uuid, refresh);
            } catch (IOException e) {
                // Never leave the plain text behind. The player stays signed in for this run only.
                Files.deleteIfExists(file);
                Log.error("Cannot protect the old saved sign in, so it was removed: " + e.getMessage());
            }
            return new Saved(refresh, name, uuid);
        }

        String token = o.get("token").getAsString();
        String protection = o.has("protection") ? o.get("protection").getAsString() : "";
        if (protection.equals("dpapi")) {
            if (!WINDOWS) {
                Files.deleteIfExists(file);
                return null;
            }
            try {
                byte[] plain = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(token));
                return new Saved(new String(plain, StandardCharsets.UTF_8), name, uuid);
            } catch (RuntimeException | LinkageError e) {
                // Copied from another account or computer, or damaged. It cannot be used, so remove it.
                Log.warn("The saved sign in cannot be read on this account, so it was removed.");
                Files.deleteIfExists(file);
                return null;
            }
        }
        return new Saved(token, name, uuid);
    }
}
