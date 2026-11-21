package khi.pentecost.game;

import khi.pentecost.control.Settings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.PauseScreen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PentecostPauseScreen extends PauseScreen {
    private static final List<String> MAIN = List.of("menu.returnToGame", "gui.advancements", "gui.stats",
            MenuView.MODS, MenuView.SETTINGS, MenuView.MANAGE, MenuView.COMMANDS, "menu.options", "menu.shareToLan", "menu.playerReporting", "menu.returnToMenu",
            "menu.disconnect", MenuView.QUIT);
    private static final Map<String, String> LINKS = new LinkedHashMap<>();
    static {
        LINKS.put("menu.sendFeedback", "Give feedback");
        LINKS.put("menu.reportBugs", "Report bugs");
    }

    private static final float STRIP_SCALE = 0.7f;

    private final MenuView view = new MenuView("pause");
    private final boolean menu;
    private long copiedUntil;
    private int coordX, coordW, coordY;

    public PentecostPauseScreen(boolean showMenu) {
        super(showMenu);
        this.menu = showMenu;
    }

    @Override
    protected void init() {
        view.reset();
        super.init();
        if (menu && minecraft != null) {
            PauseCinema.begin(minecraft);
        }
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        WindowMode.fixStaleSize(this);
        view.harvest(children());
        g.fill(0, 0, width, height, PauseCinema.active() ? 0x520A0A0B : 0x800A0A0B);
        Ui.dither(g, width, height);
        view.draw(this, g, mouseX, mouseY, partialTick, "PAUSED", place(), List.of(),
                menu ? MAIN : List.of(), menu ? LINKS : Map.of());
        strip(g, mouseX, mouseY);
    }

    private boolean overCoords(double mx, double my) {
        return coordW > 0 && mx >= coordX && mx <= coordX + coordW && my >= coordY - 2 && my <= coordY + 9;
    }

    private void strip(GuiGraphics g, int mx, int my) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        var font = minecraft.font;
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        String stats = minecraft.getFps() + " fps   " + used + " MB   ";
        int x = 6;
        int y = height - 10;
        int ink = 0x70F2F2F2;
        BlockPos p = minecraft.player.blockPosition();
        boolean copied = System.currentTimeMillis() < copiedUntil;
        String where = copied ? "copied" : p.getX() + " " + p.getY() + " " + p.getZ();
        int statsW = font.width(stats);
        coordX = x + Math.round(statsW * STRIP_SCALE);
        coordW = Math.round(font.width(where) * STRIP_SCALE);
        coordY = y;
        boolean hover = overCoords(mx, my);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(STRIP_SCALE, STRIP_SCALE, 1f);
        g.drawString(font, stats, 0, 0, ink, false);
        g.drawString(font, where, statsW, 0, hover || copied ? 0xFFF2F2F2 : ink, false);
        if (hover && !copied) {
            g.fill(statsW, 9, statsW + font.width(where), 10, 0x80F2F2F2);
        }
        g.pose().popPose();
    }

    private String place() {
        if (minecraft == null) {
            return "";
        }
        if (minecraft.getSingleplayerServer() != null) {
            return minecraft.getSingleplayerServer().getWorldData().getLevelName();
        }
        return minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().name : "";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && minecraft != null && minecraft.player != null && overCoords(mouseX, mouseY)) {
            BlockPos p = minecraft.player.blockPosition();
            minecraft.keyboardHandler.setClipboard(p.getX() + " " + p.getY() + " " + p.getZ());
            copiedUntil = System.currentTimeMillis() + 1500;
            return true;
        }
        if (button == 0 && view.click(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
