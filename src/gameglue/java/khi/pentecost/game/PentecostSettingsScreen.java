package khi.pentecost.game;

import khi.pentecost.boot.PentecostMain;
import khi.pentecost.control.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Pentecost's own settings. Pages on the left, the chosen page on the right.
 * Every row says what it does and when it takes effect.
 */
public final class PentecostSettingsScreen extends Screen {
    /** One line on a page. value: text shown for a choice. on: state of a switch. action: what a click does. */
    private record Row(String title, String note, String when, Supplier<String> value, BooleanSupplier on, Runnable action) {
        static Row info(String title, String value) {
            return new Row(title, "", "", () -> value, null, null);
        }

        static Row button(String title, String note, Runnable action) {
            return new Row(title, note, "", () -> "Open", null, action);
        }
    }

    private record Page(String name, List<Row> rows, List<String> planned) {}

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    private final List<Page> pages = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int page = 1;

    public PentecostSettingsScreen(Screen parent) {
        super(Component.literal("Pentecost settings"));
        this.parent = parent;
        pages.add(new Page("General", List.of(
                Row.info("Pentecost", PentecostMain.VERSION),
                Row.info("Minecraft", SharedConstants.getCurrentVersion().getName()),
                Row.info("Java", System.getProperty("java.version")),
                Row.button("Game folder", "Saves, mods, configs and logs.",
                        () -> Util.getPlatform().openFile(net.minecraft.client.Minecraft.getInstance().gameDirectory))),
                List.of()));
        pages.add(new Page("Interface", List.of(
                new Row("Window", "Borderless covers the screen with no frame. Switching apps stays instant.", "Now",
                        () -> WindowMode.label(Settings.get(Settings.WINDOW)), null,
                        () -> WindowMode.set(WindowMode.next(Settings.get(Settings.WINDOW)))),
                new Row("Pentecost menus", "Pentecost draws the main and pause menus.", "Now",
                        null, () -> Settings.on(Settings.MENUS), () -> Settings.toggle(Settings.MENUS)),
                new Row("Mod buttons in black and white", "Buttons mods add to the menus match the rest.", "Now",
                        null, () -> Settings.on(Settings.GRAY_BUTTONS), () -> Settings.toggle(Settings.GRAY_BUTTONS)),
                new Row("Pentecost loading screen", "Logo, progress line, RAM gauge and time left while loading.", "Now",
                        null, () -> Settings.on(Settings.LOADING), () -> Settings.toggle(Settings.LOADING)),
                new Row("Background pattern", "The slow dot pattern behind Pentecost's screens.", "Now",
                        null, () -> Settings.on(Settings.PATTERN), () -> Settings.toggle(Settings.PATTERN))),
                List.of("Restyle every screen", "Overlay layout (F3 and F6)", "Boot splash style", "Text size")));
        pages.add(new Page("Performance", List.of(),
                List.of("Launch cache", "Chunk building threads", "Far mob throttle", "Virtual threads on Java 25")));
        pages.add(new Page("Java", List.of(),
                List.of("Memory", "Garbage collector", "Extra flags", "Java version")));
        pages.add(new Page("Shaders", List.of(),
                List.of("Shader pack, through Oculus or Iris", "Pack settings", "Shaders on or off")));
        pages.add(new Page("Gameplay", List.of(),
                List.of("Faster mobs", "Smarter mobs", "Stall warning")));
        pages.add(new Page("Building", List.of(),
                List.of("Block rotation", "Block outline")));
        pages.add(new Page("Server", List.of(),
                List.of("World Wide Local Server", "Operators", "Server tools")));
        pages.add(new Page("Profiles", List.of(),
                List.of("Save a setup", "Switch setups", "Export and import")));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

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

        int x = 56;
        int y = 30;
        Ui.wordmark(g, "SETTINGS", x, y, 2, Ui.TEXT);
        y += 28 + 14;
        g.fill(x, y, vw - 56, y + 1, Ui.LINE);
        y += 16;

        // Pages.
        int sideWidth = 120;
        int sy = y;
        for (int i = 0; i < pages.size(); i++) {
            boolean active = i == page;
            boolean hover = Ui.over(mx, my, x - 6, sy, sideWidth, 18);
            if (active) {
                g.fill(x - 6, sy, x - 6 + sideWidth, sy + 18, Ui.TEXT);
            } else if (hover) {
                g.fill(x - 6, sy, x - 6 + sideWidth, sy + 18, Ui.HOVER);
            }
            g.drawString(font, (active ? "> " : "") + pages.get(i).name(), x, sy + 5,
                    active ? Ui.BLACK : hover ? Ui.TEXT : Ui.MUTED, false);
            int index = i;
            hits.add(new Hit(x - 6, sy, sideWidth, 18, () -> page = index));
            sy += 20;
        }

        // The chosen page.
        int px = x + sideWidth + 24;
        int pw = Math.min(520, vw - 56 - px);
        Page p = pages.get(page);
        int py = y;
        g.drawString(font, p.name().toUpperCase(java.util.Locale.ROOT), px, py, Ui.FAINT, false);
        py += 14;
        for (Row r : p.rows()) {
            py = row(g, r, px, py, pw, mx, my);
        }
        if (!p.planned().isEmpty()) {
            py += p.rows().isEmpty() ? 0 : 16;
            g.drawString(font, "COMING", px, py, Ui.FAINT, false);
            py += 14;
            for (String s : p.planned()) {
                g.drawString(font, s, px + 4, py, Ui.FAINT, false);
                py += 13;
            }
        }

        // Done, bottom right.
        String done = "Done";
        int dw = font.width(done) + 24;
        int dx = vw - 56 - dw;
        int dy = vh - 34;
        boolean hover = Ui.over(mx, my, dx, dy, dw, 18);
        if (hover) {
            g.fill(dx, dy, dx + dw, dy + 18, Ui.TEXT);
        } else {
            Ui.outline(g, dx, dy, dx + dw, dy + 18, Ui.MUTED);
        }
        g.drawString(font, done, dx + 12, dy + 5, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(dx, dy, dw, 18, this::onClose));
        g.pose().popPose();
    }

    /** One row: title, quieter note under it, when it applies, and its control. A hairline closes it. */
    private int row(GuiGraphics g, Row r, int x, int y, int w, int mx, int my) {
        boolean clickable = r.action() != null;
        boolean hover = clickable && Ui.over(mx, my, x, y, w, 30);
        if (hover) {
            g.fill(x, y, x + w, y + 30, Ui.HOVER);
        }
        g.drawString(font, r.title(), x + 4, y + 5, Ui.TEXT, false);
        if (!r.note().isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(r.note(), w - 150), x + 4, y + 17, Ui.FAINT, false);
        }
        int right = x + w - 6;
        if (r.on() != null) {
            boolean on = r.on().getAsBoolean();
            int sx = right - 24;
            int sy = y + 10;
            Ui.outline(g, sx, sy, sx + 24, sy + 11, on ? Ui.TEXT : Ui.FAINT);
            g.fill(on ? sx + 14 : sx + 2, sy + 2, on ? sx + 22 : sx + 10, sy + 9, on ? Ui.TEXT : Ui.FAINT);
            right = sx - 10;
        } else if (r.value() != null) {
            String v = r.value().get();
            int vwid = font.width(v);
            if (clickable) {
                Ui.outline(g, right - vwid - 14, y + 7, right, y + 23, hover ? Ui.TEXT : Ui.FAINT);
                g.drawString(font, v, right - vwid - 7, y + 11, Ui.TEXT, false);
                right -= vwid + 24;
            } else {
                g.drawString(font, v, right - vwid, y + 11, Ui.SOFT, false);
                right -= vwid + 10;
            }
        }
        if (!r.when().isEmpty()) {
            int tw = font.width(r.when());
            Ui.outline(g, right - tw - 8, y + 9, right, y + 21, Ui.LINE);
            g.drawString(font, r.when(), right - tw - 4, y + 11, Ui.FAINT, false);
        }
        g.fill(x, y + 30, x + w, y + 31, Ui.LINE);
        if (clickable) {
            hits.add(new Hit(x, y, w, 30, r.action()));
        }
        return y + 31;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        float ui = Ui.scale(minecraft);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
