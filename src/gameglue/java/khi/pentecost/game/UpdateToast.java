package khi.pentecost.game;

import khi.pentecost.boot.PentecostMain;
import khi.pentecost.update.ReleaseInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * "Update ready", with Update and Later, in Pentecost's own look: the boat, a pixel title, the two versions, buttons that
 * fill on hover, and a thin line along the bottom that runs out as the toast's time does. The game's toasts cannot be
 * clicked, so this one reads the mouse itself: a click that starts inside one of its two buttons counts, while a menu is open.
 * It stays about twenty seconds (the clock waits while the mouse is over it) and is gone the moment either button is pressed.
 * The update is still on the Settings, General page afterwards.
 */
final class UpdateToast implements Toast {
    private static final int W = 232;
    private static final int H = 60;
    private static final long SHOW_MS = 20_000;

    private final ReleaseInfo release;
    private boolean wasDown = true;
    private boolean finished;
    private long lastVisible;
    private long held;

    UpdateToast(ReleaseInfo release) {
        this.release = release;
    }

    @Override
    public int width() {
        return W;
    }

    @Override
    public int height() {
        return H;
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long visibleMs) {
        Minecraft mc = toasts.getMinecraft();
        Font font = mc.font;
        Vector3f at = g.pose().last().pose().getTranslation(new Vector3f());
        double scale = mc.getWindow().getGuiScale();
        int mx = (int) (mc.mouseHandler.xpos() / scale - at.x());
        int my = (int) (mc.mouseHandler.ypos() / scale - at.y());
        boolean inside = Ui.over(mx, my, 0, 0, W, H);
        if (inside) {
            held += Math.max(0, visibleMs - lastVisible);
        }
        lastVisible = visibleMs;
        long shown = visibleMs - held;

        g.fill(0, 0, W, H, 0xF20A0A0B);
        Ui.outline(g, 0, 0, W, H, 0x66FFFFFF);

        Ui.logo(g, 11, 10, 1, Ui.TEXT);
        Ui.pixelText(g, "UPDATE READY", 36, 11, 1, Ui.TEXT);

        // 0.3.0  ->  0.3.1
        String cur = PentecostMain.VERSION;
        String next = release.version();
        int vy = 26;
        g.drawString(font, cur, 36, vy, Ui.FAINT, false);
        int ax = 36 + font.width(cur) + 6;
        g.fill(ax, vy + 4, ax + 11, vy + 5, Ui.MUTED);
        for (int i = 0; i < 3; i++) {
            g.fill(ax + 10 - i, vy + 4 - i, ax + 11 - i, vy + 5 - i, Ui.MUTED);
            g.fill(ax + 10 - i, vy + 4 + i, ax + 11 - i, vy + 5 + i, Ui.MUTED);
        }
        g.drawString(font, next, ax + 17, vy, Ui.TEXT, false);

        int by = H - 22;
        boolean overUpdate = Ui.over(mx, my, 11, by, 70, 14);
        boolean overLater = Ui.over(mx, my, 87, by, 52, 14);
        button(g, font, "toast.update", 11, by, 70, "Update", overUpdate);
        button(g, font, "toast.later", 87, by, 52, "Later", overLater);

        // The time left.
        float left = Math.max(0f, 1f - shown / (float) SHOW_MS);
        g.fill(1, H - 2, W - 1, H - 1, Ui.LINE);
        g.fill(1, H - 2, 1 + Math.round((W - 2) * left), H - 1, 0xB3FFFFFF);

        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !wasDown && mc.screen != null) {
            if (overUpdate) {
                finished = true;
                Updater.ask(mc.screen);
            } else if (overLater) {
                finished = true;
            }
        }
        wasDown = down;
        return finished || shown > SHOW_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    private static void button(GuiGraphics g, Font font, String key, int x, int y, int w, String label, boolean hover) {
        float p = Ui.ease(key, hover ? 1f : 0f, 18f);
        g.fill(x, y, x + w, y + 14, Ui.mix(0x00F2F2F2, 0xFFF2F2F2, p));
        Ui.outline(g, x, y, x + w, y + 14, Ui.mix(0x66FFFFFF, 0xFFF2F2F2, p));
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, Ui.mix(Ui.TEXT, Ui.BLACK, p), false);
    }
}
