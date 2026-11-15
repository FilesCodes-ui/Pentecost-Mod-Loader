package khi.pentecost.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown while Pentecost downloads an update and then closes so the installer can replace its files. */
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean ready = Updater.phase() == Updater.Phase.READY;
        String status = ready ? "Closing. The installer finishes the update."
                : "Downloading " + Math.round(Updater.progress() * 100) + "%";
        PentecostLevelLoadingScreen.draw(g, minecraft, "UPDATING", status, ready ? 1f : Updater.progress(), null);
    }
}
