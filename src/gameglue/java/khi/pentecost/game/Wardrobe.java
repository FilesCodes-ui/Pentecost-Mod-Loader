package khi.pentecost.game;

import com.mojang.authlib.GameProfile;
import khi.pentecost.account.AccountInfo;
import khi.pentecost.account.SkinApi;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.wardrobe.SkinImage;
import khi.pentecost.wardrobe.WardrobeStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Puts saved skins on the real account and keeps the wardrobe's data. The screen asks, this class does the work
 * on its own thread and leaves a message behind. Rules on servers and worlds are checked every frame by {@link #frame()}.
 *
 * Mojang limits how often a skin can change, so every change waits at least {@link #COOLDOWN_MS} after the last one.
 * The skin worn before a change is kept in the wardrobe history, so one press goes back.
 */
final class Wardrobe {
    record Player(String name, String id) {}

    private interface Job {
        void run() throws IOException;
    }

    private interface Change {
        void run(String token) throws IOException;
    }

    private static final long COOLDOWN_MS = 20_000;

    private static WardrobeStore store;
    private static boolean storeFailed;
    private static volatile boolean working;
    private static volatile String message = "";
    private static volatile long messageUntil;
    private static volatile long lastApplyAt;
    private static volatile String added = "";

    /** What the account wears now as far as Pentecost knows, so history does not depend on Mojang's caches. */
    private static byte[] current;
    private static boolean currentSlim;
    private static String currentFor = "";

    private static String lastPlace = "";
    private static volatile String pendingOutfit = "";
    private static long pendingSince;

    private Wardrobe() {}

    /** The wardrobe's data, or null when its folder cannot be used. */
    static synchronized WardrobeStore store() {
        if (store == null && !storeFailed) {
            try {
                store = WardrobeStore.open(Dirs.config().resolve("pentecost").resolve("wardrobe"));
            } catch (IOException | RuntimeException e) {
                storeFailed = true;
                Log.error("Cannot open the wardrobe: " + e);
            }
        }
        return store;
    }

    static String message() {
        return System.currentTimeMillis() < messageUntil ? message : "";
    }

    static void say(String text) {
        message = text;
        messageUntil = System.currentTimeMillis() + 7000;
    }

    static boolean busy() {
        return working;
    }

    /** Seconds until the next change to the account is allowed, 0 when it is allowed now. */
    static int waitSeconds() {
        long ms = lastApplyAt + COOLDOWN_MS - System.currentTimeMillis();
        return ms <= 0 ? 0 : (int) (ms / 1000) + 1;
    }

    static boolean ready() {
        return Account.signedIn() && !working && waitSeconds() == 0;
    }

    /** The id of the skin that was added last, once. The screen selects it. */
    static String takeAdded() {
        String a = added;
        added = "";
        return a;
    }

    private static String reason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static void tell(boolean auto, String text) {
        say(text);
        if (auto) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.gui != null) {
                    mc.gui.getChat().addMessage(Component.literal("[Pentecost] " + text));
                }
            });
        }
    }

    // ------------------------------------------------------------------ changing the account

    private static boolean start(String what, boolean auto, Change change) {
        if (!Account.signedIn()) {
            tell(auto, "Sign in to change your skin. Saving and previewing work without an account.");
            return false;
        }
        if (working) {
            say("Wait for the last change to finish.");
            return false;
        }
        int wait = waitSeconds();
        if (wait > 0) {
            say("Mojang limits skin changes. Try again in " + wait + " seconds.");
            return false;
        }
        String token = Minecraft.getInstance().getUser().getAccessToken();
        if (token == null || token.isBlank() || token.equals("0")) {
            tell(auto, "No Microsoft session. Sign in again from the account screen.");
            return false;
        }
        working = true;
        Thread t = new Thread(() -> {
            try {
                change.run(token);
            } catch (IOException | RuntimeException e) {
                Log.warn("Wardrobe could not " + what + ": " + e);
                tell(auto, "Could not " + what + ". " + reason(e));
            } finally {
                working = false;
            }
        }, "pentecost-wardrobe");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** capeId: null leaves the cape as it is, empty takes it off, an id wears that cape. */
    private static boolean wearBytes(byte[] png, boolean slim, String capeId, String label, String outfitId, boolean auto) {
        return start("wear " + label, auto, token -> {
            WardrobeStore st = store();
            byte[] up = SkinImage.upgradeLegacy(png);
            keepCurrent(st, WardrobeStore.sha1(up));
            SkinApi.upload(token, up, slim);
            lastApplyAt = System.currentTimeMillis();
            synchronized (Wardrobe.class) {
                current = up;
                currentSlim = slim;
                currentFor = Account.uuid();
            }
            AccountSkin.set(up);
            String capeNote = applyCape(token, capeId);
            if (st != null) {
                st.setLastOutfit(outfitId);
            }
            tell(auto, "Now wearing " + label + capeNote);
            Account.refreshInfo();
        });
    }

    private static String applyCape(String token, String capeId) throws IOException {
        if (capeId == null) {
            return "";
        }
        AccountInfo info = Account.info();
        AccountInfo.Cape active = info == null ? null : info.activeCape();
        if (capeId.isEmpty()) {
            if (active != null) {
                SkinApi.hideCape(token);
                lastApplyAt = System.currentTimeMillis();
                return " without a cape";
            }
            return "";
        }
        if (active != null && active.id().equals(capeId)) {
            return "";
        }
        boolean owned = info != null && info.capes.stream().anyMatch(c -> c.id().equals(capeId));
        if (!owned) {
            return ". Your account does not own that cape, so the cape stayed as it was";
        }
        SkinApi.setCape(token, capeId);
        lastApplyAt = System.currentTimeMillis();
        return " with a cape";
    }

    /** The skin the account wears now. Loads it from Mojang once. */
    private static byte[] currentSkin() throws IOException {
        String who = Account.uuid();
        synchronized (Wardrobe.class) {
            if (current != null && who.equals(currentFor)) {
                return current;
            }
        }
        AccountInfo info = Account.info();
        if (info == null || info.skinUrl.isBlank()) {
            throw new IOException("Your account's skin has not loaded yet. Try again in a moment.");
        }
        byte[] png = SkinApi.download(info.skinUrl);
        synchronized (Wardrobe.class) {
            current = png;
            currentSlim = "SLIM".equalsIgnoreCase(info.skinVariant);
            currentFor = who;
        }
        return png;
    }

    /** Puts the skin worn now in the history, unless it is the one about to be put on. */
    private static void keepCurrent(WardrobeStore st, String newSha) {
        if (st == null) {
            return;
        }
        try {
            byte[] now = currentSkin();
            String sha = WardrobeStore.sha1(now);
            if (sha.equals(newSha)) {
                return;
            }
            AccountInfo info = Account.info();
            AccountInfo.Cape cape = info == null ? null : info.activeCape();
            String name = "Earlier skin";
            for (WardrobeStore.Skin s : st.skins()) {
                if (s.sha1.equals(sha)) {
                    name = s.name;
                    break;
                }
            }
            st.remember(now, currentSlim, cape == null ? "" : cape.id(), name);
        } catch (IOException | RuntimeException e) {
            Log.warn("Wardrobe could not keep the skin worn before: " + e);
        }
    }

    /** Wears a saved skin. capeId as in wearBytes. */
    static boolean wear(WardrobeStore.Skin s, String capeId) {
        WardrobeStore st = store();
        if (st == null) {
            return false;
        }
        try {
            return wearBytes(st.bytes(s), s.slim, capeId, s.name, "", false);
        } catch (IOException e) {
            say("Could not read " + s.name + ". " + reason(e));
            return false;
        }
    }

    static boolean wearOutfit(WardrobeStore.Outfit o, boolean auto) {
        WardrobeStore st = store();
        WardrobeStore.Skin s = st == null ? null : st.skin(o.skinId);
        if (s == null) {
            say("The skin of that outfit is gone.");
            return false;
        }
        try {
            return wearBytes(st.bytes(s), s.slim, o.capeId, o.name, o.id, auto);
        } catch (IOException e) {
            say("Could not read " + s.name + ". " + reason(e));
            return false;
        }
    }

    /** Puts a skin from the history back on. */
    static boolean wearPast(WardrobeStore.Past p) {
        WardrobeStore st = store();
        if (st == null) {
            return false;
        }
        try {
            return wearBytes(st.historyBytes(p), p.slim, p.capeId, p.name.isEmpty() ? "an earlier skin" : p.name, "", false);
        } catch (IOException e) {
            say("Could not read that skin. " + reason(e));
            return false;
        }
    }

    /** A random saved skin other than the one worn most recently. */
    static boolean wearRandom() {
        WardrobeStore st = store();
        if (st == null || st.skins().isEmpty()) {
            say("Save a skin first.");
            return false;
        }
        List<WardrobeStore.Skin> all = new ArrayList<>(st.skins());
        String now;
        synchronized (Wardrobe.class) {
            now = current == null ? "" : WardrobeStore.sha1(current);
        }
        if (all.size() > 1) {
            all.removeIf(s -> s.sha1.equals(now));
        }
        return wear(all.get(new java.util.Random().nextInt(all.size())), null);
    }

    /** One skin for each calendar day, the same all day. */
    static boolean wearOfTheDay() {
        WardrobeStore st = store();
        if (st == null || st.skins().isEmpty()) {
            say("Save a skin first.");
            return false;
        }
        List<WardrobeStore.Skin> all = new ArrayList<>(st.skins());
        all.sort(Comparator.comparing((WardrobeStore.Skin s) -> s.id));
        long day = LocalDate.now().toEpochDay();
        return wear(all.get((int) Math.floorMod(day * 2654435761L, (long) all.size())), null);
    }

    // ------------------------------------------------------------------ adding skins

    private static void background(String what, Job job) {
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (IOException | RuntimeException e) {
                Log.warn("Wardrobe could not " + what + ": " + e);
                say("Could not " + what + ". " + reason(e));
            }
        }, "pentecost-wardrobe-add");
        t.setDaemon(true);
        t.start();
    }

    private static void keep(byte[] png, String name, String from, boolean slim) throws IOException {
        WardrobeStore st = store();
        if (st == null) {
            throw new IOException("The wardrobe folder cannot be used");
        }
        long t0 = System.currentTimeMillis();
        WardrobeStore.Skin s = st.addSkin(png, name, from);
        if (s.added >= t0) {
            st.setSlim(s.id, slim);
            say("Saved " + s.name);
        } else {
            say(s.name + " is already in the wardrobe");
        }
        added = s.id;
    }

    private static void keepFromProfile(String id, String name) throws IOException {
        SkinApi.Textures t = SkinApi.texturesOf(id);
        if (t == null || t.skinUrl() == null || t.skinUrl().isBlank()) {
            throw new IOException(name + " has no custom skin");
        }
        keep(SkinApi.download(t.skinUrl()), name, name, t.slim());
    }

    static void addByName(String text) {
        String n = text == null ? "" : text.strip();
        if (!SkinApi.isName(n)) {
            say("That is not a Minecraft name. Use 3 to 16 letters, digits or underscores.");
            return;
        }
        say("Looking up " + n + "...");
        background("add " + n, () -> keepFromProfile(SkinApi.idOf(n), n));
    }

    static void addPlayer(Player p) {
        say("Saving the skin of " + p.name() + "...");
        background("save the skin of " + p.name(), () -> keepFromProfile(p.id(), p.name()));
    }

    static void addCurrent() {
        if (!Account.signedIn()) {
            say("Sign in first, or add your skin by name.");
            return;
        }
        background("add your skin", () -> {
            byte[] png = currentSkin();
            boolean slim;
            synchronized (Wardrobe.class) {
                slim = currentSlim;
            }
            keep(png, "My skin", "Current skin", slim);
        });
    }

    /** A skin file, or a wardrobe zip. The text may be a path with quotes, as Windows copies it. */
    static void addFile(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.isEmpty()) {
            say("Type or paste the path of a PNG file.");
            return;
        }
        try {
            addFile(Path.of(t));
        } catch (RuntimeException e) {
            say("That is not a file path.");
        }
    }

    static void addFile(Path path) {
        background("add " + path.getFileName(), () -> {
            if (!Files.isRegularFile(path)) {
                throw new IOException("There is no file at " + path);
            }
            String file = path.getFileName().toString();
            String lower = file.toLowerCase(Locale.ROOT);
            WardrobeStore st = store();
            if (st == null) {
                throw new IOException("The wardrobe folder cannot be used");
            }
            if (lower.endsWith(".zip")) {
                int n = st.importFrom(path);
                say(n == 0 ? "Nothing new in that wardrobe file" : "Added " + n + (n == 1 ? " skin" : " skins") + " from the wardrobe file");
                return;
            }
            if (Files.size(path) > 1_000_000) {
                throw new IOException("That file is too big to be a skin");
            }
            String stem = lower.endsWith(".png") ? file.substring(0, file.length() - 4) : file;
            byte[] png = Files.readAllBytes(path);
            keep(png, stem, file, SkinImage.check(png).slim());
        });
    }

    /** Fix for an old 64 by 32 skin: converts it to the current layout. */
    static void fix(WardrobeStore.Skin s) {
        background("fix " + s.name, () -> {
            WardrobeStore st = store();
            if (st == null) {
                throw new IOException("The wardrobe folder cannot be used");
            }
            st.replaceImage(s.id, SkinImage.upgradeLegacy(st.bytes(s)));
            added = s.id;
            say("Converted " + s.name + " to the current layout");
        });
    }

    static void exportAll() {
        background("export the wardrobe", () -> {
            WardrobeStore st = store();
            if (st == null) {
                throw new IOException("The wardrobe folder cannot be used");
            }
            Path zip = st.root().resolve("export-" + LocalDate.now() + ".zip");
            st.exportTo(zip);
            say("Exported to " + zip);
        });
    }

    /** Everyone in the world or server now, for saving their skins. Game thread only. */
    static List<Player> playersHere() {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) {
            return List.of();
        }
        List<Player> out = new ArrayList<>();
        for (PlayerInfo p : conn.getOnlinePlayers()) {
            GameProfile gp = p.getProfile();
            if (gp != null && gp.getId() != null && gp.getName() != null) {
                out.add(new Player(gp.getName(), gp.getId().toString().replace("-", "")));
            }
        }
        out.sort(Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ rules for servers and worlds

    /** "server:address name", "world:name", or empty when not in a game. */
    static String place() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "";
        }
        ServerData sd = mc.getCurrentServer();
        if (sd != null) {
            return "server:" + sd.ip + " " + sd.name;
        }
        if (mc.getSingleplayerServer() != null) {
            return "world:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "";
    }

    /** Called every frame. When the player joins a place that has a rule, wears the rule's outfit once. */
    static void frame() {
        String place = place();
        if (!place.equals(lastPlace)) {
            lastPlace = place;
            pendingOutfit = "";
            if (!place.isEmpty()) {
                plan(place);
            }
        }
        String want = pendingOutfit;
        if (want.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() - pendingSince > 180_000) {
            pendingOutfit = "";
            return;
        }
        if (ready()) {
            pendingOutfit = "";
            WardrobeStore st = store();
            WardrobeStore.Outfit o = st == null ? null : st.outfit(want);
            if (o != null) {
                wearOutfit(o, true);
            }
        }
    }

    private static void plan(String place) {
        WardrobeStore st = store();
        if (st == null || st.rules().isEmpty()) {
            return;
        }
        int colon = place.indexOf(':');
        WardrobeStore.Rule rule = st.match(place.substring(0, colon), place.substring(colon + 1));
        WardrobeStore.Outfit outfit = rule == null ? null : st.outfit(rule.outfitId);
        if (outfit == null || outfit.id.equals(st.lastOutfit())) {
            return;
        }
        pendingOutfit = outfit.id;
        pendingSince = System.currentTimeMillis();
    }
}
