package khi.pentecost.game;

import khi.pentecost.core.Wwls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.HttpUtil;

import java.util.List;

/** World Wide Local Server: friends join a world by a short code. No port forwarding, no accounts for them to make. */
public final class WwlsScreen extends Screen {
    /** The world open to friends right now, if any. It outlives the screen. */
    private static volatile String hostedCode;
    private static volatile Wwls.Mapping hostedMapping;
    private static volatile String status = "";

    private final Screen parent;
    private EditBox codeBox;
    private Button open;
    private Button close;
    private Button copy;
    private Button join;

    public WwlsScreen(Screen parent) {
        super(Component.literal("World Wide Local Server"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.getSingleplayerServer() != null;
        open = addRenderableWidget(Button.builder(Component.literal("Open this world to friends"), b -> host())
                .bounds(cx - 100, 70, 200, 20).build());
        open.active = inWorld && hostedCode == null;
        copy = addRenderableWidget(Button.builder(Component.literal("Copy code"),
                b -> mc.keyboardHandler.setClipboard(hostedCode == null ? "" : hostedCode)).bounds(cx - 100, 94, 98, 20).build());
        copy.active = hostedCode != null;
        close = addRenderableWidget(Button.builder(Component.literal("Stop sharing"), b -> stop())
                .bounds(cx + 2, 94, 98, 20).build());
        close.active = hostedCode != null;
        codeBox = new EditBox(font, cx - 100, 160, 200, 20, Component.literal("Room code"));
        codeBox.setMaxLength(12);
        codeBox.setHint(Component.literal("Room code"));
        addRenderableWidget(codeBox);
        join = addRenderableWidget(Button.builder(Component.literal("Join"), b -> join())
                .bounds(cx - 100, 184, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 100, height - 32, 200, 20).build());
    }

    private void host() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        open.active = false;
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
                    rebuild();
                });
            } catch (Wwls.WwlsException e) {
                status = e.getMessage();
                mc.execute(this::rebuild);
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
        rebuild();
    }

    private void join() {
        String code = Wwls.cleanCode(codeBox.getValue());
        if (code.length() < 6) {
            status = "Type the room code first.";
            return;
        }
        join.active = false;
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
            mc.execute(() -> {
                if (join != null) {
                    join.active = true;
                }
            });
        }, "Pentecost WWLS join");
        t.setDaemon(true);
        t.start();
    }

    private void rebuild() {
        if (minecraft != null && minecraft.screen == this) {
            clearWidgets();
            init();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int cx = width / 2;
        g.drawCenteredString(font, title, cx, 20, 0xFFFFFF);
        g.drawCenteredString(font, "HOST", cx, 52, 0x808080);
        if (hostedCode != null) {
            g.pose().pushPose();
            g.pose().translate(cx, 122, 0);
            g.pose().scale(2f, 2f, 1f);
            g.drawCenteredString(font, hostedCode, 0, 0, 0xFFFFFF);
            g.pose().popPose();
        } else if (minecraft.getSingleplayerServer() == null) {
            g.drawCenteredString(font, "Open a world first to share it.", cx, 122, 0x808080);
        }
        g.drawCenteredString(font, "JOIN", cx, 142, 0x808080);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(status), 280);
        int y = 216;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            g.drawCenteredString(font, line, cx, y, 0xC0C0C0);
            y += 11;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
