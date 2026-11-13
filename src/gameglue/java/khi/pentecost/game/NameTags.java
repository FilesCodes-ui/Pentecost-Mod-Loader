package khi.pentecost.game;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import khi.pentecost.core.TagHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

import java.util.List;

/**
 * The [dev] tag. Above a dev's head: "[dev]" over the name, with a small boat that bobs beside it.
 * In the player list and in chat: "[dev]" in front of the name and a boat after it.
 */
final class NameTags implements TagHooks.Drawer, TagHooks.Marker {
    private static final String PREFIX = "[dev] ";
    private static final String BOAT = " ⛵";

    /** One boat, as rows of pixels. M mast, S sail, H hull, W water. A second frame flaps the sail and moves the waves. */
    private static final String[][] BOAT_FRAMES = {
            {"     M     ", "     MS    ", "     MSS   ", "     MSSS  ", "     MSSSS ", "     M     ",
                    " HHHHHHHHH ", "  HHHHHHH  ", "WWW WWW WWW"},
            {"     M     ", "     MS    ", "     MSS   ", "     MSS   ", "     MSSS  ", "     M     ",
                    " HHHHHHHHH ", "  HHHHHHH  ", "W WWW WWW W"}};

    static void install() {
        NameTags tags = new NameTags();
        TagHooks.drawer = tags;
        TagHooks.marker = tags;
    }

    private static boolean tagged(Component c) {
        return c.getSiblings().size() == 3 && c.getSiblings().get(0).getString().equals(PREFIX);
    }

    private Component wrap(Component name) {
        if (tagged(name)) {
            return name;
        }
        return Component.empty()
                .append(Component.literal(PREFIX).withStyle(ChatFormatting.GOLD))
                .append(name)
                .append(Component.literal(BOAT).withStyle(ChatFormatting.AQUA));
    }

    @Override
    public Object untag(Object component) {
        Component c = (Component) component;
        return tagged(c) ? c.getSiblings().get(1) : c;
    }

    @Override
    public Object displayName(Object component, Object entity) {
        if (entity instanceof Player p && PentecostDev.isDev(p.getUUID())) {
            return wrap((Component) component);
        }
        return component;
    }

    @Override
    public Object tab(Object component, Object info) {
        if (info instanceof PlayerInfo pi && PentecostDev.isDev(pi.getProfile().getId())) {
            return wrap((Component) component);
        }
        return component;
    }

    @Override
    public boolean showOwn(Object entity) {
        Minecraft mc = Minecraft.getInstance();
        return entity == mc.player && mc.player != null && !mc.options.getCameraType().isFirstPerson()
                && PentecostDev.isDev(mc.player.getUUID());
    }

    @Override
    public void nameTag(Object renderer, Object entityObject, Object poseObject, Object bufferObject, int light) {
        if (!(entityObject instanceof AbstractClientPlayer player) || !PentecostDev.isDev(player.getUUID())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getEntityRenderDispatcher().distanceToSqr(player) > 4096.0) {
            return;
        }
        PoseStack pose = (PoseStack) poseObject;
        MultiBufferSource buffer = (MultiBufferSource) bufferObject;
        Font font = ((EntityRenderer<?>) renderer).getFont();
        boolean visible = !player.isDiscrete();
        Component name = player.getName();
        pose.pushPose();
        pose.translate(0.0F, player.getNameTagOffsetY(), 0.0F);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = pose.last().pose();

        Component tag = Component.literal("[dev]");
        float tagX = -font.width(tag) / 2.0F;
        font.drawInBatch(tag, tagX, -11.0F, 0x20FFAA00, false, matrix, buffer,
                visible ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, light);
        if (visible) {
            font.drawInBatch(tag, tagX, -11.0F, 0xFFFFAA00, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
        }
        drawBoat(pose, buffer, font.width(name) / 2.0F + 5.0F, light);
        pose.popPose();
    }

    private static void drawBoat(PoseStack pose, MultiBufferSource buffer, float x, int light) {
        double t = (System.nanoTime() / 1e9) % 3600.0;
        int frame = (int) (t * 3.0) % 2;
        float bob = (float) Math.sin(t * 2.2) * 0.7F;
        float roll = (float) Math.sin(t * 1.7) * 7.0F;
        String[] rows = BOAT_FRAMES[frame];
        pose.pushPose();
        pose.translate(x + 5.5F, 4.0F + bob, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.textBackground());
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int c = 0; c < row.length(); c++) {
                int color = switch (row.charAt(c)) {
                    case 'M' -> 0xFF8B5A2B;
                    case 'S' -> 0xFFF2F2F2;
                    case 'H' -> 0xFF7A4B1F;
                    case 'W' -> 0xFF3A8CFF;
                    default -> 0;
                };
                if (color != 0) {
                    quad(vc, m, c - 5.5F, r - 4.5F, 1.0F, 1.0F, color, light);
                }
            }
        }
        pose.popPose();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x, float y, float w, float h, int argb, int light) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        vc.vertex(m, x, y + h, 0.01F).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x + w, y + h, 0.01F).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x + w, y, 0.01F).color(r, g, b, a).uv2(light).endVertex();
        vc.vertex(m, x, y, 0.01F).color(r, g, b, a).uv2(light).endVertex();
    }
}
