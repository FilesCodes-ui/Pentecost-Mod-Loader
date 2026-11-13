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
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Pentecost's own settings. Pages on the left, the chosen page on the right, scrolling when long.
 * Every row says what it does and when it takes effect.
 * Video holds Embeddium's options and Shaders drives Oculus, when those mods are installed.
 */
public final class PentecostSettingsScreen extends Screen {
    /** A slider: range, current value, setter, and the text for a value. */
    private record Slider(int min, int max, int step, IntSupplier value, IntConsumer set, Supplier<String> text) {}

    /**
     * One line on a page. value: text shown for a choice. on: state of a switch. action: what a click does.
     * enabled: false greys the row out. A "#" in when marks a section header.
     */
    private record Row(String title, String note, String when, Supplier<String> value, BooleanSupplier on,
                       Runnable action, Slider slider, BooleanSupplier enabled) {
        Row(String title, String note, String when, Supplier<String> value, BooleanSupplier on, Runnable action) {
            this(title, note, when, value, on, action, null, () -> true);
        }

        static Row info(String title, String value) {
            return new Row(title, "", "", () -> value, null, null);
        }

        static Row button(String title, String note, Runnable action) {
            return new Row(title, note, "", () -> "Open", null, action);
        }

        static Row header(String title) {
            return new Row(title, "", "#", null, null, null);
        }

        boolean isHeader() {
            return "#".equals(when);
        }
    }

    private record Page(String name, Supplier<List<Row>> rows, List<String> planned) {}

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    private final List<Page> pages = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int page = 1;
    private double scroll;
    private int scrollMax;
    private double lastMouseX;
    /** Embeddium's screen, built but not shown: holds its option pages and its apply step. */
    private Object embeddium;

    public PentecostSettingsScreen(Screen parent) {
        this(parent, "Interface");
    }

    public PentecostSettingsScreen(Screen parent, String startPage) {
        super(Component.literal("Pentecost settings"));
        this.parent = parent;
        pages.add(new Page("General", () -> List.of(
                Row.info("Pentecost", PentecostMain.VERSION),
                Row.info("Minecraft", SharedConstants.getCurrentVersion().getName()),
                Row.info("Java", System.getProperty("java.version")),
                Row.button("Game folder", "Saves, mods, configs and logs.",
                        () -> Util.getPlatform().openFile(net.minecraft.client.Minecraft.getInstance().gameDirectory))),
                List.of()));
        List<Row> iface = List.of(
                new Row("Window", "Borderless covers the screen with no frame. Switching apps stays instant.", "Now",
                        () -> WindowMode.label(Settings.get(Settings.WINDOW)), null,
                        () -> WindowMode.set(WindowMode.next(Settings.get(Settings.WINDOW)))),
                new Row("Pentecost menus", "Pentecost draws the main and pause menus.", "Now",
                        null, () -> Settings.on(Settings.MENUS), () -> Settings.toggle(Settings.MENUS)),
                new Row("Restyle every screen", "Buttons, sliders, checkboxes and backgrounds everywhere, mods' too.", "Now",
                        null, () -> Settings.on(Settings.RESTYLE), () -> Settings.toggle(Settings.RESTYLE)),
                new Row("Pentecost F3 screen", "Readable panels in place of the long text columns.", "Now",
                        null, () -> Settings.on(Settings.OVERLAY), () -> Settings.toggle(Settings.OVERLAY)),
                new Row("F3 detail", "Standard shows the usual facts. Full shows every line.", "Now",
                        () -> "full".equals(Settings.get(Settings.OVERLAY_DETAIL)) ? "Full" : "Standard", null,
                        () -> Settings.set(Settings.OVERLAY_DETAIL,
                                "full".equals(Settings.get(Settings.OVERLAY_DETAIL)) ? "standard" : "full")),
                new Row("Pentecost loading screen", "Logo, progress line, RAM gauge and time left while loading.", "Now",
                        null, () -> Settings.on(Settings.LOADING), () -> Settings.toggle(Settings.LOADING)),
                new Row("Mod buttons in black and white", "Buttons mods add to the menus match the rest.", "Now",
                        null, () -> Settings.on(Settings.GRAY_BUTTONS), () -> Settings.toggle(Settings.GRAY_BUTTONS)),
                new Row("Background pattern", "The slow dot pattern behind Pentecost's screens.", "Now",
                        null, () -> Settings.on(Settings.PATTERN), () -> Settings.toggle(Settings.PATTERN)));
        pages.add(new Page("Interface", () -> iface, List.of("Boot splash style", "Text size")));
        pages.add(new Page("Video", this::videoRows, List.of()));
        pages.add(new Page("Shaders", this::shaderRows, List.of()));
        pages.add(new Page("Performance", List::of,
                List.of("Launch cache", "Chunk building threads", "Far mob throttle", "Virtual threads on Java 25")));
        pages.add(new Page("Java", List::of,
                List.of("Memory", "Garbage collector", "Extra flags", "Java version")));
        pages.add(new Page("Gameplay", List::of,
                List.of("Faster mobs", "Smarter mobs", "Stall warning")));
        pages.add(new Page("Building", List::of,
                List.of("Block rotation", "Block outline")));
        pages.add(new Page("Server", List::of,
                List.of("World Wide Local Server", "Operators", "Server tools")));
        pages.add(new Page("Profiles", List::of,
                List.of("Save a setup", "Switch setups", "Export and import")));
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).name().equalsIgnoreCase(startPage)) {
                page = i;
            }
        }
    }

    // ---------------------------------------------------------------- video and shaders

    private List<ExternalOptions.VideoPage> videoPages;

    private List<Row> videoRows() {
        List<Row> rows = new ArrayList<>();
        if (!ExternalOptions.hasEmbeddium()) {
            rows.add(Row.button("Video settings", "The game's own video settings.",
                    () -> minecraft.setScreen(new net.minecraft.client.gui.screens.VideoSettingsScreen(this, minecraft.options))));
            return rows;
        }
        if (embeddium == null) {
            embeddium = ExternalOptions.embeddiumScreen(this);
            videoPages = ExternalOptions.pages(embeddium);
        }
        for (ExternalOptions.VideoPage vp : videoPages) {
            rows.add(Row.header(vp.name()));
            for (ExternalOptions.VideoOption v : vp.options()) {
                rows.add(videoRow(v));
            }
        }
        rows.add(Row.header("More"));
        rows.add(Row.button("Embeddium's own screen", "The same options in Embeddium's layout.",
                () -> ExternalOptions.openEmbeddium(this)));
        return rows;
    }

    private Row videoRow(ExternalOptions.VideoOption v) {
        String when = v.restart() ? "Restart" : "Now";
        BooleanSupplier enabled = () -> ExternalOptions.available(v);
        return switch (v.kind()) {
            case "switch" -> new Row(v.name(), v.tooltip(), when, null,
                    () -> Boolean.TRUE.equals(ExternalOptions.value(v)),
                    () -> ExternalOptions.set(embeddium, v, !Boolean.TRUE.equals(ExternalOptions.value(v))),
                    null, enabled);
            case "choice" -> new Row(v.name(), v.tooltip(), when, () -> ExternalOptions.choiceText(v), null,
                    () -> ExternalOptions.nextChoice(embeddium, v, hasShiftDown()), null, enabled);
            case "slider" -> {
                int[] range = ExternalOptions.sliderRange(v);
                yield new Row(v.name(), v.tooltip(), when, null, null, null,
                        new Slider(range[0], range[1], Math.max(1, range[2]),
                                () -> ExternalOptions.value(v) instanceof Integer n ? n : range[0],
                                n -> ExternalOptions.set(embeddium, v, n),
                                () -> ExternalOptions.sliderText(v)), enabled);
            }
            default -> new Row(v.name(), v.tooltip(), when, () -> String.valueOf(ExternalOptions.value(v)), null, null);
        };
    }

    private List<Row> shaderRows() {
        List<Row> rows = new ArrayList<>();
        if (!ExternalOptions.hasShaders()) {
            rows.add(Row.info("Shaders", "Install Oculus (Forge) or Iris (Fabric)"));
            return rows;
        }
        rows.add(new Row("Shaders", "Turns the chosen shader pack on or off.", "Now", null,
                ExternalOptions::shadersOn, () -> ExternalOptions.setShaders(!ExternalOptions.shadersOn())));
        rows.add(Row.button("Pack settings", "The chosen pack's own options.", () -> ExternalOptions.openPackSettings(this)));
        rows.add(Row.button("Shader folder", "Put shader packs here.", () -> {
            java.nio.file.Path dir = ExternalOptions.packFolder();
            if (dir != null) {
                Util.getPlatform().openFile(dir.toFile());
            }
        }));
        rows.add(Row.header("Packs"));
        String current = ExternalOptions.currentPack();
        List<String> packs = ExternalOptions.packs();
        if (packs.isEmpty()) {
            rows.add(Row.info("No packs yet", "Add one to the shader folder"));
        }
        for (String pack : packs) {
            boolean picked = pack.equals(current);
            rows.add(new Row(pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack,
                    picked ? "In use." : "Click to use this pack.", "Now", null,
                    () -> picked && ExternalOptions.shadersOn(),
                    () -> ExternalOptions.usePack(pack)));
        }
        return rows;
    }

    // ---------------------------------------------------------------- drawing

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
        sliders.clear();
        sliderRows.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int x = 56;
        int y = 30;
        Ui.logo(g, x, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, "SETTINGS", x + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
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
            hits.add(new Hit(x - 6, sy, sideWidth, 18, () -> {
                page = index;
                scroll = 0;
            }));
            sy += 20;
        }

        // The chosen page, clipped and scrolled.
        int px = x + sideWidth + 24;
        int pw = Math.min(560, vw - 56 - px);
        int top = y;
        int bottom = vh - 48;
        Page p = pages.get(page);
        g.enableScissor((int) (px * ui), (int) (top * ui), (int) ((px + pw + 4) * ui), (int) (bottom * ui));
        int py = top - (int) scroll;
        g.drawString(font, p.name().toUpperCase(Locale.ROOT), px, py, Ui.FAINT, false);
        py += 14;
        List<Row> rows = p.rows().get();
        for (Row r : rows) {
            py = r.isHeader() ? header(g, r, px, py, pw) : row(g, r, px, py, pw, mx, my, top, bottom);
        }
        if (!p.planned().isEmpty()) {
            py += rows.isEmpty() ? 0 : 16;
            g.drawString(font, "COMING", px, py, Ui.FAINT, false);
            py += 14;
            for (String s : p.planned()) {
                g.drawString(font, s, px + 4, py, Ui.FAINT, false);
                py += 13;
            }
        }
        g.disableScissor();
        scrollMax = Math.max(0, py + (int) scroll - bottom + 8);
        scroll = Math.max(0, Math.min(scrollMax, scroll));
        if (scrollMax > 0) {
            int track = bottom - top;
            int bar = Math.max(16, track * track / (track + scrollMax));
            int by = top + (int) ((track - bar) * scroll / scrollMax);
            g.fill(px + pw + 6, by, px + pw + 7, by + bar, Ui.MUTED);
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

    private int header(GuiGraphics g, Row r, int x, int y, int w) {
        y += 10;
        g.drawString(font, r.title().toUpperCase(Locale.ROOT), x + 4, y, Ui.MUTED, false);
        g.fill(x, y + 12, x + w, y + 13, 0x40FFFFFF);
        return y + 16;
    }

    /** One row: title, quieter note under it, when it applies, and its control. A hairline closes it. */
    private int row(GuiGraphics g, Row r, int x, int y, int w, int mx, int my, int top, int bottom) {
        boolean enabled = r.enabled().getAsBoolean();
        boolean visible = y + 30 > top && y < bottom;
        boolean clickable = enabled && (r.action() != null || r.slider() != null) && visible;
        boolean inside = my >= top && my < bottom;
        boolean hover = clickable && inside && Ui.over(mx, my, x, y, w, 30);
        if (hover && r.slider() == null) {
            g.fill(x, y, x + w, y + 30, Ui.HOVER);
        }
        g.drawString(font, r.title(), x + 4, y + 5, enabled ? Ui.TEXT : Ui.FAINT, false);
        int right = x + w - 6;
        if (r.on() != null) {
            int sx = right - 24;
            Ui.toggle(g, "settings:" + pages.get(page).name() + ":" + r.title(), sx, y + 10, r.on().getAsBoolean() && enabled);
            right = sx - 10;
        } else if (r.slider() != null) {
            Slider s = r.slider();
            int bw = 140;
            int bx = right - bw;
            int value = s.value().getAsInt();
            float t = s.max() > s.min() ? (value - s.min()) / (float) (s.max() - s.min()) : 0f;
            g.fill(bx, y + 14, bx + bw, y + 15, Ui.FAINT);
            g.fill(bx, y + 14, bx + Math.round(bw * t), y + 15, enabled ? Ui.TEXT : Ui.FAINT);
            int kx = bx + Math.round((bw - 4) * t);
            g.fill(kx, y + 9, kx + 4, y + 20, enabled && (hover || dragging == s) ? Ui.TEXT : Ui.SOFT);
            String text = s.text().get();
            g.drawString(font, text, bx - 10 - font.width(text), y + 11, enabled ? Ui.SOFT : Ui.FAINT, false);
            right = bx - 20 - font.width(text);
            if (clickable) {
                hits.add(new Hit(bx - 4, y, bw + 8, 30, () -> {
                    dragging = s;
                    slide(s, bx, bw);
                }));
                sliders.add(new int[]{bx, bw});
                sliderRows.add(s);
            }
        } else if (r.value() != null) {
            String v = r.value().get();
            int vwid = font.width(v);
            if (clickable) {
                Ui.outline(g, right - vwid - 14, y + 7, right, y + 23, hover ? Ui.TEXT : Ui.FAINT);
                g.drawString(font, v, right - vwid - 7, y + 11, Ui.TEXT, false);
                right -= vwid + 24;
            } else {
                g.drawString(font, v, right - vwid, y + 11, enabled ? Ui.SOFT : Ui.FAINT, false);
                right -= vwid + 10;
            }
        }
        if (!r.when().isEmpty()) {
            int tw = font.width(r.when());
            Ui.outline(g, right - tw - 8, y + 9, right, y + 21, Ui.LINE);
            g.drawString(font, r.when(), right - tw - 4, y + 11, Ui.FAINT, false);
            right -= tw + 16;
        }
        if (!r.note().isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(r.note(), Math.max(20, right - x - 8)), x + 4, y + 17, Ui.FAINT, false);
        }
        g.fill(x, y + 30, x + w, y + 31, Ui.LINE);
        if (clickable && r.action() != null && inside) {
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + 30, bottom) - Math.max(y, top), r.action()));
        }
        return y + 31;
    }

    // ---------------------------------------------------------------- input

    private Slider dragging;
    private final List<int[]> sliders = new ArrayList<>();
    private final List<Slider> sliderRows = new ArrayList<>();

    private void slide(Slider s, int bx, int bw) {
        float ui = Ui.scale(minecraft);
        double mx = lastMouseX / ui;
        float t = (float) Math.max(0, Math.min(1, (mx - bx) / bw));
        int raw = s.min() + Math.round(t * (s.max() - s.min()));
        int snapped = s.min() + Math.round((raw - s.min()) / (float) s.step()) * s.step();
        snapped = Math.max(s.min(), Math.min(s.max(), snapped));
        if (snapped != s.value().getAsInt()) {
            s.set().accept(snapped);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        lastMouseX = mouseX;
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
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging != null) {
            lastMouseX = mouseX;
            int i = sliderRows.indexOf(dragging);
            if (i >= 0) {
                slide(dragging, sliders.get(i)[0], sliders.get(i)[1]);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, Math.min(scrollMax, scroll - delta * 24));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
