package khi.pentecost.game;

import com.mojang.blaze3d.platform.GlUtil;
import khi.pentecost.boot.PentecostMain;
import khi.pentecost.control.Settings;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pentecost's F3 screen: the game's information in readable panels.
 * Standard shows what most players look for, in more depth than the game's own list.
 * Full shows every line the game and mods give, in panels.
 * Lines mods add through Forge's event get their own panel in both.
 *
 * The game asks for F3 only while it is on. Pentecost keeps the last lines it was given and draws the panels
 * itself once per frame, so they can slide in and out. The right column moves down while the F6 readout is open.
 */
final class DebugView {
    private record Line(String key, String value) {}

    private record Panel(String title, List<Line> lines) {}

    private static List<String> lastLeft = List.of();
    private static List<String> lastRight = List.of();
    private static List<String> lastFromMods = List.of();

    private DebugView() {}

    /** Called while the game draws F3. Keeps the lines; the panels are drawn by render(). */
    static void draw(GuiGraphics g, List<String> left, List<String> right, List<String> fromMods) {
        lastLeft = new ArrayList<>(left);
        lastRight = new ArrayList<>(right);
        lastFromMods = new ArrayList<>(fromMods);
    }

    /**
     * Called once per frame after the game's HUD. topRight: how far down the right column starts,
     * in screen-scaled pixels (the F6 readout sits above it).
     */
    static void render(float topRight) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        boolean on = mc.options.renderDebug && !mc.options.hideGui && player != null && level != null;
        float e = Ui.ease("pentecost:f3", on ? 1f : 0f, 12f);
        if (e <= 0.001f || player == null || level == null) {
            return;
        }
        float ui = Ui.scale(mc);
        int vw = (int) (mc.getWindow().getGuiScaledWidth() / ui);

        List<Panel> leftCol = new ArrayList<>();
        List<Panel> rightCol = new ArrayList<>();
        if ("full".equals(Settings.get(Settings.OVERLAY_DETAIL))) {
            leftCol.add(new Panel("GAME", raw(lastLeft)));
            rightCol.add(new Panel("SYSTEM", raw(lastRight)));
        } else {
            standard(mc, player, level, leftCol, rightCol);
        }
        if (!lastFromMods.isEmpty()) {
            leftCol.add(new Panel("FROM MODS", raw(lastFromMods)));
        }

        GuiGraphics g = GuiBatch.on(new GuiGraphics(mc, mc.renderBuffers().bufferSource()));
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.pose().scale(ui, ui, 1);
        Font font = mc.font;
        int alpha = Math.round(e * 255);
        int y = 8;
        int i = 0;
        for (Panel p : leftCol) {
            float slide = stagger(e, i++);
            y = panel(g, font, p, 8, y, false, vw, slide, alpha) + 6;
        }
        y = 8 + Math.round(topRight / ui);
        i = 0;
        for (Panel p : rightCol) {
            float slide = stagger(e, i++);
            y = panel(g, font, p, vw - 8, y, true, vw, slide, alpha) + 6;
        }
        g.pose().popPose();
        g.flush();
    }

    /** Later panels follow a little behind the first, so the column unfolds instead of moving as one block. */
    private static float stagger(float e, int index) {
        float t = Math.max(0f, Math.min(1f, e * (1f + index * 0.15f) - index * 0.15f));
        return 1f - (1f - t) * (1f - t);
    }

    private static void standard(Minecraft mc, Player player, Level level, List<Panel> left, List<Panel> right) {
        int fps = mc.getFps();
        List<Line> perf = new ArrayList<>();
        perf.add(new Line("Frame rate", fps + " FPS"));
        perf.add(new Line("Frame time", fps > 0 ? String.format(Locale.ROOT, "%.1f ms", 1000.0 / fps) : "-"));
        int cap = mc.options.framerateLimit().get();
        perf.add(new Line("Frame limit", (cap >= 260 ? "None" : cap + " FPS") + (mc.options.enableVsync().get() ? ", VSync" : "")));
        if (mc.getSingleplayerServer() != null) {
            float tick = mc.getSingleplayerServer().getAverageTickTime();
            float tps = Math.min(20f, tick > 0 ? 1000f / tick : 20f);
            perf.add(new Line("Server tick", String.format(Locale.ROOT, "%.1f ms   %.0f TPS", tick, tps)));
        }
        perf.add(new Line("Render distance", mc.options.getEffectiveRenderDistance() + " chunks"));
        perf.add(new Line("Chunks", mc.levelRenderer.getChunkStatistics()));
        perf.add(new Line("Entities", mc.level.getEntityCount() + " loaded"));
        perf.add(new Line("Particles", String.valueOf(mc.particleEngine.countParticles())));
        left.add(new Panel("PERFORMANCE", perf));

        BlockPos pos = player.blockPosition();
        List<Line> world = new ArrayList<>();
        world.add(new Line("Dimension", level.dimension().location().toString()));
        world.add(new Line("Biome", level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("-")));
        world.add(new Line("Light", "Sky " + level.getBrightness(LightLayer.SKY, pos)
                + "   Block " + level.getBrightness(LightLayer.BLOCK, pos)));
        long day = level.getDayTime();
        world.add(new Line("Time", "Day " + (day / 24000L) + ", " + clock(day % 24000L)
                + "   Moon " + (level.getMoonPhase() + 1) + " of 8"));
        world.add(new Line("Weather", level.isThundering() ? "Thunder" : level.isRaining() ? "Rain" : "Clear"));
        world.add(new Line("Difficulty", cap(level.getDifficulty().getKey())
                + String.format(Locale.ROOT, "   local %.2f", level.getCurrentDifficultyAt(pos).getEffectiveDifficulty())));
        left.add(new Panel("WORLD", world));

        List<Line> me = new ArrayList<>();
        me.add(new Line("Position", String.format(Locale.ROOT, "%.2f  %.2f  %.2f", player.getX(), player.getY(), player.getZ())));
        me.add(new Line("Block", pos.getX() + "  " + pos.getY() + "  " + pos.getZ()));
        me.add(new Line("Chunk", (pos.getX() >> 4) + "  " + (pos.getY() >> 4) + "  " + (pos.getZ() >> 4)
                + "   in chunk " + (pos.getX() & 15) + "  " + (pos.getY() & 15) + "  " + (pos.getZ() & 15)));
        me.add(new Line("Region", "r." + (pos.getX() >> 9) + "." + (pos.getZ() >> 9) + ".mca"));
        String facing = player.getDirection().getName();
        me.add(new Line("Facing", cap(facing) + String.format(Locale.ROOT, "   %.1f / %.1f",
                net.minecraft.util.Mth.wrapDegrees(player.getYRot()), player.getXRot())));
        Vec3 v = player.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;
        me.add(new Line("Speed", String.format(Locale.ROOT, "%.2f blocks/s", speed)));
        me.add(new Line("Health", String.format(Locale.ROOT, "%.1f of %.1f   food %d",
                player.getHealth(), player.getMaxHealth(), player.getFoodData().getFoodLevel())));
        if (mc.gameMode != null) {
            me.add(new Line("Game mode", cap(mc.gameMode.getPlayerMode().getName())));
        }
        left.add(new Panel("PLAYER", me));

        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos t = bh.getBlockPos();
            BlockState state = level.getBlockState(t);
            List<Line> look = new ArrayList<>();
            look.add(new Line("Block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
            look.add(new Line("At", t.getX() + "  " + t.getY() + "  " + t.getZ() + "   side " + bh.getDirection().getName()));
            for (Property<?> prop : state.getProperties()) {
                look.add(new Line(prop.getName(), String.valueOf(state.getValue(prop))));
            }
            if (!level.getFluidState(t).isEmpty()) {
                look.add(new Line("Fluid", BuiltInRegistries.FLUID.getKey(level.getFluidState(t).getType()).toString()));
            }
            long tags = state.getTags().count();
            if (tags > 0) {
                look.add(new Line("Tags", String.valueOf(tags)));
            }
            look.add(new Line("Distance", String.format(Locale.ROOT, "%.1f", Math.sqrt(t.distToCenterSqr(player.position())))));
            left.add(new Panel("LOOKING AT", look));
        } else if (hit instanceof EntityHitResult eh && hit.getType() == HitResult.Type.ENTITY) {
            Entity target = eh.getEntity();
            List<Line> look = new ArrayList<>();
            look.add(new Line("Entity", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString()));
            look.add(new Line("Name", target.getName().getString()));
            if (target instanceof LivingEntity living) {
                look.add(new Line("Health", String.format(Locale.ROOT, "%.1f of %.1f", living.getHealth(), living.getMaxHealth())));
            }
            look.add(new Line("Distance", String.format(Locale.ROOT, "%.1f", target.distanceTo(player))));
            left.add(new Panel("LOOKING AT", look));
        }

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        List<Line> sys = new ArrayList<>();
        sys.add(new Line("Pentecost", PentecostMain.VERSION));
        sys.add(new Line("Java", System.getProperty("java.version") + "   " + System.getProperty("java.vendor", "")));
        sys.add(new Line("Memory", String.format(Locale.ROOT, "%d%%   %d of %d MB",
                used * 100 / rt.maxMemory(), used >> 20, rt.maxMemory() >> 20)));
        sys.add(new Line("Allocated", String.format(Locale.ROOT, "%d MB", rt.totalMemory() >> 20)));
        sys.add(new Line("CPU", GlUtil.getCpuInfo()));
        sys.add(new Line("Threads", rt.availableProcessors() + " cores   " + Thread.activeCount() + " running"));
        sys.add(new Line("GPU", GlUtil.getRenderer()));
        sys.add(new Line("OpenGL", GlUtil.getOpenGLVersion()));
        sys.add(new Line("Display", mc.getWindow().getWidth() + " x " + mc.getWindow().getHeight()
                + "   GUI " + (int) mc.getWindow().getGuiScale() + "x"));
        long up = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        sys.add(new Line("Running for", String.format(Locale.ROOT, "%d:%02d:%02d", up / 3600, up / 60 % 60, up % 60)));
        right.add(new Panel("SYSTEM", sys));

        int forge = 0;
        int fabric = 0;
        int quilt = 0;
        for (ModInfo m : PentecostRuntime.mods) {
            String id = m.id();
            if (m.loader() == LoaderKind.LIBRARY || id.startsWith("fabric-") || id.startsWith("fabric_")) {
                continue;
            }
            switch (m.loader()) {
                case FORGE -> forge++;
                case QUILT -> quilt++;
                default -> fabric++;
            }
        }
        List<Line> mods = new ArrayList<>();
        mods.add(new Line("Forge", String.valueOf(forge)));
        mods.add(new Line("Fabric", String.valueOf(fabric)));
        if (quilt > 0) {
            mods.add(new Line("Quilt", String.valueOf(quilt)));
        }
        right.add(new Panel("MODS", mods));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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

    /** The game draws text with almost no alpha as fully solid, so nearly invisible text is skipped. */
    private static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        if ((color >>> 24) >= 4) {
            g.drawString(font, s, x, y, color, false);
        }
    }

    /** Fades a color's alpha by a 0..255 factor. */
    private static int fade(int color, int alpha) {
        int a = (color >>> 24) * alpha / 255;
        return (a << 24) | (color & 0xFFFFFF);
    }

    /**
     * Draws one block. x is the left edge, or the right edge when alignRight. slide: 0 hidden off the side,
     * 1 in place. Returns the bottom. No box: a white label, then each line on its own dark strip that is
     * only as wide as its text, like selected text in a terminal.
     */
    private static int panel(GuiGraphics g, Font font, Panel p, int x, int y, boolean alignRight, int vw,
                             float slide, int alpha) {
        int maxW = Math.max(120, vw / 2 - 24);
        int keyW = 0;
        for (Line l : p.lines()) {
            keyW = Math.max(keyW, font.width(l.key()));
        }
        keyW = Math.min(keyW, maxW / 2);
        int width = font.width(p.title()) + 8;
        for (Line l : p.lines()) {
            int w = (l.key().isEmpty() ? 0 : keyW + 8) + font.width(l.value()) + 8;
            width = Math.max(width, Math.min(maxW, w));
        }
        int height = 12 + p.lines().size() * 10;
        int shift = Math.round((1f - slide) * (width + 16));
        if (alpha < 8) {
            return y + height;
        }
        int tw = font.width(p.title()) + 8;
        int tx = alignRight ? x - tw + shift : x - shift;
        g.fill(tx, y, tx + tw, y + 11, fade(Ui.TEXT, alpha));
        text(g, font, p.title(), tx + 4, y + 2, fade(Ui.BLACK, alpha));
        int ly = y + 12;
        for (Line l : p.lines()) {
            String value = font.plainSubstrByWidth(l.value(), maxW - (l.key().isEmpty() ? 0 : keyW + 8) - 8);
            int lw = (l.key().isEmpty() ? 0 : keyW + 8) + font.width(value) + 8;
            int lx = alignRight ? x - lw + shift : x - shift;
            g.fill(lx, ly, lx + lw, ly + 10, fade(0xB40A0A0B, alpha));
            if (alignRight) {
                // Mirrored: the key sits against the right edge and the value to its left.
                int right = lx + lw - 4;
                if (!l.key().isEmpty()) {
                    String key = font.plainSubstrByWidth(l.key(), keyW);
                    text(g, font, key, right - font.width(key), ly + 1, fade(Ui.FAINT, alpha));
                    right -= keyW + 8;
                }
                text(g, font, value, right - font.width(value), ly + 1, fade(Ui.TEXT, alpha));
            } else {
                int vx = lx + 4;
                if (!l.key().isEmpty()) {
                    text(g, font, font.plainSubstrByWidth(l.key(), keyW), vx, ly + 1, fade(Ui.FAINT, alpha));
                    vx += keyW + 8;
                }
                text(g, font, value, vx, ly + 1, fade(Ui.TEXT, alpha));
            }
            ly += 10;
        }
        return y + height;
    }
}
