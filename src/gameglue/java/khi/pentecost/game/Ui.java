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

    // ---------------------------------------------------------------- wordmark

    private static final Map<Character, String[]> GLYPHS = new HashMap<>();
    static {
        GLYPHS.put('A', new String[]{"01110", "10001", "10001", "11111", "10001", "10001", "10001"});
        GLYPHS.put('C', new String[]{"01111", "10000", "10000", "10000", "10000", "10000", "01111"});
        GLYPHS.put('D', new String[]{"11110", "10001", "10001", "10001", "10001", "10001", "11110"});
        GLYPHS.put('E', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "11111"});
        GLYPHS.put('F', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "10000"});
        GLYPHS.put('I', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "11111"});
        GLYPHS.put('M', new String[]{"10001", "11011", "10101", "10101", "10001", "10001", "10001"});
        GLYPHS.put('N', new String[]{"10001", "11001", "10101", "10101", "10011", "10001", "10001"});
        GLYPHS.put('O', new String[]{"01110", "10001", "10001", "10001", "10001", "10001", "01110"});
        GLYPHS.put('P', new String[]{"11110", "10001", "10001", "11110", "10000", "10000", "10000"});
        GLYPHS.put('R', new String[]{"11110", "10001", "10001", "11110", "10100", "10010", "10001"});
        GLYPHS.put('S', new String[]{"01111", "10000", "10000", "01110", "00001", "00001", "11110"});
        GLYPHS.put('T', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "00100"});
        GLYPHS.put('U', new String[]{"10001", "10001", "10001", "10001", "10001", "10001", "01110"});
    }

    private static final Map<Character, boolean[][]> FINE = new HashMap<>();

    /**
     * A letter at twice the resolution: each pixel becomes four, and inside corners get filled,
     * so diagonals and curves come out smooth while the letter stays pixel art.
     */
    private static boolean[][] fine(char ch) {
        return FINE.computeIfAbsent(ch, c -> {
            String[] g = GLYPHS.get(c);
            if (g == null) {
                return null;
            }
            boolean[][] f = new boolean[14][10];
            for (int r = 0; r < 7; r++) {
                for (int col = 0; col < 5; col++) {
                    if (g[r].charAt(col) == '1') {
                        f[r * 2][col * 2] = f[r * 2][col * 2 + 1] = f[r * 2 + 1][col * 2] = f[r * 2 + 1][col * 2 + 1] = true;
                    }
                }
            }
            for (int r = 0; r < 7; r++) {
                for (int col = 0; col < 5; col++) {
                    if (on(g, r, col)) {
                        continue;
                    }
                    for (int dr = -1; dr <= 1; dr += 2) {
                        for (int dc = -1; dc <= 1; dc += 2) {
                            if (on(g, r + dr, col) && on(g, r, col + dc)) {
                                f[r * 2 + (dr > 0 ? 1 : 0)][col * 2 + (dc > 0 ? 1 : 0)] = true;
                            }
                        }
                    }
                }
            }
            return f;
        });
    }

    private static boolean on(String[] g, int r, int c) {
        return r >= 0 && r < 7 && c >= 0 && c < 5 && g[r].charAt(c) == '1';
    }

    /** Width of a word in wordmark letters, at px screen units per fine pixel. */
    static int wordmarkWidth(String word, int px) {
        return Math.max(0, word.length() * 12 - 2 + 4) * px;
    }

    /**
     * Draws a word in Pentecost's thick, slanted pixel letters, the same letters as the boot splash.
     * px: size of one fine pixel. A letter is 10 by 14 fine pixels.
     */
    static void wordmark(GuiGraphics g, String word, int x, int y, int px, int color) {
        g.drawManaged(() -> {
            int cx = x;
            for (char ch : word.toUpperCase(Locale.ROOT).toCharArray()) {
                boolean[][] f = fine(ch);
                if (f != null) {
                    for (int r = 0; r < 14; r++) {
                        int shift = (13 - r) / 3;
                        int run = -1;
                        for (int c = 0; c <= 10; c++) {
                            boolean lit = c < 10 && f[r][c];
                            if (lit && run < 0) {
                                run = c;
                            } else if (!lit && run >= 0) {
                                g.fill(cx + (run + shift) * px, y + r * px, cx + (c + shift) * px, y + (r + 1) * px, color);
                                run = -1;
                            }
                        }
                    }
                }
                cx += 12 * px;
            }
        });
    }

    // ---------------------------------------------------------------- background

    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    private static DynamicTexture dither;
    private static ResourceLocation ditherId;
    private static int ditherCols;
    private static int ditherRows;
    private static int ditherFrame;

    /**
     * The boot splash's dithered backdrop: a slow pattern of light pixels, strongest on the right,
     * away from the menu text. Drawn in plain GUI space over the whole screen.
     */
    static void dither(GuiGraphics g, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        int cell = 3;
        int cols = Math.max(1, (width + cell - 1) / cell);
        int rows = Math.max(1, (height + cell - 1) / cell);
        if (dither == null || cols != ditherCols || rows != ditherRows) {
            if (ditherId != null) {
                mc.getTextureManager().release(ditherId);
            }
            dither = new DynamicTexture(cols, rows, true);
            ditherId = mc.getTextureManager().register("pentecost_dither", dither);
            ditherCols = cols;
            ditherRows = rows;
            ditherFrame = 0;
        }
        NativeImage image = dither.getPixels();
        if (image != null && ditherFrame++ % 2 == 0) {
            double t = net.minecraft.Util.getMillis() * 0.0012;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    double v = Math.pow((double) x / cols, 1.8) * 0.55 + 0.06 * Math.sin(x * 0.5 + y * 0.3 - t);
                    image.setPixelRGBA(x, y, v * 16 > BAYER[y & 3][x & 3] ? 0x1CFFFFFF : 0);
                }
            }
            dither.upload();
        }
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(ditherId, 0, 0, cols * cell, rows * cell, 0f, 0f, cols, rows, cols, rows);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }
}
