package khi.pentecost.game;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.adapter.fabric.FabricLoaderImpl;
import khi.pentecost.control.Choices;
import khi.pentecost.control.Features;
import khi.pentecost.control.Features.Feature;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pentecost's Mods screen. One list of every mod from every loader, and for each mod
 * a plain-language list of what it changes, each with its own switch.
 *
 * Everything here is drawn with flat shapes, not game textures, so no mod or resource pack restyles it.
 */
public final class PentecostModsScreen extends Screen {
    private static final int BG = 0xFF0A0A0B;
    private static final int BG_WORLD = 0xE6000000;
    private static final int LINE = 0x24FFFFFF;
    private static final int HOVER = 0x0DFFFFFF;
    private static final int PICKED = 0x1AFFFFFF;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFF9A9A9A;
    private static final int FAINT = 0xFF636363;
    private static final int BLACK = 0xFF0A0A0B;
    private static final int ROW = 32;
    private static final int MARGIN = 24;

    private record Icon(ResourceLocation texture, int width, int height) {}

    private record Clip(int x1, int y1, int x2, int y2) {
        boolean has(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    private record Hit(int x, int y, int w, int h, Clip clip, Runnable action) {}

    private static final Map<String, Optional<Icon>> ICONS = new HashMap<>();

    private final Screen parent;
    private final Map<String, Optional<Function<Screen, Screen>>> configs = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private List<ModInfo> shown = List.of();
    private EditBox search;
    private String filter = "All";
    private ModInfo selected;
    private double listScroll;
    private double detailScroll;
    private int listMax;
    private int detailMax;
    private int listWidth;
    /** The screen draws at its own size, about two screen pixels per pixel, whatever the GUI scale is. */
    private float ui = 1f;
    private int vw;
    private int vh;

    public PentecostModsScreen(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
    }

    private void measure() {
        double scale = minecraft.getWindow().getGuiScale();
        ui = scale > 2 ? (float) (2.0 / scale) : 1f;
        vw = (int) (width / ui);
        vh = (int) (height / ui);
        listWidth = Math.max(190, Math.min(280, vw * 30 / 100));
    }

    @Override
    protected void init() {
        measure();
        String typed = search == null ? "" : search.getValue();
        search = new EditBox(font, MARGIN, 62, listWidth, 12, Component.literal("Search"));
        search.setBordered(false);
        search.setTextColor(TEXT & 0xFFFFFF);
        search.setHint(Component.literal("Search").withStyle(s -> s.withColor(FAINT & 0xFFFFFF)));
        search.setValue(typed);
        search.setResponder(text -> refill());
        addRenderableWidget(search);
        refill();
    }

    // ---------------------------------------------------------------- data

    private static boolean isLibrary(ModInfo m) {
        if (m.loader() == LoaderKind.LIBRARY) {
            return true;
        }
        String id = m.id();
        return ((id.startsWith("fabric-") || id.startsWith("fabric_")) && !id.equals("fabric-api") && !id.equals("fabric_api"))
                || id.equals("mixinextras");
    }

    private static List<ModInfo> allMods() {
        List<ModInfo> out = new ArrayList<>();
        for (ModInfo m : PentecostRuntime.allMods.isEmpty() ? PentecostRuntime.mods : PentecostRuntime.allMods) {
            if (!isLibrary(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private static String loaderOf(ModInfo m) {
        return m.loader().label;
    }

    private void refill() {
        String query = search.getValue().toLowerCase(Locale.ROOT).trim();
        List<ModInfo> list = new ArrayList<>();
        for (ModInfo m : allMods()) {
            boolean off = Choices.isModOff(m.id());
            if (filter.equals("Off") ? !off : !filter.equals("All") && !loaderOf(m).equals(filter)) {
                continue;
            }
            if (query.isEmpty() || m.name().toLowerCase(Locale.ROOT).contains(query)
                    || m.id().toLowerCase(Locale.ROOT).contains(query)) {
                list.add(m);
            }
        }
        list.sort(Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT)));
        shown = list;
        listScroll = 0;
        if (selected == null || !list.contains(selected)) {
            selected = list.isEmpty() ? null : list.get(0);
            String wanted = System.getenv("PENTECOST_TEST_MOD");
            for (ModInfo m : list) {
                if (m.id().equals(wanted)) {
                    selected = m;
                }
            }
            detailScroll = 0;
            String scroll = System.getenv("PENTECOST_TEST_SCROLL");
            if (scroll != null && selected != null && selected.id().equals(wanted)) {
                detailScroll = Double.parseDouble(scroll);
                detailMax = Integer.MAX_VALUE;
            }
        }
    }

    // ---------------------------------------------------------------- config screens

    private Optional<Function<Screen, Screen>> configFor(ModInfo m) {
        return configs.computeIfAbsent(m.id(), id -> {
            if (Choices.isModOff(id) || PentecostRuntime.mods.stream().noneMatch(x -> x.id().equals(id))) {
                return Optional.empty();
            }
            try {
                return m.loader() == LoaderKind.FORGE ? forgeConfig(m) : fabricConfig(m);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        });
    }

    /** Forge mods register a settings screen with Forge. Asked through reflection: Forge may be absent. */
    @SuppressWarnings("unchecked")
    private Optional<Function<Screen, Screen>> forgeConfig(ModInfo m) throws ReflectiveOperationException {
        if (!PentecostRuntime.forgeLayer) {
            return Optional.empty();
        }
        ClassLoader cl = getClass().getClassLoader();
        Class<?> modList = Class.forName("net.minecraftforge.fml.ModList", true, cl);
        Object mods = modList.getMethod("get").invoke(null);
        Optional<?> container = (Optional<?>) modList.getMethod("getModContainerById", String.class).invoke(mods, m.id());
        if (container.isEmpty()) {
            return Optional.empty();
        }
        Object info = container.get().getClass().getMethod("getModInfo").invoke(container.get());
        Class<?> handler = Class.forName("net.minecraftforge.client.ConfigScreenHandler", true, cl);
        for (Method method : handler.getMethods()) {
            if (method.getName().equals("getScreenFactoryFor") && method.getParameterCount() == 1) {
                Optional<BiFunction<Minecraft, Screen, Screen>> factory =
                        (Optional<BiFunction<Minecraft, Screen, Screen>>) method.invoke(null, info);
                return factory.map(f -> back -> f.apply(minecraft, back));
            }
        }
        return Optional.empty();
    }

    /** Fabric and Quilt mods offer a settings screen through the "modmenu" entrypoint. */
    private Optional<Function<Screen, Screen>> fabricConfig(ModInfo m) throws ReflectiveOperationException {
        ClassLoader cl = getClass().getClassLoader();
        for (String value : m.entrypoints("modmenu")) {
            Object api;
            try {
                api = FabricLoaderImpl.INSTANCE.create(value, Object.class);
            } catch (Exception e) {
                continue;
            }
            if (api == null) {
                continue;
            }
            Class<?> apiType = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi", true, cl);
            Object factory = apiType.getMethod("getModConfigScreenFactory").invoke(api);
            Method create = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory", true, cl)
                    .getMethod("create", Screen.class);
            if (create.invoke(factory, this) == null) {
                continue;
            }
            return Optional.of(back -> {
                try {
                    return (Screen) create.invoke(factory, back);
                } catch (ReflectiveOperationException e) {
                    return back;
                }
            });
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- icons

    private static String iconPath(ModInfo m) {
        if (m.raw() != null) {
            for (String key : new String[]{"icon", "logoFile"}) {
                JsonElement e = m.raw().get(key);
                if (e != null && e.isJsonPrimitive()) {
                    return e.getAsString();
                }
                if (e != null && e.isJsonObject() && !e.getAsJsonObject().entrySet().isEmpty()) {
                    return e.getAsJsonObject().entrySet().iterator().next().getValue().getAsString();
                }
            }
        }
        return "assets/" + m.id() + "/icon.png";
    }

    private static Optional<Icon> icon(ModInfo m) {
        return ICONS.computeIfAbsent(m.id(), id -> {
            String path = iconPath(m);
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            try (ZipFile zip = new ZipFile(m.jar().toFile())) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    NativeImage image = NativeImage.read(in);
                    ResourceLocation rl = new ResourceLocation("pentecost",
                            "modicon/" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
                    Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
                    return Optional.of(new Icon(rl, image.getWidth(), image.getHeight()));
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    private void drawIcon(GuiGraphics g, ModInfo m, int x, int y, int size) {
        Optional<Icon> icon = icon(m);
        if (icon.isPresent()) {
            Icon i = icon.get();
            int w = size;
            int h = size;
            if (i.width() > i.height()) {
                h = Math.max(1, size * i.height() / i.width());
            } else if (i.height() > i.width()) {
                w = Math.max(1, size * i.width() / i.height());
            }
            g.blit(i.texture(), x + (size - w) / 2, y + (size - h) / 2, w, h, 0f, 0f,
                    i.width(), i.height(), i.width(), i.height());
        } else {
            outline(g, x, y, x + size, y + size, LINE);
            String letter = m.name().isEmpty() ? "?" : m.name().substring(0, 1).toUpperCase(Locale.ROOT);
            float scale = size / 16f;
            text(g, letter, x + (size - font.width(letter) * scale) / 2f, y + (size - 8 * scale) / 2f, MUTED, scale);
        }
    }

    // ---------------------------------------------------------------- drawing tools

    private static void outline(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    private void text(GuiGraphics g, String s, float x, float y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, 0, 0, color, false);
        g.pose().popPose();
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, false);
    }

    private void clip(GuiGraphics g, Clip c) {
        g.enableScissor((int) (c.x1() * ui), (int) (c.y1() * ui), (int) Math.ceil(c.x2() * ui), (int) Math.ceil(c.y2() * ui));
    }

    private void hit(int x, int y, int w, int h, Clip clip, Runnable action) {
        hits.add(new Hit(x, y, w, h, clip, action));
    }

    private static boolean over(int mx, int my, int x, int y, int w, int h, Clip clip) {
        return mx >= x && mx < x + w && my >= y && my < y + h && (clip == null || clip.has(mx, my));
    }

    /** An outlined button. It fills white while the pointer is on it. */
    private void button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, Clip clip, Runnable action) {
        boolean hover = over(mx, my, x, y, w, 18, clip);
        if (hover) {
            g.fill(x, y, x + w, y + 18, TEXT);
        } else {
            outline(g, x, y, x + w, y + 18, MUTED);
        }
        text(g, label, x + (w - font.width(label)) / 2, y + 5, hover ? BLACK : TEXT);
        hit(x, y, w, 18, clip, action);
    }

    private static void toggle(GuiGraphics g, int x, int y, boolean on) {
        if (on) {
            g.fill(x, y, x + 20, y + 10, TEXT);
            g.fill(x + 12, y + 2, x + 18, y + 8, BLACK);
        } else {
            outline(g, x, y, x + 20, y + 10, FAINT);
            g.fill(x + 2, y + 2, x + 8, y + 8, FAINT);
        }
    }

    // ---------------------------------------------------------------- frame

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        measure();
        g.fill(0, 0, width, height, minecraft.level == null ? BG : BG_WORLD);
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        draw(g, (int) (mouseX / ui), (int) (mouseY / ui), partialTick);
        g.pose().popPose();
    }

    private void draw(GuiGraphics g, int mx, int my, float partialTick) {
        final int width = vw;
        final int height = vh;
        hits.clear();

        // Header.
        text(g, "Mods", MARGIN, 18, TEXT, 2f);
        List<ModInfo> all = allMods();
        long off = all.stream().filter(m -> Choices.isModOff(m.id())).count();
        String summary = all.size() + " installed" + (off > 0 ? "   " + off + " off" : "");
        text(g, summary, width - MARGIN - font.width(summary), 26, FAINT);
        g.fill(MARGIN, 46, width - MARGIN, 47, LINE);

        int top = 56;
        int bottom = height - 16;
        int lx = MARGIN;
        int rx = lx + listWidth + 24;

        // ---- left: search, filters, list
        g.fill(lx, top + 20, lx + listWidth, top + 21, search.isFocused() ? MUTED : LINE);
        int cx = lx;
        int cy = top + 30;
        List<String> filters = new ArrayList<>(List.of("All", "Forge", "Fabric"));
        if (all.stream().anyMatch(m -> m.loader() == LoaderKind.QUILT)) {
            filters.add("Quilt");
        }
        if (off > 0 || filter.equals("Off")) {
            filters.add("Off");
        }
        for (String f : filters) {
            int w = font.width(f);
            boolean active = filter.equals(f);
            boolean hover = over(mx, my, cx - 2, cy - 2, w + 4, 14, null);
            text(g, f, cx, cy, active ? TEXT : hover ? MUTED : FAINT);
            if (active) {
                g.fill(cx, cy + 10, cx + w, cy + 11, TEXT);
            }
            hit(cx - 2, cy - 2, w + 4, 14, null, () -> {
                filter = f;
                refill();
            });
            cx += w + 14;
        }

        int ly1 = top + 50;
        int ly2 = bottom;
        Clip listClip = new Clip(lx, ly1, lx + listWidth, ly2);
        listMax = Math.max(0, shown.size() * ROW - (ly2 - ly1));
        listScroll = Math.max(0, Math.min(listMax, listScroll));
        clip(g, listClip);
        for (int i = 0; i < shown.size(); i++) {
            ModInfo m = shown.get(i);
            int ry = ly1 + i * ROW - (int) listScroll;
            if (ry + ROW < ly1 || ry > ly2) {
                continue;
            }
            int w = listWidth - 8;
            boolean isOff = Choices.isModOff(m.id());
            boolean hover = over(mx, my, lx, ry, w, ROW, listClip);
            if (m == selected) {
                g.fill(lx, ry, lx + w, ry + ROW, PICKED);
                g.fill(lx, ry, lx + 1, ry + ROW, TEXT);
            } else if (hover) {
                g.fill(lx, ry, lx + w, ry + ROW, HOVER);
            }
            drawIcon(g, m, lx + 8, ry + 4, 24);
            int tw = w - 46;
            text(g, font.plainSubstrByWidth(m.name(), tw), lx + 40, ry + 6, isOff ? FAINT : TEXT);
            String line = isOff ? "Off" : loaderOf(m) + "  " + m.version();
            text(g, font.plainSubstrByWidth(line, tw), lx + 40, ry + 18, FAINT);
            hit(lx, ry, w, ROW, listClip, () -> {
                selected = m;
                detailScroll = 0;
            });
        }
        g.disableScissor();
        if (shown.isEmpty()) {
            text(g, "Nothing matches.", lx, ly1 + 8, FAINT);
        }
        if (listMax > 0) {
            int track = ly2 - ly1;
            int bar = Math.max(16, track * track / (track + listMax));
            int by = ly1 + (int) ((track - bar) * listScroll / listMax);
            g.fill(lx + listWidth - 2, by, lx + listWidth - 1, by + bar, MUTED);
        }

        // ---- right: the selected mod
        g.fill(rx - 12, top, rx - 11, bottom, LINE);
        int footer = bottom - 28;
        if (selected != null) {
            drawDetail(g, selected, rx, top, width - MARGIN, footer, mx, my);
        }
        g.fill(rx, footer, width - MARGIN, footer + 1, LINE);
        int pending = Choices.pendingRestart();
        if (pending > 0) {
            text(g, "Restart to apply " + pending + " change" + (pending == 1 ? "" : "s"), rx, footer + 15, TEXT);
        }
        button(g, "Done", width - MARGIN - 64, footer + 10, 64, mx, my, null, this::onClose);

        super.render(g, mx, my, partialTick);
    }

    private void drawDetail(GuiGraphics g, ModInfo m, int x1, int y1, int x2, int y2, int mx, int my) {
        Clip clip = new Clip(x1, y1, x2, y2 - 4);
        int x = x1;
        int w = x2 - x1 - 8;
        int y = y1 + 4 - (int) detailScroll;
        boolean modOff = Choices.isModOff(m.id());
        clip(g, clip);

        drawIcon(g, m, x, y, 40);
        text(g, font.plainSubstrByWidth(m.name(), (int) ((w - 52) / 1.5f)), x + 52, y + 5, TEXT, 1.5f);
        text(g, font.plainSubstrByWidth(loaderOf(m) + "   " + m.version() + "   " + m.id(), w - 52), x + 52, y + 24, FAINT);
        y += 54;

        String about = description(m);
        if (!about.isEmpty()) {
            for (FormattedCharSequence line : font.split(Component.literal(about), Math.min(w, 420))) {
                g.drawString(font, line, x, y, MUTED, false);
                y += 11;
            }
            y += 10;
        }

        y = row(g, x, y, w, mx, my, clip, modOff ? "Off" : "On", "Whether this mod loads. Needs a restart.", !modOff,
                () -> Choices.setModOff(m.id(), !Choices.isModOff(m.id())));
        Optional<Function<Screen, Screen>> config = configFor(m);
        if (config.isPresent()) {
            y += 8;
            button(g, "Settings", x, y, 70, mx, my, clip, () -> minecraft.setScreen(config.get().apply(this)));
            y += 22;
        }
        y += 16;

        List<Feature> features = Features.forMod(m.id());
        List<Feature> simple = features.stream().filter(f -> !f.risky()).toList();
        List<Feature> advanced = features.stream().filter(Feature::risky).toList();
        text(g, "WHAT IT CHANGES", x, y, FAINT);
        y += 14;
        if (modOff) {
            text(g, "Nothing. The mod is off.", x, y, MUTED);
            y += 14;
        } else if (features.isEmpty()) {
            text(g, "Nothing outside its own content.", x, y, MUTED);
            y += 14;
        } else {
            for (Feature f : simple) {
                y = featureRow(g, m, f, x, y, w, mx, my, clip);
            }
            if (!advanced.isEmpty()) {
                y += 16;
                text(g, "ADVANCED", x, y, FAINT);
                y += 12;
                text(g, font.plainSubstrByWidth("Turning these off can break the mod.", w), x, y, FAINT);
                y += 12;
                for (Feature f : advanced) {
                    y = featureRow(g, m, f, x, y, w, mx, my, clip);
                }
            }
        }
        g.disableScissor();

        int contentHeight = y + (int) detailScroll - (y1 + 4) + 12;
        detailMax = Math.max(0, contentHeight - (y2 - y1 - 4));
        detailScroll = Math.max(0, Math.min(detailMax, detailScroll));
        if (detailMax > 0) {
            int track = y2 - y1 - 4;
            int bar = Math.max(16, track * track / (track + detailMax));
            int by = y1 + (int) ((track - bar) * detailScroll / detailMax);
            g.fill(x2 - 1, by, x2, by + bar, MUTED);
        }
    }

    /** One line with a title, a quieter note under it, and a switch. A hairline closes it. */
    private int row(GuiGraphics g, int x, int y, int w, int mx, int my, Clip clip, String title, String note,
                    boolean on, Runnable action) {
        if (over(mx, my, x, y, w, 28, clip)) {
            g.fill(x, y, x + w, y + 28, HOVER);
        }
        text(g, font.plainSubstrByWidth(title, w - 40), x + 4, y + 5, on ? TEXT : MUTED);
        text(g, font.plainSubstrByWidth(note, w - 40), x + 4, y + 16, FAINT);
        toggle(g, x + w - 26, y + 9, on);
        g.fill(x, y + 28, x + w, y + 29, LINE);
        hit(x, y, w, 28, clip, action);
        return y + 29;
    }

    private int featureRow(GuiGraphics g, ModInfo m, Feature f, int x, int y, int w, int mx, int my, Clip clip) {
        boolean isOff = Choices.isOff(m.id(), f.key());
        String note = f.detail() + (f.restart() ? " Needs a restart." : "");
        return row(g, x, y, w, mx, my, clip, f.label(), note, !isOff,
                () -> Choices.setOff(m.id(), f.key(), !Choices.isOff(m.id(), f.key())));
    }

    private static String description(ModInfo m) {
        if (m.raw() != null) {
            JsonElement e = m.raw().get("description");
            if (e != null && e.isJsonPrimitive()) {
                return e.getAsString().trim();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx /= ui;
        my /= ui;
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()
                        && (h.clip() == null || h.clip().has(mx, my))) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx / ui < MARGIN + listWidth + 12) {
            listScroll = Math.max(0, Math.min(listMax, listScroll - delta * 24));
        } else {
            detailScroll = Math.max(0, Math.min(detailMax, detailScroll - delta * 24));
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
