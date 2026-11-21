package khi.pentecost.game;

import khi.pentecost.core.ConnectionLimiter;
import khi.pentecost.core.HostGuard;
import khi.pentecost.core.JoinApprovals;
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

public final class WwlsScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static volatile Wwls.Session session;
    private static volatile HostGuard guard;
    private static volatile JoinApprovals approvals;
    private static volatile String status = "";
    private static volatile boolean busy;
    private static String typed = "";

    private static volatile int failures;
    private static volatile long lockedUntil;
    private static volatile long nextHostAt;

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();

    public WwlsScreen(Screen parent) {
        super(Component.literal("World Wide Local Server"));
        this.parent = parent;
    }

    static boolean hosting() {
        return live() != null;
    }

    static String hostAddress() {
        Wwls.Session s = live();
        return s == null ? null : s.address();
    }

    static String hostCode() {
        Wwls.Session s = live();
        return s == null ? null : s.code();
    }

    static void joinFromDiscord(String raw) {
        String code = Wwls.cleanCode(raw);
        if (code.length() != Wwls.CODE_LENGTH) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            typed = code;
            boolean inWorld = mc.level != null && !khi.pentecost.game.menuworld.MenuHost.showing();
            if (inWorld) {
                khi.pentecost.game.PentecostToast.show(Component.literal("A friend invited you"),
                        Component.literal("Leave your world and open WWLS. The code is filled in."));
                return;
            }
            WwlsScreen screen = new WwlsScreen(mc.screen);
            mc.setScreen(screen);
            screen.join();
        });
    }

    static void answerFor(java.util.UUID id, boolean yes) {
        JoinApprovals ap = approvals;
        if (ap == null) {
            return;
        }
        for (JoinApprovals.Request r : waitingList()) {
            if (r.id().equals(id)) {
                if (yes) {
                    if (ap.approve(id)) {
                        status = r.name() + " can join now. Ask them to press Join again.";
                    }
                } else {
                    ap.deny(id);
                    status = r.name() + " was turned away.";
                }
            }
        }
    }

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

    private static void announce(Minecraft mc, JoinApprovals.Request r) {
        khi.pentecost.game.PentecostToast.show(Component.literal(r.name() + " wants to join"), Component.literal("Press Y to let them in, N for no."));
        mc.gui.getChat().addMessage(Component.literal("[Pentecost] " + r.name()
                + " wants to join your world. Press Y to let them in, N to say no, O to open WWLS."));
    }

    static List<JoinApprovals.Request> waitingList() {
        JoinApprovals ap = approvals;
        if (live() == null || ap == null) {
            return List.of();
        }
        return ap.pending();
    }

    static void answer(boolean yes) {
        JoinApprovals ap = approvals;
        List<JoinApprovals.Request> list = waitingList();
        if (ap == null || list.isEmpty()) {
            return;
        }
        JoinApprovals.Request r = list.get(0);
        if (yes) {
            if (ap.approve(r.id())) {
                status = r.name() + " can join now. Ask them to press Join again.";
            }
        } else {
            ap.deny(r.id());
            status = r.name() + " was turned away.";
        }
    }

    static void open(Minecraft mc) {
        mc.setScreen(new WwlsScreen(mc.screen));
    }

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
                FriendsScreen.useConfig(mc);
                Wwls.Session s = Wwls.host(port, server::isRunning);
                mc.execute(() -> {
                    ConnectionLimiter limiter = new ConnectionLimiter();
                    boolean ok = server.publishServer(mc.gameMode.getPlayerMode(), server.getWorldData().getAllowCommands(), port);
                    if (!ok) {
                        closeAsync(s);
                        status = "The game could not open the world on port " + port + ".";
                    } else {
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
                    lockedUntil = System.currentTimeMillis() + Math.min(300_000L, 30_000L * (n - 2));
                }
                status = e.getMessage();
            }
            busy = false;
        }, "Pentecost WWLS join");
        t.setDaemon(true);
        t.start();
    }

    private long opened;

    private static float ease(double v) {
        double c = Math.max(0, Math.min(1, v));
        return (float) (1 - Math.pow(1 - c, 3));
    }

    private static int fade(int color, float a) {
        int al = (int) (((color >>> 24) & 0xFF) * a);
        return (Math.max(0, Math.min(255, al)) << 24) | (color & 0xFFFFFF);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, boolean enabled, boolean solid, Runnable action) {
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.TEXT);
        } else if (solid && enabled) {
            g.fill(x, y, x + w, y + 20, 0x26FFFFFF);
            Ui.outline(g, x, y, x + w, y + 20, 0x80FFFFFF);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, enabled ? 0x66FFFFFF : 0x24FFFFFF);
        }
        int color = !enabled ? Ui.FAINT : hover ? Ui.BLACK : Ui.TEXT;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, color, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
    }

    private void card(GuiGraphics g, int x, int y, int w, int h, String title, String right, float in) {
        int off = (int) ((1 - in) * 8);
        g.fill(x, y + off, x + w, y + h + off, fade(0x66000000, in));
        Ui.outline(g, x, y + off, x + w, y + h + off, fade(0x26FFFFFF, in));
        g.fill(x, y + off, x + 14, y + off + 1, fade(Ui.TEXT, in));
        g.drawString(font, title, x + 14, y + off + 12, fade(Ui.FAINT, in), false);
        if (right != null) {
            g.drawString(font, right, x + w - 14 - font.width(right), y + off + 12, fade(Ui.MUTED, in), false);
        }
    }

    private int boxes(GuiGraphics g, String text, int x, int y, int w, boolean caret, boolean lit) {
        int n = Wwls.CODE_LENGTH;
        int gap = 4;
        int bw = Math.max(14, Math.min(30, (w - (n - 1) * gap) / n));
        int total = n * bw + (n - 1) * gap;
        int bx = x + (w - total) / 2;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        for (int i = 0; i < n; i++) {
            int cx = bx + i * (bw + gap);
            boolean filled = i < text.length();
            boolean next = caret && i == text.length();
            g.fill(cx, y, cx + bw, y + 30, filled ? (lit ? 0x26FFFFFF : 0x14FFFFFF) : 0x0AFFFFFF);
            Ui.outline(g, cx, y, cx + bw, y + 30, next ? 0xAAFFFFFF : filled ? 0x55FFFFFF : 0x22FFFFFF);
            if (filled) {
                String ch = text.substring(i, i + 1);
                float s = 2f;
                Ui.text(g, font, ch, cx + (bw - font.width(ch) * s) / 2f, y + 8, Ui.TEXT, s);
            } else if (next && blink) {
                g.fill(cx + bw / 2, y + 7, cx + bw / 2 + 1, y + 23, Ui.TEXT);
            }
        }
        return y + 30;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (opened == 0) {
            opened = net.minecraft.Util.getMillis();
        }
        double t = (net.minecraft.Util.getMillis() - opened) / 1000.0;
        g.fill(0, 0, width, height, Ui.back(minecraft));
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        boolean inWorld = minecraft.getSingleplayerServer() != null;
        Wwls.Session open = live();
        HostGuard watch = guard;
        if (open == null && watch != null && !watch.reason().isEmpty()) {
            status = watch.reason();
            guard = null;
        }
        if (open == null) {
            approvals = null;
        }

        int margin = 48;
        Ui.logo(g, margin, 24, 2, Ui.TEXT);
        Ui.wordmark(g, "WWLS", margin + (Ui.LOGO_W + 5) * 2, 26, 2, Ui.TEXT);
        String sub = "WORLD WIDE LOCAL SERVER";
        g.drawString(font, sub, vw - margin - font.width(sub), 40, Ui.MUTED, false);
        g.fill(margin, 66, vw - margin, 67, Ui.LINE);

        int areaW = Math.min(760, vw - margin * 2);
        int x0 = (vw - areaW) / 2;
        boolean two = areaW >= 500;
        int gap = 16;
        int colW = two ? (areaW - gap) / 2 : areaW;
        int top = 86;

        JoinApprovals ap = approvals;
        List<JoinApprovals.Request> asking = open == null || ap == null ? List.of() : ap.pending();
        int shown = Math.min(3, asking.size());
        int hostH = open == null ? 150 : 150 + (shown > 0 ? 20 + shown * 26 : 0) + (asking.size() > shown ? 14 : 0);
        int joinH = 150;
        int cardH = two ? Math.max(hostH, joinH) : 0;

        float inA = ease((t - 0.05) / 0.45);
        int hx = x0;
        int hy = top;
        int count = open == null ? 0 : Math.max(0, minecraft.getSingleplayerServer() == null ? 0 : minecraft.getSingleplayerServer().getPlayerCount() - 1);
        String hostRight = open == null ? null : count == 1 ? "1 FRIEND INSIDE" : count + " FRIENDS INSIDE";
        card(g, hx, hy, colW, two ? cardH : hostH, "HOST", hostRight, inA);
        int off = (int) ((1 - inA) * 8);
        int y = hy + off + 32;
        int ix = hx + 14;
        int iw = colW - 28;
        String code = open == null ? null : open.code();
        if (code == null) {
            Ui.text(g, font, inWorld ? "Share this world" : "No world open", ix, y, fade(Ui.TEXT, inA), 1.4f);
            y += 20;
            String why = inWorld ? "Friends with your room code can ask to join. You say yes or no for each one."
                    : "Open a singleplayer world first, then come back here to share it.";
            for (String line : Ui.wrap(font, why, iw)) {
                g.drawString(font, line, ix, y, fade(Ui.SOFT, inA), false);
                y += 11;
            }
            int by = hy + off + (two ? cardH : hostH) - 34;
            button(g, busy ? "Opening..." : "Open to friends", ix, by, iw, mx, my, inWorld && !busy, true, this::host);
        } else {
            double pulse = 0.5 + 0.5 * Math.sin(t * 3);
            g.fill(ix, y + 2, ix + 4, y + 6, fade(0xFF8FD18F, (float) (0.5 + 0.5 * pulse)));
            g.drawString(font, "Open now", ix + 8, y, 0xFF8FD18F, false);
            y += 14;
            y = boxes(g, code, ix, y, iw, false, true) + 8;
            int half = (iw - 6) / 2;
            button(g, "Copy code", ix, y, half, mx, my, true, true, () -> {
                Wwls.Session s = live();
                minecraft.keyboardHandler.setClipboard(s == null ? "" : s.code());
                status = "Copied. Send it to your friend.";
            });
            button(g, "Stop sharing", ix + half + 6, y, half, mx, my, true, false, this::stop);
            y += 26;
            if (watch != null && watch.limiter().paused()) {
                g.drawString(font, "Heavy traffic: new connections paused.", ix, y, Ui.SOFT, false);
                y += 12;
            }
            if (shown > 0) {
                y += 4;
                g.drawString(font, "ASKING TO JOIN", ix, y, Ui.FAINT, false);
                y += 14;
                for (int i = 0; i < shown; i++) {
                    JoinApprovals.Request r = asking.get(i);
                    g.fill(ix, y, ix + iw, y + 22, 0x0FFFFFFF);
                    g.drawString(font, r.name(), ix + 6, y + 7, Ui.TEXT, false);
                    int noW = 34;
                    int yesW = 54;
                    button(g, "No", ix + iw - noW - 1, y + 1, noW, mx, my, true, false, () -> ap.deny(r.id()));
                    button(g, "Let in", ix + iw - noW - 6 - yesW, y + 1, yesW, mx, my, true, true, () -> {
                        if (ap.approve(r.id())) {
                            status = r.name() + " can join now. Ask them to try the code again.";
                        }
                    });
                    y += 26;
                }
                if (asking.size() > shown) {
                    g.drawString(font, "+" + (asking.size() - shown) + " more waiting", ix, y, Ui.MUTED, false);
                }
            }
        }

        float inB = ease((t - 0.15) / 0.45);
        int jx = two ? x0 + colW + gap : x0;
        int jy = two ? top : top + hostH + gap;
        card(g, jx, jy, colW, two ? cardH : joinH, "JOIN", null, inB);
        int offB = (int) ((1 - inB) * 8);
        y = jy + offB + 32;
        ix = jx + 14;
        Ui.text(g, font, "Join a friend", ix, y, fade(Ui.TEXT, inB), 1.4f);
        y += 20;
        g.drawString(font, "Type or paste their room code.", ix, y, fade(Ui.SOFT, inB), false);
        y += 16;
        boxes(g, typed, ix, y, iw, true, false);
        int jby = jy + offB + (two ? cardH : joinH) - 34;
        button(g, busy ? "Looking..." : "Join", ix, jby, iw, mx, my, !busy && typed.length() == Wwls.CODE_LENGTH, true, this::join);

        int sy = (two ? top + cardH : jy + joinH) + 14;
        if (!status.isEmpty()) {
            int dotC = busy ? ((int) (t * 3) % 2 == 0 ? Ui.TEXT : Ui.FAINT) : Ui.MUTED;
            g.fill(x0, sy + 2, x0 + 4, sy + 6, dotC);
            for (String line : Ui.wrap(font, status, areaW - 10)) {
                g.drawString(font, line, x0 + 10, sy, Ui.SOFT, false);
                sy += 11;
            }
        }

        int bw = 70;
        int fw = 80;
        button(g, "Back", vw - margin - bw, vh - 32, bw, mx, my, true, false, this::onClose);
        button(g, "Friends", vw - margin - bw - 8 - fw, vh - 32, fw, mx, my, true, false, () -> minecraft.setScreen(new FriendsScreen(this)));
        g.pose().popPose();
    }

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
        if (keyCode == 259 && !typed.isEmpty()) {
            typed = typed.substring(0, typed.length() - 1);
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
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
