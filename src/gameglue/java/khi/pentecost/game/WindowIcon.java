package khi.pentecost.game;

import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

import java.nio.ByteBuffer;

final class WindowIcon {
    private static final String[] BOAT = {
            ".....#......",
            ".....##.....",
            ".....#.#....",
            ".....#..#...",
            "..#..#...#..",
            ".##..#....#.",
            "#.#..#######",
            "###..#......",
            ".....#......",
            "############",
            ".##########."
    };

    private static final int GRID = 16;
    private static final int FRAMES = 8;
    private static final int[] SIZES = {32, 64};
    private static final long STEP_MS = 140;
    private static final int TILE = 0xFF0A0A0B;
    private static final int WHITE = 0xFFF2F2F2;
    private static final int DIM = 0xFF676767;

    private static ByteBuffer[][] frames;
    private static long last;
    private static int next;
    private static boolean failed;
    private static boolean logged;

    private WindowIcon() {}

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

    private static ByteBuffer render(int size, int frame) {
        int[] grid = new int[GRID * GRID];
        for (int r = 0; r < BOAT.length; r++) {
            for (int c = 0; c < BOAT[r].length(); c++) {
                if (BOAT[r].charAt(c) == '#') {
                    grid[(r + 1) * GRID + c + 2] = WHITE;
                }
            }
        }
        double phase = frame * Math.PI * 2 / FRAMES;
        for (int line = 0; line < 2; line++) {
            int base = 10 + line * 2;
            for (int c = 1; c <= 14; c++) {
                double wave = Math.sin(c * 0.9 - phase + line * 1.7);
                int row = base + (wave > 0.55 ? -1 : 0) + 1;
                grid[row * GRID + c] = line == 0 ? WHITE : DIM;
            }
        }
        int k = size / GRID;
        double radius = size * 0.22;
        ByteBuffer out = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int argb = 0;
                if (inside(x + 0.5, y + 0.5, size, radius)) {
                    int cell = grid[(y / k) * GRID + x / k];
                    argb = cell != 0 ? cell : TILE;
                }
                out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        out.flip();
        return out;
    }

    private static boolean inside(double x, double y, int size, double radius) {
        double dx = Math.max(Math.max(radius - x, x - (size - radius)), 0);
        double dy = Math.max(Math.max(radius - y, y - (size - radius)), 0);
        return dx * dx + dy * dy <= radius * radius;
    }
}
