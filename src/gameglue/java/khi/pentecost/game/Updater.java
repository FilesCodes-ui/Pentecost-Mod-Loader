package khi.pentecost.game;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.boot.PentecostMain;
import khi.pentecost.control.Settings;
import khi.pentecost.update.ReleaseInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Looks at Pentecost's GitHub releases for a newer version, tells the player with a toast, and updates only when they say so.
 * Nothing is downloaded or changed until the player presses Update and confirms. The update is the release's installer:
 * it is checked against the SHA-256 GitHub lists for it, then started, and Pentecost closes so the installer can replace its files.
 */
final class Updater {
    enum Phase { IDLE, CHECKING, CURRENT, AVAILABLE, FAILED, DOWNLOADING, READY }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
    private static final String AGENT = "Pentecost/" + PentecostMain.VERSION + " (update check)";

    private static volatile Phase phase = Phase.IDLE;
    private static volatile ReleaseInfo release;
    private static volatile String message = "";
    private static volatile float progress;
    private static boolean startupDone;
    private static boolean toasted;

    private Updater() {}

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    static Phase phase() {
        return phase;
    }

    static ReleaseInfo release() {
        return release;
    }

    static float progress() {
        return progress;
    }

    static boolean available() {
        return phase == Phase.AVAILABLE && release != null && windows();
    }

    static boolean busy() {
        return phase == Phase.CHECKING || phase == Phase.DOWNLOADING;
    }

    /** What the Updates row shows. */
    static String statusText() {
        return switch (phase) {
            case IDLE -> "Not checked yet";
            case CHECKING -> "Checking";
            case CURRENT -> "You have the newest version";
            case AVAILABLE -> release == null ? "" : "Version " + release.version() + " is ready";
            case DOWNLOADING -> "Downloading " + Math.round(progress * 100) + "%";
            case READY -> "Starting the installer";
            case FAILED -> message;
        };
    }

    /** Called when the main menu first shows: one quiet check, if the player has not turned it off. */
    static void startupCheck() {
        if (startupDone) {
            return;
        }
        startupDone = true;
        if (Settings.on(Settings.UPDATE_CHECK)) {
            check(false);
        }
    }

    /** Asks GitHub for the newest release. A failure is silent unless the player asked for the check. */
    static void check(boolean manual) {
        if (busy()) {
            return;
        }
        phase = Phase.CHECKING;
        Thread t = new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(ReleaseInfo.LATEST_API))
                        .header("User-Agent", AGENT).header("Accept", "application/vnd.github+json")
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 404) {
                    phase = Phase.CURRENT;
                    return;
                }
                if (r.statusCode() / 100 != 2) {
                    throw new IOException(r.statusCode() == 403 ? "GitHub says too many checks, try later" : "GitHub answered " + r.statusCode());
                }
                ReleaseInfo info = ReleaseInfo.parse(r.body());
                if (ReleaseInfo.newer(info.version(), PentecostMain.VERSION)) {
                    release = info;
                    phase = Phase.AVAILABLE;
                    Log.info("Update available: " + info.version());
                } else {
                    phase = Phase.CURRENT;
                }
            } catch (IOException | RuntimeException e) {
                message = "Could not check: " + (e.getMessage() == null ? "no reason given" : e.getMessage());
                phase = Phase.FAILED;
                Log.info("Update check failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                phase = Phase.IDLE;
            }
        }, "pentecost-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Called every frame. Shows the toast once, when an update is known and a menu is open to click it. */
    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (!startupDone && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            startupCheck();
        }
        if (toasted || !available()) {
            return;
        }
        if (mc.screen != null && mc.getOverlay() == null) {
            toasted = true;
            mc.getToasts().addToast(new UpdateToast(release));
        }
    }

    /** The "Update to X?" screen. Nothing happens until the player says yes. */
    static void ask(Screen back) {
        ReleaseInfo r = release;
        Minecraft mc = Minecraft.getInstance();
        if (r == null || !available()) {
            return;
        }
        mc.setScreen(new UpdatePromptScreen(back, r, () -> {
            download(r);
            mc.setScreen(new UpdateScreen(back));
        }));
    }

    /** Test switch: the update prompt or the toast with made-up notes, so the look can be checked without a release. Downloads nothing. */
    static Screen preview(Screen title, boolean prompt) {
        ReleaseInfo fake = new ReleaseInfo("v9.9.9", "9.9.9",
                "## What is new\n- A new video settings page with Embeddium and Oculus\n- A skin page with a rotating preview\n"
                        + "- A new look for the installer\n\nFixes\n- The update toast waits while the mouse is over it",
                "Pentecost-Installer-9.9.9.exe", "https://github.com/" + ReleaseInfo.REPO + "/releases/download/v9.9.9/x.exe",
                "0".repeat(64), 56_000_000L);
        if (!prompt) {
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().getToasts().addToast(new UpdateToast(fake)));
            return title;
        }
        return new UpdatePromptScreen(title, fake, () -> Minecraft.getInstance().setScreen(title));
    }

    private static void download(ReleaseInfo r) {
        phase = Phase.DOWNLOADING;
        progress = 0f;
        Thread t = new Thread(() -> {
            try {
                Path dir = Files.createTempDirectory("pentecost-update");
                Path file = dir.resolve(r.assetName());
                HttpRequest req = HttpRequest.newBuilder(URI.create(r.url()))
                        .header("User-Agent", AGENT).timeout(Duration.ofMinutes(20)).GET().build();
                HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("The download answered " + resp.statusCode());
                }
                if (!"https".equalsIgnoreCase(resp.uri().getScheme())) {
                    throw new IOException("The download is not https");
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long total = r.size() > 0 ? r.size() : resp.headers().firstValueAsLong("content-length").orElse(-1);
                long done = 0;
                try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(file)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            progress = Math.min(1f, done / (float) total);
                        }
                    }
                }
                String got = HexFormat.of().formatHex(md.digest());
                if (!got.equalsIgnoreCase(r.sha256())) {
                    Files.deleteIfExists(file);
                    throw new IOException("The download did not match its checksum, so it was thrown away");
                }
                Path mc = Dirs.home().getParent();
                new ProcessBuilder(file.toString(), "--update", "--wait", Long.toString(ProcessHandle.current().pid()),
                        "--minecraft-dir", mc.toString()).start();
                Log.info("Update " + r.version() + " downloaded and checked, installer started");
                phase = Phase.READY;
            } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
                message = "Update failed: " + (e.getMessage() == null ? "no reason given" : e.getMessage());
                phase = Phase.FAILED;
                Log.error(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                message = "Update stopped";
                phase = Phase.FAILED;
            }
        }, "pentecost-update-download");
        t.setDaemon(true);
        t.start();
    }

    static void toast(String title, String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(text)));
    }
}
