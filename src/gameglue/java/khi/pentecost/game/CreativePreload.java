package khi.pentecost.game;

import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;

final class CreativePreload {
    private static ClientPacketListener doneFor;

    private CreativePreload() {}

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.player == null || mc.gameMode == null || doneFor == conn) {
            return;
        }
        if (!mc.gameMode.hasInfiniteItems()) {
            return;
        }
        doneFor = conn;
        long start = System.nanoTime();
        try {
            new CreativeModeInventoryScreen(mc.player, conn.enabledFeatures(), mc.options.operatorItemsTab().get());
            Log.info(String.format("Creative inventory prepared in %.0f ms", (System.nanoTime() - start) / 1e6));
        } catch (RuntimeException | LinkageError e) {
            Log.warn("Could not prepare the creative inventory early. It builds on first open instead: " + e);
        }
    }
}
