package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.cape.CapeArt;
import khi.pentecost.control.Settings;
import khi.pentecost.core.CapeHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PentecostCape implements CapeHooks.Impl {
    static final String SETTING = "cape.pentecost";
    private static final long FRAME_NS = 50_000_000L;

    private static final class Wearer {
        final ResourceLocation loc;
        final DynamicTexture tex;
        final CapeArt art = new CapeArt();
        final CapeArt.State state = new CapeArt.State();
        final CapeArt.Motion motion = new CapeArt.Motion();
        final int[] pixels = new int[CapeArt.TEX_W * CapeArt.TEX_H];
        final long born = System.nanoTime();
        long drawn;
        double strafe, speed;

        Wearer(UUID id) {
            loc = new ResourceLocation("pentecost", "cape/" + id.toString().replace("-", ""));
            tex = new DynamicTexture(new NativeImage(CapeArt.TEX_W, CapeArt.TEX_H, true));
            Minecraft.getInstance().getTextureManager().register(loc, tex);
        }
    }

    private final Map<UUID, Wearer> wearers = new HashMap<>();

    private PentecostCape() {}

    static void install() {
        CapeHooks.impl = new PentecostCape();
    }

    private static volatile java.util.Set<String> listed = java.util.Set.of();
    private static long listedAt;

    private static boolean wears(AbstractClientPlayer p) {
        long now = System.currentTimeMillis();
        if (now - listedAt > 10_000) {
            listedAt = now;
            try {
                listed = khi.pentecost.auth.DevFeed.capes();
            } catch (RuntimeException e) {
                listed = java.util.Set.of();
            }
        }
        UUID id = p.getUUID();
        if (!PentecostDev.isOwnerId(id)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return id.equals(mc.getUser().getProfileId()) || listed.contains(id.toString().replace("-", ""))
                || PentecostPlayers.provenDev(id);
    }

    @Override
    public Object cape(Object playerObject) {
        if (!(playerObject instanceof AbstractClientPlayer p) || !Settings.on(SETTING) || !wears(p)) {
            return null;
        }
        if (!Minecraft.getInstance().isSameThread()) {
            Wearer w = wearers.get(p.getUUID());
            return w == null ? null : w.loc;
        }
        Wearer w = wearers.computeIfAbsent(p.getUUID(), Wearer::new);
        long now = System.nanoTime();
        if (now - w.drawn >= FRAME_NS) {
            w.drawn = now;
            draw(w, p, now);
        }
        return w.loc;
    }

    private static void draw(Wearer w, AbstractClientPlayer p, long now) {
        double dx = p.getX() - p.xo, dy = p.getY() - p.yo, dz = p.getZ() - p.zo;
        double yaw = Math.toRadians(p.yBodyRot);
        double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        double fwdX = -Math.sin(yaw), fwdZ = Math.cos(yaw);
        double side = (dx * rightX + dz * rightZ) / 0.2;
        double fwd = (dx * fwdX + dz * fwdZ) / 0.28;
        w.strafe += (Math.max(-1, Math.min(1, side)) - w.strafe) * 0.35;
        w.speed += (Math.max(0, Math.min(1, fwd)) - w.speed) * 0.35;
        CapeArt.Motion m = w.motion;
        m.t = (now - w.born) / 1e9;
        m.strafe = w.strafe;
        m.speed = w.speed;
        m.vy = dy;
        m.onGround = p.onGround() || p.isPassenger();
        w.art.texture(w.pixels, w.state, m);
        NativeImage img = w.tex.getPixels();
        if (img == null) {
            return;
        }
        int[] src = w.pixels;
        for (int y = 0; y < CapeArt.TEX_H; y++) {
            int row = y * CapeArt.TEX_W;
            for (int x = 0; x < CapeArt.TEX_W; x++) {
                int c = src[row + x];
                int abgr = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF);
                img.setPixelRGBA(x, y, abgr);
            }
        }
        w.tex.upload();
    }
}
