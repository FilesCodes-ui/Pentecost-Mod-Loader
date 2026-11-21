package khi.pentecost.game;

import com.mojang.blaze3d.platform.InputConstants;
import khi.pentecost.boot.Log;
import khi.pentecost.core.ZoomHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Zoom {
    static final String NAME = "Pentecost Zoom";
    static final String CATEGORY = "Pentecost";

    private static KeyMapping key;
    private static boolean ready;
    private static boolean failed;
    private static boolean promptFailed;
    private static double current = 1.0;
    private static long last;

    private Zoom() {}

    public static void install() {
        ZoomHooks.guiPass = Zoom::drawInterface;
    }

    private static float slide() {
        double t = Math.max(0.0, Math.min(1.0, (ZoomHooks.factor - 1.0) / 0.5));
        return (float) (t * t * (3.0 - 2.0 * t));
    }

    private static void drawInterface(Object gui, Object graphics, float partialTick) {
        net.minecraft.client.gui.Gui g = (net.minecraft.client.gui.Gui) gui;
        net.minecraft.client.gui.GuiGraphics gg = (net.minecraft.client.gui.GuiGraphics) graphics;
        float s = slide();
        if (s <= 0.001f) {
            g.render(gg, partialTick);
            WwlsPrompt.draw(gg);
            return;
        }
        if (s >= 0.999f) {
            return;
        }
        int w = gg.guiWidth();
        int h = gg.guiHeight();
        int off = Math.round(s * h / 2f);
        int half = h / 2;
        if (half - off > 0) {
            gg.enableScissor(0, 0, w, half - off);
            gg.pose().pushPose();
            gg.pose().translate(0, -off, 0);
            g.render(gg, partialTick);
            gg.pose().popPose();
            gg.disableScissor();
        }
        if (half + off < h) {
            gg.enableScissor(0, half + off, w, h);
            gg.pose().pushPose();
            gg.pose().translate(0, off, 0);
            g.render(gg, partialTick);
            gg.pose().popPose();
            gg.disableScissor();
        }
    }

    static KeyMapping key() {
        return key;
    }

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (!ready && !failed) {
            setUp(mc);
        }
        long now = System.nanoTime();
        double dt = last == 0 ? 0.016 : Math.min(0.1, (now - last) / 1e9);
        last = now;
        boolean holding = key != null && mc.screen == null && mc.level != null && key.isDown();
        ZoomHooks.holding = holding;
        double target = holding ? ZoomHooks.level : 1.0;
        current += (target - current) * (1.0 - Math.exp(-dt * 16.0));
        if (Math.abs(current - target) < 0.002) {
            current = target;
        }
        ZoomHooks.factor = current;
        if (!promptFailed) {
            try {
                WwlsPrompt.frame(mc);
            } catch (RuntimeException e) {
                promptFailed = true;
                Log.error("The join prompt stopped: " + e);
            }
        }
    }

    private static void setUp(Minecraft mc) {
        try {
            Options options = mc.options;
            if (options == null) {
                return;
            }
            for (KeyMapping k : options.keyMappings) {
                if (k.getName().equals(NAME)) {
                    key = k;
                    ready = true;
                    return;
                }
            }
            KeyMapping made = new KeyMapping(NAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
            KeyMapping[] all = java.util.Arrays.copyOf(options.keyMappings, options.keyMappings.length + 1);
            all[all.length - 1] = made;
            java.lang.reflect.Field f = Options.class.getField("keyMappings");
            f.setAccessible(true);
            f.set(options, all);
            key = made;
            restoreSaved(mc);
            KeyMapping.resetMapping();
            ready = true;
            Log.info("Zoom key ready: " + key.saveString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            Log.error("Cannot add the zoom key: " + e);
        }
    }

    private static void restoreSaved(Minecraft mc) {
        try {
            Path file = mc.gameDirectory.toPath().resolve("options.txt");
            if (!Files.exists(file)) {
                return;
            }
            String prefix = "key_" + NAME + ":";
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith(prefix)) {
                    key.setKey(InputConstants.getKey(line.substring(prefix.length())));
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.error("Cannot read the saved zoom key: " + e);
        }
    }
}
