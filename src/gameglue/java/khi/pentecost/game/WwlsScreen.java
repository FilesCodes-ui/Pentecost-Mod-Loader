package khi.pentecost.game;

import khi.pentecost.core.Wwls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.HttpUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * World Wide Local Server: friends join a world by a short code. No port forwarding, no accounts for them to make.
 * Drawn like the other Pentecost screens: black and white, one centered column.
 */
public final class WwlsScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    /** The world open to friends right now, if any. It outlives the screen. */
    private static volatile String hostedCode;
    private static volatile Wwls.Mapping hostedMapping;
    private static volatile String status = "";
    private static volatile boolean busy;
    private static String typed = "";

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();

    public WwlsScreen(Screen parent) {
        super(Component.literal("World Wide Local Server"));
        this.parent = parent;
    }

    // ---------------------------------------------------------------- actions

    private void host() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || busy) {
            return;
        }
        busy = true;
        status = "Asking your router to open a port...";
        Thread t = new Thread(() -> {
            int port = HttpUtil.getAvailablePort();
            try {
                Wwls.Mapping mapping = Wwls.openPort(port);
                String code = Wwls.newCode();
                Wwls.register(code, mapping.publicIp() + ":" + port);
                mc.execute(() -> {
                    boolean ok = server.publishServer(mc.gameMode.getPlayerMode(), server.getWorldData().getAllowCommands(), port);
                    if (!ok) {
                        Wwls.unregister(code);
                        Wwls.closePort(mapping);
                        status = "The game could not open the world on port " + port + ".";
                    } else {
                        hostedMapping = mapping;
                        hostedCode = code;
                        status = "Open. Give friends the code. It works until you stop sharing or close the world.";
                    }
                    busy = false;
                });
            } catch (Wwls.WwlsException e) {
                status = e.getMessage();
                busy = false;
            }
        }, "Pentecost WWLS host");
        t.setDaemon(true);
        t.start();
    }

    private void stop() {
        String code = hostedCode;
        Wwls.Mapping mapping = hostedMapping;
        hostedCode = null;
        hostedMapping = null;
        status = "Stopped. Friends already inside stay until they leave.";
        Thread t = new Thread(() -> {
            if (code != null) {
                Wwls.unregister(code);
            }
            if (mapping != null) {
                Wwls.closePort(mapping);
            }
        }, "Pentecost WWLS stop");
        t.setDaemon(true);
        t.start();
    }

    private void join() {
        String code = Wwls.cleanCode(typed);
        if (busy) {
            return;
        }
        if (code.length() < 6) {
            status = "Type the room code first.";
            return;
        }
        busy = true;
        status = "Looking for the world...";
        Minecraft mc = Minecraft.getInstance();
        Thread t = new Thread(() -> {
            try {
                String address = Wwls.find(code);
                mc.execute(() -> ConnectScreen.startConnecting(this, mc, ServerAddress.parseString(address),
                        new ServerData("Friend's world", address, false), false));
            } catch (Wwls.WwlsException e) {
                status = e.getMessage();
            }
            busy = false;
        }, "Pentecost WWLS join");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- drawing

    private void centered(GuiGraphics g, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, boolean enabled, Runnable action) {
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, enabled ? 0x66FFFFFF : 0x24FFFFFF);
        }
        int color = !enabled ? Ui.FAINT : hover ? Ui.BLACK : Ui.TEXT;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, color, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
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
        int colW = Math.min(280, vw - 40);
        int x0 = cx - colW / 2;
        boolean inWorld = minecraft.getSingleplayerServer() != null;

        // Header, centered: logo and wordmark, the full name under it, a hairline.
        int y = Math.max(20, vh / 12);
        String word = "WWLS";
        int total = (Ui.LOGO_W + 5) * 2 + Ui.wordmarkWidth(word, 2);
        int hx = cx - total / 2;
        Ui.logo(g, hx, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, word, hx + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        y += 34;
        centered(g, "WORLD WIDE LOCAL SERVER", cx, y, Ui.MUTED);
        y += 14;
        g.fill(x0, y, x0 + colW, y + 1, Ui.LINE);
        y += 16;

        // Host.
        centered(g, "HOST", cx, y, Ui.FAINT);
        y += 14;
        String code = hostedCode;
        if (code == null) {
            button(g, "Open this world to friends", x0, y, colW, mx, my, inWorld && !busy, this::host);
            y += 26;
            if (!inWorld) {
                centered(g, "Open a world first to share it.", cx, y, Ui.MUTED);
                y += 14;
            }
        } else {
            float scale = 3f;
            Ui.text(g, font, code, cx - font.width(code) * scale / 2f, y, Ui.TEXT, scale);
            y += 34;
            int half = (colW - 6) / 2;
            button(g, "Copy code", x0, y, half, mx, my, true,
                    () -> minecraft.keyboardHandler.setClipboard(hostedCode == null ? "" : hostedCode));
            button(g, "Stop sharing", x0 + half + 6, y, half, mx, my, true, this::stop);
            y += 26;
        }
        y += 14;

        // Join.
        centered(g, "JOIN", cx, y, Ui.FAINT);
        y += 14;
        Ui.outline(g, x0, y, x0 + colW, y + 22, 0x66FFFFFF);
        if (typed.isEmpty()) {
            g.drawString(font, "Room code", x0 + 8, y + 7, Ui.FAINT, false);
        } else {
            g.drawString(font, typed, x0 + 8, y + 7, Ui.TEXT, false);
        }
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = x0 + 8 + (typed.isEmpty() ? 0 : font.width(typed));
            g.fill(caret, y + 5, caret + 1, y + 17, Ui.TEXT);
        }
        y += 28;
        button(g, "Join", x0, y, colW, mx, my, !busy, this::join);
        y += 30;

        // Status.
        for (String line : Ui.wrap(font, status, colW)) {
            centered(g, line, cx, y, Ui.SOFT);
            y += 11;
        }

        // Back, bottom center.
        int bw = font.width("Back") + 40;
        button(g, "Back", cx - bw / 2, vh - 32, bw, mx, my, true, this::onClose);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (Character.isLetterOrDigit(c) && typed.length() < 12) {
            typed += Character.toUpperCase(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 259 && !typed.isEmpty()) { // backspace
            typed = typed.substring(0, typed.length() - 1);
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // enter
            join();
            return true;
        }
        if (Screen.isPaste(keyCode)) {
            String clip = Wwls.cleanCode(minecraft.keyboardHandler.getClipboard());
            typed = clip.length() > 12 ? clip.substring(0, 12) : clip;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        minecraft.setScreen(parent);
    }
}
