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
    private static boolean shown = "1".equals(System.getenv("PENTECOST_TEST_HUD"));
    private static boolean keyWasDown;
    private static boolean exportTested;
    private static long lastFrame;
    private static double frameMs;

    private PerfHud() {}

    /** Called once per frame, after the game drew its own HUD. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (lastFrame != 0) {
            frameMs = frameMs * 0.9 + (now - lastFrame) / 1_000_000.0 * 0.1;
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
        rows.add(new String[]{"Frames", mc.getFps() + " fps   " + String.format("%.1f ms", frameMs)});
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
        int w = labelW + valueW + 28;
        int h = 22 + rows.size() * 11;
        float slide = 1f - (1f - e) * (1f - e);
        int x = vw - w - 8 + Math.round((1f - slide) * (w + 16));
        int y = 8;
        int alpha = Math.round(e * 255);

        // Room F6 takes at the top right, in the game's scaled pixels, grown and shrunk with the animation.
        DebugView.render((y + h + 6) * ui * slide);

        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        g.pose().pushPose();
        g.pose().translate(0, 0, 210);
        g.pose().scale(ui, ui, 1);
        g.fill(x, y, x + w, y + h, fade(0xD00A0A0B, alpha));
        text(g, font, "Pentecost " + PentecostMain.VERSION, x + 8, y + 6, fade(0xFFF2F2F2, alpha));
        g.fill(x + 8, y + 17, x + w - 8, y + 18, fade(0x24FFFFFF, alpha));
        int ry = y + 22;
        for (String[] r : rows) {
            if (r != null) {
                text(g, font, r[0], x + 8, ry, fade(0xFF9A9A9A, alpha));
                text(g, font, r[1], x + w - 8 - font.width(r[1]), ry, fade(0xFFF2F2F2, alpha));
            }
            ry += 11;
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
