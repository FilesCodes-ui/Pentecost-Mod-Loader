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

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Pentecost's settings, in three columns.
 * Left: pages. Middle: the page's folders as tabs, then that folder's settings.
 * Right: details for the setting under the pointer, and the page's shortcuts.
 * Video holds Embeddium's options and Shaders drives Oculus, when those mods are installed.
 */
public final class PentecostSettingsScreen extends Screen {
    private enum Kind { INFO, BUTTON, SWITCH, CHOICE, SLIDER, PICK, SKIN, KEY, HEADER }

    private record Slider(int min, int max, int step, IntSupplier value, IntConsumer set, Supplier<String> text) {}

    /** One setting. note: one line under the title. about: the full text for the details panel. */
    private record Row(Kind kind, String title, String note, String about, String when, Supplier<String> value,
                       BooleanSupplier on, Runnable action, Slider slider, BooleanSupplier enabled) {
        static Row info(String title, String value) {
            return new Row(Kind.INFO, title, "", "", "", () -> value, null, null, null, () -> true);
        }

        static Row header(String title) {
            return new Row(Kind.HEADER, title, "", "", "", null, null, null, null, () -> true);
        }

        static Row button(String title, String note, Runnable action) {
            return new Row(Kind.BUTTON, title, note, note, "", () -> "Open", null, action, null, () -> true);
        }

        static Row toggle(String title, String note, String when, BooleanSupplier on, Runnable action) {
            return new Row(Kind.SWITCH, title, note, note, when, null, on, action, null, () -> true);
        }

        static Row choice(String title, String note, String when, Supplier<String> value, Runnable action) {
            return new Row(Kind.CHOICE, title, note, note, when, value, null, action, null, () -> true);
        }

        static Row pick(String title, String note, BooleanSupplier on, Runnable action) {
            return new Row(Kind.PICK, title, note, note, "Now", null, on, action, null, () -> true);
        }
    }

    private record Folder(String name, List<Row> rows) {}

    private record Shortcut(String label, Runnable action) {}

    private record Page(String name, String about, Supplier<List<Folder>> build, List<String> planned, Supplier<List<Shortcut>> shortcuts) {}

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private record SliderSpot(int x, int w, Slider slider) {}

    private final Screen parent;
    private final List<Page> pages = new ArrayList<>();
    private final Map<Integer, List<Folder>> built = new HashMap<>();
    private final Map<Integer, Integer> folderOf = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, SliderSpot> sliderSpots = new HashMap<>();
    private int page = 1;
    private double scroll;
    private int scrollMax;
    private double lastMouseX;
    private String dragKey;
    private Row hovered;
    /** A choice waiting for the arrow to land. It applies after the glide, so the reload never freezes the arrow. */
    private Row pickPending;
    private int pickPage = -1;
    private long pickSince;
    private boolean pickReady;
    /** Embeddium's screen, built but not shown: holds its option pages and its apply step. */
    private Object embeddium;

    public PentecostSettingsScreen(Screen parent) {
        this(parent, "Interface");
    }

    public PentecostSettingsScreen(Screen parent, String startPage) {
        super(Component.literal("Pentecost settings"));
        this.parent = parent;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        pages.add(new Page("General", "Versions and folders. Everything Pentecost keeps lives in the game folder.",
                () -> List.of(new Folder("About", List.of(
                        Row.info("Pentecost", PentecostMain.VERSION),
                        Row.info("Minecraft", SharedConstants.getCurrentVersion().getName()),
                        Row.info("Java", System.getProperty("java.version"))))),
                List.of(),
                () -> List.of(
                        new Shortcut("Game folder", () -> Util.getPlatform().openFile(mc.gameDirectory)),
                        new Shortcut("Logs", () -> Util.getPlatform().openFile(new java.io.File(mc.gameDirectory, "logs"))),
                        new Shortcut("Screenshots", () -> Util.getPlatform().openFile(new java.io.File(mc.gameDirectory, "screenshots"))))));
        pages.add(new Page("Interface", "How Pentecost looks: the window, the menus, the F3 screen and the loading screen.",
                () -> List.of(
                        new Folder("Window", List.of(
                                Row.choice("Window", "Borderless covers the screen with no frame. Switching apps stays instant.", "Now",
                                        () -> WindowMode.label(Settings.get(Settings.WINDOW)),
                                        () -> WindowMode.set(WindowMode.next(Settings.get(Settings.WINDOW)))))),
                        new Folder("Menus", List.of(
                                Row.toggle("Pentecost menus", "Pentecost draws the main and pause menus.", "Now",
                                        () -> Settings.on(Settings.MENUS), () -> Settings.toggle(Settings.MENUS)),
                                Row.toggle("Restyle every screen", "Buttons, sliders, checkboxes and backgrounds everywhere, mods' too.", "Now",
                                        () -> Settings.on(Settings.RESTYLE), () -> Settings.toggle(Settings.RESTYLE)),
                                Row.toggle("Mod buttons in black and white", "Buttons mods add to the menus match the rest.", "Now",
                                        () -> Settings.on(Settings.GRAY_BUTTONS), () -> Settings.toggle(Settings.GRAY_BUTTONS)),
                                Row.toggle("Background pattern", "The slow dot pattern behind Pentecost's screens.", "Now",
                                        () -> Settings.on(Settings.PATTERN), () -> Settings.toggle(Settings.PATTERN)))),
                        new Folder("F3", List.of(
                                Row.toggle("Pentecost F3 screen", "Readable panels in place of the long text columns.", "Now",
                                        () -> Settings.on(Settings.OVERLAY), () -> Settings.toggle(Settings.OVERLAY)),
                                Row.choice("F3 detail", "Standard shows the usual facts. Full shows every line.", "Now",
                                        () -> "full".equals(Settings.get(Settings.OVERLAY_DETAIL)) ? "Full" : "Standard",
                                        () -> Settings.set(Settings.OVERLAY_DETAIL,
                                                "full".equals(Settings.get(Settings.OVERLAY_DETAIL)) ? "standard" : "full")))),
                        new Folder("Loading", List.of(
                                Row.toggle("Pentecost loading screen", "Logo, progress line, RAM gauge and time left while loading.", "Now",
                                        () -> Settings.on(Settings.LOADING), () -> Settings.toggle(Settings.LOADING))))),
                List.of("Boot splash style", "Text size"), List::of));
        pages.add(new Page("Video", "The game's video options, from Embeddium when it is installed. Changes apply at once.",
                this::videoFolders, List.of(), this::videoShortcuts));
        pages.add(new Page("Shaders", "Pick a shader pack, or Off. Pentecost drives Oculus (Forge) or Iris (Fabric).",
                this::shaderFolders, List.of(), this::shaderShortcuts));
        pages.add(new Page("Shader pack", "The options of the pack in use, grouped the way the pack groups them. "
                + "Changes wait until you press Apply, then the pack reloads once.",
                this::packFolders, List.of(), this::packShortcuts));
        pages.add(new Page("Controls", "Mouse, movement and every key bind, mods' included. Click a key, then press the new one. "
                + "Escape clears it. A ! marks a key two actions share.",
                this::controlFolders, List.of(), this::controlShortcuts));
        pages.add(new Page("Skin", "Your skin and which layers show. The skin comes from your account.",
                this::skinFolders, List.of(), List::of));
        pages.add(new Page("Resource packs", "Turn packs on or off. Shift-click a pack in use to give it more priority. "
                + "Changes apply when you press Done.",
                this::resourcePackFolders, List.of(), this::resourcePackShortcuts));
        pages.add(planned("Performance", "Launch cache", "Chunk building threads", "Far mob throttle", "Virtual threads on Java 25"));
        pages.add(planned("Java", "Memory", "Garbage collector", "Extra flags", "Java version"));
        pages.add(planned("Gameplay", "Faster mobs", "Smarter mobs", "Stall warning"));
        pages.add(planned("Building", "Block rotation", "Block outline"));
        pages.add(planned("Server", "World Wide Local Server", "Operators", "Server tools"));
        pages.add(planned("Profiles", "Save a setup", "Switch setups", "Export and import"));
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).name().equalsIgnoreCase(startPage)) {
                page = i;
            }
        }
    }

    private static Page planned(String name, String... items) {
        return new Page(name, "Coming to Pentecost.", List::of, List.of(items), List::of);
    }

    private List<Folder> folders(int index) {
        return built.computeIfAbsent(index, i -> pages.get(i).build().get());
    }

    // ---------------------------------------------------------------- controls

    private KeyMapping listening;

    private static String folderFor(String category) {
        return switch (category) {
            case "key.categories.movement" -> "Movement";
            case "key.categories.gameplay" -> "Gameplay";
            case "key.categories.inventory", "key.categories.creative" -> "Inventory";
            case "key.categories.multiplayer", "key.categories.ui", "key.categories.misc" -> "Interface";
            case "Pentecost" -> "Pentecost";
            default -> "Mods";
        };
    }

    private List<Folder> controlFolders() {
        Options o = minecraft.options;
        List<Folder> out = new ArrayList<>();
        out.add(new Folder("Mouse", List.of(
                new Row(Kind.SLIDER, "Mouse sensitivity", "How far the view turns for each move of the mouse.",
                        "How far the view turns for each move of the mouse. While you zoom, it slows down by itself.", "Now",
                        () -> (int) Math.round(o.sensitivity().get() * 200) + "%", null, null,
                        new Slider(0, 200, 1, () -> (int) Math.round(o.sensitivity().get() * 200),
                                n -> o.sensitivity().set(n / 200.0), () -> (int) Math.round(o.sensitivity().get() * 200) + "%"),
                        () -> true),
                optionToggle("Invert mouse", "Pushing the mouse forward looks down.", o.invertYMouse()),
                optionToggle("Smooth scrolling off", "Each wheel notch moves exactly one step.", o.discreteMouseScroll()),
                optionToggle("Toggle sneak", "Press once to sneak, again to stop.", o.toggleCrouch()),
                optionToggle("Toggle sprint", "Press once to sprint, again to stop.", o.toggleSprint()),
                optionToggle("Auto jump", "Step up one block without pressing jump.", o.autoJump()))));
        Map<String, Map<String, List<Row>>> groups = new LinkedHashMap<>();
        for (String name : List.of("Movement", "Gameplay", "Inventory", "Interface", "Pentecost", "Mods")) {
            groups.put(name, new LinkedHashMap<>());
        }
        for (KeyMapping k : o.keyMappings) {
            groups.get(folderFor(k.getCategory())).computeIfAbsent(k.getCategory(), c -> new ArrayList<>()).add(keyRow(k));
        }
        for (Map.Entry<String, Map<String, List<Row>>> group : groups.entrySet()) {
            List<Row> rows = new ArrayList<>();
            boolean headers = group.getKey().equals("Mods") || group.getKey().equals("Interface");
            for (Map.Entry<String, List<Row>> category : group.getValue().entrySet()) {
                if (headers) {
                    rows.add(Row.header(I18n.get(category.getKey())));
                }
                rows.addAll(category.getValue());
            }
            if (!rows.isEmpty()) {
                out.add(new Folder(group.getKey(), rows));
            }
        }
        return out;
    }

    private Row optionToggle(String title, String note, net.minecraft.client.OptionInstance<Boolean> option) {
        return Row.toggle(title, note, "Now", () -> Boolean.TRUE.equals(option.get()), () -> {
            option.set(!Boolean.TRUE.equals(option.get()));
            minecraft.options.save();
        });
    }

    private Row keyRow(KeyMapping k) {
        String about = "Default key: " + k.getDefaultKey().getDisplayName().getString()
                + ". Click, then press the new key or a mouse button. Escape clears it.";
        return new Row(Kind.KEY, I18n.get(k.getName()), "", about, "Now", () -> {
            if (listening == k) {
                return "Press a key...";
            }
            return k.getTranslatedKeyMessage().getString();
        }, () -> conflicts(k), () -> listening = listening == k ? null : k, null, () -> true);
    }

    private boolean conflicts(KeyMapping k) {
        if (k.isUnbound()) {
            return false;
        }
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != k && k.same(other)) {
                return true;
            }
        }
        return false;
    }

    private void bindListening(InputConstants.Key key) {
        KeyMapping k = listening;
        listening = null;
        if (k == null) {
            return;
        }
        minecraft.options.setKey(k, key);
        KeyMapping.resetMapping();
        minecraft.options.save();
    }

    private List<Shortcut> controlShortcuts() {
        return List.of(new Shortcut("Reset all keys", () -> {
            for (KeyMapping k : minecraft.options.keyMappings) {
                k.setKey(k.getDefaultKey());
            }
            KeyMapping.resetMapping();
            minecraft.options.save();
        }));
    }

    /** A key bind on one short line: the action on the left, the key in a box on the right. */
    private int keyRowDraw(GuiGraphics g, Row r, int x, int y, int w, int mx, int my, int top, int bottom) {
        int h = 22;
        boolean visible = y + h > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean over = visible && inside && Ui.over(mx, my, x, y, w, h);
        boolean waiting = r.value().get().startsWith("Press");
        boolean clash = r.on().getAsBoolean();
        if (over) {
            hovered = r;
            g.fill(x, y, x + w, y + h, Ui.HOVER);
        }
        g.drawString(font, font.plainSubstrByWidth(r.title(), w - 150), x + 4, y + 7, Ui.TEXT, false);
        String v = r.value().get();
        int bw = Math.max(90, Math.min(140, font.width(v) + 18));
        int bx = x + w - bw - 4;
        int color = clash ? 0xFFE08080 : waiting ? Ui.TEXT : Ui.MUTED;
        if (waiting) {
            g.fill(bx, y + 2, bx + bw, y + h - 2, Ui.TEXT);
        } else {
            Ui.outline(g, bx, y + 2, bx + bw, y + h - 2, over ? Ui.TEXT : color);
        }
        String shown = font.plainSubstrByWidth(v, bw - 10);
        g.drawString(font, shown, bx + bw / 2 - font.width(shown) / 2, y + 7,
                waiting ? Ui.BLACK : clash ? 0xFFE08080 : Ui.SOFT, false);
        if (clash) {
            g.drawString(font, "shared", bx - 6 - font.width("shared"), y + 7, 0xFFE08080, false);
        }
        g.fill(x, y + h, x + w, y + h + 1, Ui.LINE);
        if (visible && inside) {
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + h, bottom) - Math.max(y, top), r.action()));
        }
        return y + h + 1;
    }

    // ---------------------------------------------------------------- skin

    private List<Folder> skinFolders() {
        Options o = minecraft.options;
        List<Row> layers = new ArrayList<>();
        for (PlayerModelPart part : PlayerModelPart.values()) {
            layers.add(Row.toggle(part.getName().getString(), "A layer of your skin.", "Now",
                    () -> o.isModelPartEnabled(part), () -> {
                        o.toggleModelPart(part, !o.isModelPartEnabled(part));
                        o.save();
                    }));
        }
        layers.add(Row.choice("Main hand", "The hand that holds items.", "Now",
                () -> o.mainHand().get() == HumanoidArm.LEFT ? "Left" : "Right",
                () -> {
                    o.mainHand().set(o.mainHand().get().getOpposite());
                    o.save();
                }));
        Row preview = new Row(Kind.SKIN, minecraft.getUser().getName(), "", "", "", () -> minecraft.getUser().getName(),
                null, null, null, () -> true);
        return List.of(new Folder("Preview", List.of(preview)), new Folder("Layers", layers));
    }

    /** The front of the skin, drawn from the texture: head, body, arms and legs, with the outer layer on top. */
    private int skinRow(GuiGraphics g, int x, int y, int w) {
        ResourceLocation skin = AccountSkin.location();
        if (skin == null) {
            skin = minecraft.getSkinManager().getInsecureSkinLocation(minecraft.getUser().getGameProfile());
        }
        int s = 4;
        int cx = x + w / 2 - 8 * s / 2;
        RenderSystem.enableBlend();
        for (int layer = 0; layer < 2; layer++) {
            int dx = layer == 0 ? 0 : 0;
            // head, body, right arm, left arm, right leg, left leg: target x, y, size, then texture u, v
            int[][] parts = layer == 0
                    ? new int[][] {{0, 0, 8, 8, 8, 8}, {0, 8, 8, 12, 20, 20}, {-4, 8, 4, 12, 44, 20}, {8, 8, 4, 12, 36, 52},
                            {0, 20, 4, 12, 4, 20}, {4, 20, 4, 12, 20, 52}}
                    : new int[][] {{0, 0, 8, 8, 40, 8}, {0, 8, 8, 12, 20, 36}, {-4, 8, 4, 12, 44, 36}, {8, 8, 4, 12, 52, 52},
                            {0, 20, 4, 12, 4, 36}, {4, 20, 4, 12, 4, 52}};
            for (int[] p : parts) {
                g.blit(skin, cx + p[0] * s + dx, y + 8 + p[1] * s, p[2] * s, p[3] * s, p[4], p[5], p[2], p[3], 64, 64);
            }
        }
        RenderSystem.disableBlend();
        String name = minecraft.getUser().getName();
        g.drawString(font, name, x + w / 2 - font.width(name) / 2, y + 8 + 32 * s + 8, Ui.TEXT, false);
        return y + 8 + 32 * s + 28;
    }

    // ---------------------------------------------------------------- resource packs

    private net.minecraft.client.gui.screens.packs.PackSelectionModel packModel;
    private final Map<String, ResourceLocation> packIcons = new HashMap<>();
    private boolean packsChanged;

    private net.minecraft.client.gui.screens.packs.PackSelectionModel packModel() {
        if (packModel == null) {
            packModel = new net.minecraft.client.gui.screens.packs.PackSelectionModel(() -> packsChanged = true,
                    this::packIcon, minecraft.getResourcePackRepository(), repo -> minecraft.options.updateResourcePacks(repo));
        }
        return packModel;
    }

    private ResourceLocation packIcon(Pack pack) {
        return packIcons.computeIfAbsent(pack.getId(), id -> {
            ResourceLocation fallback = new ResourceLocation("textures/misc/unknown_pack.png");
            try (net.minecraft.server.packs.PackResources resources = pack.open()) {
                net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> icon = resources.getRootResource("pack.png");
                if (icon == null) {
                    return fallback;
                }
                try (java.io.InputStream in = icon.get()) {
                    com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in);
                    ResourceLocation location = new ResourceLocation("pentecost", "pack/" + Integer.toHexString(id.hashCode()) + "/icon");
                    minecraft.getTextureManager().register(location, new net.minecraft.client.renderer.texture.DynamicTexture(image));
                    return location;
                }
            } catch (java.io.IOException | RuntimeException e) {
                return fallback;
            }
        });
    }

    private List<Folder> resourcePackFolders() {
        return List.of(new Folder("Packs", List.of()));
    }

    private List<Shortcut> resourcePackShortcuts() {
        return List.of(
                new Shortcut("Pack folder", () -> Util.getPlatform().openFile(minecraft.getResourcePackDirectory().toFile())),
                new Shortcut("Refresh list", () -> {
                    if (packsChanged) {
                        applyPacks();
                    }
                    minecraft.getResourcePackRepository().reload();
                    packModel = null;
                }));
    }

    private void applyPacks() {
        if (!packsChanged || packModel == null) {
            return;
        }
        packsChanged = false;
        packModel.commit();
    }

    /** Two columns like the game's own screen: packs you can add on the left, packs in use on the right. */
    private int packColumns(GuiGraphics g, int x, int y, int w, int mx, int my, int top, int bottom) {
        var model = packModel();
        int gap = 16;
        int colW = (w - gap) / 2;
        int rx = x + colW + gap;
        List<net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry> left = model.getUnselected().toList();
        List<net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry> right = model.getSelected().toList();
        g.drawString(font, "AVAILABLE", x + 4, y, Ui.FAINT, false);
        g.drawString(font, "IN USE  (the top one wins)", rx + 4, y, Ui.FAINT, false);
        int ly = y + 16;
        int ry = y + 16;
        if (left.isEmpty()) {
            g.drawString(font, "No more packs. Put packs in the pack folder.", x + 4, ly, Ui.MUTED, false);
            ly += 14;
        }
        for (var e : left) {
            ly = packCard(g, e, x, ly, colW, mx, my, top, bottom, false);
        }
        for (var e : right) {
            ry = packCard(g, e, rx, ry, colW, mx, my, top, bottom, true);
        }
        return Math.max(ly, ry);
    }

    private int packCard(GuiGraphics g, net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry e, int x, int y, int w,
                         int mx, int my, int top, int bottom, boolean inUse) {
        int h = 46;
        boolean visible = y + h > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean over = visible && inside && Ui.over(mx, my, x, y, w, h);
        boolean canAdd = !inUse && e.canSelect();
        if (over && canAdd) {
            g.fill(x, y, x + w, y + h, Ui.HOVER);
        }
        Ui.outline(g, x, y, x + w, y + h, over && canAdd ? Ui.MUTED : 0x33FFFFFF);
        if (visible) {
            RenderSystem.enableBlend();
            g.blit(e.getIconTexture(), x + 6, y + 7, 0, 0, 32, 32, 32, 32);
            RenderSystem.disableBlend();
        }
        int tx = x + 46;
        int room = w - 52 - (inUse ? 48 : 14);
        g.drawString(font, font.plainSubstrByWidth(e.getTitle().getString(), room), tx, y + 7, Ui.TEXT, false);
        List<String> desc = Ui.wrap(font, e.getDescription().getString().replace('\n', ' '), w - 52);
        for (int i = 0; i < Math.min(2, desc.size()); i++) {
            String line = desc.get(i);
            if (i == 1 && desc.size() > 2) {
                line = font.plainSubstrByWidth(line, w - 64) + "...";
            }
            g.drawString(font, line, tx, y + 19 + i * 10, Ui.MUTED, false);
        }
        if (!e.getCompatibility().isCompatible()) {
            String warn = "Other version";
            g.drawString(font, warn, x + w - 8 - font.width(warn), y + h - 11, 0xFFE0B080, false);
        }
        if (inUse) {
            int bx = x + w - 18;
            if (packButton(g, "x", bx, y + 5, mx, my, e.canUnselect(), visible && inside, top, bottom, e::unselect)) {
                // drawn
            }
            packButton(g, "v", bx - 16, y + 5, mx, my, e.canMoveDown(), visible && inside, top, bottom, e::moveDown);
            packButton(g, "^", bx - 32, y + 5, mx, my, e.canMoveUp(), visible && inside, top, bottom, e::moveUp);
        } else {
            g.drawString(font, ">", x + w - 14, y + 7, canAdd ? Ui.SOFT : Ui.FAINT, false);
            if (canAdd && visible && inside) {
                hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + h, bottom) - Math.max(y, top), e::select));
            }
        }
        return y + h + 4;
    }

    private boolean packButton(GuiGraphics g, String label, int x, int y, int mx, int my, boolean enabled, boolean live,
                               int top, int bottom, Runnable action) {
        boolean hover = enabled && live && Ui.over(mx, my, x, y, 14, 14);
        if (hover) {
            g.fill(x, y, x + 14, y + 14, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + 14, y + 14, enabled ? 0x66FFFFFF : 0x22FFFFFF);
        }
        g.drawString(font, label, x + 7 - font.width(label) / 2, y + 3, hover ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
        if (enabled && live && y + 14 > top && y < bottom) {
            hits.add(new Hit(x, y, 14, 14, action));
        }
        return enabled;
    }

    // ---------------------------------------------------------------- video and shaders

    private List<Folder> videoFolders() {
        List<Folder> out = new ArrayList<>();
        if (!ExternalOptions.hasEmbeddium()) {
            out.add(new Folder("Video", List.of(Row.button("Video settings", "The game's own video settings.",
                    () -> minecraft.setScreen(new net.minecraft.client.gui.screens.VideoSettingsScreen(this, minecraft.options))))));
            return out;
        }
        embeddium = ExternalOptions.embeddiumScreen(this);
        for (ExternalOptions.VideoPage vp : ExternalOptions.pages(embeddium)) {
            List<Row> rows = new ArrayList<>();
            for (ExternalOptions.VideoOption v : vp.options()) {
                rows.add(videoRow(v));
            }
            // Pages without options (Oculus's "Shader Packs" link) are left out: Shaders has its own page here.
            if (!rows.isEmpty()) {
                out.add(new Folder(vp.name(), rows));
            }
        }
        return out;
    }

    private List<Shortcut> videoShortcuts() {
        return ExternalOptions.hasEmbeddium()
                ? List.of(new Shortcut("Embeddium's own screen", () -> ExternalOptions.openEmbeddium(this)))
                : List.of();
    }

    private Row videoRow(ExternalOptions.VideoOption v) {
        String when = v.restart() ? "Restart" : "Now";
        BooleanSupplier enabled = () -> ExternalOptions.available(v);
        return switch (v.kind()) {
            case "switch" -> new Row(Kind.SWITCH, v.name(), v.tooltip(), v.about(), when, null,
                    () -> Boolean.TRUE.equals(ExternalOptions.value(v)),
                    () -> ExternalOptions.set(embeddium, v, !Boolean.TRUE.equals(ExternalOptions.value(v))), null, enabled);
            case "choice" -> new Row(Kind.CHOICE, v.name(), v.tooltip(), v.about(), when, () -> ExternalOptions.choiceText(v), null,
                    () -> ExternalOptions.nextChoice(embeddium, v, hasShiftDown()), null, enabled);
            case "slider" -> {
                int[] range = ExternalOptions.sliderRange(v);
                yield new Row(Kind.SLIDER, v.name(), v.tooltip(), v.about(), when, () -> ExternalOptions.sliderText(v), null, null,
                        new Slider(range[0], range[1], Math.max(1, range[2]),
                                () -> ExternalOptions.value(v) instanceof Integer n ? n : range[0],
                                n -> ExternalOptions.setOnly(v, n),
                                () -> ExternalOptions.sliderText(v)), enabled);
            }
            default -> new Row(Kind.INFO, v.name(), v.tooltip(), v.about(), when, () -> String.valueOf(ExternalOptions.value(v)),
                    null, null, null, enabled);
        };
    }

    private List<Folder> shaderFolders() {
        List<Row> rows = new ArrayList<>();
        if (!ExternalOptions.hasShaders()) {
            rows.add(Row.info("Shaders", "Install Oculus (Forge) or Iris (Fabric)"));
            return List.of(new Folder("Packs", rows));
        }
        rows.add(Row.pick("Off", "No shader pack. The game's own look.", () -> !ExternalOptions.shadersOn(),
                () -> ExternalOptions.setShaders(false)));
        if (ExternalOptions.installBundledPack()) {
            rows.add(Row.pick("Pentecost shaders", "Made for Pentecost. Sun and moon shadows, warm torches, softer colors.",
                    () -> ExternalOptions.shadersOn() && ExternalOptions.BUNDLED_PACK.equals(ExternalOptions.currentPack()),
                    ExternalOptions::usePentecostPack));
        }
        for (String pack : ExternalOptions.packs()) {
            if (ExternalOptions.BUNDLED_PACK.equals(pack)) {
                continue;
            }
            String shown = pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
            rows.add(Row.pick(shown, pack.endsWith(".zip") ? "Zip pack." : "Folder pack.",
                    () -> ExternalOptions.shadersOn() && pack.equals(ExternalOptions.currentPack()),
                    () -> ExternalOptions.usePack(pack)));
        }
        return List.of(new Folder("Packs", rows));
    }

    private void openPage(String name) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).name().equals(name)) {
                built.remove(i);
                if (name.equals("Shader pack")) {
                    packPath.clear();
                    packPath.add(0);
                }
                page = i;
                scroll = 0;
                return;
            }
        }
    }

    /** Last profile picked on the Shader pack page, shown until the pack changes. */
    private String shaderProfile = "Pick one";

    /** The shader pack's groups, read when the page is built. */
    private List<ExternalOptions.ShaderScreen> packScreens = List.of();
    /** Where the player is in the pack's groups, like folders on a computer. First entry: the main group. */
    private final List<Integer> packPath = new ArrayList<>(List.of(0));

    /** The names along the path, for the line at the top of the page. */
    private List<String> packCrumbs() {
        List<String> out = new ArrayList<>();
        for (int i : packPath) {
            if (i >= 0 && i < packScreens.size()) {
                out.add(packScreens.get(i).title());
            }
        }
        return out;
    }

    /** Goes to a group on the path (depth 0 is the main group). */
    private void packGoTo(int depth) {
        while (packPath.size() > depth + 1) {
            packPath.remove(packPath.size() - 1);
        }
        built.remove(page);
        scroll = 0;
    }

    private List<Folder> packFolders() {
        List<Folder> out = new ArrayList<>();
        if (!ExternalOptions.hasShaders()) {
            out.add(new Folder("Pack", List.of(Row.info("Shaders", "Install Oculus (Forge) or Iris (Fabric)"))));
            return out;
        }
        packScreens = ExternalOptions.shaderMenu();
        if (packScreens.isEmpty()) {
            packPath.clear();
            packPath.add(0);
            out.add(new Folder("Pack", List.of(Row.info("No pack in use", "Pick one on the Shaders page"))));
            return out;
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < packScreens.size(); i++) {
            index.put(packScreens.get(i).id(), i);
        }
        packPath.removeIf(i -> i < 0 || i >= packScreens.size());
        if (packPath.isEmpty()) {
            packPath.add(0);
        }
        ExternalOptions.ShaderScreen sc = packScreens.get(packPath.get(packPath.size() - 1));
        // Groups first, like folders above files, then the options in the pack's own order.
        List<Row> groups = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (ExternalOptions.ShaderEntry en : sc.entries()) {
            String about = en.about().isEmpty() ? "An option of this shader pack." : en.about();
            switch (en.kind()) {
                case "bool" -> rows.add(new Row(Kind.SWITCH, en.label(), firstLine(about), about, "On apply", null,
                        () -> "true".equals(ExternalOptions.shaderValue(en.id(), true)),
                        () -> ExternalOptions.setShaderValue(en.id(),
                                "true".equals(ExternalOptions.shaderValue(en.id(), true)) ? "false" : "true"),
                        null, () -> true));
                case "string" -> rows.add(new Row(Kind.CHOICE, en.label(), firstLine(about), about, "On apply",
                        () -> ExternalOptions.shaderValueLabel(en.id(), ExternalOptions.shaderValue(en.id(), false)),
                        null, () -> {
                            List<String> values = en.values();
                            if (values.isEmpty()) {
                                return;
                            }
                            int at = values.indexOf(ExternalOptions.shaderValue(en.id(), false));
                            ExternalOptions.setShaderValue(en.id(), values.get((at + 1) % values.size()));
                        }, null, () -> true));
                case "link" -> {
                    Integer target = index.get(en.id());
                    if (target != null && !packPath.contains(target)) {
                        String note = en.about().isEmpty() ? "A group of options." : firstLine(en.about());
                        groups.add(new Row(Kind.BUTTON, en.label(), note,
                                en.about().isEmpty() ? "Opens this group of options." : en.about(), "", () -> "Open  >", null,
                                () -> {
                                    packPath.add(target);
                                    built.remove(page);
                                    scroll = 0;
                                }, null, () -> true));
                    }
                }
                case "profile" -> rows.add(0, new Row(Kind.CHOICE, en.label(), firstLine(about), about, "On apply",
                        () -> shaderProfile, null, () -> {
                            List<String> names = en.values();
                            int at = names.indexOf(shaderProfile);
                            shaderProfile = names.get((at + 1) % names.size());
                            ExternalOptions.useShaderProfile(shaderProfile);
                        }, null, () -> true));
                default -> { }
            }
        }
        List<Row> all = new ArrayList<>(groups);
        all.addAll(rows);
        if (all.isEmpty()) {
            all.add(Row.info("This group", "Has no options"));
        }
        out.add(new Folder(sc.title(), all));
        return out;
    }

    private static String firstLine(String s) {
        int dot = s.indexOf(". ");
        String line = dot > 0 ? s.substring(0, dot + 1) : s;
        return line.length() > 90 ? line.substring(0, 87) + "..." : line;
    }

    private List<Shortcut> packShortcuts() {
        if (!ExternalOptions.hasShaders()) {
            return List.of();
        }
        int waiting = ExternalOptions.pendingShaderChanges();
        List<Shortcut> out = new ArrayList<>();
        out.add(new Shortcut(waiting > 0 ? "Apply " + waiting + (waiting == 1 ? " change" : " changes") : "Apply", () -> {
            ExternalOptions.applyShaderChanges();
            built.remove(page);
        }));
        if (waiting > 0) {
            out.add(new Shortcut("Undo changes", () -> {
                ExternalOptions.discardShaderChanges();
                built.remove(page);
            }));
        }
        out.add(new Shortcut("Reset to defaults", () -> {
            ExternalOptions.resetShaderOptions();
            shaderProfile = "Pick one";
            built.remove(page);
        }));
        if (packPath.size() > 1) {
            out.add(new Shortcut("Up one level", () -> packGoTo(packPath.size() - 2)));
        }
        out.add(new Shortcut("Back to packs", () -> openPage("Shaders")));
        return out;
    }

    private List<Shortcut> shaderShortcuts() {
        if (!ExternalOptions.hasShaders()) {
            return List.of();
        }
        return List.of(
                new Shortcut("Use Pentecost shaders", () -> {
                    ExternalOptions.usePentecostPack();
                    built.remove(page);
                }),
                new Shortcut("Pack settings", () -> openPage("Shader pack")),
                new Shortcut("Shader folder", () -> {
                    java.nio.file.Path dir = ExternalOptions.packFolder();
                    if (dir != null) {
                        Util.getPlatform().openFile(dir.toFile());
                    }
                }),
                new Shortcut("Refresh list", () -> built.remove(page)));
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void onClose() {
        runPendingPick();
        applyPacks();
        minecraft.setScreen(parent);
    }

    /** Marks a choice. The arrow moves now, the choice applies a frame after the arrow settles. */
    private void choosePick(Row r) {
        pickPending = r;
        pickPage = page;
        pickSince = System.nanoTime();
        pickReady = false;
    }

    private void runPendingPick() {
        Row r = pickPending;
        pickPending = null;
        pickPage = -1;
        pickReady = false;
        if (r != null && r.action() != null) {
            r.action().run();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (pickPending != null && (pickReady || pickPage != page
                || System.nanoTime() - pickSince > 600_000_000L)) {
            runPendingPick();
        }
        g.fill(0, 0, width, height, minecraft.level == null ? 0xFF0A0A0B : 0xD80A0A0B);
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        sliderSpots.clear();
        hovered = null;
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int margin = 48;
        int y = 26;
        Ui.logo(g, margin, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, "SETTINGS", margin + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        String version = "Pentecost " + PentecostMain.VERSION;
        g.drawString(font, version, vw - margin - font.width(version), y + 18, Ui.FAINT, false);
        y += 28 + 12;
        g.fill(margin, y, vw - margin, y + 1, Ui.LINE);
        int top = y + 16;
        int bottom = vh - 44;

        // Left: pages.
        int sideW = 118;
        int sy = top;
        for (int i = 0; i < pages.size(); i++) {
            boolean active = i == page;
            boolean hover = Ui.over(mx, my, margin - 6, sy, sideW, 18);
            if (hover && !active) {
                g.fill(margin - 6, sy, margin - 6 + sideW, sy + 18, Ui.HOVER);
            }
            sy += 20;
        }
        // The chosen page's bar glides between pages.
        float barY = Ui.ease("settings:pagebar", top + page * 20, 16f);
        g.fill(margin - 6, Math.round(barY), margin - 6 + sideW, Math.round(barY) + 18, Ui.TEXT);
        sy = top;
        for (int i = 0; i < pages.size(); i++) {
            boolean active = i == page;
            boolean under = Math.abs(barY - sy) < 9;
            g.drawString(font, (active ? "> " : "") + pages.get(i).name(), margin, sy + 5,
                    under ? Ui.BLACK : Ui.over(mx, my, margin - 6, sy, sideW, 18) ? Ui.TEXT : Ui.MUTED, false);
            int index = i;
            hits.add(new Hit(margin - 6, sy, sideW, 18, () -> {
                page = index;
                scroll = 0;
            }));
            sy += 20;
        }

        // Right: details and shortcuts, when there is room.
        int rightW = vw >= 820 ? 220 : 0;
        int rx = vw - margin - rightW;
        int cx = margin + sideW + 22;
        int cw = (rightW > 0 ? rx - 22 : vw - margin) - cx;

        // Middle: folders as tabs, then rows.
        Page p = pages.get(page);
        List<Folder> folders = folders(page);
        int folder = Math.min(folderOf.getOrDefault(page, 0), Math.max(0, folders.size() - 1));
        int rowsTop = top;
        if (folders.size() > 1) {
            int tx = cx;
            int ty = top;
            int pickedX = cx;
            int pickedW = 0;
            int pickedY = ty;
            for (int i = 0; i < folders.size(); i++) {
                String name = folders.get(i).name();
                int w = font.width(name);
                if (tx + w > cx + cw) {
                    tx = cx;
                    ty += 18;
                }
                boolean active = i == folder;
                boolean hover = Ui.over(mx, my, tx - 4, ty - 4, w + 8, 16);
                g.drawString(font, name, tx, ty, active ? Ui.TEXT : hover ? Ui.SOFT : Ui.MUTED, false);
                if (active) {
                    pickedX = tx;
                    pickedW = w;
                    pickedY = ty;
                }
                int index = i;
                hits.add(new Hit(tx - 4, ty - 4, w + 8, 16, () -> {
                    folderOf.put(page, index);
                    scroll = 0;
                }));
                tx += w + 16;
            }
            // The underline glides to the chosen folder.
            float ux = Ui.ease("settings:tabx:" + page, pickedX, 16f);
            float uw = Ui.ease("settings:tabw:" + page, pickedW, 16f);
            float uy = Ui.ease("settings:taby:" + page, pickedY, 16f);
            g.fill(Math.round(ux), Math.round(uy) + 11, Math.round(ux + uw), Math.round(uy) + 12, Ui.TEXT);
            rowsTop = ty + 22;
            g.fill(cx, rowsTop - 6, cx + cw, rowsTop - 5, Ui.LINE);
        }

        if (folders.size() <= 1 && "Shader pack".equals(pages.get(page).name()) && !packCrumbs().isEmpty()) {
            List<String> crumbs = packCrumbs();
            int tx = cx;
            int ty = top;
            int sep = font.width("  /  ");
            for (int i = 0; i < crumbs.size(); i++) {
                String name = crumbs.get(i);
                int w = font.width(name);
                if (tx + w > cx + cw) {
                    tx = cx;
                    ty += 18;
                }
                boolean last = i == crumbs.size() - 1;
                boolean hover = !last && Ui.over(mx, my, tx - 4, ty - 4, w + 8, 16);
                g.drawString(font, name, tx, ty, last ? Ui.TEXT : hover ? Ui.SOFT : Ui.MUTED, false);
                if (last) {
                    g.fill(tx, ty + 11, tx + w, ty + 12, Ui.TEXT);
                } else {
                    int depth = i;
                    hits.add(new Hit(tx - 4, ty - 4, w + 8, 16, () -> packGoTo(depth)));
                    g.drawString(font, "  /  ", tx + w, ty, Ui.FAINT, false);
                }
                tx += w + sep;
            }
            rowsTop = ty + 22;
            g.fill(cx, rowsTop - 6, cx + cw, rowsTop - 5, Ui.LINE);
        }
        g.enableScissor((int) (cx * ui), (int) (rowsTop * ui), (int) ((cx + cw + 4) * ui), (int) (bottom * ui));
        int py = rowsTop - (int) scroll;
        boolean packPage = "Resource packs".equals(p.name());
        List<Row> rows = packPage || folders.isEmpty() ? List.of() : folders.get(folder).rows();
        if (packPage) {
            py = packColumns(g, cx, py, cw, mx, my, rowsTop, bottom);
        }
        boolean picks = rows.stream().anyMatch(r -> r.kind() == Kind.PICK);
        int pickTarget = -1;
        for (Row r : rows) {
            if (r.kind() == Kind.PICK) {
                boolean chosen = pickPending != null && pickPage == page
                        ? pickPending.title().equals(r.title()) : r.on().getAsBoolean();
                if (chosen) {
                    pickTarget = py;
                }
                py = pick(g, r, cx, py, cw, mx, my, rowsTop, bottom);
            } else {
                py = row(g, r, cx, py, cw, mx, my, rowsTop, bottom);
            }
        }
        if (picks && pickTarget >= 0) {
            // The arrow glides to the chosen button.
            float eased = Ui.ease("settings:pick:" + page, pickTarget + (int) scroll, 14f);
            float ay = eased - (int) scroll;
            arrow(g, cx + 10, Math.round(ay) + 12, Ui.TEXT);
            if (pickPending != null && (Math.abs(eased - (pickTarget + (int) scroll)) < 0.6f
                    || System.nanoTime() - pickSince > 400_000_000L)) {
                pickReady = true;
            }
        }
        if (!p.planned().isEmpty()) {
            g.drawString(font, "COMING", cx, py + 4, Ui.FAINT, false);
            py += 18;
            for (String s : p.planned()) {
                g.drawString(font, s, cx + 4, py, Ui.MUTED, false);
                py += 14;
            }
        }
        g.disableScissor();
        scrollMax = Math.max(0, py + (int) scroll - bottom + 8);
        scroll = Math.max(0, Math.min(scrollMax, scroll));
        if (scrollMax > 0) {
            int track = bottom - rowsTop;
            int bar = Math.max(16, track * track / (track + scrollMax));
            int by = rowsTop + (int) ((track - bar) * scroll / scrollMax);
            g.fill(cx + cw + 6, by, cx + cw + 7, by + bar, Ui.MUTED);
        }

        if (rightW > 0) {
            details(g, p, rx, top, rightW, bottom, mx, my);
        }

        // Done, bottom right.
        String done = "Done";
        int dw = font.width(done) + 24;
        int dx = vw - margin - dw;
        int dy = vh - 32;
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

    /** A small pixel arrow pointing right. */
    private static void arrow(GuiGraphics g, int x, int y, int color) {
        for (int r = 0; r < 7; r++) {
            int w = r <= 3 ? r + 1 : 7 - r;
            g.fill(x, y - 3 + r, x + w, y - 2 + r, color);
        }
    }

    /** Right column: what the hovered setting does, then the page's shortcuts. */
    private void details(GuiGraphics g, Page p, int x, int y, int w, int bottom, int mx, int my) {
        g.fill(x, y, x + w, bottom, 0x660A0A0B);
        Ui.outline(g, x, y, x + w, bottom, Ui.LINE);
        int ix = x + 12;
        int iw = w - 24;
        int ty = y + 12;
        g.drawString(font, "DETAILS", ix, ty, Ui.FAINT, false);
        ty += 16;
        if (hovered != null) {
            for (String line : Ui.wrap(font, hovered.title(), iw)) {
                g.drawString(font, line, ix, ty, Ui.TEXT, false);
                ty += 11;
            }
            ty += 4;
            if (!hovered.when().isEmpty()) {
                String when = "Applies " + switch (hovered.when()) {
                    case "Now" -> "right away";
                    case "Done" -> "when you press Done";
                    case "On apply" -> "when you press Apply";
                    default -> "after a restart";
                };
                g.drawString(font, when, ix, ty, Ui.SOFT, false);
                ty += 14;
            }
            String about = hovered.about().isEmpty() ? hovered.note() : hovered.about();
            for (String line : Ui.wrap(font, about, iw)) {
                if (ty > bottom - 80) {
                    break;
                }
                g.drawString(font, line, ix, ty, Ui.MUTED, false);
                ty += 11;
            }
        } else {
            for (String line : Ui.wrap(font, p.about(), iw)) {
                g.drawString(font, line, ix, ty, Ui.MUTED, false);
                ty += 11;
            }
        }
        List<Shortcut> shortcuts = p.shortcuts().get();
        if (!shortcuts.isEmpty()) {
            int sy = bottom - 12 - shortcuts.size() * 22;
            g.drawString(font, "SHORTCUTS", ix, sy - 14, Ui.FAINT, false);
            for (Shortcut s : shortcuts) {
                boolean hover = Ui.over(mx, my, ix, sy, iw, 18);
                if (hover) {
                    g.fill(ix, sy, ix + iw, sy + 18, Ui.TEXT);
                } else {
                    Ui.outline(g, ix, sy, ix + iw, sy + 18, 0x47FFFFFF);
                }
                g.drawString(font, s.label(), ix + 8, sy + 5, hover ? Ui.BLACK : Ui.TEXT, false);
                hits.add(new Hit(ix, sy, iw, 18, () -> {
                    s.action().run();
                }));
                sy += 22;
            }
        }
    }

    /** A choice drawn as a button. The arrow marks the one in use. */
    private int pick(GuiGraphics g, Row r, int x, int y, int w, int mx, int my, int top, int bottom) {
        boolean visible = y + 24 > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean hover = visible && inside && Ui.over(mx, my, x, y, w, 24);
        boolean on = pickPending != null && pickPage == page
                ? pickPending.title().equals(r.title()) : r.on().getAsBoolean();
        if (hover) {
            hovered = r;
            g.fill(x, y, x + w, y + 24, Ui.HOVER);
        }
        Ui.outline(g, x, y, x + w, y + 24, on ? Ui.TEXT : hover ? Ui.MUTED : 0x33FFFFFF);
        g.drawString(font, r.title(), x + 24, y + 8, on ? Ui.TEXT : Ui.SOFT, false);
        if (on) {
            String tag = "In use";
            g.drawString(font, tag, x + w - 10 - font.width(tag), y + 8, Ui.FAINT, false);
        }
        if (visible && inside) {
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + 24, bottom) - Math.max(y, top), () -> choosePick(r)));
        }
        return y + 28;
    }

    /**
     * One setting row. The title and the control share the top line; the description wraps across
     * the whole row under them, and the row grows to fit it.
     */
    private int row(GuiGraphics g, Row r, int x, int y, int w, int mx, int my, int top, int bottom) {
        if (r.kind() == Kind.SKIN) {
            return skinRow(g, x, y, w);
        }
        if (r.kind() == Kind.KEY) {
            return keyRowDraw(g, r, x, y, w, mx, my, top, bottom);
        }
        if (r.kind() == Kind.HEADER) {
            g.drawString(font, r.title().toUpperCase(Locale.ROOT), x + 4, y + 10, Ui.FAINT, false);
            return y + 24;
        }
        List<String> noteLines = r.note().isEmpty() ? List.of() : Ui.wrap(font, r.note(), w - 12);
        if (noteLines.size() > 3) {
            noteLines = new ArrayList<>(noteLines.subList(0, 3));
            String last = noteLines.get(2);
            noteLines.set(2, font.plainSubstrByWidth(last, w - 24) + "...");
        }
        int h = Math.max(28, 24 + noteLines.size() * 10 + 4);
        boolean enabled = r.enabled().getAsBoolean();
        boolean visible = y + h > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean over = visible && inside && Ui.over(mx, my, x, y, w, h);
        if (over) {
            hovered = r;
        }
        boolean clickable = enabled && visible && (r.action() != null || r.slider() != null);
        if (over && clickable && r.kind() != Kind.SLIDER) {
            g.fill(x, y, x + w, y + h, Ui.HOVER);
        }
        g.drawString(font, r.title(), x + 4, y + 7, enabled ? Ui.TEXT : Ui.FAINT, false);
        int right = x + w - 6;
        String key = pages.get(page).name() + ":" + r.title();
        switch (r.kind()) {
            case SWITCH -> {
                int sx = right - 24;
                Ui.toggle(g, "settings:" + key, sx, y + 6, r.on().getAsBoolean() && enabled);
                right = sx - 10;
            }
            case SLIDER -> {
                Slider s = r.slider();
                int bw = Math.min(160, w / 3);
                int bx = right - bw;
                int value = s.value().getAsInt();
                float target = s.max() > s.min() ? (value - s.min()) / (float) (s.max() - s.min()) : 0f;
                float t = Ui.ease("settings:slider:" + key, target * 1000f, 20f) / 1000f;
                g.fill(bx, y + 11, bx + bw, y + 12, Ui.FAINT);
                g.fill(bx, y + 11, bx + Math.round(bw * t), y + 12, enabled ? Ui.TEXT : Ui.FAINT);
                int kx = bx + Math.round((bw - 4) * t);
                boolean active = key.equals(dragKey);
                g.fill(kx, y + 6, kx + 4, y + 17, enabled && (over || active) ? Ui.TEXT : Ui.SOFT);
                String text = s.text().get();
                g.drawString(font, text, bx - 10 - font.width(text), y + 7, enabled ? Ui.SOFT : Ui.FAINT, false);
                right = bx - 20 - font.width(text);
                if (clickable && inside) {
                    sliderSpots.put(key, new SliderSpot(bx, bw, s));
                    hits.add(new Hit(bx - 6, y, bw + 12, Math.min(h, 24), () -> {
                        dragKey = key;
                        slide(sliderSpots.get(key));
                    }));
                }
            }
            case CHOICE, BUTTON -> {
                String v = r.value().get();
                int vwid = font.width(v);
                boolean hot = over && clickable;
                if (hot) {
                    g.fill(right - vwid - 14, y + 3, right, y + 19, Ui.TEXT);
                } else {
                    Ui.outline(g, right - vwid - 14, y + 3, right, y + 19, enabled ? 0x66FFFFFF : 0x22FFFFFF);
                }
                g.drawString(font, v, right - vwid - 7, y + 7, hot ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
                right -= vwid + 24;
            }
            case INFO -> {
                if (r.value() != null) {
                    String v = r.value().get();
                    g.drawString(font, v, right - font.width(v), y + 7, Ui.SOFT, false);
                    right -= font.width(v) + 10;
                }
            }
            default -> { }
        }
        if ("Restart".equals(r.when())) {
            int tw = font.width("Restart");
            Ui.outline(g, right - tw - 8, y + 5, right, y + 17, Ui.LINE);
            g.drawString(font, "Restart", right - tw - 4, y + 7, Ui.FAINT, false);
        }
        int ny = y + 21;
        for (String line : noteLines) {
            g.drawString(font, line, x + 4, ny, Ui.FAINT, false);
            ny += 10;
        }
        g.fill(x, y + h, x + w, y + h + 1, Ui.LINE);
        if (clickable && r.action() != null && inside) {
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + h, bottom) - Math.max(y, top), r.action()));
        }
        return y + h + 1;
    }

    // ---------------------------------------------------------------- input

    private void slide(SliderSpot spot) {
        if (spot == null) {
            return;
        }
        Slider s = spot.slider();
        float ui = Ui.scale(minecraft);
        double mx = lastMouseX / ui;
        float t = (float) Math.max(0, Math.min(1, (mx - spot.x()) / spot.w()));
        int raw = s.min() + Math.round(t * (s.max() - s.min()));
        int snapped = s.min() + Math.round((raw - s.min()) / (float) s.step()) * s.step();
        snapped = Math.max(s.min(), Math.min(s.max(), snapped));
        if (snapped != s.value().getAsInt()) {
            s.set().accept(snapped);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            bindListening(keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE ? InputConstants.UNKNOWN
                    : InputConstants.getKey(keyCode, scanCode));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (listening != null && button != 0) {
            bindListening(InputConstants.Type.MOUSE.getOrCreate(button));
            return true;
        }
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
        if (dragKey != null) {
            lastMouseX = mouseX;
            slide(sliderSpots.get(dragKey));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragKey != null) {
            // Sliders apply once, when let go, so a long drag does not reload chunks at every step.
            if (embeddium != null) {
                ExternalOptions.apply(embeddium);
            }
            minecraft.options.save();
        }
        dragKey = null;
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
