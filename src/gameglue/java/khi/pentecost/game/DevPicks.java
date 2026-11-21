package khi.pentecost.game;

import com.google.gson.JsonObject;
import khi.pentecost.auth.DevFeed;
import khi.pentecost.boot.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class DevPicks {
    private static volatile boolean busy;

    private DevPicks() {}

    static boolean busy() {
        return busy;
    }

    static boolean allowed() {
        return PentecostDev.ownIsOwner();
    }

    static boolean isPick(String texture) {
        if (texture == null || texture.isEmpty()) {
            return false;
        }
        for (DevFeed.SkinPick p : DevFeed.skinPicks()) {
            if (p.texture().equals(texture)) {
                return true;
            }
        }
        return false;
    }

    private static Path data() {
        String app = System.getenv("APPDATA");
        return Path.of(app == null ? System.getProperty("user.home") : app, "PentecostManager");
    }

    static void send(boolean add, String name, String texture) {
        if (!allowed() || busy || texture == null || !texture.matches("[0-9a-f]{20,80}")) {
            return;
        }
        busy = true;
        Wardrobe.say(add ? "Making " + name + " a Pentecost pick..." : "Taking " + name + " out of the picks...");
        Thread t = new Thread(() -> {
            try {
                Path data = data();
                Path exeFile = data.resolve("manager-path.txt");
                if (!Files.isRegularFile(exeFile)) {
                    Wardrobe.say("Open the Pentecost Manager once so the game can find it, then try again.");
                    return;
                }
                Path exe = Path.of(Files.readString(exeFile, StandardCharsets.UTF_8).strip());
                if (!Files.isRegularFile(exe)) {
                    Wardrobe.say("The Pentecost Manager moved. Open it once, then try again.");
                    return;
                }
                Path inbox = Files.createDirectories(data.resolve("dev-inbox"));
                JsonObject o = new JsonObject();
                o.addProperty("op", add ? "addPick" : "removePick");
                o.addProperty("name", name);
                o.addProperty("texture", texture);
                Files.writeString(inbox.resolve(System.currentTimeMillis() + ".json"), o.toString(), StandardCharsets.UTF_8);
                Path result = inbox.resolve("result.txt");
                Files.deleteIfExists(result);
                Process p = new ProcessBuilder(exe.toString(), "--dev-inbox").redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!p.waitFor(3, TimeUnit.MINUTES)) {
                    p.destroy();
                    Wardrobe.say("Publishing took too long. Check the Dev tab in the Manager.");
                    return;
                }
                String r = Files.isRegularFile(result) ? Files.readString(result, StandardCharsets.UTF_8).strip() : "error: no answer";
                if (r.startsWith("ok")) {
                    Wardrobe.say(add ? name + " is a Pentecost pick now. Everyone sees it in Browse." : name + " is no longer a pick.");
                    FeedHooks.ping();
                } else {
                    Wardrobe.say("Could not publish. " + r.replaceFirst("^error: ", ""));
                }
            } catch (Exception e) {
                Log.warn("Dev picks: " + e);
                Wardrobe.say("Could not publish. " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "pentecost-dev-picks");
        t.setDaemon(true);
        t.start();
    }
}
