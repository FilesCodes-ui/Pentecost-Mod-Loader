package khi.pentecost.game;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Mth;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Consumer;

final class PentecostLoadingOverlay extends Overlay {
    private final Minecraft minecraft;
    private final ReloadInstance reload;
    private final Consumer<Optional<Throwable>> onFinish;
    private final boolean fadeIn;
    private final long started = Util.getMillis();
    private float progress;
    private long fadeOutStart = -1L;
    private long fadeInStart = -1L;
    private static boolean fontsReady;
    private static final long MENU_WAIT_MS = 20_000;
    private boolean finished;
    private long finishedAt;
    private boolean waitForMenu;
    private boolean menuStarted;
    private long settledSince = -1L;
    private static final long SETTLE_MS = 600;
    private static final long FADE_OUT_MS = 350;

    private PentecostLoadingOverlay(Minecraft mc, ReloadInstance reload, Consumer<Optional<Throwable>> onFinish, boolean fadeIn) {
        this.minecraft = mc;
        this.reload = reload;
        this.onFinish = onFinish;
        this.fadeIn = fadeIn;
    }

    @SuppressWarnings("unchecked")
    static Overlay replace(LoadingOverlay original) {
        try {
            Field reload = LoadingOverlay.class.getDeclaredField("reload");
            Field onFinish = LoadingOverlay.class.getDeclaredField("onFinish");
            Field fadeIn = LoadingOverlay.class.getDeclaredField("fadeIn");
            reload.setAccessible(true);
            onFinish.setAccessible(true);
            fadeIn.setAccessible(true);
            ReloadInstance r = (ReloadInstance) reload.get(original);
            Consumer<Optional<Throwable>> f = (Consumer<Optional<Throwable>>) onFinish.get(original);
            if (r == null || f == null) {
                return null;
            }
            return new PentecostLoadingOverlay(Minecraft.getInstance(), r, f, fadeIn.getBoolean(original));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        long now = Util.getMillis();
        if (!menuStarted) {
            khi.pentecost.boot.Splash.close();
            menuStarted = true;
            try {
                khi.pentecost.game.menuworld.MenuHost.prepareEarly();
            } catch (Throwable t) {
                khi.pentecost.boot.Log.warn("Menu world could not start early: " + t);
            }
        }
        float out = fadeOutStart > -1L ? (now - fadeOutStart) / (float) FADE_OUT_MS : -1f;
        float alpha;
        if (out >= 0f) {
            if (minecraft.screen != null) {
                minecraft.screen.render(g, 0, 0, partialTick);
            }
            alpha = 1f - Mth.clamp(out, 0f, 1f);
        } else {
            alpha = 1f;
        }
        int a = Mth.ceil(alpha * 255f);
        g.fill(0, 0, w, h, (a << 24) | 0x0A0A0B);
        float actual = reload.getActualProgress();
        progress = Mth.clamp(progress * 0.95f + actual * 0.05f, 0f, 1f);
        if (a > 8) {
            draw(g, w, h, a);
        }

        if (out >= 1f) {
            minecraft.setOverlay(null);
        }
        if (!finished && reload.isDone()) {
            finished = true;
            finishedAt = Util.getMillis();
            fontsReady = true;
            try {
                reload.checkExceptions();
                onFinish.accept(Optional.empty());
            } catch (Throwable t) {
                onFinish.accept(Optional.of(t));
            }
            if (minecraft.screen != null) {
                minecraft.screen.init(minecraft, w, h);
            }
            waitForMenu = minecraft.level == null && minecraft.screen instanceof net.minecraft.client.gui.screens.TitleScreen;
            if (waitForMenu) {
                khi.pentecost.game.menuworld.MenuHost.attach(minecraft);
            }
        }
        if (finished && waitForMenu && fadeOutStart == -1L) {
            try {
                khi.pentecost.game.menuworld.MenuHost.frame(minecraft);
            } catch (Throwable t) {
                waitForMenu = false;
            }
        }
        if (finished && fadeOutStart == -1L) {
            boolean ready = !waitForMenu || khi.pentecost.game.menuworld.MenuHost.settled();
            long t = Util.getMillis();
            if (!ready) {
                settledSince = -1L;
            } else if (settledSince < 0) {
                settledSince = t;
            }
            boolean wait = waitForMenu && (!ready || t - settledSince < SETTLE_MS) && t - finishedAt < MENU_WAIT_MS;
            if (!wait) {
                fadeOutStart = Util.getMillis();
            }
        }
    }

    private void draw(GuiGraphics g, int w, int h, int a) {
        float ui = Ui.scale(minecraft);
        int vw = (int) (w / ui);
        int vh = (int) (h / ui);
        int text = (a << 24) | 0xF2F2F2;
        int muted = (a << 24) | 0x8A8A8A;
        int faint = (a << 24) | 0x3A3A3A;
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int px = 2;
        int markW = (Ui.LOGO_W + 5) * px + Ui.wordmarkWidth("PENTECOST", px);
        int x = (vw - markW) / 2;
        int y = vh * 42 / 100 - 14 * px;
        Ui.logo(g, x, y - px, px, text);
        Ui.wordmark(g, "PENTECOST", x + (Ui.LOGO_W + 5) * px, y, px, text);

        int ly = y + 14 * px + 18;
        g.fill(x, ly, x + markW, ly + 1, faint);
        g.fill(x, ly, x + Math.round(markW * progress), ly + 1, text);
        String status = progress < 0.999f ? "Loading resources" : finished && fadeOutStart == -1L ? "Preparing the menu world" : "Ready";
        if (fontsReady) g.drawString(minecraft.font, status, (vw - minecraft.font.width(status)) / 2, ly + 10, muted, false);

        Runtime rt = Runtime.getRuntime();
        double used = (rt.totalMemory() - rt.freeMemory()) / 1073741824.0;
        double max = rt.maxMemory() / 1073741824.0;
        int percent = (int) Math.round(Math.min(1.0, used / Math.max(0.1, max)) * 100);
        int bx = 40;
        int by = vh - 40;
        Ui.pixelText(g, "RAM " + percent + "%", bx, by - 2, 1, muted);
        int segments = 16;
        int lit = (int) Math.round(Math.min(1.0, used / Math.max(0.1, max)) * segments);
        for (int s = 0; s < segments; s++) {
            g.fill(bx + s * 9, by + 12, bx + s * 9 + 7, by + 20, s < lit ? text : faint);
        }

        long elapsed = Util.getMillis() - started;
        String left;
        if (progress >= 0.999f) {
            left = "Done";
        } else if (progress < 0.05f || elapsed < 500) {
            left = "Estimating";
        } else {
            long rest = (long) (elapsed * (1f - progress) / progress / 1000f) + 1;
            left = "About " + rest + " s left";
        }
        if (fontsReady) g.drawString(minecraft.font, left, vw - 40 - minecraft.font.width(left), by, muted, false);
        g.pose().popPose();
    }
}
