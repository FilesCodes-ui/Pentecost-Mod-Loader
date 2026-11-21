package khi.pentecost.game;

import khi.pentecost.boot.PentecostMain;
import khi.pentecost.update.ReleaseInfo;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

final class UpdateNotice {
    private static final int W = 252;
    private static final int H = 86;
    private static final long SHOW_MS = 60_000;
    private static final long SLIDE_MS = 380;

    private static ReleaseInfo release;
    private static long startedAt;
    private static boolean closed;
    private static int hx;
    private static int hy;
    private static boolean drawn;

    private UpdateNotice() {}

    static void show(ReleaseInfo r) {
        release = r;
        startedAt = 0;
        closed = false;
    }

    static int roomTaken() {
        return drawn ? H + 8 : 0;
    }

    private static boolean visible() {
        return release != null && !closed;
    }

    static void draw(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
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
        float ui = Ui.scale(mc);
        int vw = (int) (screen.width / ui);
        int vh = (int) (screen.height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);

        float p = Math.min(1f, age / (float) SLIDE_MS);
        float slide = 1f - (1f - p) * (1f - p) * (1f - p);
        int x = vw - 28 - W + Math.round((1f - slide) * (W + 40));
        int y = vh - 28 - H;
        hx = x;
        hy = y;
        drawn = true;

        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        g.fill(x, y, x + W, y + H, 0xF20A0A0B);
        Ui.outline(g, x, y, x + W, y + H, 0x66FFFFFF);
        g.fill(x, y, x + 2, y + H, Ui.TEXT);

        Ui.logo(g, x + 14, y + 13, 1, Ui.TEXT);
        Ui.pixelText(g, "UPDATE READY", x + 40, y + 13, 2, Ui.TEXT);

        String cur = PentecostMain.VERSION;
        String next = release.version();
        int vy = y + 38;
        g.drawString(font, cur, x + 14, vy, Ui.FAINT, false);
        int ax = x + 14 + font.width(cur) + 6;
        g.fill(ax, vy + 4, ax + 11, vy + 5, Ui.MUTED);
        for (int i = 0; i < 3; i++) {
            g.fill(ax + 10 - i, vy + 4 - i, ax + 11 - i, vy + 5 - i, Ui.MUTED);
            g.fill(ax + 10 - i, vy + 4 + i, ax + 11 - i, vy + 5 + i, Ui.MUTED);
        }
        g.drawString(font, next, ax + 17, vy, Ui.TEXT, false);
        g.drawString(font, "Your worlds and settings stay.", x + 14 + 0, vy + 12, Ui.MUTED, false);

        boolean overClose = Ui.over(mx, my, x + W - 24, y + 8, 16, 16);
        float pc = Ui.ease("notice.close", overClose ? 1f : 0f, 18f);
        g.fill(x + W - 24, y + 8, x + W - 8, y + 24, Ui.mix(0x00F2F2F2, 0xFFF2F2F2, pc));
        int cross = Ui.mix(Ui.MUTED, Ui.BLACK, pc);
        for (int i = 0; i < 8; i++) {
            g.fill(x + W - 20 + i, y + 12 + i, x + W - 19 + i, y + 13 + i, cross);
            g.fill(x + W - 13 - i, y + 12 + i, x + W - 12 - i, y + 13 + i, cross);
        }

        int bx = x + 14;
        int by = y + H - 26;
        boolean overUpdate = Ui.over(mx, my, bx, by, 76, 16);
        float pu = Ui.ease("notice.update", overUpdate ? 1f : 0f, 18f);
        g.fill(bx, by, bx + 76, by + 16, Ui.mix(0x00F2F2F2, 0xFFF2F2F2, pu));
        Ui.outline(g, bx, by, bx + 76, by + 16, Ui.mix(0x66FFFFFF, 0xFFF2F2F2, pu));
        String label = "Update now";
        g.drawString(font, label, bx + (76 - font.width(label)) / 2, by + 4, Ui.mix(Ui.TEXT, Ui.BLACK, pu), false);

        float left = Math.max(0f, 1f - age / (float) SHOW_MS);
        g.fill(x + 2, y + H - 2, x + W - 1, y + H - 1, Ui.LINE);
        g.fill(x + 2, y + H - 2, x + 2 + Math.round((W - 3) * left), y + H - 1, 0xB3FFFFFF);

        g.pose().popPose();
    }

    static boolean click(Screen screen, double mouseX, double mouseY) {
        if (!drawn || !visible()) {
            return false;
        }
        float ui = Ui.scale(Minecraft.getInstance());
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        if (!Ui.over(mx, my, hx, hy, W, H)) {
            return false;
        }
        if (Ui.over(mx, my, hx + 14, hy + H - 26, 76, 16)) {
            closed = true;
            if (Updater.available()) {
                Updater.ask(screen);
            }
        } else if (Ui.over(mx, my, hx + W - 24, hy + 8, 16, 16)) {
            closed = true;
        }
        return true;
    }
}
