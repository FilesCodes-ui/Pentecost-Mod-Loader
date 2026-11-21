package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import net.minecraft.client.Minecraft;

final class PortalRefresh {
    static final String SETTING = "fix.portalRefresh";

    private static final long BURST_MS = 3000;
    private static final long SETTLE_MS = 1000;

    private static String lastDim;
    private static long prevChange;
    private static long lastChange;
    private static boolean pending;

    private PortalRefresh() {}

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            lastDim = null;
            pending = false;
            return;
        }
        String dim = mc.player.level().dimension().location().toString();
        long now = System.currentTimeMillis();
        if (lastDim != null && !lastDim.equals(dim)) {
            prevChange = lastChange;
            lastChange = now;
            if (prevChange != 0 && lastChange - prevChange <= BURST_MS) {
                pending = true;
            }
        }
        lastDim = dim;
        if (!pending || now - lastChange < SETTLE_MS) {
            return;
        }
        pending = false;
        if (!Settings.on(SETTING)) {
            return;
        }
        if (!mc.level.dimension().location().toString().equals(dim)) {
            return;
        }
        try {
            mc.levelRenderer.allChanged();
            Log.info("Portal refresh: dimension changed twice within " + (lastChange - prevChange)
                    + " ms, reloaded drawn chunks in " + dim);
        } catch (RuntimeException e) {
            Log.warn("Portal refresh failed: " + e);
        }
    }
}
