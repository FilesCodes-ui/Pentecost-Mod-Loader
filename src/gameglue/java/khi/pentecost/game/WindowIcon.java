package khi.pentecost.game;

import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

import java.nio.ByteBuffer;

/**
 * The window and taskbar icon: Pentecost's mark, a triangle turning in three dimensions, white on a black
 * rounded tile. The turn runs over a whole number of frames, so the loop joins back onto itself.
 * The same art is drawn by .tools\make_icon.py for pentecost.ico. Change both together.
 */
final class WindowIcon {

    private static final int GRID = 16;
    private static final int FRAMES = 24;
    private static final int[] SIZES = {32, 64};
    private static final long STEP_MS = 90;
    private static final int TILE = 0xFF0A0A0B;

    private static ByteBuffer[][] frames;
    private static long last;
    private static int next;
    private static boolean failed;
    private static boolean logged;

    private WindowIcon() {}

    /** Called every frame. Moves to the next frame of the turn about eleven times a second. */
    static void frame() {
        if (failed) {
            return;
        }
        long now = System.nanoTime() / 1_000_000L;
        if (now - last < STEP_MS) {
            return;
        }
        last = now;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getWindow() == null) {
                return;
            }
            show(mc.getWindow().getWindow());
        } catch (Throwable t) {
            failed = true;
            Log.warn("Window icon animation stopped: " + t);
        }
    }

    /** Puts the next frame of the turn on the window. */
    private static void show(long handle) {
        if (frames == null) {
            frames = new ByteBuffer[FRAMES][SIZES.length];
            for (int f = 0; f < FRAMES; f++) {
                for (int s = 0; s < SIZES.length; s++) {
                    frames[f][s] = render(SIZES[s], f);
                }
            }
        }
        try (GLFWImage.Buffer images = GLFWImage.malloc(SIZES.length)) {
            for (int s = 0; s < SIZES.length; s++) {
                images.get(s).set(SIZES[s], SIZES[s], frames[next][s]);
            }
            GLFW.glfwSetWindowIcon(handle, images);
        }
        if (!logged) {
            logged = true;
            Log.info("Window icon set on window " + handle);
        }
        next = (next + 1) % FRAMES;
    }

    /** Called by the game right after it set its own icon. Puts the mark on the window at boot. */
    static void apply(Object window) {
        if (failed) {
            return;
        }
        try {
            show(((com.mojang.blaze3d.platform.Window) window).getWindow());
            last = System.nanoTime() / 1_000_000L;
        } catch (Throwable t) {
            failed = true;
            Log.warn("Window icon animation stopped: " + t);
        }
    }

    /** One frame at one size, as RGBA bytes. Size must be a multiple of 16. */
    private static ByteBuffer render(int size, int frame) {
        int[] cells = TriMark.loop(GRID, GRID, frame, FRAMES);
        int k = size / GRID;
        double radius = size * 0.22;
        ByteBuffer out = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = 0;
                if (inside(x + 0.5, y + 0.5, size, radius)) {
                    int v = cells[(y / k) * GRID + x / k];
                    argb = v < 30 ? TILE : (0xFF000000 | (v << 16) | (v << 8) | v);
                }
                out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        out.flip();
        return out;
    }

    /** True when the point is inside a square of this size with rounded corners. */
    private static boolean inside(double x, double y, int size, double radius) {
        double dx = Math.max(Math.max(radius - x, x - (size - radius)), 0);
        double dy = Math.max(Math.max(radius - y, y - (size - radius)), 0);
        return dx * dx + dy * dy <= radius * radius;
    }
}
