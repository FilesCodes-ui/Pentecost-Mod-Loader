package khi.pentecost.game;

import khi.pentecost.control.Settings;
import khi.pentecost.core.Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;

/**
 * Pentecost's look for the game's standard parts: buttons, sliders, checkboxes, list and screen backgrounds.
 * Every screen built from them changes at once, the game's and mods' alike.
 * Parts that draw their own pictures (icon buttons, mod-made widgets) keep their look.
 */
final class WidgetStyle implements Hooks.Style {
    private static Field sliderValue;
    private static Field checkboxLabel;

    static void install() {
        Hooks.style = new WidgetStyle();
    }

    private static boolean on() {
        return Settings.on(Settings.RESTYLE);
    }

    @Override
    public boolean widget(Object widget, Object graphics, int mouseX, int mouseY, float partialTick) {
        if (!on() || !(graphics instanceof GuiGraphics g) || !(widget instanceof AbstractWidget w)) {
            return false;
        }
        try {
            if (w instanceof net.minecraft.client.gui.components.TabButton t) {
                tab(g, t);
            } else if (w instanceof net.minecraft.client.gui.components.EditBox e) {
                return editBox(g, e, mouseX, mouseY, partialTick);
            } else if (w instanceof AbstractSliderButton s) {
                slider(g, s);
            } else if (w instanceof Checkbox c) {
                checkbox(g, c);
            } else if (w instanceof AbstractButton b) {
                button(g, b);
            } else {
                return false;
            }
            return true;
        } catch (RuntimeException | ReflectiveOperationException e) {
            return false;
        }
    }

    private static void button(GuiGraphics g, AbstractButton b) {
        Font font = Minecraft.getInstance().font;
        int x = b.getX();
        int y = b.getY();
        int w = b.getWidth();
        int h = b.getHeight();
        boolean hover = b.active && b.isHoveredOrFocused();
        int text;
        if (hover) {
            g.fill(x, y, x + w, y + h, Ui.TEXT);
            text = Ui.BLACK;
        } else {
            g.fill(x, y, x + w, y + h, 0x8C0A0A0B);
            Ui.outline(g, x, y, x + w, y + h, b.active ? 0x47FFFFFF : 0x1AFFFFFF);
            text = b.active ? Ui.TEXT : Ui.FAINT;
        }
        // A button with its own label code draws it (mods put icons there, like Create's menu button).
        // Plain buttons get Pentecost's label: no drop shadow, so dark text on the hover bar stays crisp.
        if (!drawsOwnLabel(b) || !ownLabel(b, g, font, text)) {
            label(g, font, b.getMessage().getString(), x, y, w, h, text);
        }
    }

    private static final java.util.Map<Class<?>, Boolean> OWN_LABEL = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when the button's class, or a parent below AbstractButton, has its own label code. */
    private static boolean drawsOwnLabel(AbstractButton b) {
        return OWN_LABEL.computeIfAbsent(b.getClass(), c -> {
            for (Class<?> k = c; k != null && k != AbstractButton.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals("renderString") && m.getParameterCount() == 3) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private static java.lang.reflect.Method renderString;

    private static boolean ownLabel(AbstractButton b, GuiGraphics g, Font font, int color) {
        try {
            if (renderString == null) {
                java.lang.reflect.Method m = AbstractButton.class.getDeclaredMethod("renderString", GuiGraphics.class, Font.class, int.class);
                m.setAccessible(true);
                renderString = m;
            }
            renderString.invoke(b, g, font, color);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void slider(GuiGraphics g, AbstractSliderButton s) throws ReflectiveOperationException {
        if (sliderValue == null) {
            Field f = AbstractSliderButton.class.getDeclaredField("value");
            f.setAccessible(true);
            sliderValue = f;
        }
        double value = Math.max(0, Math.min(1, sliderValue.getDouble(s)));
        Font font = Minecraft.getInstance().font;
        int x = s.getX();
        int y = s.getY();
        int w = s.getWidth();
        int h = s.getHeight();
        boolean hover = s.active && s.isHoveredOrFocused();
        g.fill(x, y, x + w, y + h, 0x8C0A0A0B);
        g.fill(x + 1, y + 1, x + 1 + (int) ((w - 2) * value), y + h - 1, hover ? 0x33FFFFFF : 0x1FFFFFFF);
        Ui.outline(g, x, y, x + w, y + h, hover ? Ui.TEXT : s.active ? 0x47FFFFFF : 0x1AFFFFFF);
        int hx = x + 1 + (int) ((w - 4) * value);
        g.fill(hx, y + 1, hx + 2, y + h - 1, s.active ? Ui.TEXT : Ui.FAINT);
        label(g, font, s.getMessage().getString(), x, y, w, h, s.active ? Ui.TEXT : Ui.FAINT);
    }

    private static void checkbox(GuiGraphics g, Checkbox c) throws ReflectiveOperationException {
        if (checkboxLabel == null) {
            Field f = Checkbox.class.getDeclaredField("showLabel");
            f.setAccessible(true);
            checkboxLabel = f;
        }
        Font font = Minecraft.getInstance().font;
        int x = c.getX();
        int y = c.getY();
        int h = c.getHeight();
        int size = 12;
        int by = y + (h - size) / 2;
        boolean hover = c.active && c.isHoveredOrFocused();
        g.fill(x, by, x + size, by + size, 0x8C0A0A0B);
        Ui.outline(g, x, by, x + size, by + size, hover ? Ui.TEXT : 0x66FFFFFF);
        if (c.selected()) {
            g.fill(x + 3, by + 3, x + size - 3, by + size - 3, Ui.TEXT);
        }
        if (checkboxLabel.getBoolean(c)) {
            g.drawString(font, c.getMessage(), x + size + 6, y + (h - 8) / 2, c.active ? Ui.TEXT : Ui.FAINT, false);
        }
    }

    /** A tab: quiet text, a white line under the chosen one. */
    private static void tab(GuiGraphics g, net.minecraft.client.gui.components.TabButton t) {
        Font font = Minecraft.getInstance().font;
        int x = t.getX();
        int y = t.getY();
        int w = t.getWidth();
        int h = t.getHeight();
        boolean picked = t.isSelected();
        if (!picked && t.isHoveredOrFocused()) {
            g.fill(x, y, x + w, y + h, Ui.HOVER);
        }
        String text = t.getMessage().getString();
        int tw = font.width(text);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - 8) / 2;
        g.drawString(font, text, tx, ty, picked ? Ui.TEXT : Ui.MUTED, false);
        if (picked) {
            g.fill(tx, ty + 11, tx + tw, ty + 12, Ui.TEXT);
        }
    }

    private static final ThreadLocal<Boolean> INSIDE_EDIT = ThreadLocal.withInitial(() -> false);
    private static Field bordered;

    /**
     * A text box: Pentecost's frame, then the box draws its own text and cursor inside it.
     * Returns false when the box has no frame of its own; then it is left as it is.
     */
    private static boolean editBox(GuiGraphics g, net.minecraft.client.gui.components.EditBox e, int mx, int my, float pt)
            throws ReflectiveOperationException {
        if (INSIDE_EDIT.get()) {
            return false;
        }
        if (bordered == null) {
            Field f = net.minecraft.client.gui.components.EditBox.class.getDeclaredField("bordered");
            f.setAccessible(true);
            bordered = f;
        }
        if (!bordered.getBoolean(e)) {
            return false;
        }
        int x = e.getX();
        int y = e.getY();
        int w = e.getWidth();
        int h = e.getHeight();
        g.fill(x, y, x + w, y + h, 0xCC0A0A0B);
        Ui.outline(g, x, y, x + w, y + h, e.isFocused() ? Ui.TEXT : 0x47FFFFFF);
        INSIDE_EDIT.set(true);
        bordered.setBoolean(e, false);
        g.pose().pushPose();
        g.pose().translate(4, (h - 8) / 2f, 0);
        try {
            e.renderWidget(g, mx - 4, my - (h - 8) / 2, pt);
        } finally {
            g.pose().popPose();
            bordered.setBoolean(e, true);
            INSIDE_EDIT.set(false);
        }
        return true;
    }

    /** Tooltips: a dark box with a thin light line, in place of the purple frame. */
    @Override
    public boolean tooltip(Object graphics, int x, int y, int width, int height, int z) {
        if (!on() || !(graphics instanceof GuiGraphics g)) {
            return false;
        }
        int x1 = x - 4;
        int y1 = y - 4;
        int x2 = x + width + 4;
        int y2 = y + height + 4;
        g.fill(x1, y1, x2, y2, z, 0xF00A0A0B);
        int line = 0x59FFFFFF;
        g.fill(x1, y1, x2, y1 + 1, z, line);
        g.fill(x1, y2 - 1, x2, y2, z, line);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, z, line);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, z, line);
        return true;
    }

    private static void label(GuiGraphics g, Font font, String text, int x, int y, int w, int h, int color) {
        String shown = font.width(text) > w - 8 ? font.plainSubstrByWidth(text, w - 14) + "..." : text;
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + (h - 8) / 2, color, false);
    }

    @Override
    public boolean background(Object screen, Object graphics) {
        if (!on() || !(graphics instanceof GuiGraphics g) || !(screen instanceof Screen s)) {
            return false;
        }
        WindowMode.fixStaleSize(s);
        g.fill(0, 0, s.width, s.height, 0xFF0A0A0B);
        Ui.dither(g, s.width, s.height);
        return true;
    }

    private static Object shownScreen;
    private static long shownAt;

    /** Screens fade in from dark over a short moment when they open. */
    @Override
    public void drawn(Object screen, Object graphics) {
        if (!on() || !(graphics instanceof GuiGraphics g) || !(screen instanceof Screen s)) {
            return;
        }
        long now = net.minecraft.Util.getMillis();
        if (screen != shownScreen) {
            shownScreen = screen;
            shownAt = now;
        }
        float t = (now - shownAt) / 180f;
        if (t >= 1f) {
            return;
        }
        float ease = 1f - (1f - t) * (1f - t) * (1f - t);
        int a = (int) ((1f - ease) * 200);
        if (a > 0) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.fill(0, 0, s.width, s.height, (a << 24) | 0x0A0A0B);
            g.pose().popPose();
        }
    }

    @Override
    public void inited(Object screen) {
        if (!on() || !(screen instanceof Screen s)) {
            return;
        }
        for (GuiEventListener child : s.children()) {
            if (child instanceof AbstractSelectionList<?> list) {
                list.setRenderBackground(false);
                list.setRenderTopAndBottom(false);
            }
        }
    }
}
