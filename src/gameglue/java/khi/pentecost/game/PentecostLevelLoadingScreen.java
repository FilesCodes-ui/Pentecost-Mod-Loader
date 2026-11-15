package khi.pentecost.game;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Pentecost's picture for the screens between a click and a world: the logo, a thin line that fills with the
 * chunks being made, and a small grid of those chunks. It keeps the game's own screen underneath (same ticking,
 * same closing), and only changes what is drawn.
 */
public final class PentecostLevelLoadingScreen extends LevelLoadingScreen {
    private static Field listenerField;

    private final StoringChunkProgressListener listener;
    private final long started = Util.getMillis();

    public PentecostLevelLoadingScreen(StoringChunkProgressListener listener) {
        super(listener);
        this.listener = listener;
    }

    /** Pentecost's version of the game's loading screen, or null when its parts cannot be read. */
    static LevelLoadingScreen replace(LevelLoadingScreen original) {
        try {
            if (listenerField == null) {
                listenerField = LevelLoadingScreen.class.getDeclaredField("progressListener");
                listenerField.setAccessible(true);
            }
            StoringChunkProgressListener l = (StoringChunkProgressListener) listenerField.get(original);
            return l == null ? null : new PentecostLevelLoadingScreen(l);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int percent = Math.max(0, Math.min(100, listener.getProgress()));
        String what = percent < 8 ? "Preparing" : percent < 60 ? "Shaping the land" : percent < 95 ? "Lighting the chunks" : "Almost there";
        draw(g, minecraft, "LOADING", what, percent / 100f, listener);
        long secs = (Util.getMillis() - started) / 1000;
        if (secs >= 2) {
            float ui = Ui.scale(minecraft);
            String t = secs + " s";
            g.drawString(font, t, (int) (width / ui) - 40 - font.width(t), (int) (height / ui) - 40, Ui.FAINT, false);
        }
    }

    /**
     * progress: 0 to 1, or below 0 when it is not known. grid: the chunks to show, or null.
     */
    static void draw(GuiGraphics g, Minecraft mc, String word, String status, float progress, StoringChunkProgressListener grid) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF0A0A0B);
        Ui.dither(g, g.guiWidth(), g.guiHeight());
        float ui = Ui.scale(mc);
        int vw = (int) (g.guiWidth() / ui);
        int vh = (int) (g.guiHeight() / ui);
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        int cx = vw / 2;
        int px = 2;
        int total = (Ui.LOGO_W + 5) * px + Ui.wordmarkWidth(word, px);
        int x = cx - total / 2;
        int top = vh * 30 / 100;
        Ui.logo(g, x, top - px, px, Ui.TEXT);
        Ui.wordmark(g, word, x + (Ui.LOGO_W + 5) * px, top, px, Ui.TEXT);

        // The line: filled when the progress is known, a short slider when it is not.
        int ly = top + 14 * px + 18;
        int lw = Math.max(120, total);
        int lx = cx - lw / 2;
        g.fill(lx, ly, lx + lw, ly + 1, 0xFF3A3A3A);
        if (progress >= 0) {
            g.fill(lx, ly, lx + Math.round(lw * Math.min(1f, progress)), ly + 1, Ui.TEXT);
        } else {
            double t = (Util.getMillis() % 1600) / 1600.0;
            int seg = lw / 4;
            int sx = lx + (int) ((lw + seg) * t) - seg;
            g.fill(Math.max(lx, sx), ly, Math.min(lx + lw, sx + seg), ly + 1, Ui.TEXT);
        }
        String line = progress >= 0 ? status + "   " + Math.round(progress * 100) + "%" : status;
        g.drawString(mc.font, line, cx - mc.font.width(line) / 2, ly + 9, Ui.MUTED, false);

        // The chunks, one small square each: lit when finished, dim while being made.
        if (grid != null && grid.getDiameter() > 0) {
            int d = grid.getDiameter();
            int gy = ly + 34;
            int cell = Math.max(2, Math.min(5, (vh - gy - 30) / Math.max(1, d)));
            int gx = cx - d * cell / 2;
            for (int cz = 0; cz < d; cz++) {
                for (int cxx = 0; cxx < d; cxx++) {
                    ChunkStatus st = grid.getStatus(cxx, cz);
                    int color = st == null ? 0xFF1C1C1E : st == ChunkStatus.FULL ? Ui.TEXT : 0xFF6E6E70;
                    int sx = gx + cxx * cell;
                    int sy = gy + cz * cell;
                    g.fill(sx, sy, sx + cell - 1, sy + cell - 1, color);
                }
            }
        }
        g.pose().popPose();
    }

    /** "Saving world", "Loading world" and the like: the same picture with an unknown progress. */
    public static final class Message extends GenericDirtMessageScreen {
        public Message(Component title) {
            super(title);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            draw(g, minecraft, "PENTECOST", getTitle().getString(), -1f, null);
        }
    }

    /** Shown while a world or a server sends the first chunks. */
    public static final class Receiving extends ReceivingLevelScreen {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            draw(g, minecraft, "PENTECOST", "Loading terrain", -1f, null);
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
