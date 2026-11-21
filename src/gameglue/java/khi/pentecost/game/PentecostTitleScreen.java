package khi.pentecost.game;

import khi.pentecost.boot.PentecostMain;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.game.menuworld.MenuHost;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.Musics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        MenuHost.attach(minecraft);
    }

    @Override
    public void removed() {
        if (!MenuHost.staysBehindMenus()) {
            MenuHost.detach(minecraft);
        }
        super.removed();
    }

    @Override
    public Music getBackgroundMusic() {
        return Musics.MENU;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        WindowMode.fixStaleSize(this);
        view.harvest(children());
        MenuHost.frame(minecraft);
        if (MenuHost.showing()) {
            int black = Math.round(MenuHost.cover() * 255F);
            if (black > 0) g.fill(0, 0, width, height, black << 24);
        } else if (MenuHost.coming()) {
            g.fill(0, 0, width, height, 0xFF000000);
        } else {
            backdrop.render(partialTick, 1.0F);
        }
        shade(g);
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
        UpdateNotice.draw(this, g, mouseX, mouseY);
        ModWarnNotice.draw(this, g, mouseX, mouseY, UpdateNotice.roomTaken());
    }

    private void shade(GuiGraphics g) {
        int reach = (int) (width * 0.55F);
        int step = 4;
        for (int x = 0; x < reach; x += step) {
            float t = 1.0F - (float) x / reach;
            int alpha = (int) (0xC0 * t * t);
            g.fill(x, 0, Math.min(x + step, reach), height, (alpha << 24) | 0x0A0A0B);
        }
        g.fillGradient(0, 0, width, height / 5, 0x70000000, 0x00000000);
        g.fillGradient(0, height - height / 5, width, height, 0x00000000, 0x70000000);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && UpdateNotice.click(this, mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && ModWarnNotice.click(this, mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && view.click(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
