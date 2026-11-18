package khi.pentecost.game;

import com.mojang.blaze3d.systems.RenderSystem;
import khi.pentecost.account.AccountInfo;
import khi.pentecost.account.SkinApi;
import khi.pentecost.boot.Log;
import khi.pentecost.wardrobe.SkinImage;
import khi.pentecost.wardrobe.WardrobeStore;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The skin wardrobe, in Pentecost's look. Saved skins with folders and tags, a turning 3D preview, capes, outfits
 * (a skin and a cape together), rules that wear an outfit on a server or in a world, and the history of skins worn before.
 * The data is {@link WardrobeStore}. Changes to the real account go through {@link Wardrobe}, which needs the Microsoft login.
 * Saving and previewing work without it.
 */
final class WardrobeScreen extends Screen {
    private enum Tab {
        SKINS("Skins"), OUTFITS("Outfits"), AUTO("Auto"), HISTORY("History"), MORE("More");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private enum Focus { NONE, SEARCH, INPUT }

    private interface Op {
        void run() throws IOException;
    }

    /** list: the area scrolls, so a click above or below the visible part must not reach it. */
    private record Hit(int x, int y, int w, int h, boolean list, Runnable action) {}

    private static final byte[] NONE = new byte[0];
    private static final int MARGIN = 48;
    private static final int PANEL_W = 176;
    private static final int CARD_W = 100;
    private static final int CARD_H = 90;
    private static final int GAP = 8;
    private static final int GOOD = 0xFF8FD18F;
    private static final int BAD = 0xFFE08080;

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();

    private Tab tab = Tab.SKINS;
    private Focus focus = Focus.NONE;
    private String query = "";
    private String folder = "";
    private boolean playersMode;
    private boolean picked;

    private String selId = "";
    private WardrobeStore.Skin selSkin;
    private byte[] selBytes;
    private SkinImage.Report selReport;
    private final Map<String, byte[]> thumbs = new HashMap<>();

    private float yaw = 20;
    private boolean dragging;
    private long lastNano;
    private boolean hat = true;
    private boolean jacket = true;
    private boolean sleeves = true;
    private boolean pants = true;
    private int pvX;
    private int pvY;
    private int pvW;
    private int pvH;
    private String capeChoice;
    private final Map<String, byte[]> capeBytes = new ConcurrentHashMap<>();
    private final Set<String> capeLoading = ConcurrentHashMap.newKeySet();

    private String inputLabel = "";
    private String inputText = "";
    private Consumer<String> inputDone;
    private String ruleOutfit = "";
    private String sure = "";
    private long sureUntil;

    private int rightX;
    private int rightW;
    private int top;
    private int bottom;
    private int scroll;
    private int maxScroll;
    private int boxX;
    private int boxY;
    private int boxW;
    private int fx;
    private int fy;
    private int fLeft;
    private int fRight;
    private String note = "";
    private long noteUntil;

    WardrobeScreen(Screen parent) {
        super(Component.literal("Wardrobe"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void say(String text) {
        note = text;
        noteUntil = System.currentTimeMillis() + 6000;
    }

    private void attempt(Op op) {
        try {
            op.run();
        } catch (IOException | RuntimeException e) {
            say(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ---------------------------------------------------------------- selection and data

    private void select(WardrobeStore st, String id) {
        WardrobeStore.Skin s = st.skin(id);
        selSkin = s;
        selId = s == null ? "" : id;
        selBytes = null;
        selReport = null;
        if (s == null) {
            return;
        }
        try {
            selBytes = st.bytes(s);
            thumbs.put(s.id, selBytes);
            selReport = SkinImage.check(selBytes);
        } catch (IOException | RuntimeException e) {
            Log.warn("Wardrobe could not read " + s.name + ": " + e);
            if (selBytes == null) {
                thumbs.put(s.id, NONE);
            }
        }
    }

    private void follow(WardrobeStore st) {
        String added = Wardrobe.takeAdded();
        if (!added.isEmpty()) {
            thumbs.remove(added);
            select(st, added);
            picked = true;
        }
        if (selSkin != null && st.skin(selId) != selSkin) {
            selSkin = null;
            selId = "";
            selBytes = null;
            selReport = null;
        }
        if (!picked) {
            picked = true;
            List<WardrobeStore.Skin> all = st.find("", "");
            if (!all.isEmpty()) {
                select(st, all.get(0).id);
            }
        }
    }

    private byte[] bytesOf(WardrobeStore st, WardrobeStore.Skin s) {
        byte[] b = thumbs.get(s.id);
        if (b == null) {
            try {
                b = st.bytes(s);
            } catch (IOException e) {
                b = NONE;
            }
            thumbs.put(s.id, b);
        }
        return b;
    }

    private byte[] pastBytes(WardrobeStore st, WardrobeStore.Past p) {
        byte[] b = thumbs.get("h" + p.sha1);
        if (b == null) {
            try {
                b = st.historyBytes(p);
            } catch (IOException e) {
                b = NONE;
            }
            thumbs.put("h" + p.sha1, b);
        }
        return b;
    }

    private List<AccountInfo.Cape> capes() {
        AccountInfo info = Account.info();
        return info == null ? List.of() : List.copyOf(info.capes);
    }

    private String activeCape() {
        AccountInfo info = Account.info();
        AccountInfo.Cape c = info == null ? null : info.activeCape();
        return c == null ? "" : c.id();
    }

    private String effectiveCape() {
        return capeChoice != null ? capeChoice : activeCape();
    }

    private String capeName(String id) {
        if (id == null || id.isEmpty()) {
            return "None";
        }
        for (AccountInfo.Cape c : capes()) {
            if (c.id().equals(id)) {
                return c.alias();
            }
        }
        return "Cape";
    }

    private ResourceLocation capeTexture(String id) {
        if (id.isEmpty()) {
            return null;
        }
        byte[] b = capeBytes.get(id);
        if (b == null) {
            AccountInfo.Cape found = null;
            for (AccountInfo.Cape c : capes()) {
                if (c.id().equals(id)) {
                    found = c;
                }
            }
            if (found != null && capeLoading.add(id)) {
                String url = found.url();
                Thread t = new Thread(() -> {
                    try {
                        capeBytes.put(id, SkinApi.download(url));
                    } catch (IOException | RuntimeException e) {
                        Log.warn("Wardrobe could not load a cape: " + e);
                        capeBytes.put(id, NONE);
                    }
                }, "pentecost-wardrobe-cape");
                t.setDaemon(true);
                t.start();
            }
            return null;
        }
        return b.length == 0 ? null : SkinTextures.cape(b);
    }

    private void openInput(String label, String initial, Consumer<String> done) {
        inputLabel = label;
        inputText = initial == null ? "" : initial;
        inputDone = done;
        focus = Focus.INPUT;
    }

    private void closeInput() {
        inputDone = null;
        inputText = "";
        if (focus == Focus.INPUT) {
            focus = Focus.NONE;
        }
    }

    private void setTab(Tab t) {
        if (tab != t) {
            tab = t;
            scroll = 0;
            closeInput();
            if (focus == Focus.SEARCH) {
                focus = Focus.NONE;
            }
        }
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

        WardrobeStore st = Wardrobe.store();
        rightX = MARGIN + PANEL_W + 16;
        rightW = vw - MARGIN - rightX;
        bottom = vh - 44;
        top = tab == Tab.SKINS && !playersMode ? 164 : 136;
        if (st != null) {
            follow(st);
        }

        g.enableScissor(Math.round((rightX - 8) * ui), Math.round(top * ui), width, Math.round(bottom * ui));
        if (st == null) {
            g.drawString(font, "The wardrobe folder cannot be used. See the log.", rightX, top + 8, BAD, false);
            maxScroll = 0;
        } else {
            switch (tab) {
                case SKINS -> skinsTab(g, st, mx, my);
                case OUTFITS -> outfitsTab(g, st, mx, my);
                case AUTO -> autoTab(g, st, mx, my);
                case HISTORY -> historyTab(g, st, mx, my);
                case MORE -> moreTab(g, mx, my);
            }
        }

        g.disableScissor();
        Ui.logo(g, MARGIN, 20, 2, Ui.TEXT);
        Ui.wordmark(g, "WARDROBE", MARGIN + (Ui.LOGO_W + 5) * 2, 22, 2, Ui.TEXT);
        g.drawString(font, "Your skins, outfits and rules", MARGIN, 56, Ui.FAINT, false);
        g.fill(MARGIN, 68, vw - MARGIN, 69, Ui.LINE);
        int tx = MARGIN;
        for (Tab t : Tab.values()) {
            tx += button(g, t.label, tx, 78, mx, my, tab == t, true, () -> setTab(t)) + 6;
        }
        if (st != null) {
            controls(g, st, vw, mx, my);
            panel(g, st, mx, my);
        }
        footer(g, vw, vh, mx, my);
        if (st != null) {
            preview(g);
        }
        g.pose().popPose();
    }

    private void controls(GuiGraphics g, WardrobeStore st, int vw, int mx, int my) {
        int y = 106;
        if (inputDone != null) {
            int x = rightX;
            g.drawString(font, inputLabel, x, y + 6, Ui.SOFT, false);
            x += font.width(inputLabel) + 10;
            int cancelW = font.width("Cancel") + 24;
            int bw = Math.max(80, vw - MARGIN - cancelW - 8 - x);
            Ui.outline(g, x, y, x + bw, y + 20, Ui.TEXT);
            boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
            g.drawString(font, font.plainSubstrByWidth(inputText, bw - 20, true) + (caret ? "_" : ""), x + 7, y + 6, Ui.TEXT, false);
            chip(g, "Cancel", vw - MARGIN - cancelW, y, mx, my, false, true, this::closeInput);
            return;
        }
        boxW = 0;
        switch (tab) {
            case SKINS -> {
                if (playersMode) {
                    chip(g, "Back to skins", rightX, y, mx, my, false, true, () -> playersMode = false);
                    return;
                }
                boxX = rightX;
                boxY = y;
                boxW = 110;
                Ui.outline(g, boxX, boxY, boxX + boxW, boxY + 20, focus == Focus.SEARCH ? Ui.TEXT : Ui.MUTED);
                boolean caret = focus == Focus.SEARCH && (System.currentTimeMillis() / 500) % 2 == 0;
                String shown = query.isEmpty() && focus != Focus.SEARCH ? "Search" : query;
                g.drawString(font, font.plainSubstrByWidth(shown, boxW - 16, true) + (caret ? "_" : ""), boxX + 7, boxY + 6,
                        query.isEmpty() && focus != Focus.SEARCH ? Ui.FAINT : Ui.TEXT, false);
                int x = boxX + boxW + 8;
                x += chip(g, "Add name", x, y, mx, my, false, true,
                        () -> openInput("Player name", "", Wardrobe::addByName)) + 5;
                x += chip(g, "Add file", x, y, mx, my, false, true,
                        () -> openInput("PNG file path", "", Wardrobe::addFile)) + 5;
                x += chip(g, "My skin", x, y, mx, my, false, true, Wardrobe::addCurrent) + 5;
                chip(g, "Players here", x, y, mx, my, false, true, () -> {
                    playersMode = true;
                    scroll = 0;
                });
                int y3 = 134;
                int x3 = rightX;
                g.drawString(font, "FOLDER", x3, y3 + 6, Ui.FAINT, false);
                x3 += font.width("FOLDER") + 10;
                List<String> all = new ArrayList<>();
                all.add("");
                all.addAll(st.folders());
                int at = Math.max(0, all.indexOf(folder));
                x3 += chip(g, "<", x3, y3, mx, my, false, all.size() > 1, () -> {
                    folder = all.get((at + all.size() - 1) % all.size());
                    scroll = 0;
                }) + 4;
                String fname = folder.isEmpty() ? "All skins" : folder;
                int fw = Math.max(80, font.width(fname) + 16);
                g.drawString(font, fname, x3 + 8, y3 + 6, Ui.TEXT, false);
                x3 += fw + 4;
                x3 += chip(g, ">", x3, y3, mx, my, false, all.size() > 1, () -> {
                    folder = all.get((at + 1) % all.size());
                    scroll = 0;
                }) + 10;
                x3 += chip(g, "New folder", x3, y3, mx, my, false, true, () -> openInput("Folder name", "", t -> {
                    attempt(() -> st.addFolder(t));
                    for (String f : st.folders()) {
                        if (f.equalsIgnoreCase(t.strip())) {
                            folder = f;
                        }
                    }
                })) + 5;
                if (!folder.isEmpty()) {
                    boolean sureNow = sure.equals("folder") && System.currentTimeMillis() < sureUntil;
                    chip(g, sureNow ? "Click again to remove" : "Remove folder", x3, y3, mx, my, sureNow, true, () -> {
                        if (sureNow) {
                            sure = "";
                            attempt(() -> st.removeFolder(folder));
                            folder = "";
                        } else {
                            sure = "folder";
                            sureUntil = System.currentTimeMillis() + 4000;
                        }
                    });
                }
            }
            case HISTORY -> chip(g, "Undo last", rightX, y, mx, my, false,
                    !st.history().isEmpty() && Account.signedIn() && !Wardrobe.busy(), () -> Wardrobe.wearPast(st.history().get(0)));
            case AUTO -> {
                List<WardrobeStore.Outfit> outfits = st.outfits();
                if (st.outfit(ruleOutfit) == null) {
                    ruleOutfit = outfits.isEmpty() ? "" : outfits.get(0).id;
                }
                int x = rightX;
                WardrobeStore.Outfit chosen = st.outfit(ruleOutfit);
                x += chip(g, "Outfit: " + (chosen == null ? "none" : chosen.name), x, y, mx, my, false, outfits.size() > 1, () -> {
                    int i = 0;
                    for (int k = 0; k < outfits.size(); k++) {
                        if (outfits.get(k).id.equals(ruleOutfit)) {
                            i = k;
                        }
                    }
                    ruleOutfit = outfits.get((i + 1) % outfits.size()).id;
                }) + 8;
                x += chip(g, "Server rule", x, y, mx, my, false, chosen != null, () -> openInput("Part of the server address",
                        "", t -> attempt(() -> st.addRule("server", t, ruleOutfit)))) + 5;
                x += chip(g, "World rule", x, y, mx, my, false, chosen != null, () -> openInput("World name",
                        "", t -> attempt(() -> st.addRule("world", t, ruleOutfit)))) + 5;
                String place = Wardrobe.place();
                chip(g, "This place", x, y, mx, my, false, chosen != null && !place.isEmpty(), () -> {
                    int colon = place.indexOf(':');
                    String kind = place.substring(0, colon);
                    String text = place.substring(colon + 1);
                    if (kind.equals("server")) {
                        text = text.split(" ")[0];
                    }
                    String match = text;
                    attempt(() -> st.addRule(kind, match, ruleOutfit));
                });
            }
            default -> {
            }
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
        hits.add(new Hit(bx, by, bw, 18, false, this::onClose));

        String text;
        int color = Ui.SOFT;
        if (System.currentTimeMillis() < noteUntil) {
            text = note;
        } else if (!Wardrobe.message().isEmpty()) {
            text = Wardrobe.message();
        } else if (!Account.signedIn()) {
            text = "Not signed in. You can save and preview skins. Wearing one needs your Microsoft account.";
            color = Ui.FAINT;
        } else if (Wardrobe.busy()) {
            text = "Working...";
        } else if (Wardrobe.waitSeconds() > 0) {
            text = "Signed in as " + Account.name() + ". Next change in " + Wardrobe.waitSeconds() + " s.";
            color = Ui.FAINT;
        } else {
            text = "Signed in as " + Account.name() + ". Drop PNG files on this window to add them.";
            color = Ui.FAINT;
        }
        g.drawString(font, font.plainSubstrByWidth(text, bx - MARGIN - 16), MARGIN, vh - 27, color, false);
    }

    // ---------------------------------------------------------------- left panel

    private void panel(GuiGraphics g, WardrobeStore st, int mx, int my) {
        int x = MARGIN;
        int y = 106;
        int boxH = Math.max(80, bottom - y - 70);
        Ui.outline(g, x, y, x + PANEL_W, y + boxH, Ui.LINE);
        pvX = x;
        pvY = y;
        pvW = PANEL_W;
        pvH = boxH;
        if (selSkin == null) {
            String hint = st.skins().isEmpty() ? "Add a skin to see it here" : "Pick a skin";
            g.drawString(font, hint, x + (PANEL_W - font.width(hint)) / 2, y + boxH / 2 - 4, Ui.FAINT, false);
        }
        int ry = y + boxH + 6;
        int rx = x;
        rx += mini(g, "Hat", rx, ry, mx, my, hat, true, () -> hat = !hat) + 4;
        rx += mini(g, "Jacket", rx, ry, mx, my, jacket, true, () -> jacket = !jacket) + 4;
        rx += mini(g, "Sleeves", rx, ry, mx, my, sleeves, true, () -> sleeves = !sleeves) + 4;
        mini(g, "Pants", rx, ry, mx, my, pants, true, () -> pants = !pants);
        ry += 20;
        WardrobeStore.Skin s = selSkin;
        mini(g, s == null ? "Arms" : s.slim ? "Arms: Slim" : "Arms: Classic", x, ry, mx, my, false, s != null,
                () -> attempt(() -> st.setSlim(s.id, !s.slim)));
        ry += 20;
        List<AccountInfo.Cape> list = capes();
        if (list.isEmpty()) {
            g.drawString(font, Account.signedIn() ? "No capes on this account" : "Sign in to use capes", x, ry + 4, Ui.FAINT, false);
        } else {
            List<String> ids = new ArrayList<>();
            ids.add("");
            list.forEach(c -> ids.add(c.id()));
            int at = Math.max(0, ids.indexOf(effectiveCape()));
            int cx = x;
            cx += mini(g, "<", cx, ry, mx, my, false, true, () -> capeChoice = ids.get((at + ids.size() - 1) % ids.size())) + 4;
            String label = "Cape: " + capeName(effectiveCape());
            g.drawString(font, font.plainSubstrByWidth(label, PANEL_W - 60), cx + 2, ry + 4, Ui.SOFT, false);
            mini(g, ">", x + PANEL_W - 22, ry, mx, my, false, true, () -> capeChoice = ids.get((at + 1) % ids.size()));
        }
    }

    /** The 3D figure. Drawn last because it writes depth, so nothing drawn after it shows through it. */
    private void preview(GuiGraphics g) {
        long now = System.nanoTime();
        float dt = lastNano == 0 ? 0 : Math.min(0.1f, (now - lastNano) / 1e9f);
        lastNano = now;
        if (!dragging) {
            yaw += dt * 28;
        }
        if (selSkin == null || selBytes == null) {
            return;
        }
        ResourceLocation tex = SkinTextures.skin(selBytes);
        if (tex == null) {
            g.drawString(font, "This file cannot be shown", pvX + 12, pvY + pvH / 2, BAD, false);
            return;
        }
        ResourceLocation cape = capeTexture(effectiveCape());
        float h = pvH - 28;
        SkinPreview.draw(g, pvX + pvW / 2f, pvY + pvH - 12, h, yaw, tex, selSkin.slim, cape,
                new SkinPreview.Layers(hat, jacket, sleeves, pants));
    }

    // ---------------------------------------------------------------- the tabs

    private void skinsTab(GuiGraphics g, WardrobeStore st, int mx, int my) {
        int y = top + 4 - scroll;
        if (playersMode) {
            y = players(g, y, mx, my);
        } else {
            if (selSkin != null) {
                y = selected(g, st, y, mx, my);
            }
            y = grid(g, st, y);
        }
        limitScroll(y);
    }

    private int players(GuiGraphics g, int y, int mx, int my) {
        List<Wardrobe.Player> list = Wardrobe.playersHere();
        g.drawString(font, "Players here", rightX, y, Ui.TEXT, false);
        g.drawString(font, "Save the skin of anyone in this world or server.", rightX, y + 12, Ui.FAINT, false);
        y += 34;
        if (list.isEmpty()) {
            g.drawString(font, "Join a world or a server to see players.", rightX, y, Ui.MUTED, false);
            return y + 20;
        }
        for (Wardrobe.Player p : list) {
            g.fill(rightX, y + 25, rightX + rightW, y + 26, Ui.LINE);
            g.drawString(font, font.plainSubstrByWidth(p.name(), rightW - 130), rightX, y + 7, Ui.TEXT, false);
            button(g, "Save skin", rightX + rightW - font.width("Save skin") - 28, y + 1, mx, my, false, true, true, () -> Wardrobe.addPlayer(p));
            y += 30;
        }
        return y;
    }

    private int selected(GuiGraphics g, WardrobeStore st, int y, int mx, int my) {
        WardrobeStore.Skin s = selSkin;
        g.drawString(font, font.plainSubstrByWidth(s.name, rightW - 90), rightX, y, Ui.TEXT, false);
        String arms = s.slim ? "Slim arms" : "Classic arms";
        g.drawString(font, arms, rightX + rightW - font.width(arms), y, Ui.MUTED, false);
        String detail = (s.folder.isEmpty() ? "No folder" : "Folder: " + s.folder)
                + (s.from.isEmpty() ? "" : "    From: " + s.from);
        g.drawString(font, font.plainSubstrByWidth(detail, rightW), rightX, y + 12, Ui.FAINT, false);
        y += 28;
        if (selReport != null) {
            for (String n : selReport.notes()) {
                for (String line : Ui.wrap(font, n, rightW)) {
                    g.drawString(font, line, rightX, y, Ui.MUTED, false);
                    y += 11;
                }
            }
            y += 2;
        }
        flowStart(rightX, y, rightX + rightW);
        boolean can = Account.signedIn() && !Wardrobe.busy();
        flow(g, Wardrobe.busy() ? "Working..." : "Wear", mx, my, false, can, () -> Wardrobe.wear(s, capeChoice));
        flow(g, "Rename", mx, my, false, true, () -> openInput("New name", s.name, t -> attempt(() -> st.rename(s.id, t))));
        flow(g, "Move to folder", mx, my, false, true, () -> openInput("Folder, empty for none", s.folder,
                t -> attempt(() -> st.setFolder(s.id, t))));
        flow(g, "Add tag", mx, my, false, true, () -> openInput("Tag", "", t -> attempt(() -> st.addTag(s.id, t))));
        for (String tag : new ArrayList<>(s.tags)) {
            flow(g, tag + "  x", mx, my, false, true, () -> attempt(() -> st.removeTag(s.id, tag)));
        }
        if (selReport != null && selReport.legacy()) {
            flow(g, "Fix", mx, my, false, true, () -> Wardrobe.fix(s));
        }
        flow(g, "Make outfit", mx, my, false, true, () -> openInput("Outfit name", s.name,
                t -> attempt(() -> {
                    st.addOutfit(t, s.id, effectiveCape());
                    say("Saved the outfit. Find it in Outfits.");
                })));
        boolean sureNow = sure.equals("skin" + s.id) && System.currentTimeMillis() < sureUntil;
        flow(g, sureNow ? "Click again to delete" : "Delete", mx, my, sureNow, true, () -> {
            if (sureNow) {
                sure = "";
                attempt(() -> st.deleteSkin(s.id));
                thumbs.remove(s.id);
            } else {
                sure = "skin" + s.id;
                sureUntil = System.currentTimeMillis() + 4000;
            }
        });
        return flowEnd() + 6;
    }

    private int grid(GuiGraphics g, WardrobeStore st, int y) {
        List<WardrobeStore.Skin> list = st.find(query, folder);
        if (list.isEmpty()) {
            String msg = st.skins().isEmpty()
                    ? "Nothing saved yet. Use Add name, Add file or My skin, or drop a PNG file on this window."
                    : "No skin matches.";
            int ty = y;
            for (String line : Ui.wrap(font, msg, rightW)) {
                g.drawString(font, line, rightX, ty, Ui.MUTED, false);
                ty += 11;
            }
            return ty + 8;
        }
        int cols = Math.max(1, (rightW + GAP) / (CARD_W + GAP));
        int rows = (list.size() + cols - 1) / cols;
        for (int i = 0; i < list.size(); i++) {
            int cx = rightX + (i % cols) * (CARD_W + GAP);
            int cy = y + (i / cols) * (CARD_H + GAP);
            if (cy + CARD_H < top - 4 || cy > bottom + 4) {
                continue;
            }
            WardrobeStore.Skin s = list.get(i);
            boolean on = s.id.equals(selId);
            Ui.outline(g, cx, cy, cx + CARD_W, cy + CARD_H, on ? Ui.TEXT : Ui.LINE);
            face(g, bytesOf(st, s), cx + (CARD_W - 48) / 2, cy + 8, 48);
            String name = font.plainSubstrByWidth(s.name, CARD_W - 12);
            g.drawString(font, name, cx + (CARD_W - font.width(name)) / 2, cy + 64, Ui.TEXT, false);
            String sub = font.plainSubstrByWidth((s.slim ? "Slim" : "Classic") + (s.folder.isEmpty() ? "" : "  " + s.folder), CARD_W - 12);
            g.drawString(font, sub, cx + (CARD_W - font.width(sub)) / 2, cy + 76, Ui.FAINT, false);
            hits.add(new Hit(cx, cy, CARD_W, CARD_H, true, () -> select(st, s.id)));
        }
        return y + rows * (CARD_H + GAP);
    }

    private void outfitsTab(GuiGraphics g, WardrobeStore st, int mx, int my) {
        int y = top + 4 - scroll;
        List<WardrobeStore.Outfit> list = st.outfits();
        if (list.isEmpty()) {
            for (String line : Ui.wrap(font, "No outfits yet. An outfit is a skin and a cape worn together. Pick a skin in Skins, choose a cape, then press Make outfit.", rightW)) {
                g.drawString(font, line, rightX, y, Ui.MUTED, false);
                y += 11;
            }
            limitScroll(y);
            return;
        }
        for (WardrobeStore.Outfit o : list) {
            WardrobeStore.Skin s = st.skin(o.skinId);
            g.fill(rightX, y + 47, rightX + rightW, y + 48, Ui.LINE);
            if (s != null) {
                face(g, bytesOf(st, s), rightX, y + 4, 36);
            }
            int bx = rightX + rightW;
            boolean sureNow = sure.equals("outfit" + o.id) && System.currentTimeMillis() < sureUntil;
            String del = sureNow ? "Click again to delete" : "Delete";
            bx -= font.width(del) + 28;
            button(g, del, bx, y + 10, mx, my, sureNow, true, true, () -> {
                if (sureNow) {
                    sure = "";
                    attempt(() -> st.deleteOutfit(o.id));
                } else {
                    sure = "outfit" + o.id;
                    sureUntil = System.currentTimeMillis() + 4000;
                }
            });
            bx -= 6 + font.width("Wear") + 28;
            button(g, "Wear", bx, y + 10, mx, my, false, Account.signedIn() && !Wardrobe.busy(), true, () -> Wardrobe.wearOutfit(o, false));
            int textW = Math.max(60, bx - rightX - 52);
            g.drawString(font, font.plainSubstrByWidth(o.name, textW), rightX + 46, y + 10, Ui.TEXT, false);
            g.drawString(font, font.plainSubstrByWidth("Skin: " + (s == null ? "missing" : s.name) + "    Cape: " + capeName(o.capeId), textW),
                    rightX + 46, y + 23, Ui.MUTED, false);
            y += 52;
        }
        limitScroll(y);
    }

    private void autoTab(GuiGraphics g, WardrobeStore st, int mx, int my) {
        int y = top + 4 - scroll;
        String help = "A rule wears an outfit when you join a server or open a world. A server rule matches part of the address or the saved name. A world rule matches the exact world name. The longest match wins. Each change waits for the Mojang limit.";
        for (String line : Ui.wrap(font, help, rightW)) {
            g.drawString(font, line, rightX, y, Ui.FAINT, false);
            y += 11;
        }
        y += 8;
        if (st.outfits().isEmpty()) {
            g.drawString(font, "Make an outfit first. Rules wear outfits.", rightX, y, Ui.MUTED, false);
            limitScroll(y + 20);
            return;
        }
        List<WardrobeStore.Rule> rules = st.rules();
        if (rules.isEmpty()) {
            g.drawString(font, "No rules yet.", rightX, y, Ui.MUTED, false);
        }
        for (WardrobeStore.Rule r : rules) {
            WardrobeStore.Outfit o = st.outfit(r.outfitId);
            g.fill(rightX, y + 29, rightX + rightW, y + 30, Ui.LINE);
            boolean sureNow = sure.equals("rule" + r.id) && System.currentTimeMillis() < sureUntil;
            String del = sureNow ? "Click again to delete" : "Delete";
            int bx = rightX + rightW - font.width(del) - 28;
            button(g, del, bx, y + 3, mx, my, sureNow, true, true, () -> {
                if (sureNow) {
                    sure = "";
                    attempt(() -> st.deleteRule(r.id));
                } else {
                    sure = "rule" + r.id;
                    sureUntil = System.currentTimeMillis() + 4000;
                }
            });
            int textW = Math.max(60, bx - rightX - 12);
            g.drawString(font, font.plainSubstrByWidth((r.kind.equals("server") ? "Server contains " : "World named ") + r.match, textW),
                    rightX, y + 3, Ui.TEXT, false);
            g.drawString(font, font.plainSubstrByWidth("Wears " + (o == null ? "missing outfit" : o.name), textW), rightX, y + 16, Ui.MUTED, false);
            y += 34;
        }
        limitScroll(y);
    }

    private void historyTab(GuiGraphics g, WardrobeStore st, int mx, int my) {
        int y = top + 4 - scroll;
        List<WardrobeStore.Past> list = st.history();
        if (list.isEmpty()) {
            g.drawString(font, "Nothing yet. A skin is kept here each time Pentecost changes your skin.", rightX, y, Ui.MUTED, false);
            limitScroll(y + 20);
            return;
        }
        for (WardrobeStore.Past p : list) {
            g.fill(rightX, y + 47, rightX + rightW, y + 48, Ui.LINE);
            face(g, pastBytes(st, p), rightX, y + 4, 36);
            int bx = rightX + rightW - font.width("Wear again") - 28;
            button(g, "Wear again", bx, y + 10, mx, my, false, Account.signedIn() && !Wardrobe.busy(), true, () -> Wardrobe.wearPast(p));
            int textW = Math.max(60, bx - rightX - 52);
            g.drawString(font, font.plainSubstrByWidth(p.name.isEmpty() ? "Earlier skin" : p.name, textW), rightX + 46, y + 10, Ui.TEXT, false);
            g.drawString(font, font.plainSubstrByWidth(ago(p.at) + "    " + (p.slim ? "Slim" : "Classic") + "    Cape: " + capeName(p.capeId), textW),
                    rightX + 46, y + 23, Ui.MUTED, false);
            y += 52;
        }
        limitScroll(y);
    }

    private void moreTab(GuiGraphics g, int mx, int my) {
        int y = top + 4 - scroll;
        flowStart(rightX, y, rightX + rightW);
        boolean can = Account.signedIn() && !Wardrobe.busy();
        flow(g, "Random skin", mx, my, false, can, Wardrobe::wearRandom);
        flow(g, "Skin of the day", mx, my, false, can, Wardrobe::wearOfTheDay);
        flow(g, "Export wardrobe", mx, my, false, true, Wardrobe::exportAll);
        flow(g, "Import wardrobe", mx, my, false, true, () -> openInput("Path of a wardrobe zip", "", Wardrobe::addFile));
        flow(g, "Open wardrobe folder", mx, my, false, Wardrobe.store() != null, () -> Util.getPlatform().openFile(Wardrobe.store().root().toFile()));
        y = flowEnd() + 8;
        String[] help = {
                "Random skin wears a saved skin picked by chance. Skin of the day picks one skin for each calendar day.",
                "Export wardrobe packs every skin, folder, outfit and rule into one zip in the wardrobe folder. Import reads such a zip and adds what is new.",
                "You can also drop PNG files, or a wardrobe zip, on this window."};
        for (String h : help) {
            for (String line : Ui.wrap(font, h, rightW)) {
                g.drawString(font, line, rightX, y, Ui.FAINT, false);
                y += 11;
            }
            y += 6;
        }
        limitScroll(y);
    }

    // ---------------------------------------------------------------- small pieces

    private void face(GuiGraphics g, byte[] png, int x, int y, int size) {
        ResourceLocation tex = png == null || png.length == 0 ? null : SkinTextures.skin(png);
        if (tex == null) {
            Ui.outline(g, x, y, x + size, y + size, Ui.FAINT);
            return;
        }
        RenderSystem.enableBlend();
        g.blit(tex, x, y, size, size, 8f, 8f, 8, 8, 64, 64);
        g.blit(tex, x, y, size, size, 40f, 8f, 8, 8, 64, 64);
    }

    private static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) {
            return "Just now";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        if (s < 48 * 3600) {
            return (s / 3600) + " hours ago";
        }
        return (s / 86400) + " days ago";
    }

    private void limitScroll(int endY) {
        maxScroll = Math.max(0, endY - bottom + scroll + 8);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
    }

    private void flowStart(int x, int y, int right) {
        fx = x;
        fy = y;
        fLeft = x;
        fRight = right;
    }

    private void flow(GuiGraphics g, String label, int mx, int my, boolean active, boolean enabled, Runnable action) {
        int w = font.width(label) + 24;
        if (fx + w > fRight && fx > fLeft) {
            fx = fLeft;
            fy += 26;
        }
        draw(g, label, fx, fy, w, 20, mx, my, active, enabled, true, action);
        fx += w + 6;
    }

    private int flowEnd() {
        return fy + 26;
    }

    private int button(GuiGraphics g, String label, int x, int y, int mx, int my, boolean active, boolean enabled, Runnable action) {
        return button(g, label, x, y, mx, my, active, enabled, false, action);
    }

    private int button(GuiGraphics g, String label, int x, int y, int mx, int my, boolean active, boolean enabled, boolean list, Runnable action) {
        int w = font.width(label) + 28;
        draw(g, label, x, y, w, 20, mx, my, active, enabled, list, action);
        return w;
    }

    private int chip(GuiGraphics g, String label, int x, int y, int mx, int my, boolean active, boolean enabled, Runnable action) {
        int w = font.width(label) + 20;
        draw(g, label, x, y, w, 20, mx, my, active, enabled, false, action);
        return w;
    }

    private int mini(GuiGraphics g, String label, int x, int y, int mx, int my, boolean active, boolean enabled, Runnable action) {
        int w = font.width(label) + 12;
        draw(g, label, x, y, w, 16, mx, my, active, enabled, false, action);
        return w;
    }

    private void draw(GuiGraphics g, String label, int x, int y, int w, int h, int mx, int my, boolean active, boolean enabled,
                      boolean list, Runnable action) {
        boolean hover = enabled && Ui.over(mx, my, x, y, w, h) && (!list || (my >= top && my <= bottom));
        if (active || hover) {
            g.fill(x, y, x + w, y + h, enabled ? Ui.TEXT : Ui.FAINT);
        } else {
            Ui.outline(g, x, y, x + w, y + h, enabled ? 0x66FFFFFF : Ui.LINE);
        }
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, active || hover ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, h, list, action));
        }
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
            if (boxW > 0 && Ui.over((int) mx, (int) my, boxX, boxY, boxW, 20)) {
                focus = Focus.SEARCH;
                return true;
            }
            if (focus == Focus.SEARCH) {
                focus = Focus.NONE;
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    if (h.list() && (my < top || my > bottom)) {
                        continue;
                    }
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    h.action().run();
                    return true;
                }
            }
            if (Ui.over((int) mx, (int) my, pvX, pvY, pvW, pvH)) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging) {
            yaw += (float) (dx / Ui.scale(minecraft)) * 1.4f;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        int n = 0;
        for (Path p : paths) {
            if (n++ >= 20) {
                break;
            }
            Wardrobe.addFile(p);
        }
    }

    private void typed(String add) {
        StringBuilder ok = new StringBuilder();
        for (char c : add.toCharArray()) {
            if (SharedConstants.isAllowedChatCharacter(c)) {
                ok.append(c);
            }
        }
        if (focus == Focus.INPUT) {
            inputText = (inputText + ok).substring(0, Math.min(260, (inputText + ok).length()));
        } else if (focus == Focus.SEARCH) {
            query = (query + ok).substring(0, Math.min(40, (query + ok).length()));
            scroll = 0;
        }
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (focus != Focus.NONE && SharedConstants.isAllowedChatCharacter(c)) {
            typed(String.valueOf(c));
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (focus != Focus.NONE) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (focus == Focus.INPUT) {
                    closeInput();
                } else {
                    focus = Focus.NONE;
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (focus == Focus.INPUT) {
                    Consumer<String> done = inputDone;
                    String text = inputText;
                    closeInput();
                    if (done != null) {
                        done.accept(text);
                    }
                } else {
                    focus = Focus.NONE;
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (focus == Focus.INPUT && !inputText.isEmpty()) {
                    inputText = inputText.substring(0, inputText.length() - 1);
                } else if (focus == Focus.SEARCH && !query.isEmpty()) {
                    query = query.substring(0, query.length() - 1);
                    scroll = 0;
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V && hasControlDown()) {
                typed(minecraft.keyboardHandler.getClipboard());
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
