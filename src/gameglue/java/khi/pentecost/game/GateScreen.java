package khi.pentecost.game;

import khi.pentecost.update.ReleaseInfo;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

public final class GateScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final String text;
    private final List<Hit> hits = new ArrayList<>();

    public GateScreen(String text) {
        super(Component.literal("Update needed"));
        this.text = text;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, Runnable action) {
        boolean hover = Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, 0x66FFFFFF);
        }
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(x, y, w, 20, action));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Ui.back(minecraft));
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int cx = vw / 2;
        int colW = Math.min(300, vw - 40);
        int x0 = cx - colW / 2;
        int y = Math.max(20, vh / 5);

        String title = "UPDATE NEEDED";
        g.drawString(font, title, cx - font.width(title) / 2, y, Ui.TEXT, false);
        y += 14;
        g.fill(x0, y, x0 + colW, y + 1, Ui.LINE);
        y += 14;
        for (String line : Ui.wrap(font, text, colW)) {
            g.drawString(font, line, cx - font.width(line) / 2, y, Ui.SOFT, false);
            y += 11;
        }
        y += 16;
        button(g, "Get the update", x0, y, colW, mx, my,
                () -> Util.getPlatform().openUri("https://github.com/" + ReleaseInfo.REPO + "/releases/latest"));
        y += 26;
        button(g, "Quit", x0, y, colW, mx, my, () -> minecraft.stop());
        g.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float ui = Ui.scale(minecraft);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
