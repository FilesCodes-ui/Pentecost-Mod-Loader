package khi.pentecost.game;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/** Sign in with a Microsoft account. Multiplayer and Realms stay shut until the player does. */
public final class PentecostLoginScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    /** The screen the player was trying to open, shown again after signing in. Can be null. */
    private final Screen then;
    private final String reason;
    private final List<Hit> hits = new ArrayList<>();

    public PentecostLoginScreen(Screen parent, Screen then, String reason) {
        super(Component.literal("Account"));
        this.parent = parent;
        this.then = then;
        this.reason = reason;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
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
        int margin = 48;
        int y = 26;
        Ui.logo(g, margin, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, "ACCOUNT", margin + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        y += 40;
        g.fill(margin, y, vw - margin, y + 1, Ui.LINE);
        y += 24;
        int w = Math.min(420, vw - margin * 2);
        int x = margin;

        Account.State state = Account.state();
        boolean noId = Account.clientId() == null;
        if (reason != null && !Account.signedIn()) {
            y = lines(g, reason, x, y, w, Ui.SOFT);
            y += 10;
        }
        switch (state) {
            case IN -> {
                Ui.text(g, font, Account.name(), x, y, Ui.TEXT, 2f);
                y += 26;
                y = lines(g, "Signed in with Microsoft. Multiplayer is open.", x, y, w, Ui.MUTED);
                y += 14;
                y = button(g, "Account details", x, y, mx, my, () -> minecraft.setScreen(new AccountScreen(this)));
                y = button(g, "Developer tag: " + (PentecostDev.ownIsListed() ? "On" : "Off"), x, y, mx, my, PentecostDev::toggleOwn);
                if (then != null) {
                    y = button(g, "Continue", x, y, mx, my, () -> minecraft.setScreen(then));
                }
                if (Account.source() == Account.Source.LAUNCHER) {
                    y = lines(g, "Signed in through your launcher. Sign out there.", x, y, w, Ui.FAINT);
                } else {
                    y = button(g, "Sign out", x, y, mx, my, Account::signOut);
                }
            }
            case WAITING -> {
                y = lines(g, "Go to " + Account.verifyUrl() + " and type this code.", x, y, w, Ui.SOFT);
                y += 8;
                Ui.text(g, font, Account.userCode(), x, y, Ui.TEXT, 3f);
                y += 40;
                y = button(g, "Open the page", x, y, mx, my, () -> Util.getPlatform().openUri(Account.verifyUrl()));
                y = button(g, "Copy the code", x, y, mx, my, () -> minecraft.keyboardHandler.setClipboard(Account.userCode()));
                y = button(g, "Cancel", x, y, mx, my, Account::cancel);
            }
            case WORKING -> y = lines(g, Account.message().isEmpty() ? "Working" : Account.message() + "...", x, y, w, Ui.SOFT);
            default -> {
                if (state == Account.State.FAILED) {
                    y = lines(g, Account.message(), x, y, w, 0xFFE08080);
                    y += 10;
                }
                if (noId) {
                    y = lines(g, "Pentecost needs a Microsoft app ID to sign you in. Make a free app in Microsoft Entra "
                            + "(App registrations, personal accounts, public client, allow public client flows), "
                            + "ask Mojang to approve it at aka.ms/AppRegInfo, then put its ID in pentecost-client-id.txt "
                            + "in your game folder. Or start Pentecost from the Modrinth App or the Minecraft Launcher: "
                            + "it then uses the account you signed in there.", x, y, w, Ui.MUTED);
                    y += 10;
                    y = button(g, "Open the game folder", x, y, mx, my,
                            () -> Util.getPlatform().openFile(minecraft.gameDirectory));
                    y = button(g, "Open Microsoft Entra", x, y, mx, my,
                            () -> Util.getPlatform().openUri("https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"));
                    y = button(g, "Open Mojang's approval form", x, y, mx, my,
                            () -> Util.getPlatform().openUri("https://aka.ms/AppRegInfo"));
                } else {
                    y = button(g, "Sign in with Microsoft", x, y, mx, my, Account::startLogin);
                }
            }
        }
        // Back, bottom right.
        String back = "Back";
        int bw = font.width(back) + 24;
        int bx = vw - margin - bw;
        int by = vh - 32;
        boolean hover = Ui.over(mx, my, bx, by, bw, 18);
        if (hover) {
            g.fill(bx, by, bx + bw, by + 18, Ui.TEXT);
        } else {
            Ui.outline(g, bx, by, bx + bw, by + 18, Ui.MUTED);
        }
        g.drawString(font, back, bx + 12, by + 5, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(bx, by, bw, 18, this::onClose));
        String promise = "Pentecost never sees your Microsoft password. You sign in on microsoft.com. Pentecost keeps one "
                + "sign-in token on this PC so you stay signed in, and signing out deletes it. The Minecraft token is used "
                + "only to start your own game session and to join servers.";
        int noteH = Ui.wrap(font, promise, Math.min(420, vw - 96)).size() * 11;
        lines(g, promise, 48, vh - noteH - 18, Math.min(420, vw - 96), Ui.FAINT);
        g.pose().popPose();
    }

    private int lines(GuiGraphics g, String text, int x, int y, int w, int color) {
        for (String line : Ui.wrap(font, text, w)) {
            g.drawString(font, line, x, y, color, false);
            y += 11;
        }
        return y;
    }

    private int button(GuiGraphics g, String label, int x, int y, int mx, int my, Runnable action) {
        int w = font.width(label) + 28;
        boolean hover = Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, 0x66FFFFFF);
        }
        g.drawString(font, label, x + 14, y + 6, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(x, y, w, 20, action));
        return y + 26;
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
    public boolean isPauseScreen() {
        return true;
    }
}
