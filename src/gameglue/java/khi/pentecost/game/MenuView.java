package khi.pentecost.game;

import khi.pentecost.control.Choices;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MenuView {
    static final String MODS = "pentecost.mods";
    static final String SETTINGS = "pentecost.settings";
    static final String ACCOUNT = "pentecost.account";
    static final String FRIENDS = "pentecost.friends";
    static final String MANAGE = "pentecost.manage";
    static final String COMMANDS = "pentecost.commands";
    static final String QUIT = "pentecost.quit";

    private record ModButton(String modId, String label, AbstractButton source) {}

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final String where;
    private final Map<String, AbstractButton> found = new LinkedHashMap<>();
    private final List<ModButton> fromMods = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int lastHover = -1;

    MenuView(String where) {
        this.where = where;
    }

    void reset() {
        found.clear();
        fromMods.clear();
        GrayButtons.clear();
    }

    void harvest(List<? extends GuiEventListener> children) {
        for (GuiEventListener child : children) {
            if (!(child instanceof AbstractWidget w) || !w.visible) {
                continue;
            }
            w.visible = false;
            if (!(w instanceof AbstractButton b)) {
                continue;
            }
            String owner = Ui.ownerOf(w);
            if (owner != null) {
                if (!owner.equals("modmenu")) {
                    Choices.seeButton(owner, where);
                    String label = w.getMessage().getString().trim();
                    ModInfo m = Ui.mod(owner);
                    fromMods.add(new ModButton(owner, label.isEmpty() && m != null ? m.name() : label, b));
                }
            } else if (!Ui.key(w).isEmpty()) {
                found.putIfAbsent(Ui.key(w), b);
            }
        }
    }

    private static String clean(String label) {
        return label.replace("...", "").replace("…", "").trim();
    }

    void draw(Screen screen, GuiGraphics g, int mouseX, int mouseY, float partialTick, String word, String under,
              List<String> lines, List<String> main, Map<String, String> links) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float ui = Ui.scale(mc);
        int vw = (int) (screen.width / ui);
        int vh = (int) (screen.height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int x = 56;
        int y = Math.max(36, vh * 18 / 100);

        int px = vh >= 300 ? 2 : 1;
        Ui.logo(g, x, y - px, px, Ui.TEXT);
        int wx = x + (Ui.LOGO_W + 5) * px;
        Ui.wordmark(g, word, wx, y, px, Ui.TEXT);
        int markWidth = wx - x + Ui.wordmarkWidth(word, px);
        y += 14 * px + 10;
        if (!under.isEmpty()) {
            g.drawString(font, under.toUpperCase(java.util.Locale.ROOT), x, y, Ui.MUTED, false);
            y += 13;
        }
        int barWidth = Math.max(200, markWidth);
        g.fill(x, y, x + barWidth, y + 1, Ui.LINE);
        y += 14;

        int index = 0;
        int hovered = -1;
        for (String key : main) {
            AbstractButton source = found.get(key);
            String label;
            boolean enabled = true;
            Runnable action;
            if (key.equals(MODS)) {
                label = "Mods";
                action = () -> mc.setScreen(new PentecostModsScreen(screen));
            } else if (key.equals(MANAGE)) {
                if (!ServerScreen.available(mc)) {
                    continue;
                }
                label = "Server tools";
                action = () -> mc.setScreen(new ServerScreen(screen));
            } else if (key.equals(COMMANDS)) {
                if (mc.player == null || !mc.player.hasPermissions(2)) {
                    continue;
                }
                label = "Commands";
                action = () -> mc.setScreen(new CommandsScreen(screen));
            } else if (key.equals(QUIT)) {
                label = "Quit game";
                action = () -> quitGame(mc);
            } else if (key.equals(FRIENDS)) {
                label = "Join a friend";
                action = () -> mc.setScreen(new WwlsScreen(screen));
            } else if (key.equals(ACCOUNT)) {
                label = Account.signedIn() ? Account.name() : Account.restoring() ? "Signing in" : "Sign in";
                action = () -> mc.setScreen(Account.signedIn() ? new AccountScreen(screen) : new PentecostLoginScreen(screen, null, null));
            } else if (key.equals(SETTINGS)) {
                if (!khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.VANILLA_SETTINGS)) {
                    continue;
                }
                label = "Pentecost";
                action = () -> mc.setScreen(new PentecostSettingsScreen(screen));
            } else if (source != null) {
                label = clean(source.getMessage().getString());
                enabled = source.active;
                action = source::onPress;
                if (key.equals("menu.shareToLan")
                        && !khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.VANILLA_SETTINGS)) {
                    label = "Open to friends";
                    enabled = true;
                    action = () -> mc.setScreen(new WwlsScreen(screen));
                }
            } else {
                continue;
            }
            index++;
            boolean hover = enabled && Ui.over(mx, my, x - 8, y, barWidth + 8, 20);
            if (hover) {
                hovered = index;
                g.fill(x - 8, y, x + barWidth, y + 20, Ui.TEXT);
            }
            int color = !enabled ? Ui.FAINT : hover ? Ui.BLACK : Ui.SOFT;
            Ui.text(g, font, (hover ? "> " : "") + label, x + (hover ? 0 : 0), y + 4, color, 1.5f);
            String number = index < 10 ? "0" + index : String.valueOf(index);
            g.drawString(font, number, x + barWidth - 8 - font.width(number), y + 6,
                    hover ? Ui.BLACK : Ui.FAINT, false);
            if (enabled) {
                hits.add(new Hit(x - 8, y, barWidth + 8, 20, action));
            }
            y += 22;
        }
        if (hovered != lastHover) {
            lastHover = hovered;
        }

        List<ModButton> shown = new ArrayList<>();
        for (ModButton b : fromMods) {
            if (Choices.isEnabled(b.modId(), "button:" + where)) {
                shown.add(b);
            }
        }
        if (!shown.isEmpty()) {
            y += 14;
            g.drawString(font, "FROM MODS", x, y, Ui.FAINT, false);
            y += 13;
            int bx = x;
            int rowHeight = 20;
            String tip = null;
            for (ModButton b : shown) {
                AbstractButton source = b.source();
                int bw = source.getWidth();
                int bh = source.getHeight();
                if (Ui.over(mx, my, bx, y, bw, bh)) {
                    tip = b.label();
                }
                if (System.getenv("PENTECOST_TEST_NOGRAY") == null
                        && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.GRAY_BUTTONS)) {
                    GrayButtons.draw(g, source, bx, y, mx, my, partialTick, ui);
                } else {
                    int ox = source.getX();
                    int oy = source.getY();
                    source.setX(bx);
                    source.setY(y);
                    source.visible = true;
                    try {
                        source.render(g, mx, my, partialTick);
                    } finally {
                        source.visible = false;
                        source.setX(ox);
                        source.setY(oy);
                    }
                }
                hits.add(new Hit(bx, y, bw, bh, source::onPress));
                bx += bw + 6;
                rowHeight = Math.max(rowHeight, bh);
            }
            if (tip != null && !tip.isEmpty()) {
                g.drawString(font, tip, bx + 4, y + rowHeight / 2 - 4, Ui.MUTED, false);
            }
        }

        int fy = vh - 22 - lines.size() * 11;
        for (String line : lines) {
            g.drawString(font, line, vw - 40 - font.width(line), fy, Ui.FAINT, false);
            fy += 11;
        }
        List<Map.Entry<String, String>> shownLinks = new ArrayList<>();
        int linksWidth = 0;
        for (Map.Entry<String, String> link : links.entrySet()) {
            if (found.get(link.getKey()) != null) {
                shownLinks.add(link);
                linksWidth += font.width(link.getValue()) + 14;
            }
        }
        int lx = vw - 40 - Math.max(0, linksWidth - 14);
        int ly = vh - 22;
        for (Map.Entry<String, String> link : shownLinks) {
            AbstractButton source = found.get(link.getKey());
            int w = font.width(link.getValue());
            boolean hover = Ui.over(mx, my, lx - 3, ly - 3, w + 6, 14);
            if (hover) {
                g.fill(lx - 3, ly - 3, lx + w + 3, ly + 11, Ui.TEXT);
            }
            g.drawString(font, link.getValue(), lx, ly, hover ? Ui.BLACK : Ui.MUTED, false);
            hits.add(new Hit(lx - 3, ly - 3, w + 6, 14, source::onPress));
            lx += w + 14;
        }
        g.pose().popPose();
    }

    static void quitGame(Minecraft mc) {
        if (mc.level != null) {
            boolean local = mc.isLocalServer();
            mc.level.disconnect();
            if (local) {
                mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(
                        net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
            } else {
                mc.clearLevel();
            }
        }
        mc.stop();
    }

    boolean click(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        float ui = Ui.scale(mc);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                h.action().run();
                return true;
            }
        }
        return false;
    }
}
