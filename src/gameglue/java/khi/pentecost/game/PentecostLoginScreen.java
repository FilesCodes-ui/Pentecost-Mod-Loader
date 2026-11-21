package khi.pentecost.game;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

public final class PentecostLoginScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static final int BAD = 0xFFE08080;
    private static final int GOOD = 0xFF8FD18F;

    private final Screen parent;
    private final Screen then;
    private final String reason;
    private final List<Hit> hits = new ArrayList<>();
    private final Stage stage = new Stage("login_stage");
    private long opened;
    private final Spin spin = new Spin(200);
    private int stageW;
    private String flash = "";
    private long flashUntil;

    public PentecostLoginScreen(Screen parent, Screen then, String reason) {
        super(Component.literal("Account"));
        this.parent = parent;
        this.then = then;
        this.reason = reason;
    }

    @Override
    protected void init() {
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
        int stageL = Math.max(220, Math.min(360, vw * 36 / 100));
        int sx = stageL / 2;
        int feet = feetY(vh);
        stage.draw(g, vw, vh, sx, feet, Math.max(52, stageL * 34 / 100));
        Stage.life(g, sx, feet, Math.max(52, stageL * 34 / 100) / 2, t);
        Stage.dust(g, sx, feet, t);
        stageW = stageL;

        float in = ease(t / 0.5);
        Ui.logo(g, 24, 18, 2, fade(Ui.TEXT, in));
        Ui.wordmark(g, "SIGN IN", 24 + (Ui.LOGO_W + 5) * 2, 20, 2, fade(Ui.TEXT, in));

        figure(g, sx, feet, vh, t);

        int x0 = stageL + 24;
        int w = Math.min(440, vw - x0 - 24);
        int y = Math.max(74, vh / 6);
        Account.State state = Account.state();
        float inT = ease((t - 0.2) / 0.5);
        int off = (int) ((1 - inT) * 10);
        y += off;
        switch (state) {
            case IN -> signedIn(g, x0, y, w, mx, my, inT);
            case WAITING -> waiting(g, x0, y, w, mx, my, t, inT);
            case WORKING -> working(g, x0, y, w, t, inT);
            default -> start(g, x0, y, w, mx, my, state == Account.State.FAILED, inT);
        }

        String promise = "Pentecost never sees your Microsoft password. You sign in on microsoft.com. Pentecost keeps one "
                + "sign-in token on this PC so you stay signed in, and signing out deletes it.";
        List<String> pl = Ui.wrap(font, promise, w - 36);
        int ph = 16 + pl.size() * 10;
        int py = vh - 44 - ph;
        g.fill(x0, py, x0 + w, py + ph, 0x80000000);
        Ui.outline(g, x0, py, x0 + w, py + ph, 0x1AFFFFFF);
        lock(g, x0 + 10, py + 8);
        int ly = py + 8;
        for (String line : pl) {
            g.drawString(font, line, x0 + 26, ly, Ui.FAINT, false);
            ly += 10;
        }

        if (System.currentTimeMillis() < flashUntil) {
            g.drawString(font, flash, x0, vh - 26, Ui.SOFT, false);
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
        g.pose().popPose();
    }

    private void lock(GuiGraphics g, int x, int y) {
        Ui.outline(g, x + 2, y, x + 8, y + 5, Ui.FAINT);
        g.fill(x, y + 4, x + 10, y + 11, Ui.FAINT);
        g.fill(x + 4, y + 6, x + 6, y + 9, 0xFF000000);
    }

    private void figure(GuiGraphics g, int sx, int feet, int vh, double t) {
        float yaw = spin.yaw();
        ResourceLocation skin = AccountSkin.location();
        float in = ease((t - 0.15) / 0.8);
        if (Account.state() == Account.State.IN && skin != null && AccountSkin.height() == 64) {
            float h = Math.min(vh * 0.46f, 200f);
            g.pose().pushPose();
            g.pose().translate(0, (1 - in) * 14, 0);
            SkinPreview.draw(g, sx, feet - 2, h, yaw, skin, false, null, new SkinPreview.Layers(true, true, true, true));
            g.pose().popPose();
            String name = Account.name();
            float ns = 2f;
            Ui.text(g, font, name, sx - font.width(name) * ns / 2f, feet + 26, Ui.TEXT, ns);
            return;
        }
        int h = (int) Math.min(vh * 0.40f, 170f);
        int bodyW = h / 4;
        int headS = h / 4;
        int top = feet - h;
        double bob = Math.sin(t * 1.6) * 2;
        int a = (int) (50 + 30 * (0.5 + 0.5 * Math.sin(t * 2.0)));
        int col = (Math.max(0, Math.min(255, (int) (a * in))) << 24) | 0xFFFFFF;
        int hy = (int) (top + bob);
        Ui.outline(g, sx - headS / 2, hy, sx + headS / 2, hy + headS, col);
        Ui.outline(g, sx - bodyW / 2, hy + headS + 2, sx + bodyW / 2, hy + headS + 2 + h * 3 / 8, col);
        Ui.outline(g, sx - bodyW / 2 - bodyW / 2 - 2, hy + headS + 2, sx - bodyW / 2 - 2, hy + headS + 2 + h * 3 / 8, col);
        Ui.outline(g, sx + bodyW / 2 + 2, hy + headS + 2, sx + bodyW / 2 + bodyW / 2 + 2, hy + headS + 2 + h * 3 / 8, col);
        Ui.outline(g, sx - bodyW / 2, hy + headS + 4 + h * 3 / 8, sx - 1, feet - 2, col);
        Ui.outline(g, sx + 1, hy + headS + 4 + h * 3 / 8, sx + bodyW / 2, feet - 2, col);
        String q = "?";
        Ui.text(g, font, q, sx - font.width(q), hy + headS / 2f - 8, col, 2f);
    }

    private int heading(GuiGraphics g, String big, String small, int x, int y, int w, float in) {
        Ui.text(g, font, big, x, y, fade(Ui.TEXT, in), 2f);
        y += 24;
        if (small != null) {
            for (String line : Ui.wrap(font, small, w)) {
                g.drawString(font, line, x, y, fade(Ui.SOFT, in), false);
                y += 11;
            }
        }
        return y + 12;
    }

    private int steps(GuiGraphics g, int x, int y, int w, int at, float in) {
        String[] s = {"Open the Microsoft page", "Type the code shown here", "Come back. Pentecost finishes on its own"};
        for (int i = 0; i < s.length; i++) {
            boolean done = i < at;
            boolean now = i == at;
            int box = now ? Ui.TEXT : done ? GOOD : 0x55FFFFFF;
            if (now) {
                g.fill(x, y, x + 16, y + 16, fade(Ui.TEXT, in));
            } else {
                Ui.outline(g, x, y, x + 16, y + 16, fade(box, in));
            }
            String n = done ? "+" : String.valueOf(i + 1);
            g.drawString(font, n, x + 8 - font.width(n) / 2, y + 4, fade(now ? Ui.BLACK : done ? GOOD : Ui.MUTED, in), false);
            g.drawString(font, s[i], x + 24, y + 4, fade(now ? Ui.TEXT : done ? Ui.MUTED : Ui.FAINT, in), false);
            y += 22;
        }
        return y + 8;
    }

    private void start(GuiGraphics g, int x, int y, int w, int mx, int my, boolean failed, float in) {
        boolean noId = Account.clientId() == null;
        String why = reason != null ? reason : "Multiplayer, Realms, friends and skins need your Minecraft account.";
        y = heading(g, "Sign in", why, x, y, w, in);
        if (failed && !Account.message().isEmpty()) {
            g.fill(x, y - 4, x + 2, y + 10, BAD);
            for (String line : Ui.wrap(font, Account.message(), w - 10)) {
                g.drawString(font, line, x + 8, y, BAD, false);
                y += 11;
            }
            y += 10;
        }
        if (noId) {
            String text = "Pentecost needs a Microsoft app ID to sign you in. Make a free app in Microsoft Entra "
                    + "(App registrations, personal accounts, public client, allow public client flows), "
                    + "ask Mojang to approve it at aka.ms/AppRegInfo, then put its ID in pentecost-client-id.txt "
                    + "in your game folder. Or start Pentecost from the Modrinth App or the Minecraft Launcher: "
                    + "it then uses the account you signed in there.";
            for (String line : Ui.wrap(font, text, w)) {
                g.drawString(font, line, x, y, Ui.MUTED, false);
                y += 11;
            }
            y += 10;
            y = button(g, "Open the game folder", x, y, w, mx, my, false, () -> Util.getPlatform().openFile(minecraft.gameDirectory));
            y = button(g, "Open Microsoft Entra", x, y, w, mx, my, false,
                    () -> Util.getPlatform().openUri("https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"));
            button(g, "Open Mojang's approval form", x, y, w, mx, my, false, () -> Util.getPlatform().openUri("https://aka.ms/AppRegInfo"));
            return;
        }
        y = steps(g, x, y, w, -1, in);
        button(g, failed ? "Try again" : "Sign in with Microsoft", x, y, w, mx, my, true, Account::startLogin);
    }

    private void waiting(GuiGraphics g, int x, int y, int w, int mx, int my, double t, float in) {
        y = heading(g, "Almost there", "Go to " + Account.verifyUrl() + " and type this code.", x, y, w, in);
        String code = Account.userCode();
        int n = Math.max(1, code.length());
        int gap = 4;
        int bw = Math.max(14, Math.min(30, (w - (n - 1) * gap) / n));
        for (int i = 0; i < code.length(); i++) {
            int cx = x + i * (bw + gap);
            g.fill(cx, y, cx + bw, y + 32, 0x1FFFFFFF);
            Ui.outline(g, cx, y, cx + bw, y + 32, 0x55FFFFFF);
            String ch = code.substring(i, i + 1);
            Ui.text(g, font, ch, cx + (bw - font.width(ch) * 2f) / 2f, y + 9, Ui.TEXT, 2f);
        }
        y += 44;
        int half = (w - 6) / 2;
        button(g, "Open the page", x, y, half, mx, my, true, () -> Util.getPlatform().openUri(Account.verifyUrl()));
        y = button(g, "Copy the code", x + half + 6, y, half, mx, my, false, () -> {
            minecraft.keyboardHandler.setClipboard(Account.userCode());
            flash = "Code copied";
            flashUntil = System.currentTimeMillis() + 1800;
        });
        y += 6;
        y = steps(g, x, y, w, 1, in);
        String dots = ".".repeat(1 + (int) (t * 2) % 3);
        g.drawString(font, "Waiting for Microsoft" + dots, x, y, Ui.MUTED, false);
        y += 18;
        button(g, "Cancel", x, y, 80, mx, my, false, Account::cancel);
    }

    private void working(GuiGraphics g, int x, int y, int w, double t, float in) {
        y = heading(g, "Signing in", Account.message().isEmpty() ? "Talking to Microsoft and Mojang." : Account.message(), x, y, w, in);
        g.fill(x, y, x + w, y + 3, 0x1FFFFFFF);
        double p = (t * 0.6) % 1.0;
        int seg = w / 4;
        int sx = x + (int) ((w + seg) * p) - seg;
        g.fill(Math.max(x, sx), y, Math.min(x + w, sx + seg), y + 3, Ui.TEXT);
        y += 16;
        steps(g, x, y, w, 2, in);
    }

    private void signedIn(GuiGraphics g, int x, int y, int w, int mx, int my, float in) {
        String via = Account.source() == Account.Source.LAUNCHER ? "Signed in through your launcher. Multiplayer is open."
                : "Signed in with Microsoft. Multiplayer is open.";
        y = heading(g, "Welcome, " + Account.name(), via, x, y, w, in);
        g.fill(x, y + 2, x + 4, y + 6, GOOD);
        g.drawString(font, "Ready", x + 8, y, GOOD, false);
        y += 20;
        if (then != null) {
            y = button(g, "Continue", x, y, w, mx, my, true, () -> minecraft.setScreen(then));
        }
        y = button(g, "Account details", x, y, w, mx, my, then == null, () -> minecraft.setScreen(new AccountScreen(this)));
        if (Account.source() != Account.Source.LAUNCHER) {
            button(g, "Sign out", x, y, w, mx, my, false, Account::signOut);
        }
    }

    private int button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, boolean solid, Runnable action) {
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
        return y + 28;
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
            if (mx < stageW && my > 60 && Account.state() == Account.State.IN) {
                spin.press(mx);
                return true;
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

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
