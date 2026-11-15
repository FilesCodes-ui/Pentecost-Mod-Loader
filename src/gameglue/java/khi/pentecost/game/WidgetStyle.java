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

    /**
     * The F3 screen. On the game's own path the lines come from its debug overlay. On Forge's path,
     * Pentecost asks Forge's overlay for its lines and posts Forge's event, so mods add their lines as usual.
     */
    @Override
    @SuppressWarnings("unchecked")
    public boolean debug(Object source, Object graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.on(Settings.OVERLAY) || !mc.options.renderDebug || !(graphics instanceof GuiGraphics g)) {
            return false;
        }
        try {
            java.util.List<String> left;
            java.util.List<String> right;
            java.util.List<String> fromMods = new java.util.ArrayList<>();
            if (source instanceof net.minecraft.client.gui.components.DebugScreenOverlay overlay) {
                left = (java.util.List<String>) call(net.minecraft.client.gui.components.DebugScreenOverlay.class,
                        overlay, "getGameInformation");
                right = (java.util.List<String>) call(net.minecraft.client.gui.components.DebugScreenOverlay.class,
                        overlay, "getSystemInformation");
            } else {
                Field f = findField(source.getClass(), "debugOverlay");
                Object overlay = f.get(source);
                call(overlay.getClass(), overlay, "update");
                java.util.ArrayList<String> l = new java.util.ArrayList<>((java.util.List<String>) call(overlay.getClass(), overlay, "getLeft"));
                java.util.ArrayList<String> r = new java.util.ArrayList<>((java.util.List<String>) call(overlay.getClass(), overlay, "getRight"));
                java.util.List<String> baseL = new java.util.ArrayList<>(l);
                java.util.List<String> baseR = new java.util.ArrayList<>(r);
                postDebugEvent(mc, g, l, r);
                for (String s : l) {
                    if (!baseL.contains(s)) {
                        fromMods.add(s);
                    }
                }
                for (String s : r) {
                    if (!baseR.contains(s)) {
                        fromMods.add(s);
                    }
                }
                left = baseL;
                right = baseR;
            }
            DebugView.draw(g, left, right, fromMods);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!debugFailed) {
                debugFailed = true;
                khi.pentecost.boot.Log.error("Pentecost F3 screen failed, showing the game's own: " + e);
                Throwable c = e.getCause();
                if (c != null) {
                    khi.pentecost.boot.Log.error("  Caused by: " + c);
                }
            }
            return false;
        }
    }

    private static boolean debugFailed;

    private static Object call(Class<?> owner, Object target, String method) throws ReflectiveOperationException {
        java.lang.reflect.Method m = owner.getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException next) {
                // Look in the parent.
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Posts Forge's debug text event, so mods add their lines to these lists. */
    private static void postDebugEvent(Minecraft mc, GuiGraphics g, java.util.ArrayList<String> left,
                                       java.util.ArrayList<String> right) {
        try {
            ClassLoader cl = Minecraft.class.getClassLoader();
            Class<?> type = Class.forName("net.minecraftforge.client.event.CustomizeGuiOverlayEvent$DebugText", false, cl);
            Object event = null;
            for (java.lang.reflect.Constructor<?> c : type.getConstructors()) {
                if (c.getParameterCount() == 5) {
                    event = c.newInstance(mc.getWindow(), g, mc.getFrameTime(), left, right);
                }
            }
            if (event == null) {
                return;
            }
            Object bus = Class.forName("net.minecraftforge.common.MinecraftForge", false, cl).getField("EVENT_BUS").get(null);
            for (java.lang.reflect.Method m : bus.getClass().getMethods()) {
                if (m.getName().equals("post") && m.getParameterCount() == 1) {
                    m.invoke(bus, event);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Without the event, the panels show the game's lines only.
        }
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
        // The chat sits over the world and is not a menu: no fade from dark when T opens it.
        if (screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
            shownScreen = screen;
            return;
        }        long now = net.minecraft.Util.getMillis();
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

    private static java.lang.reflect.Method addWidget;
    private static boolean cornerOpen;

    /**
     * A mod's own main menu has none of Pentecost's entries. A small Pentecost button in its top right corner
     * opens a short list: Mods, Join a friend and Settings.
     */
    private static void cornerButtons(Screen s) {
        String where = Settings.get(Settings.MENU_BUTTONS);
        String cls = s.getClass().getName();
        // A mod's main menu is a TitleScreen, or a screen the mod swapped in for it (Management Wanted).
        boolean mainMenu = s instanceof net.minecraft.client.gui.screens.TitleScreen
                || khi.pentecost.control.Choices.swapsOf(net.minecraft.client.gui.screens.TitleScreen.class.getName())
                        .containsValue(cls);
        if (!mainMenu || s instanceof PentecostTitleScreen || cls.startsWith("net.minecraft.") || where.equals("off")) {
            return;
        }
        for (GuiEventListener child : s.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button b && "Pentecost".equals(b.getMessage().getString())) {
                return;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        int w = 78;
        boolean left = where.equals("topLeft") || where.equals("bottomLeft");
        boolean bottom = where.startsWith("bottom");
        int x = left ? 6 : s.width - w - 6;
        int top = bottom ? s.height - 24 : 6;
        String[] labels = {"Mods", "Join a friend", "Settings"};
        Runnable[] actions = {
                () -> mc.setScreen(new PentecostModsScreen(s)),
                () -> mc.setScreen(new WwlsScreen(s)),
                () -> mc.setScreen(new PentecostSettingsScreen(s, "Interface"))};
        java.util.List<net.minecraft.client.gui.components.Button> extra = new java.util.ArrayList<>();
        cornerOpen = false;
        net.minecraft.client.gui.components.Button main = net.minecraft.client.gui.components.Button.builder(
                net.minecraft.network.chat.Component.literal("Pentecost"), b -> {
                    cornerOpen = !cornerOpen;
                    extra.forEach(e -> e.visible = cornerOpen);
                }).bounds(x, top, w, 18).build();
        add(s, main);
        for (int i = 0; i < labels.length; i++) {
            Runnable action = actions[i];
            net.minecraft.client.gui.components.Button e = net.minecraft.client.gui.components.Button.builder(
                    net.minecraft.network.chat.Component.literal(labels[i]), b -> action.run())
                    .bounds(x, bottom ? top - 20 * (labels.length - i) : top + 20 + i * 20, w, 18).build();
            e.visible = false;
            extra.add(e);
            add(s, e);
        }
    }

    private static void add(Screen s, AbstractWidget widget) {
        try {
            if (addWidget == null) {
                addWidget = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
                addWidget.setAccessible(true);
            }
            addWidget.invoke(s, widget);
        } catch (ReflectiveOperationException | RuntimeException e) {
            khi.pentecost.boot.Log.error("Cannot add the Pentecost button to " + s.getClass().getName() + ": " + e);
        }
    }

    private static final String[] DIRT = {"textures/gui/options_background.png", "textures/gui/light_dirt_background.png"};
    private static boolean darkDirt;

    /**
     * Lists and some screens tile the dirt picture behind themselves. While the restyle is on,
     * Pentecost swaps those pictures for near-black ones, so every list keeps a solid background.
     */
    @Override
    public void inited(Object screen) {
        Minecraft mc = Minecraft.getInstance();
        if (screen instanceof Screen s) {
            cornerButtons(s);
        }
        boolean want = on();
        if (want == darkDirt) {
            return;
        }
        darkDirt = want;
        for (String path : DIRT) {
            net.minecraft.resources.ResourceLocation id = new net.minecraft.resources.ResourceLocation("minecraft", path);
            if (want) {
                com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(16, 16, false);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        // Near black, with a faint dot pattern. Pixels are stored ABGR.
                        int v = ((x * 7 + y * 13) % 11 == 0) ? 0x3A : 0x2C;
                        img.setPixelRGBA(x, y, 0xFF000000 | (v + 2) << 16 | v << 8 | v);
                    }
                }
                mc.getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(img));
            } else {
                mc.getTextureManager().release(id);
            }
        }
    }
}
