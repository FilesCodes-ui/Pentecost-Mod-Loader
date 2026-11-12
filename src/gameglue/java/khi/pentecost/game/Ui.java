package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The look shared by Pentecost's own screens: black and white, flat shapes, one scale.
 * Nothing here uses game textures, so no mod or resource pack restyles it.
 */
final class Ui {
    static final int BG = 0xFF0A0A0B;
    static final int LINE = 0x24FFFFFF;
    static final int HOVER = 0x0DFFFFFF;
    static final int TEXT = 0xFFF2F2F2;
    static final int SOFT = 0xFFB8B8B8;
    static final int MUTED = 0xFF9A9A9A;
    static final int FAINT = 0xFF636363;
    static final int BLACK = 0xFF0A0A0B;

    record Gray(ResourceLocation texture, int width, int height) {}

    private static final Map<String, Optional<Gray>> GRAY = new HashMap<>();
    private static Field onPress;

    private Ui() {}

    /** Pentecost screens draw at about two screen pixels per pixel, whatever the GUI scale is. */
    static float scale(Minecraft mc) {
        double gui = mc.getWindow().getGuiScale();
        return gui > 2 ? (float) (2.0 / gui) : 1f;
    }

    static void outline(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    static void text(GuiGraphics g, Font font, String s, float x, float y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, 0, 0, color, false);
        g.pose().popPose();
    }

    static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** The translation key of a widget's label, or "" when the label is plain text. */
    static String key(AbstractWidget w) {
        return w.getMessage().getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    static ModInfo mod(String id) {
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /**
     * The mod that put this widget on a screen, or null for the game's own widgets.
     * A mod either uses its own widget class, or a game button whose click code lives in the mod.
     */
    static String ownerOf(AbstractWidget w) {
        if (PentecostRuntime.loader == null) {
            return null;
        }
        String owner = modOfClass(w.getClass().getName());
        if (owner == null && w instanceof Button b) {
            try {
                if (onPress == null) {
                    onPress = Button.class.getDeclaredField("onPress");
                    onPress.setAccessible(true);
                }
                Object action = onPress.get(b);
                if (action != null) {
                    owner = modOfClass(action.getClass().getName());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return owner;
    }

    private static String modOfClass(String name) {
        int lambda = name.indexOf("$$Lambda");
        if (lambda > 0) {
            name = name.substring(0, lambda);
        }
        if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.startsWith("khi.pentecost.")
                || name.startsWith("net.minecraftforge.")) {
            return null;
        }
        String id = PentecostRuntime.loader.modOf(name);
        return id == null || id.equals("forge") || id.equals("minecraft") ? null : id;
    }

    /** A mod's icon in black and white. */
    static Optional<Gray> grayIcon(String modId) {
        return GRAY.computeIfAbsent(modId, id -> {
            ModInfo m = mod(id);
            if (m == null) {
                return Optional.empty();
            }
            String path = "assets/" + id + "/icon.png";
            if (m.raw() != null) {
                for (String k : new String[]{"icon", "logoFile"}) {
                    if (m.raw().has(k) && m.raw().get(k).isJsonPrimitive()) {
                        path = m.raw().get(k).getAsString();
                        break;
                    }
                }
            }
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
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int p = image.getPixelRGBA(x, y);
                            int a = p >>> 24;
                            int b = (p >> 16) & 0xFF;
                            int gr = (p >> 8) & 0xFF;
                            int r = p & 0xFF;
                            int l = Math.min(255, (int) (r * 0.30 + gr * 0.59 + b * 0.11));
                            image.setPixelRGBA(x, y, (a << 24) | (l << 16) | (l << 8) | l);
                        }
                    }
                    ResourceLocation rl = new ResourceLocation("pentecost",
                            "modicon_gray/" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
                    Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
                    return Optional.of(new Gray(rl, image.getWidth(), image.getHeight()));
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
