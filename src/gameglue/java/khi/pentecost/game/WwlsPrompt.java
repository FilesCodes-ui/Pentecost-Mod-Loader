package khi.pentecost.game;

import com.mojang.blaze3d.platform.InputConstants;
import khi.pentecost.core.JoinApprovals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The prompt a host sees while a friend waits to be let in, so the host never has to hunt for the WWLS screen.
 * It stays at the top of the screen until the host answers: Y lets the friend in, N turns them away, O opens WWLS.
 * The keys only count while no screen is open, so they never get in the way of typing in chat.
 */
final class WwlsPrompt {
    private static boolean yesDown;
    private static boolean noDown;
    private static boolean openDown;

    private WwlsPrompt() {}

    /** Called once per frame. */
    static void frame(Minecraft mc) {
        boolean y = false;
        boolean n = false;
        boolean o = false;
        if (mc.screen == null && mc.level != null && mc.getSingleplayerServer() != null
                && !WwlsScreen.waitingList().isEmpty()) {
            long window = mc.getWindow().getWindow();
            y = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Y);
            n = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_N);
            o = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_O);
        }
        if (y && !yesDown) {
            WwlsScreen.answer(true);
        } else if (n && !noDown) {
            WwlsScreen.answer(false);
        } else if (o && !openDown) {
            WwlsScreen.open(mc);
        }
        yesDown = y;
        noDown = n;
        openDown = o;
    }

    /** Drawn on top of the game's interface. */
    static void draw(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.level == null || mc.options.hideGui) {
            return;
        }
        List<JoinApprovals.Request> waiting = WwlsScreen.waitingList();
        if (waiting.isEmpty()) {
            return;
        }
        Font font = mc.font;
        String line1 = waiting.get(0).name() + " wants to join your world";
        String line2 = "[Y] Let in    [N] No    [O] Open WWLS";
        String line3 = waiting.size() > 1 ? "+" + (waiting.size() - 1) + " more waiting" : null;
        int w = Math.max(font.width(line1), font.width(line2)) + 24;
        int h = line3 == null ? 34 : 46;
        int x = (g.guiWidth() - w) / 2;
        int y = 10;
        g.fill(x, y, x + w, y + h, 0xE60A0A0B);
        Ui.outline(g, x, y, x + w, y + h, 0x99FFFFFF);
        g.drawString(font, line1, x + (w - font.width(line1)) / 2, y + 8, Ui.TEXT, false);
        g.drawString(font, line2, x + (w - font.width(line2)) / 2, y + 21, Ui.MUTED, false);
        if (line3 != null) {
            g.drawString(font, line3, x + (w - font.width(line3)) / 2, y + 33, Ui.FAINT, false);
        }
    }
}
