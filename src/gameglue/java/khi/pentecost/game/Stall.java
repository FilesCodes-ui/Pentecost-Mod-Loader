package khi.pentecost.game;

import khi.pentecost.control.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Shows "Stall 312 ms" above the hotbar when one frame took longer than the limit the player set. */
final class Stall {
    private static long last;
    private static int frames;

    private Stall() {}

    static void frame() {
        long now = System.nanoTime();
        long gapMs = (now - last) / 1_000_000L;
        last = now;
        if (!Settings.on(Settings.STALL_WARNING) || ++frames < 300) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.screen != null || !mc.isWindowActive()) {
            return;
        }
        int limit = Math.max(50, Settings.num(Settings.STALL_MS, 150));
        if (gapMs >= limit && gapMs < 5000) {
            mc.gui.setOverlayMessage(Component.literal("Stall " + gapMs + " ms"), false);
        }
    }
}
