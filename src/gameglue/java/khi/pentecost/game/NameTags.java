package khi.pentecost.game;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import khi.pentecost.control.Settings;
import khi.pentecost.core.TagHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Player name tags in Pentecost's look: the name in plain white letters with a hairline under it, no box. The line
 * is short at rest and grows to the width of the name when you look at the player or stand close. A dev also gets a
 * small tag above the name, "DEV" between two hairlines, which grows into "PENTECOST DEV" the same way.
 * Settings > Interface > Name tags picks the style and whether the length is short, long or follows your gaze.
 * In the player list and in chat a dev gets "[dev]" in front of the name and a boat after it.
 */
final class NameTags implements TagHooks.Drawer, TagHooks.Marker {
    private static final String PREFIX = "[dev] ";
    private static final String BOAT = " \u26F5";
    private static final String SHORT = "DEV";
    private static final String LONG = "PENTECOST DEV";
    private static final int INK = 0xF2F2F2;
    /** The game's own tag is 0.025. Smaller keeps the tag quiet. */
    private static final float TAG_SCALE = 0.015F;

    /** One boat, as rows of pixels. M mast, S sail, H hull, W water. A second frame flaps the sail and moves the waves. */
    private static final String[][] BOAT_FRAMES = {
            {"     M     ", "     MS    ", "     MSS   ", "     MSSS  ", "     MSSSS ", "     M     ",
                    " HHHHHHHHH ", "  HHHHHHH  ", "WWW WWW WWW"},
            {"     M     ", "     MS    ", "     MSS   ", "     MSS   ", "     MSSS  ", "     M     ",
                    " HHHHHHHHH ", "  HHHHHHH  ", "W WWW WWW W"}};

    /** How far each player's tag is open, 0 short to 1 long, eased so it never jumps. */
    private static final class State {
        float expand;
        long last;
        /** 0 to 1, how far the tag has faded and risen in since the player came into view. */
        float appear;
        long lastSeen;
    }

    private final Map<UUID, State> states = new HashMap<>();

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
                .append(Component.literal(PREFIX).withStyle(ChatFormatting.WHITE))
                .append(name)
                .append(Component.literal(BOAT).withStyle(ChatFormatting.GRAY));
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
    public boolean replaces(Object entity) {
        return entity instanceof AbstractClientPlayer && !"vanilla".equals(Settings.get(Settings.NAME_TAGS));
    }

    @Override
    public void nameTag(Object renderer, Object entityObject, Object poseObject, Object bufferObject, int light) {
        if (!(entityObject instanceof AbstractClientPlayer player)) {
            return;
        }
        boolean mine = replaces(player);
        boolean dev = PentecostDev.isDev(player.getUUID());
        if (!mine && !dev) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        double dist2 = mc.getEntityRenderDispatcher().distanceToSqr(player);
        if (dist2 > 4096.0) {
            return;
        }
        float dist = (float) Math.sqrt(dist2);
        float expand = openness(mc, player, dist);
        float appear = appear(player);
        // Full strength up close, fading to a third far away. A tag that has just come into view fades in.
        float alpha = Math.max(0.35F, Math.min(1.0F, 1.0F - (dist - 20.0F) / 40.0F)) * appear;

        PoseStack pose = (PoseStack) poseObject;
        MultiBufferSource buffer = (MultiBufferSource) bufferObject;
        Font font = ((EntityRenderer<?>) renderer).getFont();
        boolean visible = !player.isDiscrete();
        Component name = (Component) untag(player.getDisplayName());
        float nameWidth = font.width(name);
        double t = (System.nanoTime() / 1e9) % 3600.0;

        pose.pushPose();
        pose.translate(0.0F, player.getNameTagOffsetY() - 0.12F * (1.0F - appear), 0.0F); // rises into place
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(-TAG_SCALE, -TAG_SCALE, TAG_SCALE);
        Matrix4f m = pose.last().pose();
        VertexConsumer lines = buffer.getBuffer(RenderType.textBackground());

        if (mine) {
            float x = -nameWidth / 2.0F;
            font.drawInBatch(name, x, 0.0F, ink(alpha * 0.25F), false, m, buffer, Font.DisplayMode.SEE_THROUGH, 0, light);
            if (visible) {
                font.drawInBatch(name, x, 0.0F, ink(alpha), false, m, buffer, Font.DisplayMode.NORMAL, 0, light);
            }
            // The hairline: short at rest, as wide as the name when open, with a brighter spot that drifts along it.
            float half = lerp(Math.max(5.0F, nameWidth * 0.2F), nameWidth / 2.0F + 3.0F, expand);
            quad(lines, m, -half, 9.5F, half * 2.0F, 1.0F, ink(alpha * 0.5F), light);
            float spot = 8.0F;
            float travel = Math.max(0.0F, half * 2.0F - spot);
            float at = -half + travel * (0.5F + 0.5F * (float) Math.sin(t * 1.3));
            quad(lines, m, at, 9.5F, spot, 1.0F, ink(alpha), light);
        }

        if (dev) {
            // The tag grows letter by letter: DEV stays, and the rest of PENTECOST DEV is written in from the left, the
            // newest letter still fading in. Shrinking takes the letters away in the same order.
            int total = LONG.length(), keep = SHORT.length();
            float grown = expand * (total - keep);
            int n = keep + (int) Math.ceil(grown - 1.0e-3F);
            String shown = LONG.substring(total - n);
            float frac = grown - (float) Math.floor(grown);
            float newest = n > keep && frac > 1.0e-3F ? frac : 1.0F;
            float w = font.width(shown);
            float x0 = -w / 2.0F;
            float breathe = 0.85F + 0.15F * (float) Math.sin(t * 1.1);
            float lineY = -7.5F;
            float run = lerp(5.0F, 16.0F, expand);
            float edge = w / 2.0F + 3.0F;
            quad(lines, m, -edge - run, lineY, run, 1.0F, ink(alpha * 0.8F * breathe), light);
            quad(lines, m, edge, lineY, run, 1.0F, ink(alpha * 0.8F * breathe), light);
            String head = shown.substring(0, 1);
            font.drawInBatch(head, x0, -11.0F, ink(alpha * newest), false, m, buffer, Font.DisplayMode.NORMAL, 0, light);
            if (shown.length() > 1) {
                font.drawInBatch(shown.substring(1), x0 + font.width(head), -11.0F, ink(alpha), false, m, buffer,
                        Font.DisplayMode.NORMAL, 0, light);
            }
            drawBoat(pose, buffer, nameWidth / 2.0F + 5.0F, light);
        }
        pose.popPose();
    }

    /** 0 to 1, how far this player's tag is open. Eases toward the target, so a change of gaze or setting never snaps. */
    private float openness(Minecraft mc, AbstractClientPlayer player, float dist) {
        String mode = Settings.get(Settings.TAG_LENGTH);
        float target;
        if ("long".equals(mode)) {
            target = 1.0F;
        } else if ("short".equals(mode)) {
            target = 0.0F;
        } else {
            Camera cam = mc.gameRenderer.getMainCamera();
            Vec3 to = player.getBoundingBox().getCenter().subtract(cam.getPosition());
            Vector3f look = cam.getLookVector();
            double len = Math.max(1e-3, to.length());
            double dot = (look.x() * to.x + look.y() * to.y + look.z() * to.z) / len;
            target = dist < 7.0F || dot > 0.99 ? 1.0F : 0.0F;
        }
        if (states.size() > 256) {
            states.clear();
        }
        State s = states.computeIfAbsent(player.getUUID(), k -> new State());
        long now = System.nanoTime();
        float dt = s.last == 0 ? 0.0F : Math.min(0.1F, (now - s.last) / 1e9F);
        s.last = now;
        s.expand += (target - s.expand) * (1.0F - (float) Math.exp(-dt / 0.18F));
        return s.expand;
    }

    /** 0 to 1, how far a player's tag has faded in. It starts over when the player has been out of view for a moment. */
    private float appear(AbstractClientPlayer player) {
        State s = states.computeIfAbsent(player.getUUID(), k -> new State());
        long now = System.nanoTime();
        boolean fresh = s.lastSeen == 0 || now - s.lastSeen > 1_000_000_000L;
        float dt = fresh ? 0.016F : Math.min(0.1F, (now - s.lastSeen) / 1e9F);
        s.lastSeen = now;
        if (fresh) {
            s.appear = 0.0F;
        }
        s.appear += (1.0F - s.appear) * (1.0F - (float) Math.exp(-dt / 0.22F));
        return s.appear > 0.995F ? 1.0F : s.appear;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int ink(float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        return (a << 24) | INK;
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
                    case 'M' -> 0xFF9A9A9A;
                    case 'S' -> 0xFFF2F2F2;
                    case 'H' -> 0xFFF2F2F2;
                    case 'W' -> 0xFF636363;
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
