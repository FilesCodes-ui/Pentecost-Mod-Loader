package khi.pentecost.game;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Windowed, fullscreen, or borderless: a window without a frame that covers the whole monitor.
 * Borderless looks like fullscreen, but switching apps does not minimize the game or blank the screen.
 */
final class WindowMode {
    static final String WINDOWED = "windowed";
    static final String FULLSCREEN = "fullscreen";
    static final String BORDERLESS = "borderless";

    private static boolean borderless;
    private static int savedX;
    private static int savedY;
    private static int savedW;
    private static int savedH;
    private static boolean applied;

    private WindowMode() {}

    static String label(String mode) {
        return switch (mode) {
            case FULLSCREEN -> "Fullscreen";
            case BORDERLESS -> "Borderless";
            default -> "Windowed";
        };
    }

    static String next(String mode) {
        return switch (mode) {
            case WINDOWED -> BORDERLESS;
            case BORDERLESS -> FULLSCREEN;
            default -> WINDOWED;
        };
    }

    /** Applies the saved mode once, when the game first shows a menu. */
    static void applySaved() {
        if (applied) {
            return;
        }
        applied = true;
        String mode = Settings.get(Settings.WINDOW);
        if (!mode.equals(WINDOWED)) {
            apply(mode);
        }
    }

    static void set(String mode) {
        Settings.set(Settings.WINDOW, mode);
        apply(mode);
    }

    private static void apply(String mode) {
        Minecraft mc = Minecraft.getInstance();
        Window w = mc.getWindow();
        try {
            if (mode.equals(FULLSCREEN)) {
                leaveBorderless(w);
                if (!w.isFullscreen()) {
                    w.toggleFullScreen();
                }
                mc.options.fullscreen().set(true);
                return;
            }
            if (w.isFullscreen()) {
                w.toggleFullScreen();
            }
            mc.options.fullscreen().set(false);
            if (mode.equals(BORDERLESS)) {
                enterBorderless(w);
            } else {
                leaveBorderless(w);
            }
        } catch (RuntimeException e) {
            Log.error("Cannot switch the window to " + label(mode) + ": " + e);
        }
    }

    private static void enterBorderless(Window w) {
        Monitor monitor = w.findBestMonitor();
        if (monitor == null) {
            Log.warn("Borderless: no monitor found. Staying windowed");
            return;
        }
        long handle = w.getWindow();
        if (!borderless) {
            int[] x = new int[1];
            int[] y = new int[1];
            int[] width = new int[1];
            int[] height = new int[1];
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, width, height);
            savedX = x[0];
            savedY = y[0];
            savedW = width[0];
            savedH = height[0];
        }
        VideoMode vm = monitor.getCurrentMode();
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwSetWindowPos(handle, monitor.getX(), monitor.getY());
        GLFW.glfwSetWindowSize(handle, vm.getWidth(), vm.getHeight());
        borderless = true;
    }

    private static void leaveBorderless(Window w) {
        if (!borderless) {
            return;
        }
        long handle = w.getWindow();
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        if (savedW > 0 && savedH > 0) {
            GLFW.glfwSetWindowSize(handle, savedW, savedH);
            GLFW.glfwSetWindowPos(handle, savedX, savedY);
        }
        borderless = false;
    }
}
