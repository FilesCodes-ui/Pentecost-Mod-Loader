package khi.pentecost.game;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.adapter.fabric.FabricLoaderImpl;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

/** One list of every mod, whatever loader it was written for. */
public final class PentecostModsScreen extends Screen {
    private record Icon(ResourceLocation texture, int width, int height) {}

    private static final Map<String, Optional<Icon>> ICONS = new HashMap<>();

    private final Screen parent;
    private final Map<String, Optional<Function<Screen, Screen>>> configs = new HashMap<>();
    private ModList list;
    private EditBox search;
    private Button configButton;
    private ModInfo selected;
    private boolean showLibraries;
    private int listWidth;

    public PentecostModsScreen(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        listWidth = Math.max(170, Math.min(220, width / 3));
        String typed = search == null ? "" : search.getValue();
        list = new ModList(minecraft, listWidth, height, 46, height - 34);
        list.setLeftPos(4);
        addRenderableWidget(list);

        search = new EditBox(font, 6, 24, listWidth - 4, 16, Component.literal("Search mods"));
        search.setHint(Component.literal("Search mods"));
        search.setValue(typed);
        search.setResponder(text -> refill());
        addRenderableWidget(search);

        int y = height - 26;
        addRenderableWidget(Button.builder(libraryLabel(), b -> {
            showLibraries = !showLibraries;
            b.setMessage(libraryLabel());
            refill();
        }).bounds(4, y, listWidth, 20).build());

        int right = width - listWidth - 16;
        int third = Math.max(60, (right - 8) / 3);
        int x = listWidth + 12;
        configButton = addRenderableWidget(Button.builder(Component.literal("Config"), b -> openConfig())
                .bounds(x, y, third, 20).build());
        addRenderableWidget(Button.builder(routeLabel(), b -> {
            List<ModsScreens.Provider> all = ModsScreens.providers();
            int at = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(ModsScreens.chosen().id())) {
                    at = i;
                }
            }
            ModsScreens.choose(all.get((at + 1) % all.size()).id());
            b.setMessage(routeLabel());
        }).bounds(x + third + 4, y, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x + (third + 4) * 2, y, third, 20).build());

        refill();
    }

    private Component libraryLabel() {
        return Component.literal(showLibraries ? "Libraries: shown" : "Libraries: hidden");
    }

    private static Component routeLabel() {
        return Component.literal("Opens: " + ModsScreens.chosen().label());
    }

    private static boolean isLibrary(ModInfo m) {
        if (m.loader() == LoaderKind.LIBRARY) {
            return true;
        }
        String id = m.id();
        boolean fabricModule = (id.startsWith("fabric-") || id.startsWith("fabric_"))
                && !id.equals("fabric-api") && !id.equals("fabric_api");
        return fabricModule || id.equals("mixinextras");
    }

    private void refill() {
        String query = search.getValue().toLowerCase(Locale.ROOT).trim();
        List<ModInfo> shown = new ArrayList<>();
        for (ModInfo m : PentecostRuntime.mods) {
            if (!showLibraries && isLibrary(m)) {
                continue;
            }
            if (query.isEmpty() || m.name().toLowerCase(Locale.ROOT).contains(query)
                    || m.id().toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(m);
            }
        }
        shown.sort(Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT)));
        list.fill(shown);
        if (selected == null || !shown.contains(selected)) {
            select(shown.isEmpty() ? null : shown.get(0));
        }
    }

    private void select(ModInfo m) {
        selected = m;
        configButton.active = m != null && configFor(m).isPresent();
    }

    private void openConfig() {
        if (selected != null) {
            configFor(selected).ifPresent(f -> minecraft.setScreen(f.apply(this)));
        }
    }

    // ---------------------------------------------------------------- config screens

    private Optional<Function<Screen, Screen>> configFor(ModInfo m) {
        return configs.computeIfAbsent(m.id(), id -> {
            try {
                return m.loader() == LoaderKind.FORGE ? forgeConfig(m) : fabricConfig(m);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        });
    }

    /** Forge mods register a config screen with Forge. Asked through reflection: Forge may be absent. */
    @SuppressWarnings("unchecked")
    private Optional<Function<Screen, Screen>> forgeConfig(ModInfo m) throws ReflectiveOperationException {
        if (!PentecostRuntime.forgeLayer) {
            return Optional.empty();
        }
        ClassLoader cl = getClass().getClassLoader();
        Class<?> modList = Class.forName("net.minecraftforge.fml.ModList", true, cl);
        Object mods = modList.getMethod("get").invoke(null);
        Optional<?> container = (Optional<?>) modList.getMethod("getModContainerById", String.class)
                .invoke(mods, m.id());
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

    /** Fabric and Quilt mods offer a config screen through the "modmenu" entrypoint. */
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
            // A mod without a config screen answers with a factory that returns nothing.
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

    private static void drawIcon(GuiGraphics g, ModInfo m, int x, int y, int size) {
        Optional<Icon> icon = icon(m);
        if (icon.isPresent()) {
            Icon i = icon.get();
            // Wide logos keep their shape inside the square.
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
            g.fill(x, y, x + size, y + size, 0xFF2B2B2B);
            String letter = m.name().isEmpty() ? "?" : m.name().substring(0, 1).toUpperCase(Locale.ROOT);
            Minecraft mc = Minecraft.getInstance();
            g.drawString(mc.font, letter, x + (size - mc.font.width(letter)) / 2, y + (size - 8) / 2, 0xFFAAAAAA);
        }
    }

    // ---------------------------------------------------------------- drawing

    private static String description(ModInfo m) {
        if (m.raw() != null) {
            JsonElement e = m.raw().get("description");
            if (e != null && e.isJsonPrimitive()) {
                return e.getAsString().trim();
            }
        }
        return "";
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int total = 0;
        for (ModInfo m : PentecostRuntime.mods) {
            if (!isLibrary(m)) {
                total++;
            }
        }
        g.drawString(font, "Mods (" + total + ")", 6, 8, 0xFFFFFF);
        g.drawString(font, "Hold Shift on any Mods button to open this list", listWidth + 12, 8, 0x808080);

        if (selected == null) {
            return;
        }
        int x = listWidth + 12;
        int y = 46;
        int wrap = Math.max(60, width - x - 8);
        drawIcon(g, selected, x, y, 48);
        g.drawString(font, selected.name(), x + 56, y + 2, 0xFFFFFF);
        g.drawString(font, "Version " + selected.version(), x + 56, y + 16, 0xA0A0A0);
        g.drawString(font, selected.loader().label + " mod, id " + selected.id(), x + 56, y + 28, 0xA0A0A0);
        y += 60;
        String text = description(selected);
        if (text.isEmpty()) {
            text = "This mod has no description.";
        }
        for (FormattedCharSequence line : font.split(Component.literal(text), wrap)) {
            if (y > height - 46) {
                break;
            }
            g.drawString(font, line, x, y, 0xD0D0D0);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ---------------------------------------------------------------- list

    private final class ModList extends ObjectSelectionList<ModList.Row> {
        ModList(Minecraft mc, int width, int height, int top, int bottom) {
            super(mc, width, height, top, bottom, 36);
        }

        void fill(List<ModInfo> mods) {
            clearEntries();
            for (ModInfo m : mods) {
                addEntry(new Row(m));
            }
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return width - 14;
        }

        @Override
        protected int getScrollbarPosition() {
            return x1 - 6;
        }

        private final class Row extends ObjectSelectionList.Entry<Row> {
            private final ModInfo mod;

            Row(ModInfo mod) {
                this.mod = mod;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int rowWidth, int rowHeight,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {
                if (mod == selected) {
                    g.fill(left - 1, top - 1, left + rowWidth - 3, top + rowHeight + 1, 0x40FFFFFF);
                }
                drawIcon(g, mod, left + 1, top, 32);
                int textWidth = rowWidth - 44;
                g.drawString(font, font.plainSubstrByWidth(mod.name(), textWidth), left + 38, top + 2, 0xFFFFFF);
                g.drawString(font, font.plainSubstrByWidth(mod.version(), textWidth), left + 38, top + 13, 0x909090);
                g.drawString(font, mod.loader().label, left + 38, top + 23, 0x6090C0);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ModList.this.setSelected(this);
                select(mod);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(mod.name());
            }
        }
    }
}
