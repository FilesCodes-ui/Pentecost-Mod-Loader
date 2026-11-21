package khi.pentecost.game;

import khi.pentecost.boot.PentecostMain;
import khi.pentecost.update.ReleaseInfo;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UpdatePromptScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static final int NOTE_LINES = 9;
    private static final int LINE_H = 11;

    private final Screen back;
    private final ReleaseInfo release;
    private final Runnable yes;
    private final List<Hit> hits = new ArrayList<>();
    private final long opened = Util.getMillis();
    private final List<String> rawNotes;
    private List<String> wrapped = List.of();
    private int wrappedFor = -1;
    private int scroll;
    private int visible = NOTE_LINES;
    private int boxX;
    private int boxY;
    private int boxW;
    private int boxH;

    UpdatePromptScreen(Screen back, ReleaseInfo release, Runnable yes) {
        super(Component.literal("Update to Pentecost " + release.version()));
        this.back = back;
        this.release = release;
        this.yes = yes;
        this.rawNotes = clean(release.notes());
    }

    static List<String> clean(String md) {
        List<String> out = new ArrayList<>();
        if (md == null) {
            return out;
        }
        boolean blank = true;
        for (String raw : md.replace("\r", "").split("\n")) {
            String s = raw.trim();
            if (s.startsWith("<!--") || s.matches("^[-=_*]{3,}$")) {
                continue;
            }
            s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").replaceAll("^#{1,6}\\s*", "");
            s = s.replace("**", "").replace("__", "").replace("`", "");
            if (s.startsWith("* ") || s.startsWith("- ")) {
                s = "- " + s.substring(2).trim();
            }
            if (s.isEmpty()) {
                if (!blank) {
                    out.add("");
                }
                blank = true;
                continue;
            }
            blank = false;
            out.add(s);
            if (out.size() >= 80) {
                break;
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, boolean primary, Runnable action) {
        boolean hover = Ui.over(mx, my, x, y, w, 20);
        float p = Ui.ease("upd.prompt." + label, hover ? 1f : 0f, 18f);
        int fill = Ui.mix(0x00F2F2F2, 0xFFF2F2F2, p);
        int edge = Ui.mix(primary ? 0xB3FFFFFF : 0x66FFFFFF, 0xFFF2F2F2, p);
        int ink = Ui.mix(Ui.TEXT, Ui.BLACK, p);
        g.fill(x, y, x + w, y + 20, fill);
        Ui.outline(g, x, y, x + w, y + 20, edge);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, ink, false);
        hits.add(new Hit(x, y, w, 20, action));
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Ui.back(minecraft));
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();

        float t = Math.min(1f, (Util.getMillis() - opened) / 360f);
        float e = 1f - (1f - t) * (1f - t) * (1f - t);

        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        g.pose().translate(0, (1f - e) * 12f, 0);

        int cx = vw / 2;
        int colW = Math.min(320, vw - 40);
        int x0 = cx - colW / 2;
        int px = 2;
        String word = "UPDATE";
        int total = (Ui.LOGO_W + 5) * px + Ui.wordmarkWidth(word, px);
        int hx = cx - total / 2;
        int top = Math.max(16, vh * 11 / 100);
        Ui.logo(g, hx, top - px, px, Ui.TEXT);
        Ui.wordmark(g, word, hx + (Ui.LOGO_W + 5) * px, top, px, Ui.TEXT);

        int y = top + 14 * px + 20;

        String cur = PentecostMain.VERSION;
        String next = release.version();
        float s = 1.5f;
        int wCur = (int) (font.width(cur) * s);
        int wNext = (int) (font.width(next) * s);
        int arrow = 20;
        int gap = 9;
        int sx = cx - (wCur + gap + arrow + gap + wNext) / 2;
        Ui.text(g, font, cur, sx, y, Ui.FAINT, s);
        int ax = sx + wCur + gap;
        int ay = y + 5;
        g.fill(ax, ay, ax + arrow, ay + 1, Ui.MUTED);
        for (int i = 0; i < 4; i++) {
            g.fill(ax + arrow - 1 - i, ay - i, ax + arrow - i, ay - i + 1, Ui.MUTED);
            g.fill(ax + arrow - 1 - i, ay + i, ax + arrow - i, ay + i + 1, Ui.MUTED);
        }
        Ui.text(g, font, next, ax + arrow + gap, y, Ui.TEXT, s);
        y += 22;

        String meta = release.size() > 0
                ? String.format(Locale.ROOT, "Installer %.1f MB   checked against its SHA-256 before it runs", release.size() / 1048576.0)
                : "Checked against its SHA-256 before it runs";
        g.drawString(font, meta, cx - font.width(meta) / 2, y, Ui.MUTED, false);
        y += 20;

        g.fill(x0, y, x0 + colW, y + 1, Ui.LINE);
        y += 9;
        g.drawString(font, "WHAT'S NEW", x0, y, Ui.FAINT, false);
        y += 13;

        if (wrappedFor != colW) {
            List<String> w = new ArrayList<>();
            for (String line : rawNotes.isEmpty() ? List.of("No release notes for this version.") : rawNotes) {
                if (line.isEmpty()) {
                    w.add("");
                } else {
                    w.addAll(Ui.wrap(font, line, colW - 20));
                }
            }
            wrapped = w;
            wrappedFor = colW;
        }
        int reserve = 20 + 14 + 26 + 28;
        visible = Math.max(3, Math.min(NOTE_LINES, (vh - y - reserve - 12) / LINE_H));
        visible = Math.min(visible, Math.max(1, wrapped.size()));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, wrapped.size() - visible)));
        boxX = x0;
        boxY = y;
        boxW = colW;
        boxH = visible * LINE_H + 12;
        Ui.outline(g, boxX, boxY, boxX + boxW, boxY + boxH, Ui.LINE);
        for (int i = 0; i < visible; i++) {
            String line = wrapped.get(scroll + i);
            boolean item = line.startsWith("- ");
            g.drawString(font, line, boxX + 10, boxY + 6 + i * LINE_H, item ? Ui.SOFT : Ui.TEXT, false);
        }
        if (wrapped.size() > visible) {
            int trackH = boxH - 8;
            int thumb = Math.max(10, trackH * visible / wrapped.size());
            int ty = boxY + 4 + (trackH - thumb) * scroll / Math.max(1, wrapped.size() - visible);
            g.fill(boxX + boxW - 4, ty, boxX + boxW - 3, ty + thumb, Ui.MUTED);
        }
        y += boxH + 14;

        int gapBtn = 8;
        int bw = (colW - gapBtn) / 2;
        button(g, "Update now", x0, y, bw, mx, my, true, yes);
        button(g, "Not now", x0 + bw + gapBtn, y, bw, mx, my, false, this::onClose);
        y += 30;

        for (String line : Ui.wrap(font, "Pentecost closes while the installer replaces its files. Your worlds, mods and settings stay.", colW)) {
            g.drawString(font, line, cx - font.width(line) / 2, y, Ui.FAINT, false);
            y += LINE_H;
        }
        g.pose().popPose();

        if (e < 1f) {
            g.fill(0, 0, width, height, ((int) ((1f - e) * 255) << 24) | 0x0A0A0B);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float ui = Ui.scale(minecraft);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    click();
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        float ui = Ui.scale(minecraft);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        if (wrapped.size() > visible && Ui.over((int) mx, (int) my, boxX, boxY, boxW, boxH)) {
            scroll = Math.max(0, Math.min(wrapped.size() - visible, scroll - (int) Math.signum(delta) * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 || key == 335) {
            click();
            yes.run();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(back);
    }
}
