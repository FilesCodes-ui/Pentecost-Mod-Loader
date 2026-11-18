package khi.pentecost.game;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A skin as a turning 3D figure, drawn with the game's own player model. The model is built here, so no player entity is needed
 * and the figure can show any skin and any cape. Render thread only.
 */
final class SkinPreview {
    private static EntityModelSet built;
    private static PlayerModel<AbstractClientPlayer> classic;
    private static PlayerModel<AbstractClientPlayer> slim;

    private SkinPreview() {}

    private static PlayerModel<AbstractClientPlayer> model(Minecraft mc, boolean slimArms) {
        EntityModelSet set = mc.getEntityModels();
        if (set != built) {
            built = set;
            classic = new PlayerModel<>(set.bakeLayer(ModelLayers.PLAYER), false);
            slim = new PlayerModel<>(set.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }
        return slimArms ? slim : classic;
    }

    /** Which outer layers to show. */
    record Layers(boolean hat, boolean jacket, boolean sleeves, boolean pants) {}

    /**
     * Draws the figure standing at (cx, feetY) in the current pose coordinates, height pixels tall, turned by yaw degrees.
     * cape may be null. The figure is drawn in front of everything drawn before it.
     */
    static void draw(GuiGraphics g, float cx, float feetY, float height, float yaw, ResourceLocation skin, boolean slimArms,
                     ResourceLocation cape, Layers layers) {
        Minecraft mc = Minecraft.getInstance();
        PlayerModel<AbstractClientPlayer> m = model(mc, slimArms);
        pose(m, layers);
        g.flush();
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(cx, feetY, 50);
        float s = height / 1.875f;
        ps.scale(s, s, -s);
        ps.mulPose(Axis.ZP.rotationDegrees(180));
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        ps.scale(-1, -1, 1);
        ps.scale(0.9375f, 0.9375f, 0.9375f);
        ps.translate(0, -1.501f, 0);
        Lighting.setupForEntityInInventory();
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        int light = 0xF000F0;
        VertexConsumer body = buffers.getBuffer(RenderType.entityTranslucent(skin));
        m.renderToBuffer(ps, body, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        if (cape != null) {
            ps.pushPose();
            ps.translate(0, 0, 0.125f);
            ps.mulPose(Axis.XP.rotationDegrees(6));
            ps.mulPose(Axis.YP.rotationDegrees(180));
            m.renderCloak(ps, buffers.getBuffer(RenderType.entitySolid(cape)), light, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
        g.flush();
        ps.popPose();
        Lighting.setupFor3DItems();
    }

    /** Standing, with the arms swinging a little so the figure looks alive. The outer layers follow the parts under them. */
    private static void pose(PlayerModel<AbstractClientPlayer> m, Layers layers) {
        float t = (System.currentTimeMillis() % 200_000L) / 50f;
        m.young = false;
        m.crouching = false;
        m.head.xRot = 0;
        m.head.yRot = 0;
        m.head.zRot = 0;
        m.body.xRot = 0;
        m.body.yRot = 0;
        m.body.zRot = 0;
        m.rightLeg.xRot = 0;
        m.rightLeg.yRot = 0;
        m.rightLeg.zRot = 0;
        m.leftLeg.xRot = 0;
        m.leftLeg.yRot = 0;
        m.leftLeg.zRot = 0;
        m.rightArm.yRot = 0;
        m.leftArm.yRot = 0;
        m.rightArm.zRot = 0.05f + (float) Math.cos(t * 0.09f) * 0.05f;
        m.leftArm.zRot = -0.05f - (float) Math.cos(t * 0.09f) * 0.05f;
        m.rightArm.xRot = (float) Math.sin(t * 0.067f) * 0.05f;
        m.leftArm.xRot = -(float) Math.sin(t * 0.067f) * 0.05f;
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        m.head.visible = true;
        m.body.visible = true;
        m.rightArm.visible = true;
        m.leftArm.visible = true;
        m.rightLeg.visible = true;
        m.leftLeg.visible = true;
        m.hat.visible = layers.hat();
        m.jacket.visible = layers.jacket();
        m.rightSleeve.visible = layers.sleeves();
        m.leftSleeve.visible = layers.sleeves();
        m.rightPants.visible = layers.pants();
        m.leftPants.visible = layers.pants();
    }
}
