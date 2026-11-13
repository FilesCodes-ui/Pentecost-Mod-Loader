package khi.pentecost.game;

import khi.pentecost.boot.PentecostMain;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pentecost's main menu. It is the game's main menu underneath, so mods that add to the main menu
 * still find it. Pentecost draws its own layout on top.
 */
public final class PentecostTitleScreen extends TitleScreen {
    private static final List<String> MAIN = List.of("menu.singleplayer", "menu.multiplayer", MenuView.FRIENDS, MenuView.MODS,
            MenuView.SETTINGS, "menu.options", MenuView.ACCOUNT, "menu.quit");
    private static final Map<String, String> LINKS = new LinkedHashMap<>();
    static {
        LINKS.put("menu.online", "Realms");
        LINKS.put("narrator.button.language", "Language");
        LINKS.put("narrator.button.accessibility", "Accessibility");
    }

    private final MenuView view = new MenuView("title");
    private final PanoramaRenderer backdrop = new PanoramaRenderer(TitleScreen.CUBE_MAP);

    public PentecostTitleScreen() {
        super(false);
    }

    @Override
    protected void init() {
        view.reset();
        super.init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        WindowMode.fixStaleSize(this);
        view.harvest(children());
        // The game's turning landscape, behind a dark veil.
        backdrop.render(partialTick, 1.0F);
        g.fill(0, 0, width, height, 0x580A0A0B);
        Ui.dither(g, width, height);
        int mods = 0;
        for (ModInfo m : PentecostRuntime.mods) {
            String id = m.id();
            if (m.loader() != LoaderKind.LIBRARY && !id.startsWith("fabric-") && !id.startsWith("fabric_")
                    && !id.equals("mixinextras")) {
                mods++;
            }
        }
        view.draw(this, g, mouseX, mouseY, partialTick, "MINECRAFT", "On Pentecost",
                List.of("Pentecost " + PentecostMain.VERSION + "   " + SharedConstants.getCurrentVersion().getName()
                        + "   " + mods + " mods", "Copyright Mojang AB. Do not distribute!"),
                MAIN, LINKS);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && view.click(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
