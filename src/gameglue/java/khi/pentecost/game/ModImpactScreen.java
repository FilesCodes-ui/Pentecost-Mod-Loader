package khi.pentecost.game;

import khi.pentecost.boot.Dirs;
import khi.pentecost.core.ModImpact;
import khi.pentecost.core.ModOwners;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ModImpactScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private static final int MARGIN = 48;
    private static final int ROW = 30;
    private static final int GOOD = 0xFF8FD18F;
    private static final int BAD = 0xFFE08080;

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private boolean byTick;
    private int scroll;
    private int maxScroll;
    private int top;
    private int bottom;

    ModImpactScreen(Screen parent) {
        super(Component.literal("Mod impact"));
        this.parent = parent;
    }

    static Path flagFile() {
        return Dirs.config().resolve("pentecost").resolve("mod-impact.json");
    }

    static Map<String, String> loaded() {
        Map<String, String> out = new HashMap<>();
        for (ModInfo m : PentecostRuntime.mods) {
            out.put(ModOwners.group(m.id()), m.version());
        }
        return out;
    }

    static void start() {
        List<ModInfo> mods = PentecostRuntime.allMods.isEmpty() ? PentecostRuntime.mods : PentecostRuntime.allMods;
        ClassLoader cl = PentecostRuntime.loader != null ? PentecostRuntime.loader : ModImpactScreen.class.getClassLoader();
        ModImpact.start(() -> ModOwners.build(mods, cl));
    }

    static void stop() {
        ModImpact.stop(loaded(), flagFile());
    }

    private static String nameOf(String id) {
        if (ModImpact.GAME.equals(id)) {
            return "Minecraft and Forge";
        }
        if ("pentecost".equals(id)) {
            return "Pentecost";
        }
        if ("fabric_api".equals(id)) {
            return "Fabric API";
        }
        for (ModInfo m : PentecostRuntime.allMods) {
            if (m.id().equals(id)) {
                return m.name();
            }
        }
        return id;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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

        Ui.logo(g, MARGIN, 20, 2, Ui.TEXT);
        Ui.wordmark(g, "IMPACT", MARGIN + (Ui.LOGO_W + 5) * 2, 22, 2, Ui.TEXT);
        g.drawString(font, "What each mod costs the game, measured while you play", MARGIN, 56, Ui.FAINT, false);
        g.fill(MARGIN, 68, vw - MARGIN, 69, Ui.LINE);

        int x = MARGIN;
        int y = 78;
        boolean running = ModImpact.running();
        x += button(g, running ? "Stop" : "Start measuring", x, y, mx, my, running, true, () -> {
            if (ModImpact.running()) {
                stop();
            } else {
                start();
            }
        }) + 6;
        x += button(g, "Per frame", x, y, mx, my, !byTick, true, () -> byTick = false) + 6;
        button(g, "Per tick", x, y, mx, my, byTick, true, () -> byTick = true);

        String status;
        int statusColor = Ui.SOFT;
        if (!ModImpact.error().isEmpty()) {
            status = ModImpact.error();
            statusColor = BAD;
        } else if (ModImpact.preparing()) {
            status = "Reading which mod owns which code...";
        } else if (running) {
            status = String.format(Locale.ROOT, "Measuring for %.0f s, %d frames. Close this screen and play normally, then come back and stop.",
                    ModImpact.seconds(), ModImpact.frames());
        } else if (ModImpact.frames() > 0) {
            status = String.format(Locale.ROOT, "Last measurement: %.0f s, %d frames.", ModImpact.seconds(), ModImpact.frames());
        } else {
            status = "Press Start, play for a minute in a usual spot, then come back and press Stop.";
            statusColor = Ui.FAINT;
        }
        g.drawString(font, font.plainSubstrByWidth(status, vw - MARGIN * 2), MARGIN, 108, statusColor, false);

        top = 126;
        bottom = vh - 44;
        g.enableScissor(0, Math.round(top * ui), width, Math.round(bottom * ui));
        list(g, vw, mx, my);
        g.disableScissor();

        int bw = font.width("Back") + 24;
        int bx = vw - MARGIN - bw;
        int by = vh - 32;
        boolean hover = Ui.over(mx, my, bx, by, bw, 18);
        if (hover) {
            g.fill(bx, by, bx + bw, by + 18, Ui.TEXT);
        } else {
            Ui.outline(g, bx, by, bx + bw, by + 18, Ui.MUTED);
        }
        g.drawString(font, "Back", bx + 12, by + 5, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(bx, by, bw, 18, this::onClose));
        g.drawString(font, font.plainSubstrByWidth("Renderers draw the world, so frame time is expected for them. Numbers are estimates from sampling.",
                bx - MARGIN - 16), MARGIN, vh - 27, Ui.FAINT, false);
        g.pose().popPose();
    }

    private void list(GuiGraphics g, int vw, int mx, int my) {
        List<ModImpact.Row> rows = new ArrayList<>(ModImpact.rows());
        rows.sort(Comparator.comparingDouble((ModImpact.Row r) -> byTick ? r.msPerTick() : r.msPerFrame()).reversed());
        int w = vw - MARGIN * 2;
        int y = top + 4 - scroll;
        if (rows.isEmpty()) {
            g.drawString(font, ModImpact.running() ? "Waiting for the first samples..." : "No measurement yet.", MARGIN, y + 4, Ui.MUTED, false);
            maxScroll = 0;
            return;
        }
        double max = 0.0001;
        for (ModImpact.Row r : rows) {
            max = Math.max(max, byTick ? r.msPerTick() : r.msPerFrame());
        }
        int nameW = Math.min(240, w / 3);
        int barX = MARGIN + nameW + 12;
        int valueW = 150;
        int barW = Math.max(40, w - nameW - 12 - valueW - 12);
        for (ModImpact.Row r : rows) {
            double v = byTick ? r.msPerTick() : r.msPerFrame();
            if (y + ROW >= top && y <= bottom) {
                g.fill(MARGIN, y + ROW - 2, MARGIN + w, y + ROW - 1, Ui.LINE);
                int nameColor = r.flagged() ? BAD : Ui.TEXT;
                g.drawString(font, font.plainSubstrByWidth(nameOf(r.mod()), nameW), MARGIN, y + 6, nameColor, false);
                String tag = r.flagged() ? "Slows the game" : r.renderer() ? "Renderer" : "";
                if (!tag.isEmpty()) {
                    g.drawString(font, tag, MARGIN, y + 17, r.flagged() ? BAD : Ui.FAINT, false);
                }
                int filled = (int) Math.round(barW * Math.min(1, v / max));
                Ui.outline(g, barX, y + 8, barX + barW, y + 16, Ui.LINE);
                if (filled > 0) {
                    g.fill(barX, y + 8, barX + filled, y + 16, r.flagged() ? BAD : r.renderer() ? Ui.FAINT : Ui.SOFT);
                }
                String value = String.format(Locale.ROOT, byTick ? "%.2f ms per tick" : "%.2f ms per frame", v);
                g.drawString(font, value, MARGIN + w - font.width(value), y + 8, v >= 1 ? Ui.TEXT : Ui.MUTED, false);
            }
            y += ROW;
        }
        maxScroll = Math.max(0, y - bottom + scroll + 8);
        scroll = Math.min(scroll, maxScroll);
    }

    private int button(GuiGraphics g, String label, int x, int y, int mx, int my, boolean active, boolean enabled, Runnable action) {
        int w = font.width(label) + 28;
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 20);
        if (active || hover) {
            g.fill(x, y, x + w, y + 20, enabled ? Ui.TEXT : Ui.FAINT);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, enabled ? 0x66FFFFFF : Ui.LINE);
        }
        g.drawString(font, label, x + 14, y + 6, active || hover ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
        return w;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 24)));
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
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
