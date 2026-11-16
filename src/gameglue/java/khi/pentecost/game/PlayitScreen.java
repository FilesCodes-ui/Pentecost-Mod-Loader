package khi.pentecost.game;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * A helper for homes where friends cannot connect directly. playit.gg gives the host a public address that
 * leads to the host's game. Pentecost does not run or install anything: this page opens the right sites,
 * lists the steps in plain words, and lets a friend join by the address the host sends.
 */
final class PlayitScreen extends PanelScreen {
    private static final String SITE = "https://playit.gg";
    private static final String DOWNLOAD = "https://playit.gg/download";

    private final Field address = new Field("FRIEND'S ADDRESS (from playit.gg)", 80);

    PlayitScreen(Screen parent) {
        super("playit.gg", parent);
        fields.add(address);
    }

    private void open(String url) {
        Util.getPlatform().openUri(url);
    }

    @Override
    protected void draw(GuiGraphics g) {
        int colW = Math.min(480, vw - 40);
        int x0 = (vw - colW) / 2;
        int y = header(g, "PLAYIT", "For homes friends cannot reach", x0, colW);

        String[] steps = {
                "1. Hosting? Open your world to friends on the LAN, and note the port number the game shows in chat.",
                "2. Make a free account on playit.gg and install their small program on your computer.",
                "3. In playit.gg, make a Minecraft Java tunnel that points to that port on your computer.",
                "4. Send your friends the public address playit.gg gives you.",
                "5. Friends: type the address below and press Join."};
        for (String s : steps) {
            for (String row : wrap(s, colW - 8)) {
                g.drawString(font, row, x0 + 4, y, Ui.SOFT, false);
                y += 11;
            }
            y += 3;
        }
        y += 6;

        int w = (colW - 6) / 2;
        button(g, "Open playit.gg", x0, y, w, true, () -> open(SITE));
        button(g, "Download the program", x0 + w + 6, y, w, true, () -> open(DOWNLOAD));
        y += 30;

        field(g, address, x0, y, colW);
        y += 34;
        button(g, "Paste", x0, y, w, true, () -> {
            String clip = minecraft.keyboardHandler.getClipboard().strip();
            address.text = clip.length() > address.max ? clip.substring(0, address.max) : clip;
        });
        button(g, "Join", x0 + w + 6, y, w, !address.text.isBlank(), () -> {
            String a = address.text.strip();
            ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(a),
                    new ServerData("playit.gg friend", a, false), false);
        });
        y += 26;
        g.drawString(font, "Pentecost does not install or run playit.gg for you.", x0, y, Ui.FAINT, false);

        button(g, "Back", x0, vh - 28, 80, true, this::onClose);
    }

    @Override
    protected void onEnter(Field f) {
        if (!address.text.isBlank()) {
            String a = address.text.strip();
            ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(a),
                    new ServerData("playit.gg friend", a, false), false);
        }
    }
}
