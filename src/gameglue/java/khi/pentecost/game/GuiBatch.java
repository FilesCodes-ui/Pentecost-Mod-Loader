package khi.pentecost.game;

import khi.pentecost.boot.Log;
import net.minecraft.client.gui.GuiGraphics;

import java.lang.reflect.Field;

/**
 * A GuiGraphics made outside the game's own screens draws every fill and every line of text as its own batch.
 * With shaders on, each batch is a costly draw call, and the readouts make hundreds. Marking the graphics
 * as managed makes it keep everything until flush() is called once.
 */
final class GuiBatch {
    private static Field managed;
    private static boolean failed;

    private GuiBatch() {}

    static GuiGraphics on(GuiGraphics g) {
        if (failed) {
            return g;
        }
        try {
            if (managed == null) {
                managed = GuiGraphics.class.getDeclaredField("managed");
                managed.setAccessible(true);
            }
            managed.setBoolean(g, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            Log.warn("Cannot batch the readouts: " + e);
        }
        return g;
    }
}
