package khi.pentecost.game;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import khi.pentecost.control.Settings;
import khi.pentecost.core.CullHooks;
import khi.pentecost.core.Occlusion;
import khi.pentecost.messenger.Lane;
import khi.pentecost.messenger.Messenger;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds mobs that are fully behind solid blocks, on the messenger's workers, so the game can skip drawing them.
 * Twenty times a second the render thread copies where the camera and the nearby mobs are; a worker ray traces from the
 * camera to each box and sends back the ids it could not see. Players, named mobs (their name tags show through walls),
 * glowing mobs and anything close to the camera are always drawn. If anything goes wrong, everything is drawn.
 */
final class EntityCulling {
    private static final long EVERY_NS = 50_000_000L;
    private static final double RANGE = 96;
    private static final double ALWAYS = 4;
    private static final int MAX = 2000;

    private static long lastAt;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static boolean wasOn;
    private static Object lastLevel;

    private EntityCulling() {}

    /** Called once a frame on the render thread. */
    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        // A quarantined job would run on this thread, which is the opposite of the point. Then draw everything.
        boolean on = Settings.on(Settings.CULL_ENTITIES) && Messenger.get().on() && !Messenger.get().isQuarantined("cull")
                && mc.level != null && mc.player != null;
        if (mc.level != lastLevel) {
            // Entity ids start over in a new world. An old list must not hide new mobs.
            lastLevel = mc.level;
            CullHooks.clear();
        }
        if (!on) {
            if (wasOn) {
                CullHooks.clear();
                wasOn = false;
            }
            return;
        }
        wasOn = true;
        long now = System.nanoTime();
        if (now - lastAt < EVERY_NS || running.get()) {
            return;
        }
        lastAt = now;
        Camera cam = mc.gameRenderer.getMainCamera();
        if (!cam.isInitialized()) {
            return;
        }
        Vec3 eye = cam.getPosition();
        ClientLevel level = mc.level;
        int[] ids = new int[MAX];
        double[] boxes = new double[MAX * 6];
        int n = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (n >= MAX) {
                break;
            }
            if (e instanceof Player || e.hasCustomName() || e.isCurrentlyGlowing() || e == cam.getEntity()) {
                continue;
            }
            AABB b = e.getBoundingBoxForCulling().inflate(0.25);
            double cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2, cz = (b.minZ + b.maxZ) / 2;
            double d2 = (cx - eye.x) * (cx - eye.x) + (cy - eye.y) * (cy - eye.y) + (cz - eye.z) * (cz - eye.z);
            if (d2 < ALWAYS * ALWAYS || d2 > RANGE * RANGE) {
                continue;
            }
            ids[n] = e.getId();
            int o = n * 6;
            boxes[o] = b.minX;
            boxes[o + 1] = b.minY;
            boxes[o + 2] = b.minZ;
            boxes[o + 3] = b.maxX;
            boxes[o + 4] = b.maxY;
            boxes[o + 5] = b.maxZ;
            n++;
        }
        int count = n;
        double ex = eye.x, ey = eye.y, ez = eye.z;
        running.set(true);
        Messenger.get().send("cull", "cull-mobs", Lane.WORKER, () -> {
            try {
                int[] result = compute(level, ex, ey, ez, ids, boxes, count);
                if (Minecraft.getInstance().level == level) {
                    CullHooks.set(result);
                }
            } catch (RuntimeException e) {
                CullHooks.clear();
                throw e;
            } finally {
                running.set(false);
            }
        });
    }

    private static int[] compute(ClientLevel level, double ex, double ey, double ez, int[] ids, double[] boxes, int count) {
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap(4096);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Occlusion.Solid solid = (x, y, z) -> {
            long key = BlockPos.asLong(x, y, z);
            byte c = cache.getOrDefault(key, (byte) 0);
            if (c != 0) {
                return c == 2;
            }
            boolean s;
            try {
                s = level.getBlockState(pos.set(x, y, z)).canOcclude();
            } catch (RuntimeException e) {
                s = false;
            }
            cache.put(key, s ? (byte) 2 : (byte) 1);
            return s;
        };
        int[] hidden = new int[count];
        int h = 0;
        for (int i = 0; i < count; i++) {
            int o = i * 6;
            if (!Occlusion.visible(ex, ey, ez, boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5], solid)) {
                hidden[h++] = ids[i];
            }
        }
        int[] out = Arrays.copyOf(hidden, h);
        Arrays.sort(out);
        return out;
    }
}
