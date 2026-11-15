package khi.pentecost.game;

import com.mojang.blaze3d.systems.RenderSystem;
import khi.pentecost.account.AccountInfo;
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

/**
 * The signed in account at a glance: who it is, how old it is, what it is allowed to do, and how this session started.
 * Everything comes from {@link Account#info()}; nothing here talks to the network on the game thread.
 */
public final class AccountScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static final int GOOD = 0xFF8FD18F;
    private static final int BAD = 0xFFE08080;
    private static final int LABEL_W = 130;

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private int scroll;
    private int maxScroll;
    private int top;
    private int bottom;
    private String flash = "";
    private long flashUntil;

    public AccountScreen(Screen parent) {
        super(Component.literal("Account"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        long age = Account.infoAge();
        if (age < 0 || age > 60_000) {
            Account.refreshInfo();
        }
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
        top = 80;
        bottom = vh - 44;

        if (!Account.signedIn()) {
            Ui.logo(g, margin, 24, 2, Ui.TEXT);
            Ui.wordmark(g, "ACCOUNT", margin + (Ui.LOGO_W + 5) * 2, 26, 2, Ui.TEXT);
            g.fill(margin, 66, vw - margin, 67, Ui.LINE);
            int y = lines(g, "You are not signed in. Multiplayer and Realms need a Minecraft account.", margin, top, Math.min(420, vw - 96), Ui.SOFT);
            button(g, "Sign in", margin, y + 10, mx, my, () -> minecraft.setScreen(new PentecostLoginScreen(parent, null, null)));
            maxScroll = 0;
        } else {
            content(g, vw, margin, mx, my);
            // The header is drawn over the scrolled content so rows slide under it.
            g.fill(0, 0, vw, 70, minecraft.level == null ? 0xFF0A0A0B : 0xF00A0A0B);
            Ui.logo(g, margin, 24, 2, Ui.TEXT);
            Ui.wordmark(g, "ACCOUNT", margin + (Ui.LOGO_W + 5) * 2, 26, 2, Ui.TEXT);
            g.fill(margin, 66, vw - margin, 67, Ui.LINE);
            g.fill(0, bottom + 2, vw, vh, minecraft.level == null ? 0xFF0A0A0B : 0xF00A0A0B);
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
        if (System.currentTimeMillis() < flashUntil) {
            g.drawString(font, flash, margin, vh - 27, Ui.SOFT, false);
        } else if (maxScroll > 0) {
            g.drawString(font, "Scroll for more", margin, vh - 27, Ui.FAINT, false);
        }
        g.pose().popPose();
    }

    private void content(GuiGraphics g, int vw, int margin, int mx, int my) {
        AccountInfo a = Account.info();
        Instant now = Instant.now();
        int y = top - scroll;

        // The identity card.
        if (y >= top - 2) {
            head(g, margin, y);
        }
        int tx = margin + 72;
        if (y >= top) {
            Ui.text(g, font, Account.name(), tx, y, Ui.TEXT, 2f);
            g.drawString(font, AccountInfo.dashed(Account.uuid()), tx, y + 24, Ui.MUTED, false);
            String via = Account.source() == Account.Source.LAUNCHER ? "Signed in through your launcher" : "Signed in with Pentecost";
            g.drawString(font, via, tx, y + 36, Ui.SOFT, false);
            if (a != null && a.banned) {
                g.drawString(font, "This account has a ban listed", tx, y + 48, BAD, false);
            }
        }
        y += 72;

        // Actions.
        int bx = margin;
        int by = y;
        String[] labels = {"Refresh", "Copy UUID", "Copy name", "NameMC page", "Edit profile",
                "Developer tag: " + (PentecostDev.ownIsListed() ? "On" : "Off"),
                Account.source() == Account.Source.PENTECOST ? "Sign out" : null};
        Runnable[] actions = {
                Account::refreshInfo,
                () -> copy(AccountInfo.dashed(Account.uuid()), "UUID copied"),
                () -> copy(Account.name(), "Name copied"),
                () -> Util.getPlatform().openUri("https://namemc.com/profile/" + Account.name()),
                () -> Util.getPlatform().openUri("https://www.minecraft.net/msaprofile/mygames/editprofile"),
                PentecostDev::toggleOwn,
                Account::signOut};
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) {
                continue;
            }
            int w = font.width(labels[i]) + 24;
            if (bx + w > vw - margin) {
                bx = margin;
                by += 26;
            }
            if (by >= top && by + 20 <= bottom) {
                boolean hover = Ui.over(mx, my, bx, by, w, 20);
                if (hover) {
                    g.fill(bx, by, bx + w, by + 20, Ui.TEXT);
                } else {
                    Ui.outline(g, bx, by, bx + w, by + 20, 0x66FFFFFF);
                }
                g.drawString(font, labels[i], bx + 12, by + 6, hover ? Ui.BLACK : Ui.TEXT, false);
                hits.add(new Hit(bx, by, w, 20, actions[i]));
            }
            bx += w + 8;
        }
        y = by + 38;

        // The columns.
        int gap = 40;
        int colW = Math.min(400, (vw - margin * 2 - gap) / 2);
        boolean two = colW >= 260;
        int xl = margin;
        int xr = two ? margin + colW + gap : margin;
        int w = two ? colW : Math.min(420, vw - margin * 2);
        int yl = y;

        if (a == null) {
            yl = lines(g, Account.infoLoading() ? "Loading account details..." : "The account details could not be loaded. Press Refresh.",
                    xl, yl, w, Ui.SOFT);
        } else {
            yl = section(g, "PROFILE", xl, yl, w);
            yl = row(g, "Created", a.createdAt == null ? "Not shown"
                    : DATE.format(a.createdAt) + " (" + AccountInfo.age(a.createdAt, now) + " ago)", Ui.TEXT, xl, yl, w);
            String changed = a.nameChangedAt != null ? DATE.format(a.nameChangedAt) : a.createdAt != null ? "Never" : "Unknown";
            yl = row(g, "Name last changed", changed, Ui.TEXT, xl, yl, w);
            yl = flag(g, "Name change", a.nameChangeAllowed, "Allowed", "Not allowed yet", true, xl, yl, w);
            yl = row(g, "Skin model", a.skinVariant.isEmpty() ? "Unknown" : a.skinVariant.charAt(0) + a.skinVariant.substring(1).toLowerCase(Locale.ROOT),
                    Ui.TEXT, xl, yl, w);
            AccountInfo.Cape cape = a.activeCape();
            yl = row(g, "Capes", a.capes.isEmpty() ? "None owned"
                    : a.capes.size() + (a.capes.size() == 1 ? " owned" : " owned") + (cape != null ? ", wearing " + cape.alias() : ", none worn"),
                    Ui.TEXT, xl, yl, w);
            yl += 10;
            yl = section(g, "GAME", xl, yl, w);
            yl = flag(g, "Java Edition", a.ownsJava, "Owned", "Not confirmed", true, xl, yl, w);
            yl = flag(g, "Bans", a.onlineChat == null ? null : !a.banned, "None listed", "A ban is listed", true, xl, yl, w);
        }

        int yr = two ? y : yl + 14;
        if (a != null) {
            yr = section(g, "PERMISSIONS", xr, yr, w);
            yr = flag(g, "Online chat", a.onlineChat, "Allowed", "Blocked", true, xr, yr, w);
            yr = flag(g, "Multiplayer servers", a.multiplayer, "Allowed", "Blocked", true, xr, yr, w);
            yr = flag(g, "Realms", a.realms, "Allowed", "Blocked", true, xr, yr, w);
            yr = flag(g, "Telemetry", a.telemetry, "On", "Off", false, xr, yr, w);
            yr = flag(g, "Profanity filter", a.profanityFilter, "On", "Off", false, xr, yr, w);
            yr += 10;
        }
        yr = section(g, "THIS SESSION", xr, yr, w);
        long up = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 60_000L;
        yr = row(g, "Playing for", up < 60 ? up + " min" : (up / 60) + " h " + (up % 60) + " min", Ui.TEXT, xr, yr, w);
        Instant exp = a == null ? null : a.tokenExpires;
        String until = AccountInfo.until(exp, now);
        yr = row(g, "Minecraft session", until.equals("ended") ? "Ended. Restart from your launcher." : until, until.equals("ended") ? BAD : Ui.TEXT, xr, yr, w);
        yr = row(g, "Pentecost", PentecostMain.VERSION + ", " + khi.pentecost.core.PentecostRuntime.mods.size() + " mods", Ui.TEXT, xr, yr, w);
        long age = Account.infoAge();
        yr = row(g, "Details loaded", Account.infoLoading() ? "Loading..." : age < 0 ? "Not yet" : (age / 1000) + " s ago", Ui.MUTED, xr, yr, w);
        if (Account.source() == Account.Source.LAUNCHER) {
            yr = lines(g, "Your launcher owns this sign in. To change account, sign out there.", xr, yr + 4, w, Ui.FAINT);
        }
        if (a != null && !a.problems.isEmpty()) {
            yr += 10;
            yr = section(g, "NOT LOADED", xr, yr, w);
            for (String p : a.problems) {
                yr = lines(g, p, xr, yr, w, Ui.FAINT);
            }
        }

        int end = Math.max(yl, yr);
        maxScroll = Math.max(0, end - bottom + scroll + 8);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
    }

    private void head(GuiGraphics g, int x, int y) {
        ResourceLocation skin = AccountSkin.location();
        if (skin == null) {
            Ui.outline(g, x, y, x + 56, y + 56, Ui.MUTED);
            String letter = Account.name().isEmpty() ? "?" : Account.name().substring(0, 1).toUpperCase(Locale.ROOT);
            Ui.text(g, font, letter, x + 20, y + 20, Ui.MUTED, 2f);
            return;
        }
        int th = AccountSkin.height();
        RenderSystem.enableBlend();
        g.blit(skin, x, y, 56, 56, 8f, 8f, 8, 8, 64, th);
        g.blit(skin, x - 2, y - 2, 60, 60, 40f, 8f, 8, 8, 64, th);
        RenderSystem.disableBlend();
    }

    private int section(GuiGraphics g, String title, int x, int y, int w) {
        if (y >= top && y + 11 <= bottom) {
            g.drawString(font, title, x, y, Ui.FAINT, false);
            g.fill(x, y + 11, x + w, y + 12, Ui.LINE);
        }
        return y + 18;
    }

    private int row(GuiGraphics g, String label, String value, int color, int x, int y, int w) {
        List<String> lines = Ui.wrap(font, value.isEmpty() ? "-" : value, w - LABEL_W);
        if (y >= top && y + 11 <= bottom) {
            g.drawString(font, label, x, y, Ui.MUTED, false);
            int ly = y;
            for (String line : lines) {
                g.drawString(font, line, x + LABEL_W, ly, color, false);
                ly += 11;
            }
        }
        return y + Math.max(1, lines.size()) * 11 + 3;
    }

    /** A yes/no row. Null is "Unknown". {@code coloured} turns on green and red; some settings are neither good nor bad. */
    private int flag(GuiGraphics g, String label, Boolean value, String yes, String no, boolean coloured, int x, int y, int w) {
        if (value == null) {
            return row(g, label, "Unknown", Ui.FAINT, x, y, w);
        }
        return row(g, label, value ? yes : no, coloured ? (value ? GOOD : BAD) : Ui.TEXT, x, y, w);
    }

    private int lines(GuiGraphics g, String text, int x, int y, int w, int color) {
        for (String line : Ui.wrap(font, text, w)) {
            if (y >= top - 40 && y + 11 <= bottom + 40) {
                g.drawString(font, line, x, y, color, false);
            }
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

    private void copy(String text, String message) {
        minecraft.keyboardHandler.setClipboard(text);
        flash = message;
        flashUntil = System.currentTimeMillis() + 1800;
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
}
