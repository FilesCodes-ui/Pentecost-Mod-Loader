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
        GLYPHS.put('B', new String[]{"11110", "10001", "10001", "11110", "10001", "10001", "11110"});
        GLYPHS.put('G', new String[]{"01111", "10000", "10000", "10011", "10001", "10001", "01111"});
        GLYPHS.put('H', new String[]{"10001", "10001", "10001", "11111", "10001", "10001", "10001"});
        GLYPHS.put('J', new String[]{"00111", "00010", "00010", "00010", "00010", "10010", "01100"});
        GLYPHS.put('K', new String[]{"10001", "10010", "10100", "11000", "10100", "10010", "10001"});
        GLYPHS.put('L', new String[]{"10000", "10000", "10000", "10000", "10000", "10000", "11111"});
        GLYPHS.put('Q', new String[]{"01110", "10001", "10001", "10001", "10101", "10010", "01101"});
        GLYPHS.put('V', new String[]{"10001", "10001", "10001", "10001", "10001", "01010", "00100"});
        GLYPHS.put('W', new String[]{"10001", "10001", "10001", "10101", "10101", "10101", "01010"});
        GLYPHS.put('X', new String[]{"10001", "10001", "01010", "00100", "01010", "10001", "10001"});
        GLYPHS.put('Y', new String[]{"10001", "10001", "01010", "00100", "00100", "00100", "00100"});
        GLYPHS.put('Z', new String[]{"11111", "00001", "00010", "00100", "01000", "10000", "11111"});
        GLYPHS.put('0', new String[]{"01110", "10001", "10011", "10101", "11001", "10001", "01110"});
        GLYPHS.put('1', new String[]{"00100", "01100", "00100", "00100", "00100", "00100", "01110"});
        GLYPHS.put('2', new String[]{"01110", "10001", "00001", "00010", "00100", "01000", "11111"});
        GLYPHS.put('3', new String[]{"11110", "00001", "00001", "01110", "00001", "00001", "11110"});
        GLYPHS.put('4', new String[]{"00010", "00110", "01010", "10010", "11111", "00010", "00010"});
        GLYPHS.put('5', new String[]{"11111", "10000", "11110", "00001", "00001", "10001", "01110"});
        GLYPHS.put('6', new String[]{"00110", "01000", "10000", "11110", "10001", "10001", "01110"});
        GLYPHS.put('7', new String[]{"11111", "00001", "00010", "00100", "01000", "01000", "01000"});
        GLYPHS.put('8', new String[]{"01110", "10001", "10001", "01110", "10001", "10001", "01110"});
        GLYPHS.put('9', new String[]{"01110", "10001", "10001", "01111", "00001", "00010", "01100"});
        GLYPHS.put('%', new String[]{"11001", "11010", "00010", "00100", "01000", "01011", "10011"});
    }

    private static final Map<Character, boolean[][]> FINE = new HashMap<>();

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

    static int wordmarkWidth(String word, int px) {
        return Math.max(0, word.length() * 12 - 2 + 4) * px;
    }

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

    static int back(Minecraft mc) {
        if (khi.pentecost.game.menuworld.MenuHost.showing()) {
            return 0xB00A0A0B;
        }
        return mc.level == null ? 0xFF0A0A0B : 0xD80A0A0B;
    }

    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    private static DynamicTexture dither;
    private static ResourceLocation ditherId;
    private static int ditherCols;
    private static int ditherRows;
    private static int ditherFrame;
    private static double[] ditherBase;
    private static double[] ditherSin;
    private static double[] ditherCos;

    static void dither(GuiGraphics g, int width, int height) {
        if (!khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.PATTERN)) {
            return;
        }
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
            if (ditherBase == null || ditherBase.length != cols) {
                ditherBase = new double[cols];
                ditherSin = new double[cols];
                ditherCos = new double[cols];
                for (int x = 0; x < cols; x++) {
                    ditherBase[x] = Math.pow((double) x / cols, 2.2) * 0.40;
                    ditherSin[x] = Math.sin(x * 0.5);
                    ditherCos[x] = Math.cos(x * 0.5);
                }
            }
            double t = net.minecraft.Util.getMillis() * 0.0012;
            for (int y = 0; y < rows; y++) {
                double c = y * 0.3 - t;
                double sc = Math.sin(c);
                double cc = Math.cos(c);
                int[] bayer = BAYER[y & 3];
                for (int x = 0; x < cols; x++) {
                    double v = ditherBase[x] + 0.05 * (ditherSin[x] * cc + ditherCos[x] * sc);
                    image.setPixelRGBA(x, y, v * 16 > bayer[x & 3] ? 0x10FFFFFF : 0);
                }
            }
            dither.upload();
        }
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(ditherId, 0, 0, cols * cell, rows * cell, 0f, 0f, cols, rows, cols, rows);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static final String[] BOAT = {
            ".....#......",
            ".....##.....",
            ".....#.#....",
            ".....#..#...",
            "..#..#...#..",
            ".##..#....#.",
            "#.#..#######",
            "###..#......",
            ".....#......",
            "############",
            ".##########."
    };

    static final int LOGO_W = 16;
    static final int LOGO_H = 13;

    static void logo(GuiGraphics g, int x, int y, int px, int color) {
        double t = net.minecraft.Util.getMillis() / 1000.0;
        g.drawManaged(() -> {
            for (int r = 0; r < BOAT.length; r++) {
                String row = BOAT[r];
                int run = -1;
                for (int c = 0; c <= row.length(); c++) {
                    boolean lit = c < row.length() && row.charAt(c) == '#';
                    if (lit && run < 0) {
                        run = c;
                    } else if (!lit && run >= 0) {
                        g.fill(x + (run + 2) * px, y + r * px, x + (c + 2) * px, y + (r + 1) * px, color);
                        run = -1;
                    }
                }
            }
            for (int line = 0; line < 2; line++) {
                int base = 10 + line * 2;
                int shade = line == 0 ? color : (color & 0x00FFFFFF) | 0x66000000;
                for (int c = 0; c < LOGO_W; c++) {
                    double wave = Math.sin(c * 0.9 - t * 0.9 + line * 1.7);
                    int dy = wave > 0.55 ? -1 : 0;
                    g.fill(x + c * px, y + (base + dy) * px, x + (c + 1) * px, y + (base + dy + 1) * px, shade);
                }
            }
        });
    }

    static void pixelText(GuiGraphics g, String text, int x, int y, int px, int color) {
        g.drawManaged(() -> {
            int cx = x;
            for (char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
                String[] glyph = GLYPHS.get(ch);
                if (glyph == null) {
                    cx += 4 * px;
                    continue;
                }
                for (int r = 0; r < 7; r++) {
                    for (int c = 0; c < 5; c++) {
                        if (glyph[r].charAt(c) == '1') {
                            g.fill(cx + c * px, y + r * px, cx + (c + 1) * px, y + (r + 1) * px, color);
                        }
                    }
                }
                cx += 6 * px;
            }
        });
    }

    private static final Map<String, float[]> SWITCHES = new HashMap<>();

    static void toggle(GuiGraphics g, String key, int x, int y, boolean on) {
        float[] state = SWITCHES.computeIfAbsent(key, k -> new float[]{on ? 1f : 0f, net.minecraft.Util.getMillis()});
        long now = net.minecraft.Util.getMillis();
        float dt = Math.min(0.1f, (now - state[1]) / 1000f);
        state[1] = now;
        float target = on ? 1f : 0f;
        state[0] += (target - state[0]) * Math.min(1f, dt * 18f);
        if (Math.abs(target - state[0]) < 0.01f) {
            state[0] = target;
        }
        float p = state[0];
        int border = mix(FAINT, TEXT, p);
        outline(g, x, y, x + 24, y + 11, border);
        int knob = x + 2 + Math.round(12 * p);
        g.fill(knob, y + 2, knob + 8, y + 9, border);
    }

    static int mix(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return r;
    }

    private static final Map<String, float[]> EASED = new HashMap<>();

    static float ease(String key, float target, float speed) {
        long now = net.minecraft.Util.getMillis();
        float[] st = EASED.computeIfAbsent(key, k -> new float[]{target, now});
        float dt = Math.min(0.1f, (now - st[1]) / 1000f);
        st[1] = now;
        st[0] += (target - st[0]) * Math.min(1f, dt * speed);
        if (Math.abs(target - st[0]) < 0.05f) {
            st[0] = target;
        }
        return st[0];
    }

    static java.util.List<String> wrap(net.minecraft.client.gui.Font font, String text, int width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (net.minecraft.util.FormattedCharSequence line : font.split(net.minecraft.network.chat.Component.literal(text), width)) {
            StringBuilder b = new StringBuilder();
            line.accept((index, style, cp) -> {
                b.appendCodePoint(cp);
                return true;
            });
            out.add(b.toString());
        }
        return out;
    }
}
