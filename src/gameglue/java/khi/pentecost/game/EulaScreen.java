package khi.pentecost.game;

import khi.pentecost.game.menuworld.MenuHost;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown once, before the first time the player opens the server list, Realms or Open to LAN, joins a server, uses a
 * room code or opens a world to friends. {@link ModsScreens#route} shows it in place of any of the game's own online
 * screens, so it does not matter which button leads there.
 * It says that Pentecost is not an official Minecraft product, points at the Minecraft EULA and the Usage Guidelines,
 * and asks the player to agree. The answer is saved with the other Pentecost settings and shared with the
 * agreement the menu world asks for, so the player is asked once, not once per feature.
 */
public final class EulaScreen extends Screen {
    private static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    private static final String GUIDELINES_URL = "https://www.minecraft.net/en-us/usage-guidelines";

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final Screen from;
    private final Runnable then;
    private final List<Hit> hits = new ArrayList<>();

    private EulaScreen(Screen from, Runnable then) {
        super(Component.literal("Before you play online"));
        this.from = from;
        this.then = then;
    }

    /** True when the player has already agreed. */
    public static boolean agreed() {
        return MenuHost.profile().eulaAccepted;
    }

    /** The game's own screens that lead to online play. Nothing may start online before the player has agreed. */
    public static boolean leadsOnline(String screenClass) {
        return screenClass.equals("net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen")
                || screenClass.equals("com.mojang.realmsclient.RealmsMainScreen")
                || screenClass.equals("net.minecraft.client.gui.screens.ShareToLanScreen");
    }

    /** The agreement in place of a screen that leads online. Agreeing opens that screen; Back goes to from. */
    public static Screen gate(Screen from, Screen wanted) {
        return new EulaScreen(from, () -> Minecraft.getInstance().setScreen(wanted));
    }

    /** Shows the agreement. When the player agrees, the screen goes back to from and then runs. Back runs nothing. */
    public static void ask(Minecraft mc, Screen from, Runnable then) {
        mc.setScreen(new EulaScreen(from, then));
    }

    private void agree() {
        MenuHost.profile().eulaAccepted = true;
        MenuHost.save();
        minecraft.setScreen(from);
        then.run();
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
        g.fill(0, 0, width, height, minecraft.level == null ? 0xFF0A0A0B : 0xD80A0A0B);
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
        int y = Math.max(20, vh / 12);

        String title = "BEFORE YOU PLAY ONLINE";
        g.drawString(font, title, cx - font.width(title) / 2, y, Ui.TEXT, false);
        y += 14;
        g.fill(x0, y, x0 + colW, y + 1, Ui.LINE);
        y += 14;

        String[] paragraphs = {
                "Minecraft is made by Mojang Studios and Microsoft. Pentecost is not an official Minecraft product "
                        + "and is not approved by or associated with Mojang or Microsoft.",
                "Joining servers, Realms, using room codes and opening your world to friends are online play. "
                        + "They are covered by the Minecraft End User License Agreement and the Minecraft Usage Guidelines.",
                "Read both, then choose I agree to go on."};
        for (String p : paragraphs) {
            for (String line : Ui.wrap(font, p, colW)) {
                g.drawString(font, line, cx - font.width(line) / 2, y, Ui.SOFT, false);
                y += 11;
            }
            y += 8;
        }
        y += 4;

        button(g, "Read the Minecraft EULA", x0, y, colW, mx, my, () -> Util.getPlatform().openUri(EULA_URL));
        y += 26;
        button(g, "Read the Usage Guidelines", x0, y, colW, mx, my, () -> Util.getPlatform().openUri(GUIDELINES_URL));
        y += 26;
        button(g, "I agree", x0, y, colW, mx, my, this::agree);

        int bw = font.width("Back") + 40;
        button(g, "Back", cx - bw / 2, vh - 32, bw, mx, my, this::onClose);
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

    @Override
    public void onClose() {
        minecraft.setScreen(from);
    }
}
