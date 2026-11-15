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
 * "Pentecost X is ready", with Update and Later. The game's toasts cannot be clicked, so this one reads the mouse itself:
 * a click that starts inside one of its two buttons counts, while a menu is open. It stays about twenty seconds and
 * is gone the moment either button is pressed. The update is still on the Settings, General page afterwards.
 */
final class UpdateToast implements Toast {
    private static final int W = 224;
    private static final int H = 48;
    private static final long SHOW_MS = 20_000;

    private final ReleaseInfo release;
    private boolean wasDown = true;
    private boolean finished;

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
        double mx = mc.mouseHandler.xpos() / scale - at.x();
        double my = mc.mouseHandler.ypos() / scale - at.y();

        g.fill(0, 0, W, H, 0xF00A0A0B);
        Ui.outline(g, 0, 0, W, H, 0x88FFFFFF);
        g.drawString(font, "Update ready", 8, 7, Ui.TEXT, false);
        g.drawString(font, "Pentecost " + release.version() + "  (you have " + PentecostMain.VERSION + ")", 8, 19, 0xFF9A9A9A, false);

        int by = H - 17;
        boolean overUpdate = Ui.over((int) mx, (int) my, 8, by, 64, 13);
        boolean overLater = Ui.over((int) mx, (int) my, 78, by, 52, 13);
        button(g, font, 8, by, 64, "Update", overUpdate);
        button(g, font, 78, by, 52, "Later", overLater);

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
        return finished || visibleMs > SHOW_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    private static void button(GuiGraphics g, Font font, int x, int y, int w, String label, boolean hover) {
        if (hover) {
            g.fill(x, y, x + w, y + 13, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 13, 0x66FFFFFF);
        }
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, hover ? Ui.BLACK : Ui.TEXT, false);
    }
}
