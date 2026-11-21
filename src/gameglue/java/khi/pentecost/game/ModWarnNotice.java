package khi.pentecost.game;

import khi.pentecost.mods.ModDiscovery;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

final class ModWarnNotice {
    private static final int W = 300;
    private static final long SHOW_MS = 60_000;
    private static final long SLIDE_MS = 380;
    private static final int MAX_LINES = 4;

    private static long startedAt;
    private static boolean closed;
    private static int hx;
    private static int hy;
    private static int hh;
    private static boolean drawn;

    private ModWarnNotice() {}

    private static boolean visible() {
        return !closed && !ModDiscovery.REFUSED.isEmpty();
    }

    static void draw(Screen screen, GuiGraphics g, int mouseX, int mouseY, int lift) {
        drawn = false;
        if (!visible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        long now = Util.getMillis();
        if (startedAt == 0) {
            startedAt = now;
        }
        long age = now - startedAt;
        if (age >= SHOW_MS) {
            closed = true;
            return;
        }
        List<ModDiscovery.Refused> list = ModDiscovery.REFUSED;
        int lines = Math.min(MAX_LINES, list.size()) + (list.size() > MAX_LINES ? 1 : 0);
        int h = 44 + lines * 22 + 6;
        float ui = Ui.scale(mc);
        int vw = (int) (screen.width / ui);
        int vh = (int) (screen.height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        float p = Math.min(1f, age / (float) SLIDE_MS);
        float slide = 1f - (1f - p) * (1f - p) * (1f - p);
        int x = vw - 28 - W + Math.round((1f - slide) * (W + 40));
        int y = vh - 28 - h - lift;
        hx = x;
        hy = y;
        hh = h;
        drawn = true;

        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        g.fill(x, y, x + W, y + h, 0xF20A0A0B);
        Ui.outline(g, x, y, x + W, y + h, 0x66FFFFFF);
        g.fill(x, y, x + 2, y + h, Ui.TEXT);
        Ui.logo(g, x + 14, y + 13, 1, Ui.TEXT);
        Ui.pixelText(g, "MODS LEFT OUT", x + 40, y + 13, 2, Ui.TEXT);
        int ly = y + 38;
        for (int i = 0; i < Math.min(MAX_LINES, list.size()); i++) {
            ModDiscovery.Refused r = list.get(i);
            g.drawString(font, font.plainSubstrByWidth(r.name() + " cannot run with Forge mods", W - 28), x + 14, ly, Ui.TEXT, false);
            g.drawString(font, font.plainSubstrByWidth(cap(r.reason()) + ".", W - 28), x + 14, ly + 10, Ui.MUTED, false);
            ly += 22;
        }
        if (list.size() > MAX_LINES) {
            g.drawString(font, "and " + (list.size() - MAX_LINES) + " more, listed in pentecost.log", x + 14, ly, Ui.MUTED, false);
        }

        boolean overClose = Ui.over(mx, my, x + W - 24, y + 8, 16, 16);
        float pc = Ui.ease("modwarn.close", overClose ? 1f : 0f, 18f);
        g.fill(x + W - 24, y + 8, x + W - 8, y + 24, Ui.mix(0x00F2F2F2, 0xFFF2F2F2, pc));
        int cross = Ui.mix(Ui.MUTED, Ui.BLACK, pc);
        for (int i = 0; i < 8; i++) {
            g.fill(x + W - 20 + i, y + 12 + i, x + W - 19 + i, y + 13 + i, cross);
            g.fill(x + W - 13 - i, y + 12 + i, x + W - 12 - i, y + 13 + i, cross);
        }
        float left = Math.max(0f, 1f - age / (float) SHOW_MS);
        g.fill(x + 2, y + h - 2, x + W - 1, y + h - 1, Ui.LINE);
        g.fill(x + 2, y + h - 2, x + 2 + Math.round((W - 3) * left), y + h - 1, 0xB3FFFFFF);
        g.pose().popPose();
    }

    static boolean click(Screen screen, double mouseX, double mouseY) {
        if (!drawn || !visible()) {
            return false;
        }
        float ui = Ui.scale(Minecraft.getInstance());
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        if (!Ui.over(mx, my, hx, hy, W, hh)) {
            return false;
        }
        if (Ui.over(mx, my, hx + W - 24, hy + 8, 16, 16)) {
            closed = true;
        }
        return true;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
