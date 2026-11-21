package khi.pentecost.game;

import com.mojang.blaze3d.systems.RenderSystem;
import khi.pentecost.account.AccountInfo;
import khi.pentecost.account.SkinApi;
import khi.pentecost.boot.PentecostMain;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AccountScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private record Row(String label, String value, int color) {}

    private record Tile(String title, List<Row> rows, boolean accounts) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static final int GOOD = 0xFF8FD18F;
    private static final int BAD = 0xFFE08080;
    private static final byte[] NONE = new byte[0];
    private static final Map<String, byte[]> CAPES = new ConcurrentHashMap<>();

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private int scroll;
    private int maxScroll;
    private int top;
    private int bottom;
    private String flash = "";
    private long flashUntil;
    private List<Account.Saved> saved = List.of();
    private long opened;
    private final Spin spin = new Spin(200);
    private int stageL;
    private final Stage stage = new Stage("account_stage");

    public AccountScreen(Screen parent) {
        super(Component.literal("Account"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        saved = Account.savedAccounts();
        long age = Account.infoAge();
        if (age < 0 || age > 60_000) {
            Account.refreshInfo();
        }
        if (opened == 0) {
            opened = Util.getMillis();
        }
    }

    @Override
    public void removed() {
        stage.release();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 24)));
        return true;
    }

    private static float ease(double t) {
        double c = Math.max(0, Math.min(1, t));
        return (float) (1 - Math.pow(1 - c, 3));
    }

    private static int fade(int color, float a) {
        int al = (int) (((color >>> 24) & 0xFF) * a);
        return (Math.max(0, Math.min(255, al)) << 24) | (color & 0xFFFFFF);
    }

    private int feetY(int vh) {
        return (int) (vh * 0.66);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        double t = (Util.getMillis() - opened) / 1000.0;
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.fill(0, 0, width, height, 0xFF000000);
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        stageL = Math.max(220, Math.min(360, vw * 36 / 100));
        stage.draw(g, vw, vh, stageL / 2, feetY(vh), Math.max(52, stageL * 34 / 100));
        Stage.life(g, stageL / 2, feetY(vh), Math.max(52, stageL * 34 / 100) / 2, t);
        Stage.dust(g, stageL / 2, feetY(vh), t);

        float in = ease(t / 0.5);
        Ui.logo(g, 24, 18, 2, fade(Ui.TEXT, in));
        Ui.wordmark(g, "ACCOUNT", 24 + (Ui.LOGO_W + 5) * 2, 20, 2, fade(Ui.TEXT, in));

        if (!Account.signedIn()) {
            signedOut(g, vw, vh, mx, my, t);
        } else {
            stage(g, vh, mx, my, t);
            right(g, vw, vh, mx, my, t);
        }

        String back = "Back";
        int bw = 70;
        int bx = vw - 24 - bw;
        int by = vh - 32;
        boolean hover = Ui.over(mx, my, bx, by, bw, 20);
        if (hover) {
            g.fill(bx, by, bx + bw, by + 20, Ui.TEXT);
        } else {
            Ui.outline(g, bx, by, bx + bw, by + 20, 0x55FFFFFF);
        }
        g.drawString(font, back, bx + (bw - font.width(back)) / 2, by + 6, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(bx, by, bw, 20, this::onClose));
        if (System.currentTimeMillis() < flashUntil) {
            g.drawString(font, flash, stageL + 24, vh - 26, Ui.SOFT, false);
        } else if (maxScroll > 0 && Account.signedIn()) {
            g.drawString(font, "Scroll for more", stageL + 24, vh - 26, Ui.FAINT, false);
        }
        g.pose().popPose();
    }

    private void signedOut(GuiGraphics g, int vw, int vh, int mx, int my, double t) {
        int sx = stageL / 2;
        int feet = feetY(vh);
        float in = ease((t - 0.2) / 0.6);
        int qh = 60;
        Ui.outline(g, sx - 16, feet - qh, sx + 16, feet - qh + 32, fade(0x55FFFFFF, in));
        Ui.text(g, font, "?", sx - 5, feet - qh + 9, fade(Ui.MUTED, in), 2f);
        int x = stageL + 24;
        int w = Math.min(380, vw - x - 24);
        int y = Math.max(90, vh / 3);
        Ui.text(g, font, "Not signed in", x, y, fade(Ui.TEXT, in), 2f);
        y += 28;
        for (String line : Ui.wrap(font, "Multiplayer, Realms and friends need a Minecraft account.", w)) {
            g.drawString(font, line, x, y, fade(Ui.SOFT, in), false);
            y += 11;
        }
        y += 12;
        bigButton(g, "Sign in", x, y, 140, mx, my, true, () -> minecraft.setScreen(new PentecostLoginScreen(parent, null, null)));
        maxScroll = 0;
    }

    private ResourceLocation cape(AccountInfo a) {
        AccountInfo.Cape c = a == null ? null : a.activeCape();
        if (c == null || c.url() == null || c.url().isEmpty()) {
            return null;
        }
        byte[] b = CAPES.get(c.id());
        if (b == null) {
            CAPES.put(c.id(), NONE);
            Thread th = new Thread(() -> {
                try {
                    CAPES.put(c.id(), SkinApi.download(c.url()));
                } catch (Exception e) {
                    CAPES.put(c.id(), NONE);
                }
            }, "pentecost-account-cape");
            th.setDaemon(true);
            th.start();
            return null;
        }
        return b.length == 0 ? null : SkinTextures.cape(b);
    }

    private void stage(GuiGraphics g, int vh, int mx, int my, double t) {
        AccountInfo a = Account.info();
        int sx = stageL / 2;
        int feet = feetY(vh);
        float in = ease((t - 0.15) / 0.8);
        float yaw = spin.yaw();
        ResourceLocation skin = AccountSkin.location();
        float h = Math.min(vh * 0.46f, 200f);
        if (skin != null && AccountSkin.height() == 64) {
            boolean slim = a != null && a.skinVariant.equalsIgnoreCase("slim");
            g.pose().pushPose();
            g.pose().translate(0, (1 - in) * 14, 0);
            SkinPreview.draw(g, sx, feet - 2, h * (0.92f + 0.08f * in), yaw, skin, slim, cape(a),
                    new SkinPreview.Layers(true, true, true, true));
            g.pose().popPose();
        } else if (skin != null) {
            RenderSystem.enableBlend();
            int s = 56;
            g.blit(skin, sx - s / 2, feet - (int) h / 2 - s / 2, s, s, 8f, 8f, 8, 8, 64, AccountSkin.height());
            RenderSystem.disableBlend();
        }
        hits.add(new Hit(0, 70, stageL, feet - 50, () -> {}));

        int y = feet + 26;
        String name = Account.name();
        float ns = 2f;
        int nw = (int) (font.width(name) * ns);
        Ui.text(g, font, name, sx - nw / 2f, y, fade(Ui.TEXT, in), ns);
        y += 22;
        String id = AccountInfo.dashed(Account.uuid());
        g.drawString(font, id, sx - font.width(id) / 2, y, fade(Ui.FAINT, in), false);
        y += 14;
        String via = Account.source() == Account.Source.LAUNCHER ? "VIA LAUNCHER" : "VIA PENTECOST";
        String badge = a != null && a.banned ? "BAN LISTED" : PentecostDev.ownIsOwner() && PentecostDev.ownIsListed() ? "DEVELOPER" : null;
        int pw = font.width(via) + 12;
        int bw2 = badge == null ? 0 : font.width(badge) + 12;
        int px = sx - (pw + (badge == null ? 0 : bw2 + 6)) / 2;
        Ui.outline(g, px, y, px + pw, y + 14, fade(0x40FFFFFF, in));
        g.drawString(font, via, px + 6, y + 3, fade(Ui.MUTED, in), false);
        if (badge != null) {
            int bx = px + pw + 6;
            int col = badge.equals("BAN LISTED") ? BAD : Ui.TEXT;
            g.fill(bx, y, bx + bw2, y + 14, fade(badge.equals("BAN LISTED") ? 0x30E08080 : 0x26FFFFFF, in));
            g.drawString(font, badge, bx + 6, y + 3, fade(col, in), false);
        }
        y += 26;
        int bw = Math.min(110, (stageL - 60) / 2);
        int unread = OnlineFriends.get().unreadTotal() + OnlineFriends.get().incoming().size();
        bigButton(g, unread > 0 ? "Friends (" + unread + ")" : "Friends", sx - bw - 4, y, bw, mx, my, true,
                () -> minecraft.setScreen(new FriendsScreen(this)));
        bigButton(g, "Wardrobe", sx + 4, y, bw, mx, my, false, () -> minecraft.setScreen(new WardrobeScreen(this)));
        if (y + 30 < vh - 40) {
            String hint = "Drag to spin, it stays where you leave it";
            g.drawString(font, hint, sx - font.width(hint) / 2, y + 30, fade(0xFF2E2E2E, in), false);
        }
    }

    private List<Tile> tiles() {
        AccountInfo a = Account.info();
        Instant now = Instant.now();
        List<Tile> out = new ArrayList<>();
        OnlineFriends net = OnlineFriends.get();
        List<Row> fr = new ArrayList<>();
        int on = 0;
        for (OnlineFriends.Friend f : net.friends()) {
            if (f.online()) {
                on++;
            }
        }
        fr.add(new Row("Status", net.connected() ? "Online" : net.why().isEmpty() ? "Connecting" : net.why(), net.connected() ? GOOD : Ui.MUTED));
        fr.add(new Row("Friends", net.friends().isEmpty() ? "None yet" : net.friends().size() + ", " + on + " online", Ui.TEXT));
        if (!net.incoming().isEmpty()) {
            fr.add(new Row("Requests", String.valueOf(net.incoming().size()), Ui.TEXT));
        }
        if (net.unreadTotal() > 0) {
            fr.add(new Row("Unread", String.valueOf(net.unreadTotal()), Ui.TEXT));
        }
        out.add(new Tile("FRIENDS", fr, false));
        if (a == null) {
            out.add(new Tile("DETAILS", List.of(new Row("", Account.infoLoading() ? "Loading account details" : "Could not load. Press Refresh.", Ui.SOFT)), false));
        } else {
            List<Row> p = new ArrayList<>();
            p.add(new Row("Created", a.createdAt == null ? "Not shown" : DATE.format(a.createdAt), Ui.TEXT));
            if (a.createdAt != null) {
                p.add(new Row("Age", AccountInfo.age(a.createdAt, now), Ui.SOFT));
            }
            p.add(new Row("Name changed", a.nameChangedAt != null ? DATE.format(a.nameChangedAt) : a.createdAt != null ? "Never" : "Unknown", Ui.TEXT));
            p.add(flag("Name change", a.nameChangeAllowed, "Allowed", "Not yet", true));
            p.add(new Row("Skin model", a.skinVariant.isEmpty() ? "Unknown" : a.skinVariant.charAt(0) + a.skinVariant.substring(1).toLowerCase(Locale.ROOT), Ui.TEXT));
            AccountInfo.Cape cape = a.activeCape();
            p.add(new Row("Capes", a.capes.isEmpty() ? "None owned" : a.capes.size() + " owned" + (cape != null ? ", wearing " + cape.alias() : ""), Ui.TEXT));
            out.add(new Tile("PROFILE", p, false));
            List<Row> perm = new ArrayList<>();
            perm.add(flag("Online chat", a.onlineChat, "Allowed", "Blocked", true));
            perm.add(flag("Servers", a.multiplayer, "Allowed", "Blocked", true));
            perm.add(flag("Realms", a.realms, "Allowed", "Blocked", true));
            perm.add(flag("Telemetry", a.telemetry, "On", "Off", false));
            perm.add(flag("Profanity filter", a.profanityFilter, "On", "Off", false));
            out.add(new Tile("PERMISSIONS", perm, false));
            List<Row> game = new ArrayList<>();
            game.add(flag("Java Edition", a.ownsJava, "Owned", "Not confirmed", true));
            game.add(flag("Bans", a.onlineChat == null ? null : !a.banned, "None listed", "A ban is listed", true));
            out.add(new Tile("GAME", game, false));
        }
        List<Row> s = new ArrayList<>();
        long up = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 60_000L;
        s.add(new Row("Playing for", up < 60 ? up + " min" : (up / 60) + " h " + (up % 60) + " min", Ui.TEXT));
        Instant exp = a == null ? null : a.tokenExpires;
        String until = AccountInfo.until(exp, now);
        s.add(new Row("Session ends", until.equals("ended") ? "Ended. Restart from your launcher." : until, until.equals("ended") ? BAD : Ui.TEXT));
        s.add(new Row("Pentecost", PentecostMain.VERSION + ", " + khi.pentecost.core.PentecostRuntime.mods.size() + " mods", Ui.TEXT));
        long age = Account.infoAge();
        s.add(new Row("Details", Account.infoLoading() ? "Loading" : age < 0 ? "Not loaded" : "Loaded " + (age / 1000) + " s ago", Ui.MUTED));
        out.add(new Tile("THIS SESSION", s, false));
        if (Account.source() != Account.Source.LAUNCHER) {
            out.add(new Tile("ACCOUNTS ON THIS PC", List.of(), true));
        } else {
            out.add(new Tile("SIGN IN", List.of(new Row("", "Your launcher owns this sign in. To change account, sign out there.", Ui.FAINT)), false));
        }
        if (a != null && !a.problems.isEmpty()) {
            List<Row> pr = new ArrayList<>();
            for (String p : a.problems) {
                pr.add(new Row("", p, Ui.FAINT));
            }
            out.add(new Tile("NOT LOADED", pr, false));
        }
        return out;
    }

    private static Row flag(String label, Boolean value, String yes, String no, boolean coloured) {
        if (value == null) {
            return new Row(label, "Unknown", Ui.FAINT);
        }
        return new Row(label, value ? yes : no, coloured ? (value ? GOOD : BAD) : Ui.TEXT);
    }

    private int labelW(int w) {
        return Math.min(110, w * 45 / 100);
    }

    private int tileHeight(Tile tile, int w) {
        if (tile.accounts()) {
            int h = 30 + saved.size() * 24 + 24;
            if (!Account.canSwitch() && Account.inWorld()) {
                h += 14;
            }
            return h + 8;
        }
        int h = 30;
        for (Row r : tile.rows()) {
            int vw = r.label().isEmpty() ? w - 24 : w - 24 - labelW(w);
            h += Math.max(1, Ui.wrap(font, r.value(), vw).size()) * 11 + 4;
        }
        return h + 6;
    }

    private void right(GuiGraphics g, int vw, int vh, int mx, int my, double t) {
        int x0 = stageL + 24;
        int areaW = vw - x0 - 24;
        top = 70;
        bottom = vh - 42;

        int cx = x0;
        int cy = 24;
        String[] labels = {"Refresh", "Copy UUID", "Copy name", "NameMC", "Edit profile",
                PentecostDev.ownIsOwner() ? "Dev tag " + (PentecostDev.ownIsListed() ? "on" : "off") : null,
                Account.source() == Account.Source.PENTECOST ? "Sign out" : null};
        Runnable[] actions = {
                () -> {
                    Account.refreshInfo();
                    note("Refreshing");
                },
                () -> copy(AccountInfo.dashed(Account.uuid()), "UUID copied"),
                () -> copy(Account.name(), "Name copied"),
                () -> Util.getPlatform().openUri("https://namemc.com/profile/" + Account.name()),
                () -> Util.getPlatform().openUri("https://www.minecraft.net/msaprofile/mygames/editprofile"),
                PentecostDev::toggleOwn,
                Account::signOut};
        int total = 0;
        for (String l : labels) {
            if (l != null) {
                total += font.width(l) + 20 + 6;
            }
        }
        cx = Math.max(x0, vw - 24 - total + 6);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) {
                continue;
            }
            int w = font.width(labels[i]) + 20;
            if (cx + w > vw - 24) {
                cx = x0;
                cy += 22;
            }
            boolean hover = Ui.over(mx, my, cx, cy, w, 18);
            if (hover) {
                g.fill(cx, cy, cx + w, cy + 18, Ui.TEXT);
            } else {
                g.fill(cx, cy, cx + w, cy + 18, 0x0DFFFFFF);
            }
            int col = hover ? Ui.BLACK : labels[i].equals("Sign out") ? BAD : Ui.SOFT;
            g.drawString(font, labels[i], cx + 10, cy + 5, col, false);
            hits.add(new Hit(cx, cy, w, 18, actions[i]));
            cx += w + 6;
        }
        top = Math.max(top, cy + 34);

        List<Tile> tiles = tiles();
        int gap = 12;
        boolean two = areaW >= 470;
        int colW = two ? (areaW - gap) / 2 : Math.min(areaW, 460);
        int[] colY = {top - scroll, top - scroll};
        g.enableScissor((int) (x0 * Ui.scale(minecraft)) - 2, (int) (top * Ui.scale(minecraft)),
                (int) (vw * Ui.scale(minecraft)), (int) (bottom * Ui.scale(minecraft)));
        int end = 0;
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            int c = two && colY[1] < colY[0] ? 1 : 0;
            int tx = x0 + c * (colW + gap);
            int ty = colY[c];
            int th = tileHeight(tile, colW);
            float in = ease((t - 0.25 - i * 0.07) / 0.5);
            int off = (int) ((1 - in) * 10);
            drawTile(g, tile, tx, ty + off, colW, th, mx, my, in);
            colY[c] += th + gap;
            end = Math.max(end, colY[c]);
        }
        g.disableScissor();
        maxScroll = Math.max(0, end + scroll - bottom);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
    }

    private void drawTile(GuiGraphics g, Tile tile, int x, int y, int w, int h, int mx, int my, float in) {
        if (y > bottom || y + h < top) {
            return;
        }
        g.fill(x, y, x + w, y + h, fade(0xC00A0A0C, in));
        Ui.outline(g, x, y, x + w, y + h, fade(0x24FFFFFF, in));
        g.fill(x, y, x + 12, y + 1, fade(Ui.TEXT, in));
        g.drawString(font, tile.title(), x + 12, y + 10, fade(Ui.FAINT, in), false);
        if (tile.title().equals("FRIENDS")) {
            boolean hover = Ui.over(mx, my, x, y, w, h) && my >= top && my <= bottom;
            String open = "OPEN  >";
            g.drawString(font, open, x + w - 12 - font.width(open), y + 10, fade(hover ? Ui.TEXT : Ui.MUTED, in), false);
            if (hover) {
                Ui.outline(g, x, y, x + w, y + h, fade(0x99FFFFFF, in));
            }
            if (y >= top && y + 20 <= bottom) {
                hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + h, bottom) - Math.max(y, top), () -> minecraft.setScreen(new FriendsScreen(this))));
            }
        }
        int ry = y + 26;
        if (tile.accounts()) {
            boolean can = Account.canSwitch();
            for (Account.Saved s : saved) {
                boolean current = s.uuid().equals(Account.uuid());
                if (current) {
                    g.fill(x + 8, ry - 2, x + w - 8, ry + 18, fade(0x0FFFFFFF, in));
                }
                g.drawString(font, s.name(), x + 12, ry + 4, fade(current ? Ui.TEXT : Ui.SOFT, in), false);
                int bx = x + w - 12;
                if (current) {
                    String u = "IN USE";
                    g.drawString(font, u, bx - font.width(u), ry + 4, fade(Ui.FAINT, in), false);
                } else {
                    bx = small(g, "Remove", bx, ry - 1, mx, my, true, () -> {
                        Account.forget(s.uuid());
                        saved = Account.savedAccounts();
                    });
                    small(g, "Switch", bx - 4, ry - 1, mx, my, can, () -> {
                        Account.switchTo(s.uuid());
                        minecraft.setScreen(new PentecostLoginScreen(parent, null, null));
                    });
                }
                ry += 24;
            }
            small(g, "+ Add account", x + 12 + font.width("+ Add account") + 16, ry, mx, my, can, () -> {
                Account.addAccount();
                minecraft.setScreen(new PentecostLoginScreen(parent, null, null));
            });
            ry += 24;
            if (!can && Account.inWorld()) {
                g.drawString(font, "Leave the world to switch or add.", x + 12, ry, fade(Ui.FAINT, in), false);
            }
            return;
        }
        int lw = labelW(w);
        for (Row r : tile.rows()) {
            int vx = r.label().isEmpty() ? x + 12 : x + 12 + lw;
            int vw = r.label().isEmpty() ? w - 24 : w - 24 - lw;
            if (!r.label().isEmpty()) {
                g.drawString(font, r.label(), x + 12, ry, fade(Ui.MUTED, in), false);
            }
            List<String> lines = Ui.wrap(font, r.value(), vw);
            for (String line : lines) {
                g.drawString(font, line, vx, ry, fade(r.color(), in), false);
                ry += 11;
            }
            if (lines.isEmpty()) {
                ry += 11;
            }
            ry += 4;
        }
    }

    private int small(GuiGraphics g, String label, int right, int y, int mx, int my, boolean enabled, Runnable action) {
        int w = font.width(label) + 16;
        int x = right - w;
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 18) && y >= top && y + 18 <= bottom;
        if (hover) {
            g.fill(x, y, x + w, y + 18, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 18, enabled ? 0x55FFFFFF : 0x1CFFFFFF);
        }
        g.drawString(font, label, x + 8, y + 5, hover ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
        if (enabled && y >= top && y + 18 <= bottom) {
            hits.add(new Hit(x, y, w, 18, action));
        }
        return x - 4;
    }

    private void bigButton(GuiGraphics g, String label, int x, int y, int w, int mx, int my, boolean solid, Runnable action) {
        boolean hover = Ui.over(mx, my, x, y, w, 22);
        if (hover) {
            g.fill(x, y, x + w, y + 22, Ui.TEXT);
        } else if (solid) {
            g.fill(x, y, x + w, y + 22, 0x2EFFFFFF);
            Ui.outline(g, x, y, x + w, y + 22, 0x99FFFFFF);
        } else {
            Ui.outline(g, x, y, x + w, y + 22, 0x55FFFFFF);
        }
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 7, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(x, y, w, 22, action));
    }

    private void note(String message) {
        flash = message;
        flashUntil = System.currentTimeMillis() + 1800;
    }

    private void copy(String text, String message) {
        minecraft.keyboardHandler.setClipboard(text);
        note(message);
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
                    if (h.w() == stageL && h.x() == 0) {
                        spin.press(mouseX / ui);
                        return true;
                    }
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (spin.held()) {
            spin.drag(mouseX / Ui.scale(minecraft));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        spin.release();
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
