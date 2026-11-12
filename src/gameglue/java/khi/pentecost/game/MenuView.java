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
import java.util.Optional;

/**
 * One of Pentecost's menus, drawn over a game menu.
 *
 * The game's screen still builds its buttons, and mods still add theirs. This view hides those widgets,
 * keeps hold of them, and draws its own layout. A click here presses the original button,
 * so every action does exactly what it did before, whoever put it there.
 *
 * Buttons from mods go to one row, in black and white. Each mod's button has a switch in the Mods screen.
 */
final class MenuView {
    /** The Mods entry. It always exists and opens Pentecost's Mods screen. */
    static final String MODS = "pentecost.mods";

    private record ModButton(String modId, String label, AbstractButton source) {}

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final String where;
    private final Map<String, AbstractButton> found = new LinkedHashMap<>();
    private final List<ModButton> fromMods = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();

    /** where: "title" or "pause". Used for the per-mod button switches. */
    MenuView(String where) {
        this.where = where;
    }

    void reset() {
        found.clear();
        fromMods.clear();
    }

    /** Takes over every widget that is still showing. Runs each frame, since mods add widgets late. */
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
                // Mod Menu's buttons only open a mods list. Pentecost has its own.
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

    /**
     * Draws the menu. main lists the entries in order, by the translation key of the game's button.
     * links maps keys of small extra actions to the text to show.
     */
    void draw(Screen screen, GuiGraphics g, int mouseX, int mouseY, String title, List<String> lines,
              List<String> main, Map<String, String> links) {
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
        int y = Math.max(40, vh * 22 / 100);
        Ui.text(g, font, title, x, y, Ui.TEXT, 3f);
        y += 56;

        for (String key : main) {
            AbstractButton source = found.get(key);
            String label;
            boolean enabled = true;
            Runnable action;
            if (key.equals(MODS)) {
                label = "Mods";
                action = () -> mc.setScreen(new PentecostModsScreen(screen));
            } else if (source != null) {
                label = clean(source.getMessage().getString());
                enabled = source.active;
                action = source::onPress;
            } else {
                continue;
            }
            int w = Math.max(160, (int) (font.width(label) * 1.5f) + 24);
            boolean hover = enabled && Ui.over(mx, my, x - 12, y - 5, w, 22);
            if (hover) {
                g.fill(x - 12, y - 5, x - 12 + w, y + 17, Ui.HOVER);
                g.fill(x - 12, y - 5, x - 11, y + 17, Ui.TEXT);
            }
            Ui.text(g, font, label, x, y, !enabled ? Ui.FAINT : hover ? Ui.TEXT : Ui.SOFT, 1.5f);
            if (enabled) {
                hits.add(new Hit(x - 12, y - 5, w, 22, action));
            }
            y += 24;
        }

        // Buttons from mods: one row, black and white.
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
            String tip = null;
            for (ModButton b : shown) {
                boolean hover = Ui.over(mx, my, bx, y, 24, 24);
                if (hover) {
                    g.fill(bx, y, bx + 24, y + 24, Ui.HOVER);
                    tip = b.label();
                }
                Ui.outline(g, bx, y, bx + 24, y + 24, hover ? Ui.TEXT : Ui.FAINT);
                Optional<Ui.Gray> icon = Ui.grayIcon(b.modId());
                if (icon.isPresent()) {
                    Ui.Gray i = icon.get();
                    g.blit(i.texture(), bx + 4, y + 4, 16, 16, 0f, 0f, i.width(), i.height(), i.width(), i.height());
                } else {
                    String letter = b.label().isEmpty() ? "?" : b.label().substring(0, 1).toUpperCase();
                    g.drawString(font, letter, bx + 12 - font.width(letter) / 2, y + 8, Ui.MUTED, false);
                }
                AbstractButton source = b.source();
                hits.add(new Hit(bx, y, 24, 24, source::onPress));
                bx += 30;
            }
            if (tip != null) {
                g.drawString(font, tip, bx + 4, y + 8, Ui.MUTED, false);
            }
        }

        // Small extra actions, bottom left.
        int lx = x;
        int ly = vh - 30;
        for (Map.Entry<String, String> link : links.entrySet()) {
            AbstractButton source = found.get(link.getKey());
            if (source == null) {
                continue;
            }
            int w = font.width(link.getValue());
            boolean hover = Ui.over(mx, my, lx - 3, ly - 3, w + 6, 14);
            g.drawString(font, link.getValue(), lx, ly, hover ? Ui.TEXT : Ui.FAINT, false);
            hits.add(new Hit(lx - 3, ly - 3, w + 6, 14, source::onPress));
            lx += w + 16;
        }

        // Quiet facts, bottom right.
        int fy = vh - 30 - (lines.size() - 1) * 11;
        for (String line : lines) {
            g.drawString(font, line, vw - 56 - font.width(line), fy, Ui.FAINT, false);
            fy += 11;
        }
        g.pose().popPose();
    }

    /** Presses whatever is under the pointer. Returns true when something was pressed. */
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
