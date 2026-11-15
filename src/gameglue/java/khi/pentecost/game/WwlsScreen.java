package khi.pentecost.game;

import khi.pentecost.core.ConnectionLimiter;
import khi.pentecost.core.HostGuard;
import khi.pentecost.core.JoinApprovals;
import khi.pentecost.core.Wwls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
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
 *
 * A world open to friends is protected four ways: a connection guard turns away floods and repeat connections,
 * the host says yes or no to every account before it can play, a watchdog closes the sharing when nobody joins
 * for 30 minutes or when floods keep coming, and the buttons here have cooldowns so nothing can be hammered.
 * When the sharing ends for any reason, the world stops listening for new connections.
 */
public final class WwlsScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    /** The world open to friends right now, if any. It outlives the screen and closes itself when the world stops. */
    private static volatile Wwls.Session session;
    private static volatile HostGuard guard;
    private static volatile JoinApprovals approvals;
    private static volatile String status = "";
    private static volatile boolean busy;
    private static String typed = "";

    /** Failed joins in a row, and the time before which another try is refused. */
    private static volatile int failures;
    private static volatile long lockedUntil;
    private static volatile long nextHostAt;

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();

    public WwlsScreen(Screen parent) {
        super(Component.literal("World Wide Local Server"));
        this.parent = parent;
    }

    /** The session, or null when none is open or it has closed itself. */
    private static Wwls.Session live() {
        Wwls.Session s = session;
        return s != null && !s.closed() ? s : null;
    }

    private static void closeAsync(Wwls.Session s) {
        if (s == null) {
            return;
        }
        Thread t = new Thread(s::close, "Pentecost WWLS stop");
        t.setDaemon(true);
        t.start();
    }

    /** Tells the host that someone is waiting: a pop-up and a line in chat. Runs on the game's main thread. */
    private static void announce(Minecraft mc, JoinApprovals.Request r) {
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.literal(r.name() + " wants to join"), Component.literal("Open WWLS to let them in."));
        mc.gui.getChat().addMessage(Component.literal("[Pentecost] " + r.name()
                + " wants to join your world. Open WWLS to say yes or no."));
    }

    // ---------------------------------------------------------------- actions

    private void host() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || busy) {
            return;
        }
        if (!EulaScreen.agreed()) {
            EulaScreen.ask(mc, this, this::host);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextHostAt) {
            status = "Wait a few seconds before opening again.";
            return;
        }
        nextHostAt = now + 5000;
        busy = true;
        guard = null;
        approvals = null;
        status = "Asking your router to open a port...";
        Thread t = new Thread(() -> {
            int port = HttpUtil.getAvailablePort();
            try {
                Wwls.Session s = Wwls.host(port, server::isRunning);
                mc.execute(() -> {
                    ConnectionLimiter limiter = new ConnectionLimiter();
                    boolean ok = server.publishServer(mc.gameMode.getPlayerMode(), server.getWorldData().getAllowCommands(), port);
                    if (!ok) {
                        closeAsync(s);
                        status = "The game could not open the world on port " + port + ".";
                    } else {
                        // However the sharing ends, the world stops listening for new connections.
                        s.onClose(() -> ConnectionGuard.stopListening(server, port));
                        try {
                            ConnectionGuard.install(server, limiter);
                            JoinApprovals ap = new JoinApprovals(r -> mc.execute(() -> announce(mc, r)));
                            JoinGate gate = JoinGate.start(server, ap);
                            s.onClose(gate::stop);
                            session = s;
                            approvals = ap;
                            guard = HostGuard.start(s, () -> Math.max(0, server.getPlayerCount() - 1), limiter);
                            status = "Open. Friends you give the code to can ask to join, and you say yes or no here. "
                                    + "They can see your internet address. It closes when you stop sharing, close the world, "
                                    + "quit the game, or nobody joins for 30 minutes.";
                        } catch (RuntimeException e) {
                            closeAsync(s);
                            status = "Pentecost could not protect the open world, so it was not shared. " + e.getMessage();
                        }
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
        Wwls.Session s = session;
        session = null;
        guard = null;
        approvals = null;
        status = "Stopped. The world no longer takes new connections. Friends already inside stay until they leave.";
        closeAsync(s);
    }

    private void join() {
        String code = Wwls.cleanCode(typed);
        if (busy) {
            return;
        }
        if (!EulaScreen.agreed()) {
            EulaScreen.ask(Minecraft.getInstance(), this, this::join);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            status = "Too many tries. Wait " + ((lockedUntil - now + 999) / 1000) + " seconds.";
            return;
        }
        if (code.length() != Wwls.CODE_LENGTH) {
            status = "Room codes have " + Wwls.CODE_LENGTH + " letters and numbers.";
            return;
        }
        busy = true;
        status = "Looking for the world...";
        Minecraft mc = Minecraft.getInstance();
        Thread t = new Thread(() -> {
            try {
                String address = Wwls.find(code);
                failures = 0;
                mc.execute(() -> ConnectScreen.startConnecting(this, mc, ServerAddress.parseString(address),
                        new ServerData("Friend's world", address, false), false));
                status = "Connecting. If the host has not said yes to you yet, they were just asked: try again in a minute.";
            } catch (Wwls.WwlsException e) {
                int n = failures + 1;
                failures = n;
                if (n >= 3) {
                    // 30 seconds after the third miss, then a minute, 90 seconds and so on, up to 5 minutes.
                    lockedUntil = System.currentTimeMillis() + Math.min(300_000L, 30_000L * (n - 2));
                }
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

        // When the watchdog closed the sharing, say why once.
        Wwls.Session open = live();
        HostGuard watch = guard;
        if (open == null && watch != null && !watch.reason().isEmpty()) {
            status = watch.reason();
            guard = null;
        }
        if (open == null) {
            approvals = null;
        }

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
        String code = open == null ? null : open.code();
        if (code == null) {
            button(g, "Open this world to friends", x0, y, colW, mx, my, inWorld && !busy, this::host);
            y += 26;
            if (!inWorld) {
                centered(g, "Open a world first to share it.", cx, y, Ui.MUTED);
                y += 14;
            }
        } else {
            float scale = 2.4f;
            Ui.text(g, font, code, cx - font.width(code) * scale / 2f, y, Ui.TEXT, scale);
            y += 34;
            int half = (colW - 6) / 2;
            button(g, "Copy code", x0, y, half, mx, my, true, () -> {
                Wwls.Session s = live();
                minecraft.keyboardHandler.setClipboard(s == null ? "" : s.code());
            });
            button(g, "Stop sharing", x0 + half + 6, y, half, mx, my, true, this::stop);
            y += 26;
            if (watch != null && watch.limiter().paused()) {
                centered(g, "Heavy traffic: new connections are paused for a moment.", cx, y, Ui.SOFT);
                y += 14;
            }

            // Anyone waiting for a yes or a no.
            JoinApprovals ap = approvals;
            List<JoinApprovals.Request> asking = ap == null ? List.of() : ap.pending();
            if (!asking.isEmpty()) {
                y += 4;
                centered(g, "ASKING TO JOIN", cx, y, Ui.FAINT);
                y += 14;
                int shown = Math.min(3, asking.size());
                for (int i = 0; i < shown; i++) {
                    JoinApprovals.Request r = asking.get(i);
                    g.drawString(font, r.name(), x0, y + 6, Ui.TEXT, false);
                    int noW = 36;
                    int yesW = 64;
                    button(g, "No", x0 + colW - noW, y, noW, mx, my, true, () -> ap.deny(r.id()));
                    button(g, "Let in", x0 + colW - noW - 6 - yesW, y, yesW, mx, my, true, () -> {
                        if (ap.approve(r.id())) {
                            status = r.name() + " can join now. Ask them to try the code again.";
                        }
                    });
                    y += 26;
                }
                if (asking.size() > shown) {
                    centered(g, "+" + (asking.size() - shown) + " more waiting", cx, y, Ui.MUTED);
                    y += 14;
                }
            }
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
        if (Character.isLetterOrDigit(c) && typed.length() < Wwls.CODE_LENGTH) {
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
            typed = clip.length() > Wwls.CODE_LENGTH ? clip.substring(0, Wwls.CODE_LENGTH) : clip;
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
