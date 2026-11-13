package khi.pentecost.game;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A thin scroll thumb that grows when the pointer is near it and can be dragged.
 * Coordinates are the screen's own drawing units, the same ones its widgets use.
 */
final class Scrollbar {
    private static int counter;

    private final String key = "scrollbar:" + (counter++);
    private int x;
    private int top;
    private int bottom;
    private int bar;
    private int barY;
    private int max;
    private boolean active;
    private boolean drag;
    private double grab;

    /** Call every frame, after the scroll position is clamped. */
    void draw(GuiGraphics g, int x, int top, int bottom, double scroll, int max, int mx, int my) {
        this.x = x;
        this.top = top;
        this.bottom = bottom;
        this.max = max;
        active = max > 0 && bottom > top;
        if (!active) {
            drag = false;
            return;
        }
        int track = bottom - top;
        bar = Math.max(24, track * track / (track + max));
        barY = top + (int) ((track - bar) * Math.max(0, Math.min(max, scroll)) / max);
        boolean near = drag || near(mx, my);
        float width = Ui.ease(key, near ? 6f : 2f, 18f);
        int w = Math.max(1, Math.round(width));
        int left = x - w / 2;
        if (w > 2) {
            g.fill(left - 1, top, left + w + 1, bottom, 0x14FFFFFF);
        }
        g.fill(left, barY, left + w, barY + bar, drag ? Ui.TEXT : near ? Ui.SOFT : Ui.MUTED);
    }

    private boolean near(int mx, int my) {
        return active && mx >= x - 8 && mx <= x + 8 && my >= top && my < bottom;
    }

    /** True when the press landed on the thumb or its track. The caller then sets its scroll from scrollAt. */
    boolean press(int mx, int my) {
        if (!near(mx, my)) {
            return false;
        }
        grab = my >= barY && my < barY + bar ? my - barY : bar / 2.0;
        drag = true;
        return true;
    }

    boolean dragging() {
        return drag;
    }

    /** The scroll position for a pointer at this height while dragging. */
    double scrollAt(int my) {
        int span = Math.max(1, (bottom - top) - bar);
        double t = Math.max(0, Math.min(1, (my - grab - top) / span));
        return t * max;
    }

    void release() {
        drag = false;
    }
}
