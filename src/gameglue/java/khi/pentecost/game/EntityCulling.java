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

final class EntityCulling {
    private static final long EVERY_NS = 50_000_000L;
    private static final double RANGE = 96;
    private static final double ALWAYS = 4;
    private static final int MAX = 2000;
    private static final int MAX_BLOCKS = 4000;
    private static final double BIG = 24;

    private static long lastAt;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static boolean wasOn;
    private static Object lastLevel;

    private EntityCulling() {}

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        boolean on = Settings.on(Settings.CULL_ENTITIES) && Messenger.get().on() && !Messenger.get().isQuarantined("cull")
                && mc.level != null && mc.player != null;
        if (mc.level != lastLevel) {
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
        long scanStart = now;
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
        Object[] bes = new Object[MAX_BLOCKS];
        net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher bed = mc.getBlockEntityRenderDispatcher();
        double[] bboxes = new double[MAX_BLOCKS * 6];
        int bn = 0;
        int ccx = ((int) Math.floor(eye.x)) >> 4, ccz = ((int) Math.floor(eye.z)) >> 4;
        int r = (int) (RANGE / 16) + 1;
        outer:
        for (int x = ccx - r; x <= ccx + r; x++) {
            for (int z = ccz - r; z <= ccz + r; z++) {
                net.minecraft.world.level.chunk.LevelChunk ch = level.getChunkSource().getChunk(x, z, false);
                if (ch == null) {
                    continue;
                }
                for (net.minecraft.world.level.block.entity.BlockEntity be : ch.getBlockEntities().values()) {
                    if (bn >= MAX_BLOCKS) {
                        break outer;
                    }
                    AABB b;
                    try {
                        net.minecraft.client.renderer.blockentity.BlockEntityRenderer<net.minecraft.world.level.block.entity.BlockEntity> rr = bed.getRenderer(be);
                        if (rr == null || rr.shouldRenderOffScreen(be) || be.isRemoved() || be.getLevel() != level) {
                            continue;
                        }
                        b = drawBox(be);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    if (b == null || !Double.isFinite(b.minX + b.minY + b.minZ + b.maxX + b.maxY + b.maxZ)
                            || b.getXsize() > BIG || b.getYsize() > BIG || b.getZsize() > BIG) {
                        continue;
                    }
                    double cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2, cz = (b.minZ + b.maxZ) / 2;
                    double d2 = (cx - eye.x) * (cx - eye.x) + (cy - eye.y) * (cy - eye.y) + (cz - eye.z) * (cz - eye.z);
                    if (d2 < ALWAYS * ALWAYS || d2 > RANGE * RANGE) {
                        continue;
                    }
                    bes[bn] = be;
                    int o = bn * 6;
                    bboxes[o] = b.minX;
                    bboxes[o + 1] = b.minY;
                    bboxes[o + 2] = b.minZ;
                    bboxes[o + 3] = b.maxX;
                    bboxes[o + 4] = b.maxY;
                    bboxes[o + 5] = b.maxZ;
                    bn++;
                }
            }
        }
        int bcount = bn;
        double ex = eye.x, ey = eye.y, ez = eye.z;
        recordScan(System.nanoTime() - scanStart, count, bcount);
        running.set(true);
        Messenger.get().send("cull", "cull-mobs", Lane.WORKER, () -> {
            long workStart = System.nanoTime();
            try {
                int[] result = compute(level, ex, ey, ez, ids, boxes, count);
                java.util.Set<Object> blocks = computeBlocks(level, ex, ey, ez, bes, bboxes, bcount);
                recordWork(System.nanoTime() - workStart);
                if (Minecraft.getInstance().level == level) {
                    CullHooks.set(result);
                    tracedLevel = level;
                    tracedX = ex;
                    tracedY = ey;
                    tracedZ = ez;
                    CullHooks.setBlocks(blocks);
                }
            } catch (RuntimeException e) {
                CullHooks.clear();
                throw e;
            } finally {
                running.set(false);
            }
        });
    }

    private static volatile double scanMs;
    private static volatile double scanMaxMs;
    private static volatile double workMs;
    private static volatile int scanMobs;
    private static volatile int scanMachines;

    private static void recordScan(long nanos, int mobs, int machines) {
        double ms = nanos / 1e6;
        scanMs = scanMs * 0.9 + ms * 0.1;
        scanMaxMs = Math.max(scanMaxMs * 0.995, ms);
        scanMobs = mobs;
        scanMachines = machines;
    }

    private static void recordWork(long nanos) {
        workMs = workMs * 0.9 + nanos / 1e6 * 0.1;
    }

    static String scanText() {
        return String.format("%.2f ms a pass, worst %.2f, %d mobs, %d machines", scanMs, scanMaxMs, scanMobs, scanMachines);
    }

    static String workText() {
        return String.format("%.2f ms a pass", workMs);
    }

    private static volatile java.lang.reflect.Method boxMethod;
    private static volatile boolean boxTried;

    private static AABB drawBox(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!boxTried) {
            try {
                boxMethod = be.getClass().getMethod("getRenderBoundingBox");
            } catch (NoSuchMethodException e) {
                boxMethod = null;
            }
            boxTried = true;
        }
        java.lang.reflect.Method m = boxMethod;
        if (m != null) {
            try {
                return m.invoke(be) instanceof AABB a ? a : null;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return new AABB(be.getBlockPos()).inflate(1);
    }

    private static java.util.Set<Object> computeBlocks(ClientLevel level, double ex, double ey, double ez, Object[] pos, double[] boxes, int count) {
        Occlusion.Solid solid = solidOf(level);
        java.util.Set<Object> hidden = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < count; i++) {
            int o = i * 6;
            int x0 = (int) Math.floor(boxes[o]), y0 = (int) Math.floor(boxes[o + 1]), z0 = (int) Math.floor(boxes[o + 2]);
            int x1 = (int) Math.ceil(boxes[o + 3]) - 1, y1 = (int) Math.ceil(boxes[o + 4]) - 1, z1 = (int) Math.ceil(boxes[o + 5]) - 1;
            Occlusion.Solid notMine = (x, y, z) -> !(x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) && solid.at(x, y, z);
            if (!Occlusion.visible(ex, ey, ez, boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5], notMine)) {
                hidden.add(pos[i]);
            }
        }
        return hidden;
    }

    private static volatile Object tracedLevel;
    private static volatile double tracedX, tracedY, tracedZ;
    private static final double DRIFT = 4;

    static {
        CullHooks.blockCheck = (dispatcher, machine) -> {
            if (!(dispatcher instanceof net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher d)
                    || !(machine instanceof net.minecraft.world.level.block.entity.BlockEntity be)) {
                return false;
            }
            Minecraft mc = Minecraft.getInstance();
            if (be.getLevel() != tracedLevel || mc.level != tracedLevel || be.isRemoved() || d.camera == null
                    || d.camera != mc.gameRenderer.getMainCamera()) {
                return false;
            }
            Vec3 p = d.camera.getPosition();
            double dx = p.x - tracedX, dy = p.y - tracedY, dz = p.z - tracedZ;
            return dx * dx + dy * dy + dz * dz < DRIFT * DRIFT;
        };
    }

    private static Occlusion.Solid solidOf(ClientLevel level) {
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap(4096);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            long key = BlockPos.asLong(x, y, z);
            byte c = cache.getOrDefault(key, (byte) 0);
            if (c != 0) {
                return c == 2;
            }
            boolean s;
            try {
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos.set(x, y, z));
                s = st.canOcclude() && st.isSolidRender(level, pos);
            } catch (RuntimeException e) {
                s = false;
            }
            cache.put(key, s ? (byte) 2 : (byte) 1);
            return s;
        };
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
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos.set(x, y, z));
                s = st.canOcclude() && st.isSolidRender(level, pos);
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
