package khi.pentecost.game;

import khi.pentecost.update.ReleaseInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Shown while Pentecost downloads an update and then closes so the installer can replace its files.
 * It is Pentecost's loading picture: the logo, a thin line that fills with the download, and the size done so far.
 */
final class UpdateScreen extends Screen {
    private final Screen back;
    private int closeIn = -1;

    UpdateScreen(Screen back) {
        super(Component.literal("Updating Pentecost"));
        this.back = back;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void tick() {
        Updater.Phase p = Updater.phase();
        if (p == Updater.Phase.FAILED) {
            Updater.toast("Update failed", Updater.statusText());
            minecraft.setScreen(back);
        } else if (p == Updater.Phase.READY) {
            if (closeIn < 0) {
                closeIn = 40;
            } else if (--closeIn == 0) {
                minecraft.stop();
            }
        }
    }

    /** "Downloading   12.3 of 54.1 MB", or just "Downloading" when the size is not known. */
    private static String downloading() {
        ReleaseInfo r = Updater.release();
        if (r == null || r.size() <= 0) {
            return "Downloading";
        }
        double total = r.size() / 1048576.0;
        return String.format(Locale.ROOT, "Downloading   %.1f of %.1f MB", total * Updater.progress(), total);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean ready = Updater.phase() == Updater.Phase.READY;
        if (ready) {
            PentecostLevelLoadingScreen.draw(g, minecraft, "UPDATING", "Handing over to the installer", -1f, null);
        } else {
            // The line draws its own percent after the status, so the status holds only the sizes.
            PentecostLevelLoadingScreen.draw(g, minecraft, "UPDATING", downloading(), Updater.progress(), null);
        }
        float ui = Ui.scale(minecraft);
        String hint = ready ? "Pentecost closes now. The installer finishes the update."
                : "Pentecost closes when the download is checked. Your worlds, mods and settings stay.";
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        g.drawString(font, hint, (int) (width / ui / 2) - font.width(hint) / 2, (int) (height / ui) - 40, Ui.FAINT, false);
        g.pose().popPose();
    }
}
