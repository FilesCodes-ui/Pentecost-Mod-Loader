package khi.pentecost.game;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A plain Pentecost panel: dark backdrop, hairlines, white-on-hover buttons and typed fields, all drawn at the
 * Pentecost interface scale. Fields are typed into by hand (no game widgets), like the WWLS screen.
 * Screens built on it do not pause the game, so a command sent from them runs at once.
 */
abstract class PanelScreen extends Screen {
    /** One line of text the player types into. */
    static final class Field {
        final String label;
        final int max;
        String text = "";

        Field(String label, int max) {
            this.label = label;
            this.max = max;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private record Slide(int x, int y, int w, int min, int max, int step, java.util.function.IntConsumer set, Runnable done) {}

    protected final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Slide> slides = new ArrayList<>();
    private Slide dragging;
    protected final List<Field> fields = new ArrayList<>();
    protected Field focus;
    protected float ui = 1;
    protected int vw;
    protected int vh;
    protected int mx;
    protected int my;

    protected PanelScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** What the screen draws, in Pentecost interface units. */
    protected abstract void draw(GuiGraphics g);

    /** Enter was pressed in a field. */
    protected void onEnter(Field f) {}

    /** A field changed. */
    protected void onChange(Field f) {}

    /** Up or down was pressed while a field has focus. Returns true when handled. */
    protected boolean onArrow(Field f, boolean up) {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, minecraft.level == null ? 0xFF0A0A0B : 0xE00A0A0B);
        Ui.dither(g, width, height);
        ui = Ui.scale(minecraft);
        vw = (int) (width / ui);
        vh = (int) (height / ui);
        mx = (int) (mouseX / ui);
        my = (int) (mouseY / ui);
        hits.clear();
        slides.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        try {
            draw(g);
        } finally {
            g.pose().popPose();
        }
    }

    /** The logo, the wordmark and a hairline, centred. Returns the y to continue from. */
    protected int header(GuiGraphics g, String word, String sub, int x0, int colW) {
        int cx = vw / 2;
        int y = Math.max(14, vh / 14);
        int total = (Ui.LOGO_W + 5) * 2 + Ui.wordmarkWidth(word, 2);
        int hx = cx - total / 2;
        Ui.logo(g, hx, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, word, hx + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        y += 32;
        if (!sub.isEmpty()) {
            centered(g, sub.toUpperCase(java.util.Locale.ROOT), cx, y, Ui.MUTED);
            y += 13;
        }
        g.fill(x0, y, x0 + colW, y + 1, Ui.LINE);
        return y + 10;
    }

    protected void centered(GuiGraphics g, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    protected void button(GuiGraphics g, String label, int x, int y, int w, boolean enabled, Runnable action) {
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, enabled ? 0x66FFFFFF : 0x24FFFFFF);
        }
        int color = !enabled ? Ui.FAINT : hover ? Ui.BLACK : Ui.TEXT;
        String shown = label;
        while (font.width(shown) > w - 8 && shown.length() > 3) {
            shown = shown.substring(0, shown.length() - 2);
        }
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + 6, color, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
    }

    /** A small label on the left and a field under it. */
    protected void field(GuiGraphics g, Field f, int x, int y, int w) {
        g.drawString(font, f.label, x, y, Ui.FAINT, false);
        int fy = y + 11;
        boolean on = focus == f;
        g.fill(x, fy, x + w, fy + 18, 0x40000000);
        Ui.outline(g, x, fy, x + w, fy + 18, on ? 0xCCFFFFFF : 0x55FFFFFF);
        String shown = f.text;
        while (font.width(shown) > w - 12 && !shown.isEmpty()) {
            shown = shown.substring(1);
        }
        boolean blink = on && (System.currentTimeMillis() / 500) % 2 == 0;
        g.drawString(font, shown + (blink ? "_" : ""), x + 5, fy + 5, Ui.TEXT, false);
        hits.add(new Hit(x, fy, w, 18, () -> focus = f));
    }

    /** A small clickable word, for lists of names. Returns its width. */
    protected int chip(GuiGraphics g, String label, int x, int y, Runnable action) {
        int w = font.width(label) + 10;
        boolean hover = Ui.over(mx, my, x, y, w, 14);
        if (hover) {
            g.fill(x, y, x + w, y + 14, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 14, 0x40FFFFFF);
        }
        g.drawString(font, label, x + 5, y + 3, hover ? Ui.BLACK : Ui.SOFT, false);
        hits.add(new Hit(x, y, w, 14, action));
        return w;
    }

    /** Sends a command as the player and notes it in the output. */
    protected void send(String command) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        String c = command.startsWith("/") ? command.substring(1) : command;
        if (c.length() > 256) {
            CommandOutput.offer("That is " + c.length() + " characters. The game sends at most 256 in one command, so split it into smaller groups.");
            return;
        }
        CommandOutput.offer("> /" + c);
        minecraft.player.connection.sendCommand(c);
    }

    /** A row with a label and an on/off pill. */
    protected void toggle(GuiGraphics g, String label, int x, int y, int w, boolean on, Runnable action) {
        boolean hover = Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.HOVER);
        }
        String shown = label;
        while (font.width(shown) > w - 44 && shown.length() > 4) {
            shown = shown.substring(0, shown.length() - 2);
        }
        g.drawString(font, shown, x + 4, y + 6, hover ? Ui.TEXT : Ui.SOFT, false);
        int pw = 30;
        int px = x + w - pw - 4;
        Ui.outline(g, px, y + 4, px + pw, y + 16, on ? 0xCCFFFFFF : 0x55FFFFFF);
        if (on) {
            g.fill(px + pw / 2, y + 5, px + pw - 1, y + 15, Ui.TEXT);
        } else {
            g.fill(px + 1, y + 5, px + pw / 2, y + 15, 0x55FFFFFF);
        }
        hits.add(new Hit(x, y, w, 20, action));
    }

    /** A label, the value, and a bar to drag. Takes 30 units of height. done runs when the pointer is let go. */
    protected void slider(GuiGraphics g, String label, int x, int y, int w, int min, int max, int step, int value,
                          String unit, java.util.function.IntConsumer set, Runnable done) {
        g.drawString(font, label, x + 2, y + 2, Ui.SOFT, false);
        String v = value + unit;
        g.drawString(font, v, x + w - font.width(v) - 2, y + 2, Ui.TEXT, false);
        g.fill(x + 2, y + 19, x + w - 2, y + 20, 0x55FFFFFF);
        float f = (value - min) / (float) Math.max(1, max - min);
        int hx = x + 2 + Math.round(Math.max(0, Math.min(1, f)) * (w - 8));
        g.fill(hx, y + 15, hx + 4, y + 24, Ui.TEXT);
        slides.add(new Slide(x, y + 12, w, min, max, step, set, done));
    }

    private void drag(Slide s, double x) {
        double f = Math.max(0, Math.min(1, (x - (s.x() + 2)) / (double) Math.max(1, s.w() - 8)));
        int raw = s.min() + (int) Math.round(f * (s.max() - s.min()));
        int v = s.min() + Math.round((raw - s.min()) / (float) s.step()) * s.step();
        s.set().accept(Math.max(s.min(), Math.min(s.max(), v)));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging != null) {
            drag(dragging, mouseX / ui);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null) {
            Slide s = dragging;
            dragging = null;
            s.done().run();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Cuts a line into pieces that fit the width. */
    protected List<String> wrap(String text, int w) {
        List<String> out = new ArrayList<>();
        String rest = text;
        while (font.width(rest) > w && rest.length() > 1) {
            int cut = rest.length();
            while (cut > 1 && font.width(rest.substring(0, cut)) > w) {
                cut--;
            }
            int space = rest.lastIndexOf(32, cut);
            if (space > cut / 2) {
                cut = space;
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        out.add(rest);
        return out;
    }

    /** The last lines of chat, wrapped, drawn in a box. */
    protected void log(GuiGraphics g, int x, int y, int w, int lines) {
        int h = lines * 11 + 8;
        g.fill(x, y, x + w, y + h, 0x55000000);
        Ui.outline(g, x, y, x + w, y + h, 0x30FFFFFF);
        List<String> rows = new ArrayList<>();
        for (String line : CommandOutput.last(lines * 2)) {
            rows.addAll(wrap(line, w - 12));
        }
        int from = Math.max(0, rows.size() - lines);
        int ty = y + 5;
        for (int i = from; i < rows.size(); i++) {
            String r = rows.get(i);
            g.drawString(font, r, x + 6, ty, r.startsWith(">") ? Ui.SOFT : Ui.MUTED, false);
            ty += 11;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX / ui;
        double y = mouseY / ui;
        focus = null;
        for (Slide s : slides) {
            if (x >= s.x() && x < s.x() + s.w() && y >= s.y() && y < s.y() + 16) {
                dragging = s;
                drag(s, x);
                return true;
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (x >= h.x() && x < h.x() + h.w() && y >= h.y() && y < h.y() + h.h()) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (focus != null && c >= 32 && c != 127 && focus.text.length() < focus.max) {
            focus.text += c;
            onChange(focus);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (focus != null && key != GLFW.GLFW_KEY_ESCAPE) {
            Field f = focus;
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!f.text.isEmpty()) {
                    f.text = f.text.substring(0, f.text.length() - 1);
                    onChange(f);
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                onEnter(f);
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                if (onArrow(f, key == GLFW.GLFW_KEY_UP)) {
                    return true;
                }
            }
            if (key == GLFW.GLFW_KEY_V && Screen.hasControlDown()) {
                String clip = minecraft.keyboardHandler.getClipboard().replace("\r", " ").replace("\n", " ");
                int room = f.max - f.text.length();
                if (room > 0) {
                    f.text += clip.length() > room ? clip.substring(0, room) : clip;
                    onChange(f);
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB && fields.size() > 1) {
                int at = fields.indexOf(f);
                focus = fields.get((at + 1) % fields.size());
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }
}
