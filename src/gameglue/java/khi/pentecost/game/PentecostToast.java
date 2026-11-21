package khi.pentecost.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

public final class PentecostToast {
    private PentecostToast() {}

    private static final int BG = 0xEE0A0A0B;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFF8A8A8A;
    private static final int FAINT = 0xFF3A3A3A;
    private static final int DOT = 0xFF1A1A1B;
    private static final int W = 220;
    private static final int PAD = 7;
    private static final long SHOW_MS = 7000;
    private static final long SLIDE_MS = 250;
    private static final int MAX = 4;

    private static final class Card {
        final String title, body;
        final Runnable action;
        long born;
        int count = 1;
        float y = -1;
        int rx, ry, rh;

        Card(String title, String body, Runnable action) {
            this.title = title;
            this.body = body;
            this.action = action;
            this.born = System.currentTimeMillis();
        }
    }

    private static boolean held;

    static boolean press(int button, int action) {
        if (button != 0) {
            return false;
        }
        if (action == 0) {
            boolean was = held;
            held = false;
            return was;
        }
        Minecraft mc = Minecraft.getInstance();
        if (action != 1 || mc.screen == null) {
            return false;
        }
        double gx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double gy = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        Card hit = null;
        synchronized (CARDS) {
            for (Card c : CARDS) {
                if (c.action != null && c.rh > 0 && gx >= c.rx && gx < c.rx + W && gy >= c.ry && gy < c.ry + c.rh) {
                    hit = c;
                }
            }
            if (hit != null) {
                hit.born = System.currentTimeMillis() - SHOW_MS;
            }
        }
        if (hit == null) {
            return false;
        }
        held = true;
        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        hit.action.run();
        return true;
    }

    private static final List<Card> CARDS = new ArrayList<>();

    public static void show(Component title, Component body) {
        show(title == null ? "" : title.getString(), body == null ? "" : body.getString());
    }

    public static void show(String title, String body) {
        show(title, body, null);
    }

    public static void show(String title, String body, Runnable action) {
        synchronized (CARDS) {
            for (Card c : CARDS) {
                if (c.title.equals(title) && c.body.equals(body)) {
                    c.count++;
                    c.born = System.currentTimeMillis();
                    return;
                }
            }
            CARDS.add(new Card(title, body, action));
            while (CARDS.size() > MAX) CARDS.remove(0);
        }
    }

    public static void render(Object graphics) {
        if (!(graphics instanceof GuiGraphics g)) return;
        List<Card> cards;
        long now = System.currentTimeMillis();
        synchronized (CARDS) {
            CARDS.removeIf(c -> now - c.born > SHOW_MS + SLIDE_MS);
            if (CARDS.isEmpty()) return;
            cards = new ArrayList<>(CARDS);
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        g.pose().pushPose();
        g.pose().translate(0, 0, 900);
        int bottom = sh - 6;
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            List<FormattedCharSequence> lines = c.body.isEmpty() ? List.of() : font.split(Component.literal(c.body), W - PAD * 2);
            int h = PAD + 9 + 4 + 9 + (lines.isEmpty() ? 0 : 3 + lines.size() * 10) + PAD - 2;
            float target = bottom - h;
            c.y = c.y < 0 ? target : c.y + (target - c.y) * 0.25f;
            long age = now - c.born;
            float in = Math.min(1f, age / (float) SLIDE_MS);
            float out = age > SHOW_MS ? Math.min(1f, (age - SHOW_MS) / (float) SLIDE_MS) : 0f;
            float slide = ease(in) * (1f - ease(out));
            int x = (int) (sw + (sw - 6 - W - sw) * slide);
            int y = Math.round(c.y);
            c.rx = x;
            c.ry = y;
            c.rh = h;
            card(g, font, c, lines, x, y, h);
            bottom = y - 4;
        }
        g.pose().popPose();
    }

    private static float ease(float t) {
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static void card(GuiGraphics g, Font font, Card c, List<FormattedCharSequence> lines, int x, int y, int h) {
        g.fill(x, y, x + W, y + h, BG);
        for (int dy = 0; dy < h; dy += 3) {
            for (int dx = 0; dx < W; dx += 3) {
                double v = Math.pow(dx / (double) W, 2.2) * 0.35;
                int b = BAYER[(dy / 3) & 3][(dx / 3) & 3];
                if (v * 16 > b) g.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, DOT);
            }
        }
        g.fill(x, y, x + W, y + 1, FAINT);
        g.fill(x, y, x + 2, y + h, TEXT);
        g.drawString(font, c.action != null ? "PENTECOST  /  CLICK TO OPEN" : "PENTECOST", x + PAD + 2, y + PAD, MUTED, false);
        if (c.count > 1) {
            String n = "x" + c.count;
            g.drawString(font, n, x + W - PAD - font.width(n), y + PAD, MUTED, false);
        }
        g.drawString(font, c.title, x + PAD + 2, y + PAD + 13, TEXT, false);
        int ly = y + PAD + 13 + 12;
        for (FormattedCharSequence l : lines) {
            g.drawString(font, l, x + PAD + 2, ly, MUTED, false);
            ly += 10;
        }
        long age = System.currentTimeMillis() - c.born;
        float left = Math.max(0f, 1f - age / (float) SHOW_MS);
        g.fill(x + 2, y + h - 1, x + 2 + (int) ((W - 2) * left), y + h, FAINT);
    }

    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
}
