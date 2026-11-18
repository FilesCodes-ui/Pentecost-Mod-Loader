package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.packs.PackStore;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Finds, installs, updates and deletes resource packs or shader packs, in Pentecost's look.
 * Files come from Modrinth through {@link PackStore}: https, modrinth.com only, hashes checked.
 * Two tabs: Browse searches Modrinth, Installed lists the pack folder and says which files Modrinth has a newer version of.
 * Network work runs on its own threads. The screen only reads what they leave behind.
 */
final class PackStoreScreen extends Screen {
    private enum Tab { BROWSE, INSTALLED }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static final String[][] SORTS = {{"Relevance", "relevance"}, {"Downloads", "downloads"}, {"Newest", "newest"}, {"Updated", "updated"}};
    private static final int MARGIN = 48;
    private static final int ROW = 62;
    private static final int ROW_INSTALLED = 48;
    private static final int GOOD = 0xFF8FD18F;
    private static final int BAD = 0xFFE08080;

    private final Screen parent;
    private final PackStore.Kind kind;
    private final Supplier<Path> folder;
    private final Runnable done;
    private final String game = SharedConstants.getCurrentVersion().getName();
    private final List<Hit> hits = new ArrayList<>();

    private Tab tab = Tab.BROWSE;
    private String query = "";
    private boolean focus;
    private String sort = "relevance";
    private long searchAt;
    private boolean searchDue;
    private int boxX;
    private int boxY;
    private int boxW;

    private final AtomicInteger searchSeq = new AtomicInteger();
    private volatile List<PackStore.Project> results = List.of();
    private volatile int total;
    private volatile boolean loading;
    private volatile String error = "";

    /** Project id to what its Install button shows: Installing, Installed or Failed with a reason. */
    private final Map<String, String> status = new ConcurrentHashMap<>();
    private volatile List<PackStore.Local> locals = List.of();
    /** SHA-1 of an installed file to what Modrinth knows about it. */
    private volatile Map<String, PackStore.Known> known = Map.of();
    private volatile boolean checking;
    private volatile boolean needRefresh;
    private volatile String knownError = "";
    /** Names of installed packs being updated or deleted right now. */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private String sure = "";
    private long sureUntil;

    private boolean started;
    private int scroll;
    private int maxScroll;
    private int top;
    private int bottom;
    private volatile String flash = "";
    private volatile long flashUntil;

    PackStoreScreen(Screen parent, PackStore.Kind kind, Supplier<Path> folder, Runnable done) {
        super(Component.literal(kind.title));
        this.parent = parent;
        this.kind = kind;
        this.folder = folder;
        this.done = done;
    }

    @Override
    protected void init() {
        if (!started) {
            started = true;
            search(0);
            needRefresh = true;
        }
    }

    @Override
    public void onClose() {
        try {
            done.run();
        } catch (RuntimeException e) {
            Log.warn("Pack store could not refresh the pack list: " + e);
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ---------------------------------------------------------------- work on other threads

    private void search(int offset) {
        int seq = searchSeq.incrementAndGet();
        loading = true;
        error = "";
        String q = query;
        String s = sort;
        List<PackStore.Project> before = offset == 0 ? List.of() : results;
        if (offset == 0) {
            results = List.of();
            total = 0;
            scroll = 0;
        }
        Thread t = new Thread(() -> {
            try {
                PackStore.Results r = PackStore.search(kind, q, s, offset, game);
                if (seq != searchSeq.get()) {
                    return;
                }
                List<PackStore.Project> all = new ArrayList<>(before);
                for (PackStore.Project p : r.projects()) {
                    if (all.stream().noneMatch(x -> x.id().equals(p.id()))) {
                        all.add(p);
                    }
                }
                results = all;
                total = r.total();
            } catch (IOException | RuntimeException e) {
                if (seq == searchSeq.get()) {
                    error = reason(e);
                }
            } finally {
                if (seq == searchSeq.get()) {
                    loading = false;
                }
            }
        }, "pentecost-pack-search");
        t.setDaemon(true);
        t.start();
    }

    private void refreshInstalled() {
        if (checking) {
            return;
        }
        checking = true;
        knownError = "";
        Path dir = folder.get();
        Thread t = new Thread(() -> {
            try {
                if (dir == null) {
                    locals = List.of();
                    known = Map.of();
                    return;
                }
                locals = PackStore.listLocal(dir, false);
                List<PackStore.Local> hashed = PackStore.listLocal(dir, true);
                locals = hashed;
                known = PackStore.identify(hashed.stream().map(PackStore.Local::sha1).filter(Objects::nonNull).toList(), game);
            } catch (IOException | RuntimeException e) {
                Log.warn("Pack store could not check " + dir + ": " + e);
                knownError = reason(e);
            } finally {
                checking = false;
            }
        }, "pentecost-pack-check");
        t.setDaemon(true);
        t.start();
    }

    private void install(PackStore.Project p) {
        String now = status.get(p.id());
        if (now != null && !now.startsWith("Failed")) {
            return;
        }
        status.put(p.id(), "Installing");
        Path dir = folder.get();
        Thread t = new Thread(() -> {
            try {
                PackStore.Pick pick = PackStore.latest(p.id(), game);
                PackStore.install(pick, dir);
                status.put(p.id(), "Installed");
                say("Installed " + p.title());
                Log.info("Installed " + kind.title.toLowerCase(Locale.ROOT) + " " + pick.filename() + " from Modrinth");
                needRefresh = true;
            } catch (IOException | RuntimeException e) {
                status.put(p.id(), "Failed: " + reason(e));
                say("Could not install " + p.title() + ". " + reason(e));
                Log.warn("Pack install failed for " + p.id() + ": " + e);
            }
        }, "pentecost-pack-install");
        t.setDaemon(true);
        t.start();
    }

    private void update(PackStore.Local l, PackStore.Known k) {
        if (k.latest() == null || !busy.add(l.name())) {
            return;
        }
        Path dir = folder.get();
        Thread t = new Thread(() -> {
            try {
                Path made = PackStore.install(k.latest(), dir);
                String newName = made.getFileName().toString();
                swapSelection(l.name(), newName);
                if (!newName.equals(l.name())) {
                    // The old file may still be open for a moment while the game reloads.
                    Thread.sleep(1500);
                    deleteWithRetry(dir, l.name());
                }
                say("Updated " + k.title() + " to " + k.latest().versionNumber());
                Log.info("Updated pack " + l.name() + " to " + newName);
            } catch (IOException | RuntimeException e) {
                say("Could not update " + k.title() + ". " + reason(e));
                Log.warn("Pack update failed for " + l.name() + ": " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                busy.remove(l.name());
                needRefresh = true;
            }
        }, "pentecost-pack-update");
        t.setDaemon(true);
        t.start();
    }

    private void delete(PackStore.Local l) {
        if (!busy.add(l.name())) {
            return;
        }
        Path dir = folder.get();
        Thread t = new Thread(() -> {
            try {
                dropSelection(l.name());
                Thread.sleep(800);
                deleteWithRetry(dir, l.name());
                say("Deleted " + shown(l.name()));
            } catch (IOException | RuntimeException e) {
                say("Could not delete " + shown(l.name()) + ". " + reason(e));
                Log.warn("Pack delete failed for " + l.name() + ": " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                busy.remove(l.name());
                needRefresh = true;
            }
        }, "pentecost-pack-delete");
        t.setDaemon(true);
        t.start();
    }

    private static void deleteWithRetry(Path dir, String name) throws IOException, InterruptedException {
        IOException last = null;
        for (int i = 0; i < 5; i++) {
            try {
                PackStore.delete(dir, name);
                return;
            } catch (IOException e) {
                last = e;
                Thread.sleep(800);
            }
        }
        throw new IOException("The file is still in use. Close this screen and try again. " + (last == null ? "" : last.getMessage()));
    }

    /** Puts the new file where the old one was in the packs in use, on the game thread. */
    private void swapSelection(String oldName, String newName) throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        minecraft.execute(() -> {
            try {
                if (kind == PackStore.Kind.RESOURCE) {
                    var repo = minecraft.getResourcePackRepository();
                    repo.reload();
                    List<String> selected = new ArrayList<>(repo.getSelectedIds());
                    int at = selected.indexOf("file/" + oldName);
                    if (at >= 0) {
                        selected.set(at, "file/" + newName);
                        repo.setSelected(selected);
                        minecraft.options.updateResourcePacks(repo);
                    }
                } else if (oldName.equals(ExternalOptions.currentPack())) {
                    ExternalOptions.usePack(newName);
                }
            } finally {
                finished.countDown();
            }
        });
        finished.await(10, TimeUnit.SECONDS);
    }

    /** Takes a pack out of use before its file is deleted, on the game thread. */
    private void dropSelection(String name) throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        minecraft.execute(() -> {
            try {
                if (kind == PackStore.Kind.RESOURCE) {
                    var repo = minecraft.getResourcePackRepository();
                    List<String> selected = new ArrayList<>(repo.getSelectedIds());
                    if (selected.remove("file/" + name)) {
                        repo.setSelected(selected);
                        minecraft.options.updateResourcePacks(repo);
                    }
                } else if (name.equals(ExternalOptions.currentPack())) {
                    ExternalOptions.setShaders(false);
                }
            } finally {
                finished.countDown();
            }
        });
        finished.await(10, TimeUnit.SECONDS);
    }

    private void say(String text) {
        flash = text;
        flashUntil = System.currentTimeMillis() + 6000;
    }

    private static String reason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 140 ? m.substring(0, 140) : m;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, minecraft.level == null ? 0xFF0A0A0B : 0xD80A0A0B);
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        if (searchDue && System.currentTimeMillis() >= searchAt) {
            searchDue = false;
            search(0);
        }
        if (needRefresh && !checking) {
            needRefresh = false;
            refreshInstalled();
        }

        top = 136;
        bottom = vh - 44;
        // The list is cut off at its own edges, so rows never slide under the header or the footer. No cover bars.
        g.enableScissor(0, Math.round(top * ui), width, Math.round(bottom * ui));
        if (tab == Tab.BROWSE) {
            browse(g, vw, mx, my);
        } else {
            installed(g, vw, mx, my);
        }
        g.disableScissor();
        Ui.logo(g, MARGIN, 20, 2, Ui.TEXT);
        Ui.wordmark(g, kind == PackStore.Kind.SHADER ? "SHADERS" : "PACKS", MARGIN + (Ui.LOGO_W + 5) * 2, 22, 2, Ui.TEXT);
        g.drawString(font, "From Modrinth, for Minecraft " + game, MARGIN, 56, Ui.FAINT, false);
        g.fill(MARGIN, 68, vw - MARGIN, 69, Ui.LINE);
        controls(g, vw, mx, my);
        footer(g, vw, vh, mx, my);
        g.pose().popPose();
    }

    private void controls(GuiGraphics g, int vw, int mx, int my) {
        int x = MARGIN;
        int y = 78;
        x += button(g, "Browse", x, y, mx, my, tab == Tab.BROWSE, true, () -> setTab(Tab.BROWSE)) + 6;
        x += button(g, "Installed", x, y, mx, my, tab == Tab.INSTALLED, true, () -> setTab(Tab.INSTALLED)) + 20;
        if (tab == Tab.BROWSE) {
            boxX = x;
            boxY = y;
            boxW = Math.max(120, Math.min(260, vw - MARGIN - x));
            Ui.outline(g, boxX, boxY, boxX + boxW, boxY + 20, focus ? Ui.TEXT : Ui.MUTED);
            boolean caret = focus && (System.currentTimeMillis() / 500) % 2 == 0;
            String shownQuery = query.isEmpty() && !focus ? "Search" : query;
            int color = query.isEmpty() && !focus ? Ui.FAINT : Ui.TEXT;
            // Long text shows its end, where the player is typing.
            String fit = font.plainSubstrByWidth(shownQuery, boxW - 24, true);
            g.drawString(font, fit + (caret ? "_" : ""), boxX + 7, boxY + 6, color, false);
        }
        int y2 = 106;
        int x2 = MARGIN;
        if (tab == Tab.BROWSE) {
            g.drawString(font, "SORT", x2, y2 + 6, Ui.FAINT, false);
            x2 += font.width("SORT") + 12;
            for (String[] s : SORTS) {
                x2 += button(g, s[0], x2, y2, mx, my, sort.equals(s[1]), true, () -> {
                    sort = s[1];
                    searchDue = false;
                    search(0);
                }) + 6;
            }
        } else {
            x2 += button(g, "Check again", x2, y2, mx, my, false, !checking, () -> needRefresh = true) + 6;
            x2 += button(g, "Open folder", x2, y2, mx, my, false, folder.get() != null, () -> Util.getPlatform().openFile(folder.get().toFile())) + 6;
        }
    }

    private void footer(GuiGraphics g, int vw, int vh, int mx, int my) {
        int bw = font.width("Back") + 24;
        int bx = vw - MARGIN - bw;
        int by = vh - 32;
        boolean hover = Ui.over(mx, my, bx, by, bw, 18);
        if (hover) {
            g.fill(bx, by, bx + bw, by + 18, Ui.TEXT);
        } else {
            Ui.outline(g, bx, by, bx + bw, by + 18, Ui.MUTED);
        }
        g.drawString(font, "Back", bx + 12, by + 5, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(bx, by, bw, 18, this::onClose));

        String text;
        int color = Ui.FAINT;
        if (System.currentTimeMillis() < flashUntil) {
            text = flash;
            color = Ui.SOFT;
        } else if (tab == Tab.BROWSE && !error.isEmpty()) {
            text = error;
            color = BAD;
        } else if (tab == Tab.BROWSE) {
            text = loading ? "Loading..." : total == 0 ? "" : "Showing " + results.size() + " of " + total;
        } else if (!knownError.isEmpty()) {
            text = "Could not ask Modrinth about your packs. " + knownError;
            color = BAD;
        } else {
            text = checking ? "Checking with Modrinth..." : "";
        }
        g.drawString(font, font.plainSubstrByWidth(text, bx - MARGIN - 16), MARGIN, vh - 27, color, false);
    }

    private void browse(GuiGraphics g, int vw, int mx, int my) {
        int w = vw - MARGIN * 2;
        int y = top + 4 - scroll;
        List<PackStore.Project> list = results;
        if (list.isEmpty()) {
            String msg = loading ? "Loading..." : !error.isEmpty() ? "Could not reach Modrinth." : "Nothing found.";
            g.drawString(font, msg, MARGIN, top + 8, Ui.MUTED, false);
            maxScroll = 0;
            return;
        }
        for (PackStore.Project p : list) {
            projectRow(g, p, MARGIN, y, w, mx, my);
            y += ROW;
        }
        if (list.size() < total) {
            if (y >= top && y + 20 <= bottom) {
                button(g, loading ? "Loading..." : "Load more", MARGIN, y + 6, mx, my, false, !loading, () -> search(results.size()));
            }
            y += 34;
        }
        limitScroll(y);
    }

    private void projectRow(GuiGraphics g, PackStore.Project p, int x, int y, int w, int mx, int my) {
        if (y + ROW < top || y > bottom) {
            return;
        }
        g.fill(x, y + ROW - 3, x + w, y + ROW - 2, Ui.LINE);
        if (p.icon().isEmpty() || !Icons.draw(g, "m:" + p.id(), () -> PackStore.icon(p.icon()), x, y + 6, 44)) {
            tile(g, p.title(), x, y + 6);
        }
        int tx = x + 58;
        int buttonColumn = 104;
        int textW = w - 58 - buttonColumn - 12;
        g.drawString(font, font.plainSubstrByWidth(p.title(), textW), tx, y + 6, Ui.TEXT, false);
        g.drawString(font, font.plainSubstrByWidth("by " + p.author() + "    " + compact(p.downloads()) + " downloads", textW), tx, y + 18, Ui.FAINT, false);
        List<String> lines = Ui.wrap(font, p.summary(), textW);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            String line = lines.get(i);
            if (i == 1 && lines.size() > 2) {
                line = font.plainSubstrByWidth(line, textW - font.width("...")) + "...";
            }
            g.drawString(font, line, tx, y + 32 + i * 11, Ui.SOFT, false);
        }
        String st = status.get(p.id());
        int bx = x + w - buttonColumn;
        int by = y + 8;
        if ("Installing".equals(st)) {
            g.drawString(font, "Installing...", bx, by + 6, Ui.MUTED, false);
        } else if ("Installed".equals(st) || haveProject(p.id())) {
            g.drawString(font, "Installed", bx, by + 6, GOOD, false);
        } else if (by >= top && by + 20 <= bottom) {
            button(g, st != null ? "Try again" : "Install", bx, by, mx, my, false, true, () -> install(p));
        }
    }

    private boolean haveProject(String id) {
        for (PackStore.Known k : known.values()) {
            if (k.projectId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void installed(GuiGraphics g, int vw, int mx, int my) {
        int w = vw - MARGIN * 2;
        int y = top + 4 - scroll;
        List<PackStore.Local> list = locals;
        if (list.isEmpty()) {
            g.drawString(font, checking ? "Reading your folder..." : "Nothing here yet. Install a pack from Browse.", MARGIN, top + 8, Ui.MUTED, false);
            maxScroll = 0;
            return;
        }
        Map<String, PackStore.Known> info = known;
        for (PackStore.Local l : list) {
            localRow(g, l, l.sha1() == null ? null : info.get(l.sha1()), MARGIN, y, w, mx, my);
            y += ROW_INSTALLED;
        }
        limitScroll(y);
    }

    private void localRow(GuiGraphics g, PackStore.Local l, PackStore.Known k, int x, int y, int w, int mx, int my) {
        if (y + ROW_INSTALLED < top || y > bottom) {
            return;
        }
        g.fill(x, y + ROW_INSTALLED - 3, x + w, y + ROW_INSTALLED - 2, Ui.LINE);
        String source;
        int sourceColor = Ui.MUTED;
        if (k != null) {
            source = k.title() + " " + k.versionNumber() + (k.outdated() ? "   Update: " + k.latest().versionNumber() : "   Up to date");
            sourceColor = k.outdated() ? GOOD : Ui.MUTED;
        } else if (l.folder()) {
            source = "Folder pack";
        } else {
            source = checking ? "Checking..." : "Not from Modrinth";
        }

        // Buttons from the right edge.
        int bx = x + w;
        int by = y + 8;
        boolean room = by >= top && by + 20 <= bottom;
        if (busy.contains(l.name())) {
            String text = "Working...";
            g.drawString(font, text, bx - font.width(text), by + 6, Ui.MUTED, false);
            bx -= font.width(text) + 20;
        } else if (room) {
            boolean confirming = l.name().equals(sure) && System.currentTimeMillis() < sureUntil;
            int dw = font.width(confirming ? "Click again to delete" : "Delete") + 28;
            bx -= dw;
            button(g, confirming ? "Click again to delete" : "Delete", bx, by, mx, my, confirming, true, () -> {
                if (l.name().equals(sure) && System.currentTimeMillis() < sureUntil) {
                    sure = "";
                    delete(l);
                } else {
                    sure = l.name();
                    sureUntil = System.currentTimeMillis() + 4000;
                }
            });
            bx -= 6;
            if (k != null && k.outdated()) {
                int uw = font.width("Update") + 28;
                bx -= uw;
                button(g, "Update", bx, by, mx, my, false, true, () -> update(l, k));
                bx -= 6;
            }
            if (kind == PackStore.Kind.SHADER && !l.name().equals(ExternalOptions.currentPack())) {
                int uw = font.width("Use") + 28;
                bx -= uw;
                button(g, "Use", bx, by, mx, my, false, true, () -> {
                    ExternalOptions.usePack(l.name());
                    say("Now using " + shown(l.name()));
                });
                bx -= 6;
            } else if (kind == PackStore.Kind.SHADER) {
                String text = "In use";
                bx -= font.width(text) + 8;
                g.drawString(font, text, bx, by + 6, GOOD, false);
                bx -= 12;
            }
        }
        // The pack's own pack.png, or the Modrinth icon once Modrinth told us which project it is.
        Path dir = folder.get();
        String iconUrl = k != null ? k.icon() : "";
        boolean drawn = Icons.draw(g, "l:" + l.name() + ":" + l.size() + ":" + iconUrl.isEmpty(), () -> {
            byte[] own = dir == null ? null : PackStore.localIcon(dir, l.name());
            return own != null ? own : iconUrl.isEmpty() ? null : PackStore.icon(iconUrl);
        }, x, y + 6, 34);
        if (!drawn) {
            tile34(g, shown(l.name()), x, y + 6);
        }
        int tx = x + 44;
        int textW = Math.max(60, bx - tx - 12);
        g.drawString(font, font.plainSubstrByWidth(shown(l.name()), textW), tx, y + 8, Ui.TEXT, false);
        g.drawString(font, font.plainSubstrByWidth(source, textW), tx, y + 21, sourceColor, false);
        if (!l.folder()) {
            g.drawString(font, size(l.size()), tx, y + 33, Ui.FAINT, false);
        }
    }

    private static String shown(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    private void tile34(GuiGraphics g, String title, int x, int y) {
        Ui.outline(g, x, y, x + 34, y + 34, Ui.LINE);
        String letter = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
        g.drawString(font, letter, x + 17 - font.width(letter) / 2, y + 13, Ui.MUTED, false);
    }

    private void tile(GuiGraphics g, String title, int x, int y) {
        Ui.outline(g, x, y, x + 44, y + 44, Ui.MUTED);
        String letter = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
        Ui.text(g, font, letter, x + 17, y + 15, Ui.MUTED, 2f);
    }

    private void limitScroll(int endY) {
        maxScroll = Math.max(0, endY - bottom + scroll + 8);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
    }

    private int button(GuiGraphics g, String label, int x, int y, int mx, int my, boolean active, boolean enabled, Runnable action) {
        int w = font.width(label) + 28;
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 20);
        if (active || hover) {
            g.fill(x, y, x + w, y + 20, enabled ? Ui.TEXT : Ui.FAINT);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, enabled ? 0x66FFFFFF : Ui.LINE);
        }
        g.drawString(font, label, x + 14, y + 6, active || hover ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
        return w;
    }

    private void setTab(Tab t) {
        if (tab != t) {
            tab = t;
            scroll = 0;
            focus = false;
            if (t == Tab.INSTALLED && !checking) {
                needRefresh = true;
            }
        }
    }

    private static String compact(long n) {
        if (n >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        }
        if (n >= 1_000) {
            return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
        }
        return Long.toString(n);
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 24)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float ui = Ui.scale(minecraft);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        if (button == 0) {
            boolean inBox = tab == Tab.BROWSE && Ui.over((int) mx, (int) my, boxX, boxY, boxW, 20);
            focus = inBox;
            if (inBox) {
                return true;
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (focus && SharedConstants.isAllowedChatCharacter(c) && query.length() < 80) {
            query += c;
            searchDue = true;
            searchAt = System.currentTimeMillis() + 500;
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (focus) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                focus = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                focus = false;
                searchDue = false;
                search(0);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!query.isEmpty()) {
                    query = query.substring(0, query.length() - 1);
                    searchDue = true;
                    searchAt = System.currentTimeMillis() + 500;
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V && hasControlDown()) {
                String clip = minecraft.keyboardHandler.getClipboard();
                StringBuilder add = new StringBuilder();
                for (char c : clip.toCharArray()) {
                    if (SharedConstants.isAllowedChatCharacter(c)) {
                        add.append(c);
                    }
                }
                query = (query + add).substring(0, Math.min(80, (query + add).length()));
                searchDue = true;
                searchAt = System.currentTimeMillis() + 500;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
