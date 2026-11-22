package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.core.Friends;
import khi.pentecost.core.Wwls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class FriendsScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private record Streak(float x0, float y0, float x1, float y1, double start, double dur, boolean bright) {}

    private enum Focus { NONE, ADD, CHAT }

    private static final int GOOD = 0xFF8FD18F;
    private static final int PX = 2;
    private static final ResourceLocation SKY = new ResourceLocation("pentecost", "friends_sky");
    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    private static final ConcurrentLinkedQueue<String> PINGS = new ConcurrentLinkedQueue<>();

    private final Screen parent;
    private final OnlineFriends online = OnlineFriends.get();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Streak> streaks = new ArrayList<>();
    private String typed = "";
    private String chatText = "";
    private String status = "";
    private String selected;
    private Focus focus = Focus.NONE;
    private long opened;
    private double nextAmbient = 4;
    private int horizon;
    private DynamicTexture skyTex;
    private int skyW = -1;
    private int skyH = -1;
    private int moonX;

    public FriendsScreen(Screen parent) {
        super(Component.literal("Friends"));
        this.parent = parent;
    }

    static void ping(String uuid) {
        PINGS.add(uuid);
    }

    private static volatile boolean watching;

    static void startWatcher(Minecraft mc) {
        if (watching) {
            return;
        }
        watching = true;
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pentecost-friends-watch");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        java.util.Set<String> wasHosting = new java.util.HashSet<>();
        ex.scheduleWithFixedDelay(() -> {
            try {
                List<Friends.Friend> list = Friends.list();
                if (list.isEmpty()) {
                    return;
                }
                Friends.check();
                for (Friends.Friend f : list) {
                    Friends.Status st = Friends.status(f.code());
                    boolean now = st != null && st.hosting();
                    if (now && wasHosting.add(f.code())) {
                        mc.execute(() -> PentecostToast.show(Component.literal(f.name() + " is hosting"), Component.literal("Open Friends to join.")));
                    } else if (!now) {
                        wasHosting.remove(f.code());
                    }
                }
            } catch (RuntimeException e) {
                khi.pentecost.boot.Log.warn("Friends check failed: " + e);
            }
        }, 20, 120, java.util.concurrent.TimeUnit.SECONDS);
    }

    static void useConfig(Minecraft mc) {
        Friends.use(mc.gameDirectory.toPath().resolve("config").resolve("pentecost").resolve("friends.json"));
    }

    @Override
    protected void init() {
        useConfig(minecraft);
        online.refresh();
        if (opened == 0) {
            opened = net.minecraft.Util.getMillis();
        }
    }

    private static int tint(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
    }

    private static int hash(int n) {
        int h = n * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    @Override
    public void removed() {
        if (skyTex != null) {
            minecraft.getTextureManager().release(SKY);
            skyTex = null;
            skyW = -1;
        }
    }

    private static double rnd(int x, int y, int seed) {
        return hash(x * 73856093 ^ y * 19349663 ^ seed * 83492791) / (double) 0x7FFFFFFF;
    }

    private static double noise(double x, double y, int seed) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = x - ix;
        double fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = rnd(ix, iy, seed);
        double b = rnd(ix + 1, iy, seed);
        double c = rnd(ix, iy + 1, seed);
        double d = rnd(ix + 1, iy + 1, seed);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static double fbm(double x, double y, int seed) {
        double v = 0;
        double amp = 0.5;
        for (int i = 0; i < 5; i++) {
            v += noise(x, y, seed + i * 31) * amp;
            x *= 2.03;
            y *= 2.03;
            amp *= 0.5;
        }
        return v;
    }

    private static int dither(double v, int x, int y) {
        double c = Math.max(0, Math.min(255, v));
        int base = (int) (c / 6) * 6;
        double frac = (c - base) / 6.0;
        return Math.min(255, frac * 16 > BAYER[y & 3][x & 3] ? base + 6 : base);
    }

    private void buildSky(int vw, int vh, int region) {
        int tw = (vw + PX - 1) / PX;
        int th = (vh + PX - 1) / PX;
        if (tw == skyW && th == skyH && skyTex != null) {
            return;
        }
        if (skyTex != null) {
            minecraft.getTextureManager().release(SKY);
        }
        skyW = tw;
        skyH = th;
        int mx = (int) (region / PX * 0.80);
        int my = (int) (th * 0.17);
        int mr = Math.max(9, th / 13);
        moonX = mx * PX;
        int hz = (int) (th * 0.80);
        horizon = hz * PX;
        int seaTop = (int) (th * 0.86);
        NativeImage img = new NativeImage(tw, th, false);
        for (int y = 0; y < th; y++) {
            double ty = y / (double) th;
            for (int x = 0; x < tw; x++) {
                double v = 7 + 16 * Math.pow(ty, 1.6);
                double band = (x / (double) tw) * 0.9 - ty * 0.7 + 0.15;
                double neb = fbm(x * 0.018, y * 0.03, 11);
                double neb2 = fbm(x * 0.05 + 7, y * 0.05, 23);
                v += Math.exp(-band * band * 10) * Math.max(0, neb - 0.38) * 2.4 * (26 + 22 * neb2);
                double dx = x - mx;
                double dy = y - my;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > mr) {
                    double halo = Math.max(0, 1 - (dist - mr) / (mr * 3.2));
                    v += halo * halo * 30;
                }
                double ridgeFar = hz - 10 - fbm(x * 0.02, 3, 41) * 22;
                double ridgeNear = hz - 2 - fbm(x * 0.035, 9, 57) * 13;
                if (y >= seaTop) {
                    double depth = (y - seaTop) / (double) Math.max(1, th - seaTop);
                    v = 10 - depth * 5;
                    double col = Math.abs(x - mx) / (double) (mr * (1.2 + depth * 2.5));
                    if (col < 1 && rnd(x / 3, y, 5) > 0.35 + col * 0.5) {
                        v += 70 * (1 - col) * (1 - depth * 0.6);
                    }
                    if (y == seaTop) {
                        v += 8;
                    }
                } else if (y >= ridgeNear) {
                    v = 3;
                } else if (y >= ridgeFar) {
                    v = 8;
                }
                int g = dither(v, x, y);
                if (y < ridgeFar - 1 && dist > mr + 1) {
                    double s = rnd(x, y, 99);
                    if (s > 0.9965) {
                        g = Math.max(g, 70 + (int) (rnd(x, y, 7) * 120));
                    } else if (s > 0.993) {
                        g = Math.max(g, 38);
                    }
                }
                if (dist <= mr) {
                    double sx = dx + mr * 0.42;
                    double shadow = Math.sqrt(sx * sx + dy * dy);
                    double lit = shadow > mr * 0.98 ? 1 : 0.1;
                    double crater = fbm(x * 0.35, y * 0.35, 71);
                    double m = 205 - crater * 70 - (dist / mr) * 25;
                    g = dither(m * lit + 10, x, y);
                }
                img.setPixelRGBA(x, y, 0xFF000000 | (g << 16) | (g << 8) | g);
            }
        }
        skyTex = new DynamicTexture(img);
        minecraft.getTextureManager().register(SKY, skyTex);
    }

    private void sea(GuiGraphics g, int vh, double t) {
        int seaTop = (int) (skyH * 0.86) * PX;
        for (int i = 0; i < 26; i++) {
            int row = seaTop + 2 + hash(i * 5 + 1) % Math.max(1, vh - seaTop - 2);
            double depth = (row - seaTop) / (double) Math.max(1, vh - seaTop);
            double phase = t * (0.5 + (i % 5) * 0.12) + i * 1.9;
            int span = (int) (10 + depth * 46);
            int x = (int) (moonX + Math.sin(phase) * span) / PX * PX;
            int len = (int) (4 + 6 * (0.5 + 0.5 * Math.sin(phase * 1.7))) * PX / 2;
            int a = (int) (90 * (1 - depth * 0.5) * (0.5 + 0.5 * Math.sin(phase * 2.3)));
            g.fill(x, row / PX * PX, x + len, row / PX * PX + PX, tint(a));
        }
    }

    private void join(OnlineFriends.Friend f) {
        Minecraft mc = Minecraft.getInstance();
        String address = f.server();
        if (!EulaScreen.agreed()) {
            EulaScreen.ask(mc, this, () -> join(f));
            return;
        }
        if (!Wwls.validAddress(address)) {
            status = "That address is not one Pentecost connects to.";
            return;
        }
        if (mc.level != null && !khi.pentecost.game.menuworld.MenuHost.showing()) {
            status = "Leave your world first, then join.";
            return;
        }
        if (address.equals(WwlsScreen.hostAddress())) {
            status = "That is your own world.";
            return;
        }
        ConnectScreen.startConnecting(this, mc, ServerAddress.parseString(address), new ServerData(f.name() + "'s world", address, false), false);
    }

    private void addFriend() {
        if (typed.isBlank()) {
            status = "Type the code your friend gave you.";
            return;
        }
        String code = typed;
        typed = "";
        status = "Asking";
        online.add(code, text -> status = text);
    }

    private void sendChat() {
        OnlineFriends.Friend f = online.friend(selected);
        if (f == null || chatText.isBlank()) {
            return;
        }
        String text = chatText;
        chatText = "";
        online.message(f.uuid(), text, err -> status = err);
    }

    private void select(String uuid) {
        selected = uuid.equals(selected) ? null : uuid;
        focus = selected == null ? Focus.NONE : Focus.CHAT;
        if (selected != null) {
            online.read(uuid);
        }
    }

    private static String stateText(OnlineFriends.Friend f) {
        if (!f.online()) {
            return "Offline";
        }
        if (!f.server().isEmpty()) {
            return "Hosting, open to join";
        }
        return switch (f.state()) {
            case "singleplayer" -> f.world().isEmpty() ? "Playing a world" : "Playing " + f.world();
            case "multiplayer" -> "On a server";
            case "idle" -> "Away";
            default -> "In the menu";
        };
    }

    private static float ease(double t) {
        double c = Math.max(0, Math.min(1, t));
        return (float) (1 - Math.pow(1 - c, 3));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        double t = (net.minecraft.Util.getMillis() - opened) / 1000.0;
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.fill(0, 0, width, height, 0xFF000000);
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int panelW = Math.max(200, Math.min(250, vw * 3 / 10));
        int region = vw - panelW - 24;
        buildSky(vw, vh, region);
        g.blit(SKY, 0, 0, skyW * PX, skyH * PX, 0f, 0f, skyW, skyH, skyW, skyH);
        sea(g, vh, t);
        sky(g, region, vh, t, mx, my);
        panel(g, vw - panelW - 24, panelW, vh, mx, my, t);

        float in = ease(t / 0.6);
        int tint = (int) (255 * in);
        Ui.logo(g, 24, 18, 2, (tint << 24) | 0xFFFFFF);
        Ui.wordmark(g, "FRIENDS", 24 + (Ui.LOGO_W + 5) * 2, 20, 2, (tint << 24) | 0xFFFFFF);
        List<OnlineFriends.Friend> list = online.friends();
        int on = 0;
        for (OnlineFriends.Friend f : list) {
            if (f.online()) {
                on++;
            }
        }
        String sub = list.isEmpty() ? "NO FRIENDS YET" : on + " ONLINE  /  " + list.size() + (list.size() == 1 ? " FRIEND" : " FRIENDS");
        g.drawString(font, sub, 24, 52, Ui.mix(0x00FFFFFF, Ui.MUTED, in), false);
        g.pose().popPose();
    }

    private void sky(GuiGraphics g, int region, int vh, double t, int mx, int my) {
        float night = ease(t / 0.8);
        for (int i = 0; i < 140; i++) {
            int x = hash(i * 2 + 1) % Math.max(1, region);
            int y = hash(i * 2 + 2) % Math.max(1, (int) (horizon * 0.85));
            double fadeDown = 1 - y / (horizon * 0.85);
            double tw = 0.5 + 0.5 * Math.sin(t * (0.6 + (i % 7) * 0.19) + i * 2.7);
            int a = (int) (night * fadeDown * (i % 9 == 0 ? 60 + 140 * tw : 20 + 60 * tw));
            int s = i % 23 == 0 ? 2 : 1;
            g.fill(x, y, x + s, y + s, tint(a));
        }

        if (t > nextAmbient) {
            double a = hash((int) (t * 10)) % 1000 / 1000.0;
            float sx = (float) (region * (0.15 + a * 0.6));
            float sy = (float) (20 + a * horizon * 0.25);
            streaks.add(new Streak(sx, sy, sx - 120, sy + 60, t, 0.9, false));
            nextAmbient = t + 7 + (hash((int) (t * 7)) % 900) / 100.0;
        }

        List<OnlineFriends.Friend> list = online.friends();
        int cx = region / 2;
        int cy = Math.min((int) (horizon * 0.5), horizon - 70);
        int maxRx = Math.max(60, region / 2 - 50);
        int maxRy = Math.max(40, Math.min(cy - 70, horizon - cy - 34));
        int n = list.size();
        float[] px = new float[n];
        float[] py = new float[n];
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            double angle = i * 2.399963 + 0.6;
            double r = Math.min(1.0, (0.45 + 0.18 * Math.sqrt(i)));
            float grow = 0.55f + 0.45f * ease((t - 0.2 - i * 0.07) / 0.9);
            px[i] = (float) (cx + Math.cos(angle) * maxRx * r * grow + Math.sin(t * 0.4 + i) * 1.5);
            py[i] = (float) (cy + Math.sin(angle) * maxRy * r * grow + Math.cos(t * 0.35 + i * 1.7) * 1.5);
            ids[i] = list.get(i).uuid();
        }

        String p;
        while ((p = PINGS.poll()) != null) {
            for (int i = 0; i < n; i++) {
                if (ids[i].equals(p)) {
                    streaks.add(new Streak(px[i], py[i], cx, cy, t, 0.8, true));
                }
            }
        }

        for (int i = 0; i < n; i++) {
            OnlineFriends.Friend f = list.get(i);
            float draw = ease((t - 0.35 - i * 0.07) / 0.8);
            if (draw <= 0) {
                continue;
            }
            double dx = px[i] - cx;
            double dy = py[i] - cy;
            double len = Math.sqrt(dx * dx + dy * dy);
            boolean on = f.uuid().equals(selected);
            int a = on ? 170 : f.online() ? 70 : 26;
            double end = (len - 9) * draw;
            for (double k = 10; k < end; k += f.online() ? 4 : 7) {
                int x = (int) (cx + dx * k / len);
                int y = (int) (cy + dy * k / len);
                g.fill(x, y, x + 1, y + 1, tint(a));
            }
        }

        double pulse = 0.5 + 0.5 * Math.sin(t * 1.3);
        Ui.outline(g, cx - 9, cy - 9, cx + 10, cy + 10, tint((int) (30 + 30 * pulse)));
        star(g, cx, cy, 4, 255, t, 0);
        String me = Account.signedIn() ? Account.name() : "You";
        g.drawString(font, me, cx - font.width(me) / 2, cy + 14, Ui.TEXT, false);

        if (n == 0) {
            String line = online.connected() ? "Your sky is empty. Add a friend with their code." : online.why();
            int a = (int) (255 * ease((t - 0.4) / 0.6));
            g.drawString(font, line, cx - font.width(line) / 2, cy + 38, (Math.max(4, a) << 24) | (Ui.FAINT & 0xFFFFFF), false);
        }

        String hover = null;
        for (int i = 0; i < n; i++) {
            OnlineFriends.Friend f = list.get(i);
            float app = ease((t - 0.2 - i * 0.07) / 0.6);
            if (app <= 0) {
                continue;
            }
            int x = Math.round(px[i]);
            int y = Math.round(py[i]);
            boolean over = Ui.over(mx, my, x - 12, y - 12, 24, 24);
            boolean on = f.uuid().equals(selected);
            int size = f.online() ? 3 : 2;
            int alpha = (int) ((f.online() ? 255 : 90) * app);
            star(g, x, y, size, over || on ? (int) (255 * app) : alpha, t, i + 1);
            if (f.online() && !f.server().isEmpty()) {
                double ring = (t * 0.8 + i * 0.3) % 1.0;
                int rr = (int) (6 + ring * 10);
                Ui.outline(g, x - rr, y - rr, x + rr + 1, y + rr + 1, tint((int) (140 * (1 - ring))));
            }
            if (on) {
                Ui.outline(g, x - 11, y - 11, x + 12, y + 12, tint(220));
                g.fill(x - 13, y - 13, x - 9, y - 12, Ui.TEXT);
                g.fill(x + 10, y + 12, x + 14, y + 13, Ui.TEXT);
            }
            if (online.unread(f.uuid()) > 0) {
                int blink = (int) (t * 2) % 2 == 0 ? 255 : 160;
                g.fill(x + 6, y - 9, x + 10, y - 5, tint(blink));
            }
            int color = over || on ? Ui.TEXT : f.online() ? Ui.SOFT : Ui.FAINT;
            g.drawString(font, f.name(), x - font.width(f.name()) / 2, y + 12, (((int) (255 * app)) << 24) | (color & 0xFFFFFF), false);
            if (over) {
                hover = stateText(f);
                String s = hover;
                g.drawString(font, s, x - font.width(s) / 2, y + 22, f.online() && !f.server().isEmpty() ? GOOD : Ui.MUTED, false);
            }
            String id = f.uuid();
            hits.add(new Hit(x - 12, y - 12, 24, 24, () -> select(id)));
        }

        streaks.removeIf(s -> t - s.start() > s.dur() + 0.4);
        for (Streak s : streaks) {
            double k = (t - s.start()) / s.dur();
            for (int j = 0; j < 14; j++) {
                double q = k - j * 0.025;
                if (q < 0 || q > 1) {
                    continue;
                }
                float x = (float) (s.x0() + (s.x1() - s.x0()) * q);
                float y = (float) (s.y0() + (s.y1() - s.y0()) * q);
                int a = (int) ((s.bright() ? 255 : 170) * (1 - j / 14.0) * (k > 1 ? Math.max(0, 1 - (k - 1) / 0.4) : 1));
                int sz = j == 0 && s.bright() ? 2 : 1;
                g.fill((int) x, (int) y, (int) x + sz, (int) y + sz, tint(a));
            }
        }

        OnlineFriends.Friend sel = online.friend(selected);
        if (sel != null) {
            card(g, region, vh, sel, mx, my, t);
        } else if (selected != null) {
            selected = null;
        }
    }

    private void star(GuiGraphics g, int x, int y, int size, int alpha, double t, int seed) {
        int a = alpha;
        if (alpha > 150) {
            a = 200 + (int) (55 * (0.5 + 0.5 * Math.sin(t * 1.7 + seed * 2.1)));
        }
        int s = size / 2;
        g.fill(x - s, y - s, x + size - s, y + size - s, tint(a));
        if (alpha > 150) {
            int arm = 3 + size;
            g.fill(x - arm, y, x - s - 1, y + 1, tint(a / 3));
            g.fill(x + size - s + 1, y, x + arm + 1, y + 1, tint(a / 3));
            g.fill(x, y - arm, x + 1, y - s - 1, tint(a / 3));
            g.fill(x, y + size - s + 1, x + 1, y + arm + 1, tint(a / 3));
        }
    }

    private void glass(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xC00A0A0C);
        Ui.outline(g, x, y, x + w, y + h, 0x24FFFFFF);
        g.fill(x, y, x + 12, y + 1, Ui.TEXT);
    }

    private void card(GuiGraphics g, int region, int vh, OnlineFriends.Friend f, int mx, int my, double t) {
        int w = Math.min(360, region - 48);
        int h = 132;
        int x = 24;
        int y = vh - h - 20;
        glass(g, x, y, w, h);
        Ui.text(g, font, f.name(), x + 14, y + 11, Ui.TEXT, 1.4f);
        int stateColor = !f.online() ? Ui.FAINT : !f.server().isEmpty() ? GOOD : Ui.MUTED;
        g.fill(x + 14, y + 30, x + 18, y + 34, stateColor);
        g.drawString(font, stateText(f), x + 22, y + 28, stateColor, false);

        int bx = x + w - 12;
        String id = f.uuid();
        int rw = 54;
        bx -= rw;
        button(g, "Remove", bx, y + 10, rw, mx, my, true, false, () -> {
            online.answer("remove", id);
            status = f.name() + " was removed.";
            selected = null;
        });
        if (f.online() && !f.server().isEmpty()) {
            int jw = 46;
            bx -= jw + 6;
            button(g, "Join", bx, y + 10, jw, mx, my, true, true, () -> join(f));
        }
        g.fill(x + 14, y + 42, x + w - 12, y + 43, 0x1FFFFFFF);

        List<String> rows = new ArrayList<>();
        List<Boolean> mine = new ArrayList<>();
        int textW = w - 40;
        for (OnlineFriends.Line line : online.chat(id)) {
            for (String part : Ui.wrap(font, line.text(), textW)) {
                rows.add(part);
                mine.add(line.mine());
            }
        }
        int shown = Math.min(4, rows.size());
        int ly = y + 50 + (4 - shown) * 11;
        for (int i = rows.size() - shown; i < rows.size(); i++) {
            String r = rows.get(i);
            if (mine.get(i)) {
                g.drawString(font, r, x + w - 14 - font.width(r), ly, Ui.MUTED, false);
            } else {
                g.drawString(font, r, x + 14, ly, Ui.TEXT, false);
            }
            ly += 11;
        }
        if (rows.isEmpty()) {
            String s = f.online() ? "Say something." : "They get it when they are back.";
            g.drawString(font, s, x + 14, y + 50 + 33, Ui.FAINT, false);
        }

        int iy = y + h - 28;
        int sw = 50;
        int iw = w - 26 - sw - 6;
        boolean chat = focus == Focus.CHAT;
        g.fill(x + 14, iy, x + 14 + iw, iy + 18, 0x14FFFFFF);
        Ui.outline(g, x + 14, iy, x + 14 + iw, iy + 18, chat ? 0x99FFFFFF : 0x2EFFFFFF);
        String shownText = chatText.isEmpty() ? "Message " + f.name() : tail(chatText, iw - 12);
        g.drawString(font, shownText, x + 20, iy + 5, chatText.isEmpty() ? Ui.FAINT : Ui.TEXT, false);
        if (chat && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = x + 20 + (chatText.isEmpty() ? 0 : font.width(shownText));
            g.fill(caret, iy + 3, caret + 1, iy + 15, Ui.TEXT);
        }
        hits.add(new Hit(x + 14, iy, iw, 18, () -> focus = Focus.CHAT));
        button(g, "Send", x + w - 12 - sw, iy - 1, sw, mx, my, !chatText.isBlank(), true, this::sendChat);
    }

    private String tail(String s, int width) {
        if (font.width(s) <= width) {
            return s;
        }
        int start = 0;
        while (start < s.length() && font.width(s.substring(start)) > width) {
            start++;
        }
        return s.substring(start);
    }

    private void tile(GuiGraphics g, int x, int y, int w, int h, String title, String right, float in) {
        g.fill(x, y, x + w, y + h, fade(0xC00A0A0C, in));
        Ui.outline(g, x, y, x + w, y + h, fade(0x24FFFFFF, in));
        g.fill(x, y, x + 12, y + 1, fade(Ui.TEXT, in));
        g.drawString(font, title, x + 12, y + 10, fade(Ui.FAINT, in), false);
        if (right != null) {
            g.drawString(font, right, x + w - 12 - font.width(right), y + 10, fade(Ui.MUTED, in), false);
        }
    }

    private static int fade(int color, float a) {
        int al = (int) (((color >>> 24) & 0xFF) * a);
        return (Math.max(0, Math.min(255, al)) << 24) | (color & 0xFFFFFF);
    }

    private void panel(GuiGraphics g, int region, int pw, int vh, int mx, int my, double t) {
        int x0 = region;
        int gap = 10;
        int y = 24;
        int ix = x0 + 12;
        int w = pw - 24;

        float in1 = ease((t - 0.1) / 0.45);
        boolean live = online.connected();
        String link = live ? "Online" : online.why().isEmpty() ? "Connecting" : online.why();
        List<String> linkLines = Ui.wrap(font, link, w - 8);
        int h1 = 26 + 16 + linkLines.size() * 10 + 8;
        tile(g, x0, y, pw, h1, "SIGNED IN AS", null, in1);
        int yy = y + 26;
        String me = !online.name().isEmpty() ? online.name() : Account.signedIn() ? Account.name() : "Not signed in";
        Ui.text(g, font, me, ix, yy, fade(Ui.TEXT, in1), 1.3f);
        yy += 16;
        int dot = live ? GOOD : (int) (t * 2) % 2 == 0 ? Ui.FAINT : 0xFF202020;
        g.fill(ix, yy + 2, ix + 4, yy + 6, dot);
        for (String line : linkLines) {
            g.drawString(font, line, ix + 8, yy, live ? GOOD : Ui.MUTED, false);
            yy += 10;
        }
        y += h1 + gap;

        float in2 = ease((t - 0.18) / 0.45);
        List<String> joinLines = Ui.wrap(font, "Friends see your address only while you share a world.", w);
        int h2 = 26 + 34 + 26 + 18 + joinLines.size() * 10 + 8;
        tile(g, x0, y, pw, h2, "YOUR FRIEND CODE", null, in2);
        yy = y + 26;
        String code = online.code();
        String shown = code.length() == 10 ? code.substring(0, 5) + " " + code.substring(5) : code.isEmpty() ? "-----  -----" : code;
        g.fill(ix, yy, ix + w, yy + 28, 0x0FFFFFFF);
        float cs = 1.8f;
        int cw = (int) (font.width(shown) * cs);
        Ui.text(g, font, shown, ix + (w - cw) / 2f, yy + 7, code.isEmpty() ? Ui.FAINT : fade(Ui.TEXT, in2), cs);
        yy += 34;
        button(g, "Copy code", ix, yy, w, mx, my, !code.isEmpty(), true, () -> {
            minecraft.keyboardHandler.setClipboard(code);
            status = "Copied. Your friend types it under Add a friend.";
        });
        yy += 26;
        g.drawString(font, "Let friends join me", ix, yy + 2, Ui.SOFT, false);
        Ui.toggle(g, "friends.join", ix + w - 24, yy, online.allowJoin());
        hits.add(new Hit(ix, yy - 2, w, 16, () -> online.setAllowJoin(!online.allowJoin())));
        yy += 16;
        for (String line : joinLines) {
            g.drawString(font, line, ix, yy, Ui.FAINT, false);
            yy += 10;
        }
        y += h2 + gap;

        float in3 = ease((t - 0.26) / 0.45);
        List<String> statusLines = Ui.wrap(font, status, w);
        int h3 = 26 + 24 + statusLines.size() * 10 + 6;
        tile(g, x0, y, pw, h3, "ADD A FRIEND", null, in3);
        yy = y + 26;
        int addW = 46;
        int fw = w - addW - 6;
        boolean add = focus == Focus.ADD;
        g.fill(ix, yy, ix + fw, yy + 18, 0x14FFFFFF);
        Ui.outline(g, ix, yy, ix + fw, yy + 18, add ? 0x99FFFFFF : 0x2EFFFFFF);
        String text = typed.isEmpty() ? "Their friend code" : tail(typed, fw - 12);
        g.drawString(font, text, ix + 6, yy + 5, typed.isEmpty() ? Ui.FAINT : Ui.TEXT, false);
        if (add && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = ix + 6 + (typed.isEmpty() ? 0 : font.width(text));
            g.fill(caret, yy + 3, caret + 1, yy + 15, Ui.TEXT);
        }
        hits.add(new Hit(ix, yy, fw, 18, () -> focus = Focus.ADD));
        button(g, "Add", ix + w - addW, yy - 1, addW, mx, my, true, true, this::addFriend);
        yy += 24;
        for (String line : statusLines) {
            g.drawString(font, line, ix, yy, Ui.SOFT, false);
            yy += 10;
        }
        y += h3 + gap;

        int limit = vh - 46;
        List<OnlineFriends.Request> in = online.incoming();
        if (!in.isEmpty() && y + 50 < limit) {
            int rows = Math.min(in.size(), Math.max(1, (limit - y - 30) / 25));
            int h4 = 26 + rows * 25 + 4;
            tile(g, x0, y, pw, h4, "WANTS TO BE FRIENDS", String.valueOf(in.size()), 1f);
            yy = y + 24;
            for (int i = 0; i < rows; i++) {
                OnlineFriends.Request r = in.get(i);
                g.drawString(font, r.name(), ix, yy + 7, Ui.TEXT, false);
                String id = r.uuid();
                button(g, "No", ix + w - 28, yy + 1, 28, mx, my, true, false, () -> online.answer("decline", id));
                button(g, "Yes", ix + w - 28 - 4 - 32, yy + 1, 32, mx, my, true, true, () -> online.answer("accept", id));
                yy += 25;
            }
            y += h4 + gap;
        }
        List<OnlineFriends.Request> out = online.outgoing();
        if (!out.isEmpty() && y + 50 < limit) {
            int rows = Math.min(out.size(), Math.max(1, (limit - y - 30) / 25));
            int h5 = 26 + rows * 25 + 4;
            tile(g, x0, y, pw, h5, "WAITING FOR", String.valueOf(out.size()), 1f);
            yy = y + 24;
            for (int i = 0; i < rows; i++) {
                OnlineFriends.Request r = out.get(i);
                g.drawString(font, r.name(), ix, yy + 7, Ui.MUTED, false);
                String id = r.uuid();
                button(g, "Cancel", ix + w - 50, yy + 1, 50, mx, my, true, false, () -> online.answer("cancel", id));
                yy += 25;
            }
        }

        int bw = 70;
        button(g, "Back", x0 + pw - bw, vh - 32, bw, mx, my, true, false, this::onClose);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, boolean enabled, boolean solid, Runnable action) {
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.TEXT);
        } else if (solid && enabled) {
            g.fill(x, y, x + w, y + 20, 0x26FFFFFF);
            Ui.outline(g, x, y, x + w, y + 20, 0x80FFFFFF);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, enabled ? 0x55FFFFFF : 0x1CFFFFFF);
        }
        int color = !enabled ? Ui.FAINT : hover ? Ui.BLACK : Ui.TEXT;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, color, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (focus == Focus.ADD) {
            if ((Character.isLetterOrDigit(c) || c == '-' || c == ' ') && typed.length() < 20) {
                typed += c;
            }
            return true;
        }
        if (focus == Focus.CHAT) {
            if (c >= 32 && c != 127 && chatText.length() < 400) {
                chatText += c;
            }
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (focus != Focus.NONE) {
            if (keyCode == 259) {
                if (focus == Focus.ADD && !typed.isEmpty()) {
                    typed = typed.substring(0, typed.length() - 1);
                } else if (focus == Focus.CHAT && !chatText.isEmpty()) {
                    chatText = chatText.substring(0, chatText.length() - 1);
                }
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                if (focus == Focus.ADD) {
                    addFriend();
                } else {
                    sendChat();
                }
                return true;
            }
            if (Screen.isPaste(keyCode)) {
                String clip = minecraft.keyboardHandler.getClipboard().replace('\n', ' ').trim();
                if (focus == Focus.ADD) {
                    typed = clip.length() > 20 ? clip.substring(0, 20) : clip;
                } else {
                    chatText = clip.length() > 400 ? clip.substring(0, 400) : clip;
                }
                return true;
            }
            if (keyCode == 256) {
                focus = Focus.NONE;
                return true;
            }
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
            focus = Focus.NONE;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
