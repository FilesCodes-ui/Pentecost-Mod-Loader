package khi.pentecost.game;

import khi.pentecost.probe.Probe;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registers the Minecraft-side endpoints of the live probe. Every supplier runs on the render thread. */
final class ProbeGame {
    private static boolean done;

    private ProbeGame() {}

    static void install() {
        if (done) return;
        done = true;
        Probe.GAME.put("frame", () -> {
            Map<String, Object> o = new LinkedHashMap<>();
            for (String[] row : PerfHud.telemetryRows(Minecraft.getInstance())) o.put(row[0].trim(), row[1]);
            o.put("fpsString", Minecraft.getInstance().fpsString);
            o.put("workMs", PerfHud.workMs());
            return o;
        });
        Probe.GAME.put("world", () -> {
            Minecraft mc = Minecraft.getInstance();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("screen", mc.screen == null ? null : mc.screen.getClass().getName());
            o.put("paused", mc.isPaused());
            o.put("singleplayer", mc.getSingleplayerServer() != null);
            if (mc.level != null) {
                o.put("dimension", mc.level.dimension().location().toString());
                o.put("gameTime", mc.level.getGameTime());
                o.put("dayTime", mc.level.getDayTime());
                o.put("raining", mc.level.isRaining());
                o.put("entities", mc.level.getEntityCount());
                o.put("renderDistance", mc.options.getEffectiveRenderDistance());
                o.put("chunkStats", mc.levelRenderer.getChunkStatistics());
                o.put("entityStats", mc.levelRenderer.getEntityStatistics());
            }
            if (mc.player != null) {
                o.put("player", mc.player.getName().getString());
                o.put("x", mc.player.getX());
                o.put("y", mc.player.getY());
                o.put("z", mc.player.getZ());
                o.put("yaw", mc.player.getYRot());
                o.put("pitch", mc.player.getXRot());
                o.put("mainHand", String.valueOf(mc.player.getMainHandItem()));
                o.put("offHand", String.valueOf(mc.player.getOffhandItem()));
                o.put("health", mc.player.getHealth());
                o.put("lightHere", mc.level == null ? null : mc.level.getMaxLocalRawBrightness(mc.player.blockPosition()));
            }
            return o;
        });
        Probe.GAME.put("options", () -> {
            Minecraft mc = Minecraft.getInstance();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("renderDistance", mc.options.renderDistance().get());
            o.put("simulationDistance", mc.options.simulationDistance().get());
            o.put("maxFps", mc.options.framerateLimit().get());
            o.put("vsync", mc.options.enableVsync().get());
            o.put("graphics", String.valueOf(mc.options.graphicsMode().get()));
            o.put("particles", String.valueOf(mc.options.particles().get()));
            o.put("window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
            o.put("guiScale", mc.getWindow().getGuiScale());
            return o;
        });
        Probe.GAME.put("gpu", () -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("vendor", com.mojang.blaze3d.platform.GlUtil.getVendor());
            o.put("renderer", com.mojang.blaze3d.platform.GlUtil.getRenderer());
            o.put("glVersion", com.mojang.blaze3d.platform.GlUtil.getOpenGLVersion());
            o.put("cpu", com.mojang.blaze3d.platform.GlUtil.getCpuInfo());
            return o;
        });
    }
}
