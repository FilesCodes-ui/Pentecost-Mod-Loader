package khi.pentecost.game;

import com.mojang.blaze3d.platform.InputConstants;
import khi.pentecost.boot.PentecostMain;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.core.Timeline;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pentecost's performance readout. F6 shows and hides it.
 * It shows frame rate, memory, how many mods of each loader run, and where launch time went.
 * Drawn with flat shapes at its own scale, like the Mods screen.
 */
public final class PerfHud {
    private static final int KEY_F6 = 295;
    private static final int KEY_F7 = 296;
    private static boolean f7WasDown;
    private static boolean shown = "1".equals(System.getenv("PENTECOST_TEST_HUD"));
    private static boolean keyWasDown;
    private static boolean exportTested;
    private static long lastFrame;
    private static double frameMs;
    private static final double[] HIST = new double[240];
    private static int histAt;

    private PerfHud() {}

    /** Called once per frame, after the game drew its own HUD. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (lastFrame != 0) {
            double instant = (now - lastFrame) / 1_000_000.0;
            frameMs = frameMs * 0.9 + instant * 0.1;
            HIST[histAt++ % HIST.length] = instant;
        }
        lastFrame = now;
        if (mc.level != null) {
            Timeline.mark("In the world");
            if (!exportTested && "1".equals(System.getenv("PENTECOST_TEST_EXPORT"))) {
                exportTested = true;
                try {
                    khi.pentecost.boot.Log.info("Test: pack exported to " + khi.pentecost.control.PackExport.export());
                } catch (java.io.IOException | RuntimeException e) {
                    khi.pentecost.boot.Log.error("Test: pack export failed: " + e);
                }
            }
        }
        boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), KEY_F6);
        if (down && !keyWasDown && mc.screen == null) {
            shown = !shown;
        }
        keyWasDown = down;
        boolean f7 = InputConstants.isKeyDown(mc.getWindow().getWindow(), KEY_F7);
        if (f7 && !f7WasDown && mc.screen == null && !khi.pentecost.core.RunProfiler.running()) {
            khi.pentecost.core.RunProfiler.start(msg -> mc.execute(() -> mc.gui.getChat()
                    .addMessage(net.minecraft.network.chat.Component.literal("[Pentecost] " + msg))));
        }
        f7WasDown = f7;
        // F6 slides in from the right edge and back out. While it is open, F3's right column moves down under it.
        float e = Ui.ease("pentecost:f6", shown && !mc.options.hideGui ? 1f : 0f, 12f);
        khi.pentecost.core.CostMeter.setEnabled(e > 0.001f);
        if (e <= 0.001f) {
            DebugView.render(0f);
            return;
        }

        List<String[]> rows = new ArrayList<>();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        int forge = 0;
        int fabric = 0;
        for (ModInfo m : PentecostRuntime.mods) {
            String id = m.id();
            if (m.loader() == LoaderKind.LIBRARY || id.startsWith("fabric-") || id.startsWith("fabric_")) {
                continue;
            }
            if (m.loader() == LoaderKind.FORGE) {
                forge++;
            } else {
                fabric++;
            }
        }
        rows.add(new String[]{"Memory", used + " / " + max + " MB"});
        rows.add(new String[]{"Mods", forge + " Forge   " + fabric + " Fabric and Quilt"});
        rows.add(null);
        List<String[]> cost = khi.pentecost.core.CostMeter.rows(6);
        if (!cost.isEmpty()) {
            rows.add(new String[]{"Mod handler time", "per tick"});
            rows.addAll(cost);
            rows.add(null);
        }
        for (Map.Entry<String, Double> mark : Timeline.marks().entrySet()) {
            rows.add(new String[]{mark.getKey(), String.format("%.1f s", mark.getValue())});
        }

        Font font = mc.font;
        double scale = mc.getWindow().getGuiScale();
        float ui = scale > 2 ? (float) (2.0 / scale) : 1f;
        int vw = (int) (mc.getWindow().getGuiScaledWidth() / ui);
        int labelW = 0;
        int valueW = 0;
        for (String[] r : rows) {
            if (r != null) {
                labelW = Math.max(labelW, font.width(r[0]));
                valueW = Math.max(valueW, font.width(r[1]));
            }
        }
        int w = Math.max(176, labelW + valueW + 40);
        int bigH = 30;
        int sparkH = 22;
        int h = 15 + bigH + 2 + sparkH + 4 + rows.size() * 10;
        float slide = 1f - (1f - e) * (1f - e);
        int x = vw - w - 8 + Math.round((1f - slide) * (w + 16));
        int y = 8;
        int alpha = Math.round(e * 255);

        // Room F6 takes at the top right, in the game's scaled pixels, grown and shrunk with the animation.
        DebugView.render((y + h + 6) * ui * slide);

        GuiGraphics g = GuiBatch.on(new GuiGraphics(mc, mc.renderBuffers().bufferSource()));
        g.pose().pushPose();
        g.pose().translate(0, 0, 210);
        g.pose().scale(ui, ui, 1);
        int back = fade(0xB00A0A0B, alpha);
        // A white label with black letters, flush right, like a terminal's title bar.
        String title = "PENTECOST " + PentecostMain.VERSION;
        int tw = font.width(title) + 8;
        g.fill(x + w - tw, y, x + w, y + 11, fade(0xFFF2F2F2, alpha));
        text(g, font, title, x + w - tw + 4, y + 2, fade(0xFF0A0A0B, alpha));
        int cy = y + 15;
        // The frame rate, large, with the frame time beside it.
        String fps = String.valueOf(mc.getFps());
        g.fill(x, cy, x + w, cy + bigH, back);
        Ui.text(g, font, fps, x + 6, cy + 3, fade(0xFFF2F2F2, alpha), 3f);
        int afterBig = x + 10 + font.width(fps) * 3;
        text(g, font, "fps", afterBig, cy + bigH - 12, fade(0xFF636363, alpha));
        String ms = String.format("%.1f ms", frameMs);
        text(g, font, ms, x + w - 6 - font.width(ms), cy + bigH - 12, fade(0xFF9A9A9A, alpha));
        // The last frames as thin bars. The dotted line is 60 frames a second.
        int sy = cy + bigH + 2;
        g.fill(x, sy, x + w, sy + sparkH, back);
        int samples = (w - 4) / 2;
        for (int i = 0; i < samples; i++) {
            double v = HIST[Math.floorMod(histAt - samples + i, HIST.length)];
            if (v <= 0) {
                continue;
            }
            int bh = (int) Math.min(sparkH - 2, v / 33.3 * (sparkH - 2));
            int bx = x + 2 + i * 2;
            g.fill(bx, sy + sparkH - 1 - Math.max(1, bh), bx + 1, sy + sparkH - 1,
                    fade(v > 25 ? 0xFFF2F2F2 : 0xFF9A9A9A, alpha));
        }
        int line60 = sy + sparkH - 1 - (int) (16.7 / 33.3 * (sparkH - 2));
        for (int dx = x + 2; dx < x + w - 2; dx += 6) {
            g.fill(dx, line60, dx + 2, line60 + 1, fade(0x66FFFFFF, alpha));
        }
        int ry = sy + sparkH + 4;
        for (String[] r : rows) {
            if (r != null) {
                g.fill(x, ry, x + w, ry + 9, back);
                text(g, font, r[0], x + 6, ry + 1, fade(0xFF9A9A9A, alpha));
                text(g, font, r[1], x + w - 6 - font.width(r[1]), ry + 1, fade(0xFFF2F2F2, alpha));
                // Dots lead the eye from the label to its value.
                for (int dx = x + 6 + font.width(r[0]) + 6; dx < x + w - 6 - font.width(r[1]) - 6; dx += 4) {
                    g.fill(dx, ry + 6, dx + 1, ry + 7, fade(0xFF3A3A3A, alpha));
                }
            }
            ry += 10;
        }
        g.pose().popPose();
        g.flush();
    }

    private static int fade(int color, int alpha) {
        return (((color >>> 24) * alpha / 255) << 24) | (color & 0xFFFFFF);
    }

    /** The game draws text with almost no alpha as fully solid, so nearly invisible text is skipped. */
    private static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        if ((color >>> 24) >= 4) {
            g.drawString(font, s, x, y, color, false);
        }
    }
}
