package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.core.OrbitHooks;
import khi.pentecost.core.ZoomHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

public final class OrbitCamera implements OrbitHooks.Impl {
    static final String SETTING = "camera.orbit";

    private static final double[] DIST = {0, 9.0, 5.0, 1.6, 4.0};
    private static final double[] UP = {0, 1.5, 0.8, 0.3, 0.6};
    private static final double[] SIDE = {0, 0, 0, 0.75, 0};
    static final int CINEMATIC = 4;
    private static double TWEEN = 0.45;
    private static final double PULL_IN = 0.03;
    private static double EASE_OUT = 0.4;
    private static double HOLD_SECONDS = 0.5;
    private static float NECK = 70f;
    private static double AIM_FOLLOW = 0.05;
    private static double WALK_TURN = 0.12;
    private static float MAX_TURN = 720f;
    private static double GHOST_START = 1.0;
    private static double GHOST_GONE = 0.35;

    private static int mode;
    private static long refreshed;
    private static boolean[] useMode = {true, true, true, true};
    private static double sensitivity = 1;
    private static boolean overHeadOn = true, ghostOn = true, crosshairOn = true, crosshairWide;
    private static boolean caveOn = true, ceilingOn = true, speedOn = true, lookOn = true, combatOn = true, comfort;
    private static double smoothing = 0.03, bumpSmoothing = 0.07;
    private static float wantYaw, wantPitch;
    private static double smoothY;
    private static double speedExtra, combatExtra, caveScale = 1, lookX, lookZ, speedNow;
    private static int combatCheck;
    private static boolean enemyNear;
    private static double rideExtra, flyLift, aimBlend, shoulderFlip = 1;
    private static boolean seeThroughOn = true, shoulderKeyOn = true, autoShoulderOn = true, bowZoomOn = true;
    private static net.minecraft.client.KeyMapping lockKey, shoulderKey, freeKey;
    private static boolean keysTried;
    private static net.minecraft.world.entity.LivingEntity lockTarget;
    private static double lockDrift;
    private static boolean lockOn = true;

    private static boolean free;
    private static net.minecraft.client.KeyMapping cineKey;
    private static int beforeCine;
    private static double cineSmoothing = 0.12;
    private static float moveYaw;
    private static boolean moveWanted;
    private static Vec3 freePos;
    private static double freeVX, freeVY, freeVZ;
    private static float freeYaw, freePitch, freeWantYaw, freeWantPitch;
    private static double freeFov = 70;
    private static CameraType freeSavedType;
    private static boolean freeSavedHideGui;
    private static net.minecraft.client.player.Input freeSavedInput;
    private static long freeLast;
    private static boolean freeReturn;
    private static double freeRetT;
    private static Vec3 retFromPos, homePos;
    private static float retFromYaw, retFromPitch, homeYaw, homePitch;
    private static double retFromFov;
    private static final double FREE_RETURN = 0.6;
    private static boolean exiting;
    private static CameraType lastSet;
    private static float camYaw, camPitch;
    private static float anchorYaw;
    private static int headSide;
    private static double overHead;
    private static double walkedFor, stoodFor;
    private static double WALK_DELAY = 0.5;
    private static double STAND_DELAY = 0.8;
    private static double OVER_HEAD_LIFT = 0.55;
    private static final double OVER_HEAD_SHIFT = 0.45;
    private static double fromDist, fromUp, fromSide, toDist, toUp, toSide, tween = 1;
    private static double dist, up, side, clip = 1;
    private static double lastX, lastY, lastZ;
    private static boolean placed;
    private static long lastNanos;
    private static long heldSince;
    private static boolean holdUsed;
    private static Vec3 camPos;
    private static Vec3 camLook;
    private static float ghost = 1f;
    private static Method place;
    private static Method fovMethod;
    private static boolean fovTried;
    private static Method portalRendering;
    private static boolean portalChecked;

    private OrbitCamera() {}

    static void install() {
        if (present("com.github.exopandora.shouldersurfing.ShoulderSurfing")
                || present("io.socol.betterthirdperson.BetterThirdPerson")) {
            Log.info("F5 camera: another third-person camera mod is installed, so Pentecost leaves F5 alone");
            return;
        }
        OrbitHooks.impl = new OrbitCamera();
        ZoomHooks.Press oldPress = ZoomHooks.press;
        ZoomHooks.press = (button, action) -> {
            if (action == 1 && mode != 0 && !exiting && (outsideCone() || mode == CINEMATIC)) {
                camYaw = wantYaw;
                camPitch = wantPitch;
                anchorYaw = camYaw;
                aimAtCamera(1);
            }
            return oldPress != null && oldPress.press(button, action);
        };
        ZoomHooks.scrollFirst = amount -> {
            Minecraft mc = Minecraft.getInstance();
            if (free && mc.screen == null && amount != 0) {
                freeFov = Mth.clamp(freeFov - Math.signum(amount) * 3, 10, 110);
                return 0;
            }
            if (mode == 0 || exiting || mc.screen != null || !mc.options.keyTogglePerspective.isDown() || amount == 0) {
                return amount;
            }
            String key = mode == 1 ? "wide" : mode == 2 ? "follow" : mode == 3 ? "close" : "cineDist";
            int def = mode == 1 ? 90 : mode == 2 ? 50 : mode == 3 ? 16 : 40;
            int min = mode == 3 ? 6 : 20;
            int now = Settings.num(P + key, def);
            int next = Mth.clamp(now - (int) Math.signum(amount) * 5, min, 250);
            Settings.set(P + key, String.valueOf(next));
            refreshed = 0;
            refresh();
            go(mode);
            tween = 0.6;
            holdUsed = true;
            return 0;
        };
        ZoomHooks.GuiPass oldGui = ZoomHooks.guiPass;
        if (oldGui != null) {
            ZoomHooks.guiPass = (gui, graphics, pt) -> {
                oldGui.draw(gui, graphics, pt);
                drawCrosshair(graphics, pt);
            };
        }
    }

    private static boolean present(String cls) {
        try {
            Class.forName(cls, false, OrbitCamera.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean usable(Minecraft mc) {
        LocalPlayer p = mc.player;
        refresh();
        return p != null && mc.level != null && Settings.on(SETTING) && anyMode() && !PauseCinema.active()
                && mc.getCameraEntity() == p && !p.isSpectator() && !p.isSleeping() && !p.isDeadOrDying();
    }

    private static boolean portalPass() {
        if (!portalChecked) {
            portalChecked = true;
            try {
                Class<?> c = Class.forName("qouteall.imm_ptl.core.render.context_management.PortalRendering", false,
                        OrbitCamera.class.getClassLoader());
                portalRendering = c.getMethod("isRendering");
            } catch (ReflectiveOperationException | LinkageError e) {
                portalRendering = null;
            }
        }
        if (portalRendering == null) {
            return false;
        }
        try {
            return (boolean) portalRendering.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static final String P = "camera.";

    private static double tenths(String key, int def) {
        return Settings.num(P + key, def) / 10.0;
    }

    private static double hundredths(String key, int def) {
        return Settings.num(P + key, def) / 100.0;
    }

    private static double seconds(String key, int defMs) {
        return Settings.num(P + key, defMs) / 1000.0;
    }

    private static boolean flag(String key, boolean def) {
        String v = Settings.get(P + key);
        return v == null || v.isEmpty() ? def : "true".equals(v);
    }

    static void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshed < 400) {
            return;
        }
        refreshed = now;
        DIST[1] = tenths("wide", 90);
        DIST[2] = tenths("follow", 50);
        DIST[3] = tenths("close", 16);
        UP[1] = tenths("wideHeight", 15);
        UP[2] = tenths("followHeight", 8);
        UP[3] = tenths("closeHeight", 3);
        DIST[4] = tenths("cineDist", 40);
        UP[4] = tenths("cineHeight", 6);
        cineSmoothing = seconds("cineSmoothing", 120);
        double shoulder = tenths("shoulder", 8);
        boolean left = "left".equals(Settings.get(P + "shoulderSide"));
        SIDE[1] = 0;
        SIDE[2] = 0;
        SIDE[3] = left ? -shoulder : shoulder;
        useMode[1] = flag("useWide", true);
        useMode[2] = flag("useFollow", true);
        useMode[3] = flag("useClose", true);
        TWEEN = Math.max(0.05, seconds("glide", 450));
        EASE_OUT = Math.max(0.02, seconds("wallEase", 400));
        HOLD_SECONDS = Math.max(0.15, seconds("hold", 500));
        NECK = Settings.num(P + "neck", 70);
        AIM_FOLLOW = Math.max(0.005, seconds("aimSmooth", 50));
        WALK_TURN = Math.max(0.01, seconds("walkTurn", 120));
        MAX_TURN = Settings.num(P + "turnSpeed", 720);
        sensitivity = Settings.num(P + "sensitivity", 100) / 100.0;
        overHeadOn = flag("overHead", true);
        WALK_DELAY = seconds("overHeadDelay", 500);
        STAND_DELAY = seconds("overHeadBack", 800);
        OVER_HEAD_LIFT = hundredths("overHeadLift", 55);
        ghostOn = flag("ghost", true);
        GHOST_START = tenths("ghostStart", 10);
        GHOST_GONE = Math.min(GHOST_START - 0.05, 0.35);
        crosshairOn = flag("crosshair", true);
        crosshairWide = flag("crosshairWide", false);
        comfort = flag("comfort", false);
        caveOn = flag("smartCave", true);
        ceilingOn = flag("smartCeiling", true);
        speedOn = flag("smartSpeed", true) && !comfort;
        lookOn = flag("smartLook", true) && !comfort;
        combatOn = flag("smartCombat", true) && !comfort;
        smoothing = comfort ? 0 : seconds("smoothing", 30);
        bumpSmoothing = comfort ? 0 : seconds("bumpSmoothing", 70);
        seeThroughOn = flag("seeThrough", true);
        shoulderKeyOn = flag("shoulderKey", true);
        autoShoulderOn = flag("autoShoulder", true);
        bowZoomOn = flag("bowZoom", true);
        lockOn = flag("lockOn", true);
    }

    private static boolean anyMode() {
        return useMode[1] || useMode[2] || useMode[3];
    }

    private static int nextMode(int from) {
        for (int m = from + 1; m <= 3; m++) {
            if (useMode[m]) {
                return m;
            }
        }
        return 0;
    }

    @Override
    public void camera(Object cameraObject, Object entity, float pt) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = (Camera) cameraObject;
        if (cam == mc.gameRenderer.getMainCamera()) {
            hideOneFrame = false;
        }
        if (cam != mc.gameRenderer.getMainCamera() || portalPass() || PauseCinema.active()) {
            return;
        }
        keys(mc);
        LocalPlayer fp = mc.player;
        if (free) {
            if (fp == null || mc.level == null || fp.isDeadOrDying()) {
                endFree(mc);
            } else {
                if (pressed(freeKey) && !freeReturn) {
                    freeReturn = true;
                    freeRetT = 0;
                    retFromPos = freePos;
                    retFromYaw = freeYaw;
                    retFromPitch = freePitch;
                    retFromFov = freeFov;
                }
                freeFrame(mc, fp, cam);
                lastSet = mc.options.getCameraType();
                return;
            }
        } else if (fp != null && mc.level != null && pressed(freeKey) && !fp.isDeadOrDying()) {
            startFree(mc, fp);
            freeFrame(mc, fp, cam);
            lastSet = mc.options.getCameraType();
            return;
        }
        if (!usable(mc)) {
            if (mode != 0) {
                if (mc.player != null) {
                    place(cam, realHeadAt(mc.player, pt), camYaw, camPitch);
                    hideOneFrame = true;
                }
                finishExit(mc);
            }
            ghost = 1f;
            lastSet = mc.options.getCameraType();
            return;
        }
        LocalPlayer p = mc.player;
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;

        handleKeys(mc, p, now);
        if (pressed(cineKey)) {
            if (mode == CINEMATIC && !exiting) {
                if (beforeCine == 0) {
                    startExit();
                } else {
                    go(beforeCine);
                }
            } else {
                beforeCine = exiting ? 0 : mode;
                if (mode == 0) {
                    enter(p);
                }
                exiting = false;
                go(CINEMATIC);
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                lastSet = mc.options.getCameraType();
            }
        }
        wrapInput(p, mode == CINEMATIC && !exiting);
        if (pressed(lockKey) && mode != 0 && lockOn) {
            lockTarget = lockTarget != null ? null : pickLockTarget(mc, p);
            lockDrift = 0;
        }
        if (mode == 0) {
            lockTarget = null;
            ghost = 1f;
            lastSet = mc.options.getCameraType();
            return;
        }

        Vec3 realHead = p.getEyePosition(pt);
        boolean jumped = placed && realHead.distanceToSqr(lastX, lastY, lastZ) > 16;
        lastX = realHead.x;
        lastY = realHead.y;
        lastZ = realHead.z;

        steerLock(p, realHead, dt, pt);

        double water = p.isUnderWater() && !comfort ? 1.6 : 1;
        double smooth = mode == CINEMATIC && !comfort ? Math.max(smoothing, cineSmoothing) : smoothing;
        double ms = smooth <= 0 || jumped ? 1 : blend(dt, smooth * water);
        camYaw += (wantYaw - camYaw) * ms;
        camPitch += (wantPitch - camPitch) * ms;

        if (jumped || !placed || bumpSmoothing <= 0) {
            smoothY = realHead.y;
        } else {
            smoothY += (realHead.y - smoothY) * blend(dt, bumpSmoothing);
            smoothY = Mth.clamp(smoothY, realHead.y - 1.2, realHead.y + 1.2);
        }
        Vec3 head = new Vec3(realHead.x, smoothY, realHead.z);
        smart(mc, p, realHead, dt, jumped);

        tween = Math.min(1, tween + dt / TWEEN);
        double e = jumped ? 1 : ease(tween);
        if (jumped) {
            tween = 1;
        }
        dist = Mth.lerp(e, fromDist, toDist);
        up = Mth.lerp(e, fromUp, toUp);
        side = Mth.lerp(e, fromSide, toSide);
        if (exiting && tween >= 1) {
            place(cam, realHeadAt(p, pt), camYaw, camPitch);
            finishExit(mc);
            hideOneFrame = true;
            return;
        }

        if (moving(p)) {
            walkedFor += dt;
            stoodFor = 0;
        } else {
            stoodFor += dt;
            walkedFor = 0;
        }
        double headWanted = !overHeadOn ? 0 : walkedFor >= WALK_DELAY ? 1 : stoodFor >= STAND_DELAY ? 0 : overHead;
        overHead += (headWanted - overHead) * (jumped ? 1 : blend(dt, OVER_HEAD_SHIFT));
        double h = ease(Mth.clamp(overHead, 0, 1));

        double yaw = Math.toRadians(camYaw);
        double pitch = Math.toRadians(camPitch);
        Vec3 look = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        double smartOn = exiting ? 1 - ease(tween) : 1;
        double upNow = up + OVER_HEAD_LIFT * h * smartOn + combatExtra * 0.25 * smartOn + flyLift * smartOn;
        if (ceilingOn) {
            upNow = Math.min(upNow, ceilingRoom(mc, realHead, upNow + 0.6) - 0.35);
            upNow = Math.max(upNow, Math.min(0, up));
        }
        double distNow = (dist + (speedExtra + combatExtra + rideExtra) * smartOn) * Mth.lerp(smartOn, 1, caveScale);
        double sideNow = side * (1 - h) * shoulderFlip;
        if (aimBlend > 0.001) {
            double ab = ease(Mth.clamp(aimBlend, 0, 1)) * smartOn;
            distNow = Mth.lerp(ab, distNow, DIST[3]);
            upNow = Mth.lerp(ab, upNow, UP[3]);
            sideNow = Mth.lerp(ab, sideNow, SIDE[3] * shoulderFlip);
        }
        Vec3 pivot = head.add(lookX * smartOn, upNow, lookZ * smartOn).add(right.scale(sideNow));
        Vec3 want = pivot.subtract(look.scale(distNow));

        double room = room(mc, realHead, want);
        double target = Math.max(0.02, room);
        if (jumped || !placed) {
            clip = target;
        } else if (target < clip) {
            clip += (target - clip) * blend(dt, PULL_IN);
        } else {
            clip += (target - clip) * blend(dt, EASE_OUT);
        }
        clip = Math.min(clip, target);
        Vec3 at = realHead.add(want.subtract(realHead).scale(clip));
        placed = true;
        camPos = at;
        camLook = look;

        aim(mc, p, dt);
        ghost = ghostOn ? ghostFor(p, at, pt) : 1f;
        place(cam, at, camYaw, camPitch);
        lastSet = mc.options.getCameraType();
    }

    private static void smart(Minecraft mc, LocalPlayer p, Vec3 head, double dt, boolean jumped) {
        net.minecraft.world.entity.Entity ride = p.getRootVehicle();
        Vec3 v = ride.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z) * 20;
        if (p.isFallFlying()) {
            speed = p.getDeltaMovement().length() * 20;
        }
        speedNow += (speed - speedNow) * blend(dt, 0.25);
        double snap = jumped ? 1 : 0;

        double speedWanted = speedOn ? Mth.clamp((speedNow - 6) / 14, 0, 1) * 4 : 0;
        speedExtra += (speedWanted - speedExtra) * Math.max(snap, blend(dt, speedWanted > speedExtra ? 0.6 : 1.2));

        double lead = lookOn && speedNow > 4 ? Mth.clamp((speedNow - 4) / 10, 0, 1) * 1.2 : 0;
        double len = Math.sqrt(v.x * v.x + v.z * v.z);
        double wantX = len > 1e-4 ? v.x / len * lead : 0;
        double wantZ = len > 1e-4 ? v.z / len * lead : 0;
        double lb = Math.max(snap, blend(dt, 0.7));
        lookX += (wantX - lookX) * lb;
        lookZ += (wantZ - lookZ) * lb;

        if (combatOn) {
            if (--combatCheck <= 0) {
                combatCheck = 10;
                enemyNear = !mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                        p.getBoundingBox().inflate(10), m -> m.isAlive() && !m.isInvisible()).isEmpty();
            }
        } else {
            enemyNear = false;
        }
        double combatWanted = enemyNear && mode != 3 ? 1.5 : 0;
        combatExtra += (combatWanted - combatExtra) * Math.max(snap, blend(dt, combatWanted > combatExtra ? 0.9 : 1.6));

        double rideWanted = p.isPassenger() ? (mode == 3 ? 1.0 : 3.0) : 0;
        rideExtra += (rideWanted - rideExtra) * Math.max(snap, blend(dt, 0.8));
        double liftWanted = p.isFallFlying() ? 0.5 : p.isVisuallySwimming() || p.isVisuallyCrawling() ? 0.3 : 0;
        flyLift += (liftWanted - flyLift) * Math.max(snap, blend(dt, 0.6));
        boolean drawing = bowZoomOn && p.isUsingItem() && mode != 3 && isAimItem(p.getUseItem());
        aimBlend += ((drawing ? 1 : 0) - aimBlend) * Math.max(snap, blend(dt, drawing ? 0.18 : 0.35));

        if (pressed(shoulderKey) && shoulderKeyOn) {
            Settings.set(P + "shoulderSide", "left".equals(Settings.get(P + "shoulderSide")) ? "right" : "left");
            refreshed = 0;
            refresh();
        }
        double flipWanted = autoShoulderOn ? shoulderSideFree(mc, head) : 1;
        shoulderFlip += (flipWanted - shoulderFlip) * Math.max(snap, blend(dt, 0.3));

        double caveWanted = caveOn ? 0.45 + 0.55 * openness(mc, head) : 1;
        caveScale += (caveWanted - caveScale) * Math.max(snap, blend(dt, caveWanted < caveScale ? 0.35 : 1.0));
    }

    private static net.minecraft.client.KeyMapping addKey(Minecraft mc, String name, com.mojang.blaze3d.platform.InputConstants.Type type,
                                                          int code) throws ReflectiveOperationException {
        net.minecraft.client.Options options = mc.options;
        for (net.minecraft.client.KeyMapping k : options.keyMappings) {
            if (k.getName().equals(name)) {
                return k;
            }
        }
        net.minecraft.client.KeyMapping made = new net.minecraft.client.KeyMapping(name, type, code, Zoom.CATEGORY);
        net.minecraft.client.KeyMapping[] all = java.util.Arrays.copyOf(options.keyMappings, options.keyMappings.length + 1);
        all[all.length - 1] = made;
        java.lang.reflect.Field f = net.minecraft.client.Options.class.getField("keyMappings");
        f.setAccessible(true);
        f.set(options, all);
        try {
            java.nio.file.Path file = mc.gameDirectory.toPath().resolve("options.txt");
            if (java.nio.file.Files.exists(file)) {
                String prefix = "key_" + name + ":";
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    if (line.startsWith(prefix)) {
                        made.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(line.substring(prefix.length())));
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            Log.warn("Could not read the saved key for " + name + ": " + e);
        }
        return made;
    }

    private static void keys(Minecraft mc) {
        if (keysTried || mc.options == null) {
            return;
        }
        keysTried = true;
        try {
            lockKey = addKey(mc, "Pentecost camera lock-on", com.mojang.blaze3d.platform.InputConstants.Type.MOUSE,
                    org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_4);
            shoulderKey = addKey(mc, "Pentecost camera shoulder swap", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                    org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT);
            freeKey = addKey(mc, "Pentecost free camera", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                    org.lwjgl.glfw.GLFW.GLFW_KEY_F7);
            cineKey = addKey(mc, "Pentecost cinematic camera", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                    org.lwjgl.glfw.GLFW.GLFW_KEY_F8);
            net.minecraft.client.KeyMapping.resetMapping();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("F5 camera keys could not be added: " + e);
        }
    }

    private static boolean pressed(net.minecraft.client.KeyMapping k) {
        boolean any = false;
        while (k != null && k.consumeClick()) {
            any = true;
        }
        return any;
    }

    private static final class CineInput extends net.minecraft.client.player.Input {
        final net.minecraft.client.player.Input inner;

        CineInput(net.minecraft.client.player.Input inner) {
            this.inner = inner;
        }

        @Override
        public void tick(boolean slow, float slowFactor) {
            inner.tick(slow, slowFactor);
            up = inner.up;
            down = inner.down;
            left = inner.left;
            right = inner.right;
            jumping = inner.jumping;
            shiftKeyDown = inner.shiftKeyDown;
            forwardImpulse = inner.forwardImpulse;
            leftImpulse = inner.leftImpulse;
            Minecraft mc = Minecraft.getInstance();
            if (mode != CINEMATIC || exiting || acting(mc)) {
                moveWanted = false;
                return;
            }
            double f = forwardImpulse;
            double l = leftImpulse;
            double mag = Math.min(Math.max(Math.abs(f), Math.abs(l)), Math.sqrt(f * f + l * l));
            if (mag < 0.01) {
                moveWanted = false;
                return;
            }
            moveYaw = camYaw - (float) Math.toDegrees(Math.atan2(l, f));
            moveWanted = true;
            LocalPlayer p = mc.player;
            float off = p == null ? 0 : Math.abs(Mth.wrapDegrees(moveYaw - p.getYRot()));
            forwardImpulse = (float) (mag * (off > 100 ? 0.35 : 1.0));
            leftImpulse = 0;
            up = true;
            down = false;
            left = false;
            right = false;
        }
    }

    private static void wrapInput(LocalPlayer p, boolean on) {
        if (on && !(p.input instanceof CineInput) && !free) {
            p.input = new CineInput(p.input);
        } else if (!on && p.input instanceof CineInput c) {
            p.input = c.inner;
            moveWanted = false;
        }
    }

    private static net.minecraft.world.entity.LivingEntity pickLockTarget(Minecraft mc, LocalPlayer p) {
        if (camPos == null || camLook == null) {
            return null;
        }
        net.minecraft.world.entity.LivingEntity best = null;
        double bestAngle = Math.toRadians(12);
        for (net.minecraft.world.entity.LivingEntity e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                p.getBoundingBox().inflate(32), e -> e != p && e.isAlive() && !e.isInvisible())) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(camPos);
            double len = to.length();
            if (len < 0.5) {
                continue;
            }
            double angle = Math.acos(Mth.clamp(to.dot(camLook) / len, -1, 1));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = e;
            }
        }
        return best;
    }

    private static void steerLock(LocalPlayer p, Vec3 head, double dt, float pt) {
        net.minecraft.world.entity.LivingEntity t = lockTarget;
        if (t == null) {
            return;
        }
        if (!t.isAlive() || t.isRemoved() || t.distanceTo(p) > 40 || lockDrift > 45) {
            lockTarget = null;
            return;
        }
        Vec3 target = t.getPosition(pt).add(0, t.getBbHeight() * 0.6, 0);
        Vec3 focus = head.add(target.subtract(head).scale(0.7));
        Vec3 d = focus.subtract(head);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(d.y, h)) + 8f, -85f, 85f);
        double b = blend(dt, 0.15);
        wantYaw += Mth.wrapDegrees(yaw - wantYaw) * b;
        wantPitch += (pitch - wantPitch) * b;
        lockDrift = Math.max(0, lockDrift - dt * 60);
    }

    private static void startFree(Minecraft mc, LocalPlayer p) {
        free = true;
        lockTarget = null;
        Vec3 from = camPos != null && mode != 0 ? camPos : p.getEyePosition();
        freePos = from;
        freeVX = freeVY = freeVZ = 0;
        freeYaw = freeWantYaw = mode != 0 ? camYaw : p.getYRot();
        freePitch = freeWantPitch = mode != 0 ? camPitch : p.getXRot();
        homePos = from;
        homeYaw = freeYaw;
        homePitch = freePitch;
        freeReturn = false;
        freeFov = mc.options.fov().get();
        freeSavedType = mc.options.getCameraType();
        freeSavedHideGui = mc.options.hideGui;
        freeSavedInput = p.input;
        p.input = new net.minecraft.client.player.Input();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        freeLast = 0;
    }

    static void endFree(Minecraft mc) {
        if (!free) {
            return;
        }
        free = false;
        freeReturn = false;
        ZoomHooks.fovOverride = 0;
        LocalPlayer p = mc.player;
        if (p != null && freeSavedInput != null) {
            p.input = freeSavedInput;
        }
        freeSavedInput = null;
        if (freeSavedType != null) {
            mc.options.setCameraType(freeSavedType);
        }
        mc.options.hideGui = freeSavedHideGui;
        lastSet = mc.options.getCameraType();
    }

    private static void freeFrame(Minecraft mc, LocalPlayer p, Camera cam) {
        long now = System.nanoTime();
        double dt = freeLast == 0 ? 0.016 : Math.min(0.1, (now - freeLast) / 1e9);
        freeLast = now;
        if (freeReturn) {
            freeRetT = Math.min(1, freeRetT + dt / FREE_RETURN);
            double e = ease(freeRetT);
            boolean firstPerson = mode == 0;
            Vec3 to = firstPerson ? p.getEyePosition() : homePos;
            float toYaw = firstPerson ? p.getYRot() : homeYaw;
            float toPitch = firstPerson ? p.getXRot() : homePitch;
            Vec3 at = retFromPos.add(to.subtract(retFromPos).scale(e));
            float yaw = retFromYaw + Mth.wrapDegrees(toYaw - retFromYaw) * (float) e;
            float pitch = retFromPitch + (toPitch - retFromPitch) * (float) e;
            ZoomHooks.fovOverride = Mth.lerp(e, retFromFov, mc.options.fov().get());
            mc.options.keyAttack.setDown(false);
            mc.options.keyUse.setDown(false);
            place(cam, at, yaw, pitch);
            ghost = 1f;
            if (freeRetT >= 1) {
                freeReturn = false;
                if (!firstPerson) {
                    camYaw = wantYaw = homeYaw;
                    camPitch = wantPitch = homePitch;
                }
                endFree(mc);
                lastNanos = 0;
            }
            return;
        }
        double look = blend(dt, 0.08);
        freeYaw += (freeWantYaw - freeYaw) * look;
        freePitch += (freeWantPitch - freePitch) * look;
        boolean typing = mc.screen != null;
        double fwd = typing ? 0 : (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = typing ? 0 : (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double rise = typing ? 0 : (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
        double speed = mc.options.keySprint.isDown() ? 12 : 3.5;
        double yaw = Math.toRadians(freeYaw);
        double pitch = Math.toRadians(freePitch);
        Vec3 f = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 l = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3 want = f.scale(fwd).add(l.scale(strafe)).add(0, rise, 0);
        if (want.lengthSqr() > 1) {
            want = want.normalize();
        }
        want = want.scale(speed);
        double g = blend(dt, 0.3);
        freeVX += (want.x - freeVX) * g;
        freeVY += (want.y - freeVY) * g;
        freeVZ += (want.z - freeVZ) * g;
        Vec3 next = freePos;
        double[] step = {freeVX * dt, freeVY * dt, freeVZ * dt};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(step[axis]) < 1e-7) {
                continue;
            }
            Vec3 tryAt = next.add(axis == 0 ? step[0] : 0, axis == 1 ? step[1] : 0, axis == 2 ? step[2] : 0);
            if (blocked(mc, p, tryAt) && !blocked(mc, p, next)) {
                if (axis == 0) {
                    freeVX = 0;
                } else if (axis == 1) {
                    freeVY = 0;
                } else {
                    freeVZ = 0;
                }
            } else {
                next = tryAt;
            }
        }
        Vec3 eye = p.getEyePosition();
        if (next.distanceTo(eye) > 64) {
            next = eye.add(next.subtract(eye).normalize().scale(64));
        }
        freePos = next;
        ZoomHooks.fovOverride = freeFov;
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        place(cam, freePos, freeYaw, freePitch);
        ghost = 1f;
    }

    private static boolean blocked(Minecraft mc, LocalPlayer p, Vec3 at) {
        AABB box = new AABB(at.x - 0.2, at.y - 0.2, at.z - 0.2, at.x + 0.2, at.y + 0.2, at.z + 0.2);
        if (box.intersects(p.getBoundingBox().inflate(0.15))) {
            return true;
        }
        return !mc.level.noCollision(box);
    }

    static boolean cleanShot() {
        return free && Minecraft.getInstance().options.hideGui;
    }

    private static boolean isAimItem(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.Item it = stack.getItem();
        return it instanceof net.minecraft.world.item.BowItem || it instanceof net.minecraft.world.item.CrossbowItem
                || it instanceof net.minecraft.world.item.TridentItem;
    }

    private static double shoulderSideFree(Minecraft mc, Vec3 head) {
        double off = SIDE[3];
        if (Math.abs(off) < 0.05) {
            return 1;
        }
        double yaw = Math.toRadians(camYaw);
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        double reach = Math.abs(off) + 0.4;
        Vec3 mine = right.scale(Math.signum(off));
        double here = freeDistance(mc, head, mine, reach);
        if (here >= reach - 0.05) {
            return 1;
        }
        double other = freeDistance(mc, head, mine.scale(-1), reach);
        return other > here + 0.3 ? -1 : 1;
    }

    private static double freeDistance(Minecraft mc, Vec3 from, Vec3 dir, double reach) {
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(dir.scale(reach)), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? reach : hit.getLocation().distanceTo(from);
    }

    private static double openness(Minecraft mc, Vec3 head) {
        double yaw = Math.toRadians(camYaw);
        Vec3 back = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
        Vec3 side = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3[] dirs = {new Vec3(0, 1, 0), back, back.add(side).normalize(), back.subtract(side).normalize(), side, side.scale(-1)};
        double total = 0;
        for (Vec3 d : dirs) {
            double reach = 5;
            Vec3 end = head.add(d.scale(reach));
            BlockHitResult hit = mc.level.clip(new ClipContext(head, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            total += hit.getType() == HitResult.Type.MISS ? 1 : hit.getLocation().distanceTo(head) / reach;
        }
        return Mth.clamp(total / dirs.length * 1.25 - 0.15, 0, 1);
    }

    private static double ceilingRoom(Minecraft mc, Vec3 head, double max) {
        if (max <= 0) {
            return max;
        }
        BlockHitResult hit = mc.level.clip(new ClipContext(head, head.add(0, max, 0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? max : hit.getLocation().y - head.y;
    }

    private static boolean pressPending;
    private static int presses;
    private static boolean hideOneFrame;
    private static long pressAt;

    private static void handleKeys(Minecraft mc, LocalPlayer p, long now) {
        CameraType type = mc.options.getCameraType();
        if (lastSet == null) {
            lastSet = type;
        }
        if (type != lastSet) {
            lastSet = type;
        }
        if (presses > 0) {
            presses = 0;
            if (!pressPending) {
                pressPending = true;
                pressAt = now;
                holdUsed = false;
            }
        }
        if (!pressPending) {
            return;
        }
        boolean down = mc.screen == null && mc.options.keyTogglePerspective.isDown();
        if (down) {
            if (!holdUsed && (now - pressAt) / 1e9 >= HOLD_SECONDS) {
                holdUsed = true;
                if (mode != 0 && !exiting) {
                    startExit();
                }
            }
            return;
        }
        pressPending = false;
        if (holdUsed) {
            return;
        }
        int next = nextMode(exiting ? 0 : mode);
        if (mode == 0) {
            enter(p);
            go(nextMode(0));
        } else if (exiting) {
            exiting = false;
            go(nextMode(0));
        } else if (next == 0) {
            startExit();
        } else {
            go(next);
        }
        mc.options.setCameraType(mode == 0 ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        lastSet = mc.options.getCameraType();
    }

    private static void go(int next) {
        mode = next;
        fromDist = dist;
        fromUp = up;
        fromSide = side;
        toDist = DIST[next];
        toUp = UP[next];
        toSide = SIDE[next];
        tween = 0;
    }

    private static void startExit() {
        exiting = true;
        fromDist = dist;
        fromUp = up;
        fromSide = side;
        toDist = 0;
        toUp = 0;
        toSide = 0;
        tween = 0;
    }

    private static void enter(LocalPlayer p) {
        camYaw = p.getYRot();
        camPitch = Mth.clamp(p.getXRot(), -85, 85);
        anchorYaw = p.getYRot();
        wantYaw = camYaw;
        wantPitch = camPitch;
        speedExtra = combatExtra = lookX = lookZ = rideExtra = flyLift = aimBlend = 0;
        shoulderFlip = 1;
        headSide = 0;
        caveScale = 1;
        dist = 0;
        up = 0;
        side = 0;
        clip = 1;
        placed = false;
        exiting = false;
        lastNanos = 0;
    }

    private static void finishExit(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null) {
            wrapInput(p, false);
        }
        if (p != null && mode != 0) {
            p.setYRot(camYaw);
            p.setXRot(Mth.clamp(camPitch, -90, 90));
            p.yRotO = p.getYRot();
            p.xRotO = p.getXRot();
            p.setYHeadRot(camYaw);
        }
        mode = 0;
        exiting = false;
        placed = false;
        camPos = null;
        ghost = 1f;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        lastSet = mc.options.getCameraType();
    }

    private static boolean outsideCone() {
        return Math.abs(Mth.wrapDegrees(camYaw - anchorYaw)) > NECK;
    }

    private static boolean moving(LocalPlayer p) {
        return p.input != null && (Math.abs(p.input.forwardImpulse) > 0.01 || Math.abs(p.input.leftImpulse) > 0.01);
    }

    private static boolean acting(Minecraft mc) {
        return mc.screen == null && (mc.options.keyAttack.isDown() || mc.options.keyUse.isDown());
    }

    private static void aim(Minecraft mc, LocalPlayer p, double dt) {
        if (mode == CINEMATIC && !exiting) {
            if (acting(mc)) {
                anchorYaw = turnToward(anchorYaw, camYaw, blend(dt, AIM_FOLLOW), dt);
                aimAtCamera(blend(dt, 0.03));
            } else if (moveWanted) {
                float ny = turnToward(p.getYRot(), moveYaw, blend(dt, 0.09), dt);
                p.setYRot(ny);
                p.setYHeadRot(ny);
                p.setXRot(p.getXRot() + (0 - p.getXRot()) * (float) blend(dt, 0.25));
                anchorYaw = ny;
            }
            return;
        }
        if (exiting) {
            anchorYaw = turnToward(anchorYaw, camYaw, blend(dt, AIM_FOLLOW), dt);
            aimAtCamera(blend(dt, AIM_FOLLOW), 180f);
            return;
        }
        if (acting(mc)) {
            anchorYaw = turnToward(anchorYaw, camYaw, blend(dt, AIM_FOLLOW), dt);
        } else if (moving(p)) {
            anchorYaw = turnToward(anchorYaw, camYaw, blend(dt, WALK_TURN), dt);
        }
        aimAtCamera(blend(dt, AIM_FOLLOW), NECK);
    }

    static void aimAtCamera(double strength) {
        aimAtCamera(strength, 180f);
    }

    static void aimAtCamera(double strength, float neck) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || camPos == null || camLook == null) {
            return;
        }
        Vec3 end = camPos.add(camLook.scale(96));
        BlockHitResult hit = mc.level.clip(new ClipContext(camPos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        Vec3 target = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
        Vec3 d = target.subtract(p.getEyePosition());
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
        float rel = Mth.wrapDegrees(yaw - anchorYaw);
        if (neck < 180f && Math.abs(Mth.wrapDegrees(camYaw - anchorYaw)) > neck) {
            return;
        }
        if (Math.abs(rel) > neck) {
            rel = Math.signum(rel) * neck;
        }
        yaw = anchorYaw + rel;
        float ny = p.getYRot() + (float) (Mth.wrapDegrees(yaw - p.getYRot()) * strength);
        float np = Mth.clamp(p.getXRot() + (float) ((pitch - p.getXRot()) * strength), -90, 90);
        p.setYRot(ny);
        p.setXRot(np);
        p.setYHeadRot(ny);
        if (strength >= 1) {
            p.yRotO = ny;
            p.xRotO = np;
        }
    }

    private static float turnToward(float from, float toward, double strength, double dt) {
        float diff = Mth.wrapDegrees(toward - from);
        float step = (float) (diff * strength);
        float limit = (float) (MAX_TURN * dt);
        return from + Mth.clamp(step, -limit, limit);
    }

    private static double room(Minecraft mc, Vec3 from, Vec3 to) {
        double best = 1;
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 1e-3) {
            return 1;
        }
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * 0.1;
            double oy = ((i >> 1 & 1) * 2 - 1) * 0.1;
            double oz = ((i >> 2 & 1) * 2 - 1) * 0.1;
            Vec3 a = from.add(ox, oy, oz);
            Vec3 b = to.add(ox, oy, oz);
            double f = solidHit(mc, a, b);
            if (f >= 0) {
                best = Math.min(best, Math.max(0, (f * len - 0.1) / len));
            }
        }
        return best;
    }

    private static double solidHit(Minecraft mc, Vec3 a, Vec3 b) {
        Vec3 from = a;
        double total = b.subtract(a).length();
        for (int tries = 0; tries < 6; tries++) {
            BlockHitResult hit = mc.level.clip(new ClipContext(from, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return -1;
            }
            net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(hit.getBlockPos());
            if (!seeThroughOn || st.canOcclude()) {
                return hit.getLocation().distanceTo(a) / total;
            }
            Vec3 dir = b.subtract(a).normalize();
            from = hit.getLocation().add(dir.scale(0.05));
            if (from.distanceTo(a) >= total) {
                return -1;
            }
        }
        return -1;
    }

    private static float ghostFor(LocalPlayer p, Vec3 at, float pt) {
        Vec3 feet = p.getPosition(pt);
        AABB box = p.getBoundingBox().move(feet.subtract(p.position()));
        double dx = Math.max(Math.max(box.minX - at.x, 0), at.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - at.y, 0), at.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - at.z, 0), at.z - box.maxZ);
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d >= GHOST_START) {
            return 1f;
        }
        double t = (d - GHOST_GONE) / (GHOST_START - GHOST_GONE);
        t = Mth.clamp(t, 0, 1);
        return (float) (0.08 + 0.92 * ease(t));
    }

    @Override
    public float ghost(Object entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player) {
            return 1f;
        }
        if (hideOneFrame) {
            return 0.08f;
        }
        return mode != 0 ? ghost : 1f;
    }

    private static Vec3 realHeadAt(LocalPlayer p, float pt) {
        return p.getEyePosition(pt);
    }

    @Override
    public Object perspective(Object newType) {
        Minecraft mc = Minecraft.getInstance();
        if (free || !usable(mc) || portalPass()) {
            return newType;
        }
        presses++;
        return mc.options.getCameraType();
    }

    @Override
    public boolean turn(Object entity, double yaw, double pitch) {
        if (free && freeReturn && entity == Minecraft.getInstance().player) {
            return true;
        }
        if (free && entity == Minecraft.getInstance().player) {
            freeWantYaw += (float) (yaw * 0.15 * sensitivity);
            freeWantPitch = Mth.clamp(freeWantPitch + (float) (pitch * 0.15 * sensitivity), -89f, 89f);
            return true;
        }
        if (mode == 0 || exiting) {
            return false;
        }
        if (lockTarget != null) {
            lockDrift += Math.abs(yaw * 0.15) + Math.abs(pitch * 0.15);
        }
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player || !usable(mc)) {
            return false;
        }
        wantYaw += (float) (yaw * 0.15 * sensitivity);
        wantPitch = Mth.clamp(wantPitch + (float) (pitch * 0.15 * sensitivity), -85f, 85f);
        return true;
    }

    private static void place(Camera cam, Vec3 at, float yaw, float pitch) {
        try {
            Method m = place;
            if (m == null) {
                m = cam.getClass().getMethod("pentecost$place", double.class, double.class, double.class, float.class, float.class);
                place = m;
            }
            m.invoke(cam, at.x, at.y, at.z, yaw, pitch);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not place the camera", e);
        }
    }

    private static double fov(Minecraft mc, float pt) {
        if (!fovTried) {
            fovTried = true;
            try {
                fovMethod = net.minecraft.client.renderer.GameRenderer.class.getDeclaredMethod("getFov", Camera.class, float.class, boolean.class);
                fovMethod.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                fovMethod = null;
            }
        }
        if (fovMethod != null) {
            try {
                return (double) fovMethod.invoke(mc.gameRenderer, mc.gameRenderer.getMainCamera(), pt, true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                fovMethod = null;
            }
        }
        return mc.options.fov().get();
    }

    private static void drawCrosshair(Object graphics, float pt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (mode == 0 || exiting || p == null || mc.options.hideGui || mc.screen != null || camPos == null
                || !crosshairOn || (mode == 1 && !crosshairWide) || (mode == CINEMATIC && !acting(mc))) {
            return;
        }
        Vec3 aimAt;
        HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            aimAt = hit.getLocation();
        } else {
            aimAt = p.getEyePosition(pt).add(p.getViewVector(pt).scale(24));
        }
        double yaw = Math.toRadians(camYaw);
        Vec3 fwd = camLook;
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 upv = right.cross(fwd);
        Vec3 d = aimAt.subtract(camPos);
        double z = d.dot(fwd);
        if (z <= 0.05) {
            return;
        }
        net.minecraft.client.gui.GuiGraphics gg = (net.minecraft.client.gui.GuiGraphics) graphics;
        int w = gg.guiWidth();
        int h = gg.guiHeight();
        double tan = Math.tan(Math.toRadians(fov(mc, pt)) / 2);
        double aspect = (double) w / h;
        double nx = d.dot(right) / z / tan / aspect;
        double ny = d.dot(upv) / z / tan;
        if (Math.abs(nx) > 1.1 || Math.abs(ny) > 1.1) {
            return;
        }
        int cx = (int) Math.round(w / 2.0 + nx * w / 2.0);
        int cy = (int) Math.round(h / 2.0 - ny * h / 2.0);
        boolean onTarget = hit != null && hit.getType() != HitResult.Type.MISS;
        int color = onTarget ? 0xE0FFFFFF : 0x90FFFFFF;
        gg.fill(cx - 4, cy, cx - 1, cy + 1, color);
        gg.fill(cx + 2, cy, cx + 5, cy + 1, color);
        gg.fill(cx, cy - 4, cx + 1, cy - 1, color);
        gg.fill(cx, cy + 2, cx + 1, cy + 5, color);
        gg.fill(cx, cy, cx + 1, cy + 1, color);
    }

    static final String[] KEYS = {"wide", "follow", "close", "wideHeight", "followHeight", "closeHeight", "shoulder",
            "shoulderSide", "useWide", "useFollow", "useClose", "glide", "wallEase", "hold", "neck", "aimSmooth", "walkTurn",
            "turnSpeed", "sensitivity", "overHead", "overHeadDelay", "overHeadBack", "overHeadLift", "ghost", "ghostStart",
            "crosshair", "crosshairWide", "comfort", "smartCave", "smartCeiling", "smartSpeed", "smartLook", "smartCombat",
            "smoothing", "bumpSmoothing", "seeThrough", "shoulderKey", "autoShoulder", "bowZoom", "preset", "lockOn", "cineDist", "cineHeight", "cineSmoothing"};

    static void preset(String name) {
        resetAll();
        switch (name) {
            case "classic" -> {
                for (String k : new String[]{"smartCave", "smartSpeed", "smartLook", "smartCombat", "overHead", "autoShoulder"}) {
                    Settings.set(P + k, "false");
                }
                Settings.set(P + "smoothing", "0");
                Settings.set(P + "bumpSmoothing", "0");
                Settings.set(P + "glide", "200");
                Settings.set(P + "neck", "180");
                Settings.set(P + "follow", "40");
            }
            case "cinematic" -> {
                Settings.set(P + "smoothing", "120");
                Settings.set(P + "bumpSmoothing", "150");
                Settings.set(P + "glide", "900");
                Settings.set(P + "aimSmooth", "120");
                Settings.set(P + "walkTurn", "250");
                Settings.set(P + "wide", "120");
                Settings.set(P + "wideHeight", "25");
            }
            default -> {
            }
        }
        Settings.set(P + "preset", name);
        refreshed = 0;
    }

    static String presetLabel() {
        return switch (Settings.get(P + "preset")) {
            case "classic" -> "Classic";
            case "cinematic" -> "Cinematic";
            default -> "Modern";
        };
    }

    static void resetAll() {
        for (String k : KEYS) {
            Settings.reset(P + k);
        }
        refreshed = 0;
    }

    private static double ease(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double blend(double dt, double tau) {
        return 1 - Math.exp(-dt / tau);
    }
}
