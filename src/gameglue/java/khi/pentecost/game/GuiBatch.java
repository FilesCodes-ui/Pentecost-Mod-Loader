package khi.pentecost.game;

import khi.pentecost.boot.Log;
import net.minecraft.client.gui.GuiGraphics;

import java.lang.reflect.Field;

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
