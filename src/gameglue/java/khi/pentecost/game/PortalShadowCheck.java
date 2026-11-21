package khi.pentecost.game;

import khi.pentecost.control.Settings;
import khi.pentecost.core.PortalShadow;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class PortalShadowCheck {
    private static final String RENDERING = "qouteall.imm_ptl.core.render.context_management.PortalRendering";

    private static boolean looked;
    private static MethodHandle isRendering;

    private PortalShadowCheck() {}

    static void install() {
        if (PortalShadow.check == null && Settings.on("render.portalShadow")) {
            PortalShadow.check = PortalShadowCheck::skip;
        }
    }

    private static boolean skip() {
        if (!Settings.on("render.portalShadow") || !portalView()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return false;
        }
        Camera cam = mc.gameRenderer.getMainCamera();
        if (!cam.isInitialized()) {
            return false;
        }
        BlockPos at = cam.getBlockPosition();
        return level.getBrightness(LightLayer.SKY, at) == 0;
    }

    private static boolean portalView() {
        if (!looked) {
            looked = true;
            try {
                Class<?> c = Class.forName(RENDERING, false, PortalShadowCheck.class.getClassLoader());
                isRendering = MethodHandles.publicLookup().findStatic(c, "isRendering", MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                isRendering = null;
            }
        }
        if (isRendering == null) {
            return false;
        }
        try {
            return (boolean) isRendering.invoke();
        } catch (Throwable e) {
            return false;
        }
    }
}
