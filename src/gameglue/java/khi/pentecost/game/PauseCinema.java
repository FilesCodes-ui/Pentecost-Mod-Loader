package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.core.ZoomHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Random;

/**
 * The pause camera. While Pentecost's pause menu is open the camera leaves the player's eyes, pulls out and films the
 * player: slow orbits, low hero shots, cranes, push-ins, wide shots, a look over the shoulder, a bird's eye spiral.
 * It is a small director: every few seconds it picks the next shot, never the same twice in a row, and the camera
 * glides there instead of cutting. A ray from the player to the camera keeps it out of walls and hills.
 *
 * The game stays as it is: a separate invisible entity carries the camera, the player is not moved, and everything
 * is put back when the menu closes. The player can turn this off in Settings > Interface.
 */
public final class PauseCinema {
    private enum Kind { ORBIT, LOW, CRANE, PUSH, WIDE, SHOULDER, SPIRAL }

    private record Saved(CameraType camera, boolean hideGui, float eyeHeight, float eyeHeightOld) {}

    private static final double TAU_ANGLE = 0.9;
    private static final double TAU_DISTANCE = 0.9;
    private static final double TAU_AIM = 0.7;
    private static final double TAU_FOV = 1.0;

    private static boolean active;
    private static boolean failed;
    private static Saved saved;
    private static Marker marker;
    private static Field eyeHeight, eyeHeightOld;
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static final Random RND = new Random();

    // the shot being played
    private static Kind kind;
    private static Kind lastKind;
    private static double shotTime, shotLength, shotAngle;
    private static int dir = 1;

    // where the camera is now, relative to the player's feet
    private static double az, rad, height, fov, clipScale = 1;
    private static double aimX, aimY, aimZ;
    private static long lastNanos;

    // The camera is tied to the player's own drawn position, with no filter between them. A filter that follows the
    // player is sampled at slightly different moments than the game draws the player, and that mismatch times the
    // player's speed shows as shake, worst close up and from the side. Only a teleport is eased out (corr).
    private static final double TAU_JUMP = 0.35;
    private static final double TRAIL = 0.12;
    private static double corrX, corrY, corrZ, lastRealX, lastRealY, lastRealZ;
    private static boolean anchorSet;
    // obstacle avoidance: the camera swings round or up to a clear spot, eased in and out
    private static double avoidAz, avoidUp, avoidAzT, avoidUpT, nextSearch;
    // Once the camera has swung round or up to dodge something it stays there. It is held at the same place in the
    // world while it orbits, and only goes back after the way has stayed clear for a while, so it never ping-pongs.
    private static boolean avoidLocked;
    private static double straightClear;
    // the pull-in the rays ask for, held steady: a ray that flickers between hit and miss while the player moves must not
    // push the camera in and out
    private static double clipWanted = 1, clearFor;
    // where the player is heading: a smoothed velocity, and the way it carries the player in LOOKAHEAD seconds. The
    // obstacle rays are cast from there as well as from where the player is, so the camera starts avoiding a wall
    // before the player reaches it instead of reacting once it is already behind it
    private static final double LOOKAHEAD = 0.8;
    private static final double MAX_LEAD = 10.0;
    private static double velX, velY, velZ, leadX, leadY, leadZ;
    // While the game is paused, and for a moment before it is, nothing the player was doing may carry into the camera.
    private static double settle;
    private static boolean wasPaused;

    private PauseCinema() {}

    public static boolean active() {
        return active;
    }

    public static boolean enabled() {
        return Settings.on(Settings.PAUSE_CAMERA);
    }

    /**
     * Called whenever the game is about to show another screen (or none). The camera plays on behind the menus of the
     * pause flow, and stops here the moment the game is back or the world goes away.
     */
    public static void screenChanging(Object shown) {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (shown == null || mc.level == null || mc.level != level) {
            end(mc);
        }
    }
    /** Flips the setting and starts or stops the camera at once. For the pause menu's own switch. */
    public static void toggle(Minecraft mc) {
        Settings.toggle(Settings.PAUSE_CAMERA);
        if (enabled()) {
            begin(mc);
        } else {
            end(mc);
        }
    }

    // ------------------------------------------------------------------ start and stop

    /** Called when the pause menu opens. Does nothing when the camera is off or there is no world. */
    public static void begin(Minecraft mc) {
        if (active || failed || !enabled() || mc.player == null || mc.level == null) {
            return;
        }
        try {
            Camera cam = mc.gameRenderer.getMainCamera();
            if (eyeHeight == null) {
                eyeHeight = Camera.class.getDeclaredField("eyeHeight");
                eyeHeight.setAccessible(true);
                eyeHeightOld = Camera.class.getDeclaredField("eyeHeightOld");
                eyeHeightOld.setAccessible(true);
            }
            LocalPlayer p = mc.player;
            float pt = mc.getFrameTime();
            Vec3 feet = p.getPosition(pt);
            double eye = p.getEyeHeight();
            Vec3 look = p.getViewVector(pt);

            saved = new Saved(mc.options.getCameraType(), mc.options.hideGui, eyeHeight.getFloat(cam), eyeHeightOld.getFloat(cam));
            level = mc.level;
            marker = new Marker(EntityType.MARKER, mc.level);

            if (saved.camera() == CameraType.FIRST_PERSON) {
                // Start exactly where the player's eyes are, looking where they look, and glide out from there.
                az = Math.atan2(-look.z, -look.x);
                rad = 0.05;
                height = eye;
                aimX = look.x * 30;
                aimY = eye + look.y * 30;
                aimZ = look.z * 30;
            } else {
                // Third person: start where the game's own camera is and where it looks, so the picture carries on
                // from there and glides into the first shot instead of pulling out from the player's eyes.
                Vec3 c = cam.getPosition();
                double yaw = Math.toRadians(cam.getYRot());
                double pit = Math.toRadians(cam.getXRot());
                double lx = -Math.sin(yaw) * Math.cos(pit);
                double ly = -Math.sin(pit);
                double lz = Math.cos(yaw) * Math.cos(pit);
                double dx = c.x - feet.x;
                double dz = c.z - feet.z;
                rad = Math.max(0.05, Math.hypot(dx, dz));
                az = rad > 0.06 ? Math.atan2(dz, dx) : Math.atan2(-look.z, -look.x);
                height = c.y - feet.y;
                aimX = dx + lx * 30;
                aimY = height + ly * 30;
                aimZ = dz + lz * 30;
            }
            fov = mc.options.fov().get();
            clipScale = 1;
            clipWanted = 1;
            clearFor = 0;
            lastKind = null;
            lastNanos = 0;
            CamTrace.reset("pause");
            anchorSet = false;
            corrX = corrY = corrZ = 0;
            velX = velY = velZ = leadX = leadY = leadZ = 0;
            settle = 0.3;
            wasPaused = false;
            avoidAz = avoidUp = avoidAzT = avoidUpT = 0;
            avoidLocked = false;
            straightClear = 0;
            nextSearch = 0;
            startShot(Kind.ORBIT, 14);
            shotAngle = az;

            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = true;
            mc.gameRenderer.setRenderHand(false);
            eyeHeight.setFloat(cam, 0F);
            eyeHeightOld.setFloat(cam, 0F);
            mc.setCameraEntity(marker);
            active = true;
            apply(mc, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            Log.warn("Pause camera is off for this session: " + e);
            end(mc);
        }
    }

    /** Called when the pause menu closes or goes to another screen. Puts everything back. */
    public static void end(Minecraft mc) {
        ZoomHooks.fovOverride = 0;
        Saved s = saved;
        if (!active && s == null) {
            return;
        }
        active = false;
        saved = null;
        try {
            if (mc.player != null && mc.getCameraEntity() == marker) {
                mc.setCameraEntity(mc.player);
            }
            if (s != null) {
                mc.options.setCameraType(s.camera());
                mc.options.hideGui = s.hideGui();
                mc.gameRenderer.setRenderHand(true);
                Camera cam = mc.gameRenderer.getMainCamera();
                if (eyeHeight != null) {
                    eyeHeight.setFloat(cam, s.eyeHeight());
                    eyeHeightOld.setFloat(cam, s.eyeHeightOld());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Pause camera could not put everything back: " + e);
        }
        marker = null;
        level = null;
    }

    // ------------------------------------------------------------------ every frame

    /** Called at the start of every frame, before the game sets up its camera, so the pose is the one for this very frame. */
    public static void frame() {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // The camera keeps playing behind the menus opened from the pause menu. It stops when the game is back.
        if (mc.player == null || mc.level == null || mc.level != level || mc.screen == null || !enabled()
                || mc.player.isDeadOrDying()) {
            end(mc);
            return;
        }
        try {
            long now = System.nanoTime();
            double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (now - lastNanos) / 1e9);
            lastNanos = now;
            apply(mc, dt);
        } catch (RuntimeException e) {
            failed = true;
            Log.warn("Pause camera stopped: " + e);
            end(mc);
        }
    }

    private static void apply(Minecraft mc, double dt) {
        LocalPlayer p = mc.player;
        Vec3 real = p.getPosition(mc.getFrameTime());
        if (!anchorSet) {
            anchorSet = true;
        } else {
            // A jump bigger than any player flies in one frame is a teleport: the camera stays and the gap eases out.
            double jx = real.x - lastRealX, jy = real.y - lastRealY, jz = real.z - lastRealZ;
            double limit = 2.5 + 80 * dt;
            boolean freezing = mc.isPaused() && !wasPaused;
            if (freezing || jx * jx + jy * jy + jz * jz > limit * limit) {
                corrX -= jx;
                corrY -= jy;
                corrZ -= jz;
            }
            double keep = 1 - blend(dt, TAU_JUMP);
            corrX *= keep;
            corrY *= keep;
            corrZ *= keep;
        }
        lastRealX = real.x;
        lastRealY = real.y;
        lastRealZ = real.z;
        wasPaused = mc.isPaused();
        Vec3 feet = new Vec3(real.x + corrX, real.y + corrY, real.z + corrZ);
        double eye = p.getEyeHeight();
        trackHeading(mc, p, dt);

        shotTime += dt;
        if (shotTime >= shotLength) {
            nextShot(p);
        }
        double s = Math.min(1, shotTime / shotLength);
        double e = s * s * (3 - 2 * s);

        double tAz = az, tRad = rad, tHeight = height, tAimY = 1.1, tFov = 62, lat = 0;
        double fx = -Math.sin(Math.toRadians(p.getYRot()));
        double fz = Math.cos(Math.toRadians(p.getYRot()));
        double behind = Math.atan2(-fz, -fx);
        boolean overShoulder = false;
        switch (kind) {
            case ORBIT -> {
                tAz = shotAngle + dir * 0.16 * shotTime;
                tRad = 5.2 + 0.6 * Math.sin(shotTime * 0.35);
                tHeight = 1.9 + 0.8 * e;
                tAimY = 1.1;
                tFov = 62;
                lat = dir * 0.12;
            }
            case LOW -> {
                tAz = shotAngle + dir * 0.08 * shotTime;
                tRad = 3.6;
                tHeight = 0.25;
                tAimY = 1.7;
                tFov = 72;
                lat = dir * 0.1;
            }
            case CRANE -> {
                tAz = shotAngle + dir * 0.12 * shotTime;
                tRad = Mth.lerp(e, 4.0, 9.0);
                tHeight = Mth.lerp(e, 1.5, 13.0);
                tAimY = 1.0;
                tFov = 58;
                lat = dir * 0.1;
            }
            case PUSH -> {
                tAz = shotAngle;
                tRad = Mth.lerp(e, 9.0, 3.8);
                tHeight = Mth.lerp(e, 1.7, 1.9);
                tAimY = 1.45;
                tFov = Mth.lerp(e, 55, 46);
                lat = dir * 0.1;
            }
            case WIDE -> {
                tAz = shotAngle + dir * 0.05 * shotTime;
                tRad = Mth.lerp(e, 16.0, 22.0);
                tHeight = Mth.lerp(e, 6.0, 9.0);
                tAimY = 2.0;
                tFov = 74;
                lat = dir * 0.08;
            }
            case SHOULDER -> {
                overShoulder = true;
                tAz = behind + dir * 0.3;
                tRad = 2.4;
                tHeight = eye + 0.35;
                tFov = 68;
            }
            case SPIRAL -> {
                tAz = shotAngle + dir * 0.25 * shotTime;
                tRad = 7.0;
                tHeight = Mth.lerp(e, 18.0, 24.0);
                tAimY = 0.0;
                tFov = 60;
            }
        }

        // where the camera looks, relative to the player's feet
        double tAimX, tAimZ;
        if (overShoulder) {
            tAimX = fx * 25;
            tAimZ = fz * 25;
            tAimY = eye - 0.1;
        } else {
            double side = lat * tRad; // keeps the player off centre, on a third
            tAimX = -Math.sin(tAz) * side;
            tAimZ = Math.cos(tAz) * side;
        }

        double azPrev = az;
        az += angleDiff(tAz, az) * blend(dt, TAU_ANGLE);
        if (avoidLocked) {
            double dAz = az - azPrev; // the orbit moves on, the dodge stays where it is in the world
            avoidAz -= dAz;
            avoidAzT -= dAz;
        }
        rad += (tRad - rad) * blend(dt, TAU_DISTANCE);
        height += (tHeight - height) * blend(dt, TAU_DISTANCE);
        aimX += (tAimX - aimX) * blend(dt, TAU_AIM);
        aimY += (tAimY - aimY) * blend(dt, TAU_AIM);
        aimZ += (tAimZ - aimZ) * blend(dt, TAU_AIM);
        fov += (tFov - fov) * blend(dt, TAU_FOV);

        avoid(mc, feet, eye, dt);
        double ba = blend(dt, 0.9);
        avoidAz += (avoidAzT - avoidAz) * ba;
        avoidUp += (avoidUpT - avoidUp) * ba;
        // The camera trails a little behind a moving player, by a smooth amount.
        Vec3 want = new Vec3(feet.x + Math.cos(az + avoidAz) * rad - velX * TRAIL, feet.y + height + avoidUp,
                feet.z + Math.sin(az + avoidAz) * rad - velZ * TRAIL);
        Vec3 origin = new Vec3(feet.x, feet.y + Math.min(eye, 1.4), feet.z);
        Vec3 cam = clip(mc, origin, want, dt);

        Vec3 aim = new Vec3(feet.x + aimX, feet.y + aimY, feet.z + aimZ);
        double dx = aim.x - cam.x, dy = aim.y - cam.y, dz = aim.z - cam.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))), -90, 90);

        marker.setPos(cam.x, cam.y, cam.z);
        marker.xo = marker.xOld = cam.x;
        marker.yo = marker.yOld = cam.y;
        marker.zo = marker.zOld = cam.z;
        marker.setYRot(yaw);
        marker.setXRot(pitch);
        marker.yRotO = yaw;
        marker.xRotO = pitch;
        ZoomHooks.fovOverride = fov;
        CamTrace.sample("pause", dt, cam.x, cam.y, cam.z, yaw, pitch, fov, String.format(
                "kind=%s t=%.1f clip=%.2f avoid=%.2f/%.1f rad=%.1f h=%.1f", kind, shotTime, clipScale, avoidAz, avoidUp, rad, height));
    }

    /** Smooths the player's velocity and works out how far it carries the player in the look-ahead time. */
    private static void trackHeading(Minecraft mc, LocalPlayer p, double dt) {
        // From the game's own ticks, which are exact, not from frame to frame, which is noisy. Zero while the game is paused.
        settle -= dt;
        if (mc.isPaused() || settle > 0) {
            velX = velY = velZ = leadX = leadY = leadZ = 0;
            return;
        }
        double tx = 0, ty = 0, tz = 0;
        if (!mc.isPaused()) {
            tx = (p.getX() - p.xo) * 20.0;
            ty = (p.getY() - p.yo) * 20.0;
            tz = (p.getZ() - p.zo) * 20.0;
        }
        double k = blend(dt, 0.25);
        velX += (tx - velX) * k;
        velY += (ty - velY) * k;
        velZ += (tz - velZ) * k;
        // Heights are left out unless clearly moving: a jump or a step must not swing the camera.
        double vy = Math.abs(velY) > 3.0 ? velY * 0.5 : 0;
        double lx = velX * LOOKAHEAD, ly = vy * LOOKAHEAD, lz = velZ * LOOKAHEAD;
        double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 0.5) {
            lx = ly = lz = 0; // standing still or barely moving
        } else if (len > MAX_LEAD) {
            double c = MAX_LEAD / len;
            lx *= c;
            ly *= c;
            lz *= c;
        }
        // eased, so the look-ahead itself never jumps when the player starts or stops
        double e = blend(dt, 0.3);
        leadX += (lx - leadX) * e;
        leadY += (ly - leadY) * e;
        leadZ += (lz - leadZ) * e;
    }

    /**
     * Clearance now and where the player is heading, whichever is worse. The camera sits relative to the player, so the
     * predicted camera spot and the predicted player spot both move by the same lead.
     */
    private static double clearAhead(Minecraft mc, Vec3 origin, Vec3 want) {
        double now = clearFraction(mc, origin, want);
        if (leadX * leadX + leadY * leadY + leadZ * leadZ < 0.25) {
            return now;
        }
        Vec3 lead = new Vec3(leadX, leadY, leadZ);
        double ahead = clearFraction(mc, origin.add(lead), want.add(lead));
        double half = clearFraction(mc, origin.add(lead.scale(0.5)), want.add(lead.scale(0.5)));
        return Math.min(now, Math.min(half, ahead));
    }

    /**
     * How much of the way from the player to {@code want} is clear, 0 to 1. Nine rays, a centre one and a ring round it,
     * so the camera has a body and does not slip past corners or poke into walls.
     */
    private static double clearFraction(Minecraft mc, Vec3 origin, Vec3 want) {
        Vec3 d = want.subtract(origin);
        double len = d.length();
        if (len < 0.2) {
            return 1;
        }
        Vec3 dir = d.scale(1 / len);
        Vec3 right = dir.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(dir).normalize();
        double best = 1;
        for (int i = -1; i < 8; i++) {
            Vec3 off = Vec3.ZERO;
            if (i >= 0) {
                double a = i * Math.PI / 4;
                off = right.scale(Math.cos(a) * 0.25).add(up.scale(Math.sin(a) * 0.25));
            }
            Vec3 from = origin.add(off.scale(0.35));
            Vec3 to = want.add(off);
            var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                best = Math.min(best, hit.getLocation().distanceTo(from) / Math.max(0.01, to.distanceTo(from)));
            }
        }
        return best;
    }

    /**
     * Looks for a clear spot a few times a second. When the wanted position is blocked it tries swinging round the
     * player and lifting up, and picks the nearest clear one. It only sets targets: the camera eases to them.
     */
    private static void avoid(Minecraft mc, Vec3 feet, double eye, double dt) {
        nextSearch -= dt;
        if (nextSearch > 0) {
            return;
        }
        nextSearch = 0.25;
        Vec3 origin = new Vec3(feet.x, feet.y + Math.min(eye, 1.4), feet.z);
        double straight = clearAhead(mc, origin,
                new Vec3(feet.x + Math.cos(az) * rad, feet.y + height, feet.z + Math.sin(az) * rad));
        if (avoidLocked) {
            straightClear = straight >= 0.92 ? straightClear + 0.25 : 0;
            if (straightClear >= 1.5) {
                // the way has been clear for a while: go back to the shot's own spot, slowly
                avoidLocked = false;
                avoidAzT = 0;
                avoidUpT = 0;
                straightClear = 0;
                return;
            }
            double here = clearAhead(mc, origin, new Vec3(feet.x + Math.cos(az + avoidAzT) * rad,
                    feet.y + height + avoidUpT, feet.z + Math.sin(az + avoidAzT) * rad));
            if (here >= 0.7) {
                return; // the dodge spot is fine: stay, do not hop
            }
        } else if (straight >= 0.92) {
            return;
        }
        double[] azOff = {0, 0.45, -0.45, 0.9, -0.9, 1.5, -1.5, 2.3};
        double[] upOff = {0, 2.5};
        double bestScore = straight, bestAz = 0, bestUp = 0;
        for (double u : upOff) {
            for (double a : azOff) {
                if (a == 0 && u == 0) {
                    continue;
                }
                double aa = az + a;
                double f = clearAhead(mc, origin,
                        new Vec3(feet.x + Math.cos(aa) * rad, feet.y + height + u, feet.z + Math.sin(aa) * rad));
                double score = f - 0.06 * Math.abs(a) - 0.05 * u + (a == avoidAzT && u == avoidUpT ? 0.1 : 0);
                if (score > bestScore + 0.12) {
                    bestScore = score;
                    bestAz = a;
                    bestUp = u;
                }
            }
        }
        avoidAzT = bestAz;
        avoidUpT = bestUp;
        avoidLocked = bestAz != 0 || bestUp != 0;
        straightClear = 0;
    }

    /** Pulls the camera in when something solid is still between it and the player. In fast, out slowly, so it never hops. */
    private static Vec3 clip(Minecraft mc, Vec3 origin, Vec3 want, double dt) {
        Vec3 d = want.subtract(origin);
        double full = d.length();
        if (full < 0.2) {
            clipScale = 1;
            return want;
        }
        double frac = clearAhead(mc, origin, want);
        double raw = Math.min(1, Math.max(0.5 / full, frac - 0.3 / full));
        // The wanted pull-in drops at once but only rises after the way has stayed clear for a while, then eases up.
        // While the player walks or sprints the rays flicker between hit and miss. Followed directly, that is a jitter.
        if (raw < clipWanted) {
            clipWanted += (raw - clipWanted) * blend(dt, 0.12);
            clearFor = 0;
        } else {
            clearFor += dt;
            if (clearFor > 0.7) {
                clipWanted += (raw - clipWanted) * blend(dt, 1.0);
            }
        }
        // In at a gentle pace, out slower: a camera that teleports in is worse than one that briefly clips a wall.
        double tau = clipWanted < clipScale ? 0.45 : 1.4;
        double step = (clipWanted - clipScale) * blend(dt, tau);
        clipScale += Math.max(-0.6 * dt, Math.min(0.5 * dt, step));
        return origin.add(d.scale(Math.min(1, clipScale)));
    }

    // ------------------------------------------------------------------ the director

    private static void nextShot(LocalPlayer p) {
        Kind next;
        if (Settings.get(Settings.PAUSE_CAMERA_STYLE).equals("orbit")) {
            next = Kind.ORBIT;
        } else {
            Kind[] all = Kind.values();
            double[] w = {3, 2, 2, 2, 1.5, 2, 1.2};
            double total = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] != kind) {
                    total += w[i];
                }
            }
            double roll = RND.nextDouble() * total;
            next = Kind.ORBIT;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == kind) {
                    continue;
                }
                roll -= w[i];
                if (roll <= 0) {
                    next = all[i];
                    break;
                }
            }
        }
        startShot(next, 9 + RND.nextDouble() * 5);
        double fx = -Math.sin(Math.toRadians(p.getYRot()));
        double fz = Math.cos(Math.toRadians(p.getYRot()));
        double front = Math.atan2(fz, fx);
        shotAngle = switch (next) {
            case PUSH -> front + dir * 0.5;       // facing the player
            case LOW -> front + dir * 0.6;
            case ORBIT, CRANE, WIDE, SPIRAL -> az; // carry on from where the camera is
            case SHOULDER -> az;
        };
    }

    private static void startShot(Kind next, double seconds) {
        lastKind = kind;
        kind = next;
        shotTime = 0;
        shotLength = seconds;
        dir = RND.nextBoolean() ? 1 : -1;
        shotAngle = az;
    }

    // ------------------------------------------------------------------ small maths

    /** How far to move toward the target this frame. Zero on the very first frame, so the camera starts at the player's eyes. */
    private static double blend(double dt, double tau) {
        return dt <= 0 ? 0 : 1 - Math.exp(-dt / tau);
    }

    private static double angleDiff(double to, double from) {
        double d = (to - from) % (Math.PI * 2);
        if (d > Math.PI) {
            d -= Math.PI * 2;
        } else if (d < -Math.PI) {
            d += Math.PI * 2;
        }
        return d;
    }
}
