package khi.pentecost.game;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pentecost's pause menu. The game's pause menu underneath, Pentecost's layout on top. */
public final class PentecostPauseScreen extends PauseScreen {
    private static final List<String> MAIN = List.of("menu.returnToGame", "gui.advancements", "gui.stats",
            MenuView.MODS, "menu.options", "menu.shareToLan", "menu.playerReporting", "menu.returnToMenu",
            "menu.disconnect");
    private static final Map<String, String> LINKS = new LinkedHashMap<>();
    static {
        LINKS.put("menu.sendFeedback", "Give feedback");
        LINKS.put("menu.reportBugs", "Report bugs");
    }

    private final MenuView view = new MenuView("pause");
    private final boolean menu;

    public PentecostPauseScreen(boolean showMenu) {
        super(showMenu);
        this.menu = showMenu;
    }

    @Override
    protected void init() {
        view.reset();
        super.init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        view.harvest(children());
        g.fill(0, 0, width, height, 0xC00A0A0B);
        view.draw(this, g, mouseX, mouseY, "Paused", List.of(), menu ? MAIN : List.of(), menu ? LINKS : Map.of());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && view.click(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
