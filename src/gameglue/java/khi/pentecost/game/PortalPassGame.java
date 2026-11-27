package khi.pentecost.game;

import khi.pentecost.compat.PortalPass;
import net.minecraft.client.Minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Decides whether the render thread is currently drawing a portal view.
 *  1. Immersive Portals' own flag, PortalRendering.isRendering(), when that mod is present.
 *  2. Otherwise (or as a second opinion) the world being drawn is not the world the player is in.
 * Only ever true on the render thread, so worker threads that call guarded methods are never affected.
 */
final class PortalPassGame {
    private static MethodHandle imm;
    private static int attempts;

    private PortalPassGame() {}

    static void install() {
        PortalPass.detector = PortalPassGame::inPortalPass;
    }

    private static boolean inPortalPass() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return false;
        if (imm == null && attempts < 300) {
            attempts++;
            try {
                Class<?> c = Class.forName("qouteall.imm_ptl.core.render.context_management.PortalRendering",
                        false, Minecraft.class.getClassLoader());
                imm = MethodHandles.publicLookup().findStatic(c, "isRendering", MethodType.methodType(boolean.class));
            } catch (Throwable notThere) {
                // Immersive Portals is not loaded (yet); the world check below still works
            }
        }
        if (imm != null) {
            try {
                if ((boolean) imm.invoke()) return true;
            } catch (Throwable t) {
                imm = null;
                attempts = 300;
            }
        }
        return mc.player != null && mc.level != null && mc.player.level() != mc.level;
    }
}
