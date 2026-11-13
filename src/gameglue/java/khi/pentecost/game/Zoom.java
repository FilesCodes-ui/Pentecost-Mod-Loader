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
