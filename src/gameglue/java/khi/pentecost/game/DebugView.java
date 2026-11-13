package khi.pentecost.game;

import com.mojang.blaze3d.platform.GlUtil;
import khi.pentecost.control.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pentecost's F3 screen: the same information as the game's, in readable panels.
 * Standard shows what most players look for. Full shows every line the game and mods give, in panels.
 * Lines mods add through Forge's event get their own panel in both.
 */
final class DebugView {
    private record Line(String key, String value) {}

    private record Panel(String title, List<Line> lines) {}

    private DebugView() {}

    /** left, right: the game's own lines. fromMods: lines mods added. */
    static void draw(GuiGraphics g, List<String> left, List<String> right, List<String> fromMods) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            return;
        }
        float ui = Ui.scale(mc);
        int vw = (int) (g.guiWidth() / ui);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.pose().scale(ui, ui, 1);

        List<Panel> leftCol = new ArrayList<>();
        List<Panel> rightCol = new ArrayList<>();
        if ("full".equals(Settings.get(Settings.OVERLAY_DETAIL))) {
            leftCol.add(new Panel("GAME", raw(left)));
            rightCol.add(new Panel("SYSTEM", raw(right)));
        } else {
            standard(mc, player, level, leftCol, rightCol);
        }
        if (!fromMods.isEmpty()) {
            leftCol.add(new Panel("FROM MODS", raw(fromMods)));
        }
        Font font = mc.font;
        int y = 8;
        for (Panel p : leftCol) {
            y = panel(g, font, p, 8, y, false, vw) + 6;
        }
        y = 8;
        for (Panel p : rightCol) {
            y = panel(g, font, p, vw - 8, y, true, vw) + 6;
        }
        g.pose().popPose();
    }

    private static void standard(Minecraft mc, Player player, Level level, List<Panel> left, List<Panel> right) {
        int fps = mc.getFps();
        List<Line> perf = new ArrayList<>();
        perf.add(new Line("Frame rate", fps + " FPS"));
        perf.add(new Line("Frame time", fps > 0 ? String.format(Locale.ROOT, "%.1f ms", 1000.0 / fps) : "-"));
        if (mc.getSingleplayerServer() != null) {
            float tick = mc.getSingleplayerServer().getAverageTickTime();
            float tps = Math.min(20f, tick > 0 ? 1000f / tick : 20f);
            perf.add(new Line("Server tick", String.format(Locale.ROOT, "%.1f ms   %.0f TPS", tick, tps)));
        }
        perf.add(new Line("Chunks", mc.levelRenderer.getChunkStatistics()));
        perf.add(new Line("Entities", String.valueOf(mc.level.getEntityCount())));
        left.add(new Panel("PERFORMANCE", perf));

        BlockPos pos = player.blockPosition();
        List<Line> world = new ArrayList<>();
        world.add(new Line("Dimension", level.dimension().location().toString()));
        world.add(new Line("Biome", level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("-")));
        world.add(new Line("Light", String.valueOf(level.getMaxLocalRawBrightness(pos))));
        long day = level.getDayTime();
        world.add(new Line("Time", "Day " + (day / 24000L) + ", " + clock(day % 24000L)));
        left.add(new Panel("WORLD", world));

        List<Line> me = new ArrayList<>();
        me.add(new Line("Position", String.format(Locale.ROOT, "%.1f  %.1f  %.1f", player.getX(), player.getY(), player.getZ())));
        me.add(new Line("Block", pos.getX() + "  " + pos.getY() + "  " + pos.getZ()));
        me.add(new Line("Chunk", (pos.getX() >> 4) + "  " + (pos.getZ() >> 4)));
        String facing = player.getDirection().getName();
        me.add(new Line("Facing", Character.toUpperCase(facing.charAt(0)) + facing.substring(1)
                + String.format(Locale.ROOT, "   %.0f / %.0f", net.minecraft.util.Mth.wrapDegrees(player.getYRot()), player.getXRot())));
        left.add(new Panel("PLAYER", me));

        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos t = bh.getBlockPos();
            List<Line> look = new ArrayList<>();
            look.add(new Line("Block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(t).getBlock()).toString()));
            look.add(new Line("At", t.getX() + "  " + t.getY() + "  " + t.getZ()));
            left.add(new Panel("LOOKING AT", look));
        }

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        List<Line> sys = new ArrayList<>();
        sys.add(new Line("Java", System.getProperty("java.version")));
        sys.add(new Line("Memory", String.format(Locale.ROOT, "%d%%   %d of %d MB",
                used * 100 / rt.maxMemory(), used >> 20, rt.maxMemory() >> 20)));
        sys.add(new Line("CPU", GlUtil.getCpuInfo()));
        sys.add(new Line("GPU", GlUtil.getRenderer()));
        sys.add(new Line("Display", mc.getWindow().getWidth() + " x " + mc.getWindow().getHeight()));
        right.add(new Panel("SYSTEM", sys));
    }

    private static String clock(long ticks) {
        long minutes = (ticks + 6000L) % 24000L * 1440L / 24000L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Raw lines as key and value where they hold a colon, plain text otherwise. Empty lines are skipped. */
    private static List<Line> raw(List<String> lines) {
        List<Line> out = new ArrayList<>();
        for (String s : lines) {
            if (s == null) {
                continue;
            }
            String t = s.replaceAll("§.", "").trim();
            if (t.isEmpty()) {
                continue;
            }
            int colon = t.indexOf(": ");
            if (colon > 0 && colon < 28) {
                out.add(new Line(t.substring(0, colon), t.substring(colon + 2)));
            } else {
                out.add(new Line("", t));
            }
        }
        return out;
    }

    /** Draws one panel. x is the left edge, or the right edge when alignRight. Returns the bottom. */
    private static int panel(GuiGraphics g, Font font, Panel p, int x, int y, boolean alignRight, int vw) {
        int maxW = Math.max(120, vw / 2 - 24);
        int keyW = 0;
        for (Line l : p.lines()) {
            keyW = Math.max(keyW, font.width(l.key()));
        }
        int width = font.width(p.title());
        for (Line l : p.lines()) {
            int w = (l.key().isEmpty() ? 0 : keyW + 10) + font.width(l.value());
            width = Math.max(width, w);
        }
        width = Math.min(maxW, width) + 16;
        int height = 18 + p.lines().size() * 11 + 4;
        int x1 = alignRight ? x - width : x;
        g.fill(x1, y, x1 + width, y + height, 0xB40A0A0B);
        g.fill(x1, y, x1 + 2, y + height, Ui.TEXT);
        g.drawString(font, p.title(), x1 + 8, y + 6, Ui.FAINT, false);
        int ly = y + 18;
        for (Line l : p.lines()) {
            int vx = x1 + 8;
            if (!l.key().isEmpty()) {
                g.drawString(font, l.key(), vx, ly, Ui.MUTED, false);
                vx += keyW + 10;
            }
            g.drawString(font, font.plainSubstrByWidth(l.value(), x1 + width - 8 - vx), vx, ly, Ui.TEXT, false);
            ly += 11;
        }
        return y + height;
    }
}
