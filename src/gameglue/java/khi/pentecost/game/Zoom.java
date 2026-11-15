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

/**
 * The zoom key. Hold it to zoom in, turn the mouse wheel to change how far, let go to zoom back out.
 * The key is a normal game key bind, so it shows up in the controls and saves with the other keys.
 */
public final class Zoom {
    static final String NAME = "Pentecost Zoom";
    static final String CATEGORY = "Pentecost";

    private static KeyMapping key;
    private static boolean ready;
    private static boolean failed;
    private static double current = 1.0;
    private static long last;

    private Zoom() {}

    /** Hands the game's interface drawing to the slide-away below. Called once at startup. */
    public static void install() {
        ZoomHooks.guiPass = Zoom::drawInterface;
    }

    /** How far the interface has slid away: 0 shown, 1 gone. Follows the zoom, so it moves with it. */
    private static float slide() {
        double t = Math.max(0.0, Math.min(1.0, (ZoomHooks.factor - 1.0) / 0.5));
        return (float) (t * t * (3.0 - 2.0 * t));
    }

    /**
     * The whole interface, the game's and the mods', slides away while you zoom: what is in the top half goes up,
     * what is in the bottom half goes down. Only while it moves is the interface drawn twice, once per half.
     */
    private static void drawInterface(Object gui, Object graphics, float partialTick) {
        net.minecraft.client.gui.Gui g = (net.minecraft.client.gui.Gui) gui;
        net.minecraft.client.gui.GuiGraphics gg = (net.minecraft.client.gui.GuiGraphics) graphics;
        float s = slide();
        if (s <= 0.001f) {
            g.render(gg, partialTick);
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

    /** Called once per frame. */
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

    /** The game read its key binds before this one existed, so read this one's saved key by hand. */
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
