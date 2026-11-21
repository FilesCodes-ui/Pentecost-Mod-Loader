package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PentecostServersScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private enum Form { NONE, ADD, EDIT, DIRECT, DELETE }

    private static final int ROW_H = 36;

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private final ServerList servers;
    private final ServerStatusPinger pinger = new ServerStatusPinger();
    private ExecutorService pool = newPool();
    private final Map<ServerData, ResourceLocation> icons = new HashMap<>();
    private final Map<ServerData, String> iconKeys = new HashMap<>();

    private List<ServerData> shown = List.of();
    private String search = "";
    private ServerData selected;
    private int scroll;
    private String status = "";
    private long statusUntil;
    private long lastClickAt;
    private ServerData lastClick;

    private Form form = Form.NONE;
    private String fName = "";
    private String fIp = "";
    private int focus;

    private int listX, listTop, listW, visibleRows;

    public PentecostServersScreen(Screen parent) {
        super(Component.literal("Multiplayer"));
        this.parent = parent;
        this.servers = new ServerList(Minecraft.getInstance());
        this.servers.load();
    }

    @Override
    protected void init() {
        super.init();
        refilter();
        refreshAll();
    }

    private void refilter() {
        String q = search.toLowerCase(Locale.ROOT).trim();
        List<ServerData> out = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            ServerData d = servers.get(i);
            if (q.isEmpty() || d.name.toLowerCase(Locale.ROOT).contains(q) || d.ip.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(d);
            }
        }
        shown = out;
        if (selected == null || !out.contains(selected)) {
            selected = out.isEmpty() ? null : out.get(0);
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, out.size() - Math.max(1, visibleRows))));
    }

    private static ExecutorService newPool() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Pentecost server pinger");
            t.setDaemon(true);
            return t;
        });
    }

    private void ping(ServerData d) {
        if (pool.isShutdown()) {
            pool = newPool();
        }
        d.pinged = false;
        d.ping = -2L;
        d.motd = Component.empty();
        d.status = Component.empty();
        pool.submit(() -> {
            try {
                pinger.pingServer(d, () -> {
                });
            } catch (UnknownHostException e) {
                d.ping = -1L;
                d.motd = Component.literal("Can't find this address");
            } catch (RuntimeException e) {
                d.ping = -1L;
                d.motd = Component.literal("Can't connect to this server");
                Log.warn("Server ping failed: " + e);
            }
        });
    }

    private void refreshAll() {
        for (int i = 0; i < servers.size(); i++) {
            ping(servers.get(i));
        }
    }

    @Override
    public void tick() {
        pinger.tick();
    }

    @Override
    public void removed() {
        pinger.removeAll();
        pool.shutdownNow();
        for (ResourceLocation rl : icons.values()) {
            minecraft.getTextureManager().release(rl);
        }
        icons.clear();
        super.removed();
    }

    private int indexOfReal(ServerData d) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i) == d) {
                return i;
            }
        }
        return -1;
    }

    private void say(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + 4500;
    }

    private void join(ServerData d) {
        if (d == null) {
            return;
        }
        servers.save();
        connect(d.ip, d);
    }

    private void connect(String ip, ServerData d) {
        if (!EulaScreen.agreed()) {
            EulaScreen.ask(minecraft, this, () -> connect(ip, d));
            return;
        }
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(ip), d, false);
    }

    private void openForm(Form kind) {
        form = kind;
        focus = kind == Form.DIRECT ? 1 : 0;
        if (kind == Form.ADD) {
            fName = "";
            fIp = "";
        } else if (kind == Form.EDIT && selected != null) {
            fName = selected.name;
            fIp = selected.ip;
        } else if (kind == Form.DIRECT) {
            fName = "";
            fIp = minecraft.options.lastMpIp == null ? "" : minecraft.options.lastMpIp;
        } else if (kind == Form.DELETE && selected == null) {
            form = Form.NONE;
        } else if (kind == Form.EDIT) {
            form = Form.NONE;
        }
    }

    private void confirmForm() {
        String name = fName.trim();
        String ip = fIp.trim();
        switch (form) {
            case ADD -> {
                if (ip.isEmpty()) {
                    return;
                }
                ServerData d = new ServerData(name.isEmpty() ? ip : name, ip, false);
                servers.add(d, false);
                servers.save();
                selected = d;
                form = Form.NONE;
                refilter();
                ping(d);
                say("Added " + d.name + ".");
            }
            case EDIT -> {
                if (selected == null || ip.isEmpty()) {
                    return;
                }
                boolean addressChanged = !ip.equals(selected.ip);
                selected.name = name.isEmpty() ? ip : name;
                selected.ip = ip;
                servers.save();
                form = Form.NONE;
                refilter();
                if (addressChanged) {
                    ping(selected);
                }
            }
            case DIRECT -> {
                if (ip.isEmpty()) {
                    return;
                }
                minecraft.options.lastMpIp = ip;
                minecraft.options.save();
                form = Form.NONE;
                connect(ip, new ServerData("Direct connection", ip, false));
            }
            case DELETE -> {
                if (selected != null) {
                    servers.remove(selected);
                    servers.save();
                    selected = null;
                    say("Removed.");
                }
                form = Form.NONE;
                refilter();
            }
            default -> {
            }
        }
    }

    private void reorder(int by) {
        if (selected == null || !search.isEmpty()) {
            if (!search.isEmpty()) {
                say("Clear the search to reorder.");
            }
            return;
        }
        int i = indexOfReal(selected);
        int j = i + by;
        if (i < 0 || j < 0 || j >= servers.size()) {
            return;
        }
        servers.swap(i, j);
        servers.save();
        refilter();
    }

    private void move(int by) {
        if (shown.isEmpty()) {
            return;
        }
        int i = Math.max(0, Math.min(shown.size() - 1, Math.max(0, shown.indexOf(selected)) + by));
        selected = shown.get(i);
        if (i < scroll) {
            scroll = i;
        } else if (visibleRows > 0 && i >= scroll + visibleRows) {
            scroll = i - visibleRows + 1;
        }
    }

    private String fit(String s, int w) {
        if (font.width(s) <= w) {
            return s;
        }
        int n = s.length();
        while (n > 1 && font.width(s.substring(0, n)) + font.width("...") > w) {
            n--;
        }
        return s.substring(0, n) + "...";
    }

    private static String plain(Component c) {
        return c == null ? "" : c.getString().replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private String detail(ServerData d) {
        if (d.ping == -2L && !d.pinged) {
            return "Pinging...";
        }
        String motd = plain(d.motd);
        return motd.isEmpty() ? d.ip : motd;
    }

    private String pingText(ServerData d) {
        if (d.ping >= 0) {
            return d.ping + " ms";
        }
        return d.ping == -1L ? "Offline" : "";
    }

    private ResourceLocation icon(ServerData d) {
        byte[] bytes = d.getIconBytes();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String key = Integer.toHexString(java.util.Arrays.hashCode(bytes));
        if (key.equals(iconKeys.get(d))) {
            return icons.get(d);
        }
        ResourceLocation old = icons.remove(d);
        if (old != null) {
            minecraft.getTextureManager().release(old);
        }
        iconKeys.put(d, key);
        try {
            NativeImage img = NativeImage.read(bytes);
            if (img.getWidth() == 64 && img.getHeight() == 64) {
                ResourceLocation rl = new ResourceLocation("pentecost", "servers/" + Integer.toHexString(System.identityHashCode(d))
                        + "_" + key);
                minecraft.getTextureManager().register(rl, new DynamicTexture(img));
                icons.put(d, rl);
                return rl;
            }
            img.close();
        } catch (IOException | RuntimeException e) {
        }
        return null;
    }

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

    private void field(GuiGraphics g, String label, String value, int x, int y, int w, boolean focused) {
        g.drawString(font, label, x, y, Ui.MUTED, false);
        Ui.outline(g, x, y + 11, x + w, y + 31, focused ? 0xCCFFFFFF : 0x55FFFFFF);
        g.drawString(font, fit(value, w - 16), x + 8, y + 17, Ui.TEXT, false);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = x + 8 + Math.min(w - 14, font.width(value));
            g.fill(caret, y + 15, caret + 1, y + 27, Ui.TEXT);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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

        int cx = vw / 2;
        int colW = Math.min(480, vw - 40);
        int x0 = cx - colW / 2;
        boolean modal = form != Form.NONE;

        int y = Math.max(14, vh / 20);
        String word = "MULTIPLAYER";
        int total = (Ui.LOGO_W + 5) * 2 + Ui.wordmarkWidth(word, 2);
        int hx = cx - total / 2;
        Ui.logo(g, hx, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, word, hx + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        y += 32;

        int refW = 74, dirW = 104, gap = 6;
        int searchW = colW - refW - dirW - gap * 2;
        Ui.outline(g, x0, y, x0 + searchW, y + 20, 0x66FFFFFF);
        if (search.isEmpty()) {
            g.drawString(font, "Type to search", x0 + 8, y + 6, Ui.FAINT, false);
        } else {
            g.drawString(font, fit(search, searchW - 16), x0 + 8, y + 6, Ui.TEXT, false);
        }
        if (!modal && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = x0 + 8 + (search.isEmpty() ? 0 : Math.min(searchW - 14, font.width(search)));
            g.fill(caret, y + 4, caret + 1, y + 16, Ui.TEXT);
        }
        button(g, "Refresh", x0 + searchW + gap, y, refW, mx, my, !modal, this::refreshAll);
        button(g, "Direct connect", x0 + searchW + gap + refW + gap, y, dirW, mx, my, !modal, () -> openForm(Form.DIRECT));
        y += 28;

        String count = search.isEmpty() ? servers.size() + (servers.size() == 1 ? " server" : " servers")
                : shown.size() + " of " + servers.size();
        g.drawString(font, count, x0, y, Ui.MUTED, false);
        String hint = "Enter join   Ctrl+N add   Ctrl+E edit   Ctrl+Up/Down move   Del remove";
        if (font.width(hint) + font.width(count) + 20 < colW) {
            g.drawString(font, hint, x0 + colW - font.width(hint), y, Ui.FAINT, false);
        }
        y += 14;
        g.fill(x0, y, x0 + colW, y + 1, Ui.LINE);
        y += 6;

        int bottomBlock = 82;
        visibleRows = Math.max(1, (vh - bottomBlock - y) / ROW_H);
        listX = x0;
        listTop = y;
        listW = colW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - visibleRows)));
        if (shown.isEmpty()) {
            centered(g, servers.size() == 0 ? "No servers yet. Add one to begin." : "No servers match \"" + search + "\".", cx, y + 20, Ui.SOFT);
        }
        for (int i = 0; i < visibleRows && scroll + i < shown.size(); i++) {
            ServerData d = shown.get(scroll + i);
            int ry = y + i * ROW_H;
            boolean isSel = d == selected;
            boolean hover = !modal && Ui.over(mx, my, x0, ry, colW, ROW_H - 4);
            if (isSel) {
                g.fill(x0, ry, x0 + colW, ry + ROW_H - 4, Ui.HOVER);
                Ui.outline(g, x0, ry, x0 + colW, ry + ROW_H - 4, 0x99FFFFFF);
            } else if (hover) {
                g.fill(x0, ry, x0 + colW, ry + ROW_H - 4, Ui.HOVER);
            }
            ResourceLocation rl = icon(d);
            if (rl != null) {
                g.blit(rl, x0 + 4, ry + 2, 28, 28, 0f, 0f, 64, 64, 64, 64);
            } else {
                Ui.outline(g, x0 + 4, ry + 2, x0 + 32, ry + 30, 0x33FFFFFF);
                String letter = d.name.isEmpty() ? "?" : d.name.substring(0, 1).toUpperCase(Locale.ROOT);
                g.drawString(font, letter, x0 + 18 - font.width(letter) / 2, ry + 12, Ui.FAINT, false);
            }
            String players = plain(d.status);
            String ping = pingText(d);
            int rightW = Math.max(font.width(players), font.width(ping)) + 10;
            g.drawString(font, fit(d.name, colW - 44 - rightW), x0 + 40, ry + 4, Ui.TEXT, false);
            boolean old = d.pinged && d.protocol != net.minecraft.SharedConstants.getCurrentVersion().getProtocolVersion()
                    && d.protocol != 0;
            String second = old ? "Different version: " + plain(d.version) : detail(d);
            g.drawString(font, fit(second, colW - 44 - rightW), x0 + 40, ry + 17, isSel ? Ui.SOFT : Ui.MUTED, false);
            if (!players.isEmpty()) {
                g.drawString(font, players, x0 + colW - 8 - font.width(players), ry + 4, Ui.MUTED, false);
            }
            if (!ping.isEmpty()) {
                g.drawString(font, ping, x0 + colW - 8 - font.width(ping), ry + 17, d.ping >= 0 ? Ui.TEXT : Ui.FAINT, false);
            }
        }
        if (shown.size() > visibleRows) {
            int trackH = visibleRows * ROW_H - 4;
            int barH = Math.max(14, trackH * visibleRows / shown.size());
            int barY = y + (trackH - barH) * scroll / Math.max(1, shown.size() - visibleRows);
            g.fill(x0 + colW + 4, y, x0 + colW + 5, y + trackH, Ui.LINE);
            g.fill(x0 + colW + 3, barY, x0 + colW + 6, barY + barH, Ui.MUTED);
        }

        int sy = vh - bottomBlock + 6;
        if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
            centered(g, status, cx, sy, Ui.SOFT);
        }

        ServerData sel = selected;
        int ay = vh - 62;
        boolean has = sel != null && !modal;
        int n = 5, ag = 4;
        int bw = (colW - ag * (n - 1)) / n;
        String[] names = {"Join", "Add", "Edit", "Move up", "Move down"};
        Runnable[] acts = {() -> join(sel), () -> openForm(Form.ADD), () -> openForm(Form.EDIT), () -> reorder(-1), () -> reorder(1)};
        boolean[] on = {has, !modal, has, has, has};
        for (int i = 0; i < n; i++) {
            button(g, names[i], x0 + i * (bw + ag), ay, bw, mx, my, on[i], acts[i]);
        }
        int by = vh - 36;
        int third = (colW - 12) / 3;
        button(g, "Back", x0, by, third, mx, my, !modal, this::onClose);
        button(g, "Remove", x0 + third + 6, by, third, mx, my, has, () -> openForm(Form.DELETE));
        button(g, "Join selected", x0 + (third + 6) * 2, by, third, mx, my, has, () -> join(sel));

        if (modal) {
            hits.clear();
            g.fill(0, 0, vw, vh, 0xB0000000);
            int bwid = Math.min(360, vw - 40), bx = cx - bwid / 2;
            boolean delete = form == Form.DELETE;
            int bh = delete ? 100 : form == Form.DIRECT ? 112 : 168;
            int byy = vh / 2 - bh / 2;
            g.fill(bx, byy, bx + bwid, byy + bh, Ui.BG);
            Ui.outline(g, bx, byy, bx + bwid, byy + bh, 0xCCFFFFFF);
            String title = switch (form) {
                case ADD -> "ADD A SERVER";
                case EDIT -> "EDIT SERVER";
                case DIRECT -> "DIRECT CONNECT";
                default -> "REMOVE THIS SERVER?";
            };
            centered(g, title, cx, byy + 10, Ui.TEXT);
            int btnY = byy + bh - 30;
            if (delete) {
                centered(g, fit(selected == null ? "" : selected.name, bwid - 24), cx, byy + 32, Ui.SOFT);
                centered(g, "It only leaves your list.", cx, byy + 46, Ui.MUTED);
            } else {
                int fy = byy + 28;
                if (form != Form.DIRECT) {
                    field(g, "Name", fName, bx + 14, fy, bwid - 28, focus == 0);
                    fy += 42;
                    field(g, "Address", fIp, bx + 14, fy, bwid - 28, focus == 1);
                } else {
                    field(g, "Address", fIp, bx + 14, fy, bwid - 28, true);
                }
            }
            int half = (bwid - 28 - 6) / 2;
            String ok = switch (form) {
                case ADD -> "Add";
                case EDIT -> "Save";
                case DIRECT -> "Connect";
                default -> "Remove";
            };
            button(g, "Cancel", bx + 14, btnY, half, mx, my, true, () -> form = Form.NONE);
            button(g, ok, bx + 14 + half + 6, btnY, half, mx, my, true, this::confirmForm);
        }
        g.pose().popPose();
    }

    private String current() {
        return focus == 0 && form != Form.DIRECT ? fName : fIp;
    }

    private void setCurrent(String v) {
        if (focus == 0 && form != Form.DIRECT) {
            fName = v;
        } else {
            fIp = v;
        }
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (Character.isISOControl(c)) {
            return super.charTyped(c, modifiers);
        }
        if (form == Form.DELETE) {
            return true;
        }
        if (form != Form.NONE) {
            if (current().length() < 100) {
                setCurrent(current() + c);
            }
            return true;
        }
        if (search.length() < 40) {
            search += c;
            scroll = 0;
            refilter();
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean ctrl = Screen.hasControlDown();
        if (form != Form.NONE) {
            switch (key) {
                case 256 -> form = Form.NONE;
                case 257, 335 -> confirmForm();
                case 258 -> focus = form == Form.DIRECT || form == Form.DELETE ? focus : 1 - focus;
                case 259 -> {
                    String v = current();
                    if (!v.isEmpty() && form != Form.DELETE) {
                        setCurrent(v.substring(0, v.length() - 1));
                    }
                }
                case 86 -> {
                    if (ctrl && form != Form.DELETE) {
                        setCurrent((current() + minecraft.keyboardHandler.getClipboard()).trim());
                    }
                }
                default -> {
                }
            }
            return true;
        }
        switch (key) {
            case 256 -> {
                if (!search.isEmpty()) {
                    search = "";
                    refilter();
                } else {
                    onClose();
                }
                return true;
            }
            case 259 -> {
                if (!search.isEmpty()) {
                    search = search.substring(0, search.length() - 1);
                    refilter();
                }
                return true;
            }
            case 257, 335 -> {
                join(selected);
                return true;
            }
            case 265 -> {
                if (ctrl) {
                    reorder(-1);
                } else {
                    move(-1);
                }
                return true;
            }
            case 264 -> {
                if (ctrl) {
                    reorder(1);
                } else {
                    move(1);
                }
                return true;
            }
            case 266 -> {
                move(-visibleRows);
                return true;
            }
            case 267 -> {
                move(visibleRows);
                return true;
            }
            case 261 -> {
                openForm(Form.DELETE);
                return true;
            }
            case 294 -> {
                refreshAll();
                return true;
            }
            default -> {
            }
        }
        if (ctrl) {
            switch (key) {
                case 78 -> openForm(Form.ADD);
                case 69 -> openForm(Form.EDIT);
                case 68 -> openForm(Form.DIRECT);
                case 82 -> refreshAll();
                case 86 -> {
                    search = (search + minecraft.keyboardHandler.getClipboard()).trim();
                    refilter();
                }
                default -> {
                    return super.keyPressed(key, scan, mods);
                }
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (form != Form.NONE) {
            return true;
        }
        scroll = Math.max(0, Math.min(Math.max(0, shown.size() - visibleRows), scroll - (int) Math.signum(delta) * 2));
        return true;
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
            if (form != Form.NONE) {
                int cx = (int) (width / ui) / 2;
                int bwid = Math.min(360, (int) (width / ui) - 40);
                int bx = cx - bwid / 2;
                int vh = (int) (height / ui);
                int bh = form == Form.DIRECT ? 112 : 168;
                int byy = vh / 2 - bh / 2;
                if (form == Form.ADD || form == Form.EDIT) {
                    if (mx >= bx + 14 && mx < bx + bwid - 14) {
                        if (my >= byy + 39 && my < byy + 59) {
                            focus = 0;
                        } else if (my >= byy + 81 && my < byy + 101) {
                            focus = 1;
                        }
                    }
                }
                return true;
            }
            if (mx >= listX && mx < listX + listW && my >= listTop) {
                int row = (int) ((my - listTop) / ROW_H);
                int idx = scroll + row;
                if (row < visibleRows && idx < shown.size()) {
                    ServerData d = shown.get(idx);
                    long now = System.currentTimeMillis();
                    boolean second = d == lastClick && now - lastClickAt < 350;
                    selected = d;
                    lastClick = d;
                    lastClickAt = now;
                    if (second) {
                        join(d);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        servers.save();
        minecraft.setScreen(parent);
    }
}
