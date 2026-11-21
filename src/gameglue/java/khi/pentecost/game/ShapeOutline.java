package khi.pentecost.game;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import khi.pentecost.control.Settings;
import khi.pentecost.core.ClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ShapeOutline {
    private ShapeOutline() {}

    private static final int RES = 32;
    private static final int MAX_SEGS = 20000;
    private static final int MAX_PARTS = 32;
    private static final Set<String> PART_NAMES = Set.of("half", "part", "segment", "section", "piece", "portion");

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pentecost-outline");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final class Cached {
        final BakedModel model;
        volatile float[] segs;

        Cached(BakedModel model) {
            this.model = model;
        }
    }

    private static final Map<BlockState, Cached> MODELS = new WeakHashMap<>();
    private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
    private static final Set<Object> FAILED = new HashSet<>();
    private static final Set<Object> SLOW = new HashSet<>();

    private static BlockEntity machine;
    private static volatile float[] machineSegs;
    private static int machineFrame = -1000;
    private static boolean machineBusy;
    private static int frame;

    static void install() {
        ClientHooks.outline = ShapeOutline::draw;
    }

    static boolean draw(Object poseO, Object linesO, double cx, double cy, double cz, Object posO, Object stateO) {
        if (!Settings.on(Settings.OUTLINE_SHAPE) || !(poseO instanceof PoseStack pose) || !(linesO instanceof VertexConsumer lines)
                || !(posO instanceof BlockPos pos) || !(stateO instanceof BlockState state)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return false;
        }
        frame++;
        List<BlockPos> parts = parts(level, pos, state);
        float[][] shapes = new float[parts.size()][];
        boolean any = false;
        for (int i = 0; i < parts.size(); i++) {
            BlockState s = i == 0 ? state : level.getBlockState(parts.get(i));
            float[] segs = modelSegs(mc, s);
            if (segs == null && i == 0) {
                return false;
            }
            shapes[i] = segs;
            any |= segs != null && segs.length > 0;
        }
        float[] own = machineSegs(mc, level, pos);
        if (!any && (own == null || own.length == 0)) {
            return false;
        }
        float r = ClientHooks.r(), g = ClientHooks.g(), b = ClientHooks.b(), a = ClientHooks.a();
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        Matrix3f n = last.normal();
        for (int i = 0; i < parts.size(); i++) {
            if (shapes[i] == null) {
                continue;
            }
            BlockPos p = parts.get(i);
            Vec3 off = (i == 0 ? state : level.getBlockState(p)).getOffset(level, p);
            emit(lines, m, n, shapes[i], p.getX() + off.x - cx, p.getY() + off.y - cy, p.getZ() + off.z - cz, r, g, b, a);
        }
        if (own != null) {
            emit(lines, m, n, own, pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz, r, g, b, a);
        }
        return true;
    }

    private static void emit(VertexConsumer lines, Matrix4f m, Matrix3f n, float[] s, double bx, double by, double bz,
                             float r, float g, float b, float a) {
        for (int i = 0; i + 5 < s.length; i += 6) {
            float dx = s[i + 3] - s[i], dy = s[i + 4] - s[i + 1], dz = s[i + 5] - s[i + 2];
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6f) {
                continue;
            }
            dx /= len;
            dy /= len;
            dz /= len;
            lines.vertex(m, (float) (s[i] + bx), (float) (s[i + 1] + by), (float) (s[i + 2] + bz)).color(r, g, b, a).normal(n, dx, dy, dz).endVertex();
            lines.vertex(m, (float) (s[i + 3] + bx), (float) (s[i + 4] + by), (float) (s[i + 5] + bz)).color(r, g, b, a).normal(n, dx, dy, dz).endVertex();
        }
    }

    private static List<BlockPos> parts(ClientLevel level, BlockPos pos, BlockState state) {
        List<BlockPos> out = new ArrayList<>();
        out.add(pos);
        if (!isPart(state)) {
            return out;
        }
        Block block = state.getBlock();
        Set<BlockPos> seen = new HashSet<>();
        seen.add(pos);
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(pos);
        while (!todo.isEmpty() && out.size() < MAX_PARTS) {
            BlockPos at = todo.poll();
            BlockState here = level.getBlockState(at);
            for (Direction d : Direction.values()) {
                BlockPos next = at.relative(d);
                if (!seen.add(next)) {
                    continue;
                }
                BlockState there = level.getBlockState(next);
                if (there.getBlock() == block && isPart(there) && differentPart(here, there)) {
                    out.add(next);
                    todo.add(next);
                }
            }
        }
        return out;
    }

    private static boolean isPart(BlockState s) {
        for (Property<?> p : s.getProperties()) {
            if (PART_NAMES.contains(p.getName())) {
                return true;
            }
            if (p.getName().equals("type") && s.getValue(p) instanceof net.minecraft.world.level.block.state.properties.ChestType c
                    && c != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                return true;
            }
        }
        return false;
    }

    private static boolean differentPart(BlockState a, BlockState b) {
        for (Property<?> p : a.getProperties()) {
            if ((PART_NAMES.contains(p.getName()) || p.getName().equals("type")) && b.hasProperty(p) && !a.getValue(p).equals(b.getValue(p))) {
                return true;
            }
        }
        return false;
    }

    private static float[] modelSegs(Minecraft mc, BlockState state) {
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        Cached c = MODELS.get(state);
        if (c != null && c.model == model) {
            return c.segs;
        }
        Cached fresh = new Cached(model);
        MODELS.put(state, fresh);
        List<Quad> quads = new ArrayList<>();
        try {
            RandomSource rand = RandomSource.create();
            for (Direction d : SIDES) {
                rand.setSeed(42L);
                for (BakedQuad q : model.getQuads(state, d, rand)) {
                    Quad quad = Quad.of(q);
                    if (quad != null) {
                        quads.add(quad);
                    }
                }
            }
        } catch (RuntimeException e) {
            fresh.segs = new float[0];
            return fresh.segs;
        }
        WORK.execute(() -> {
            try {
                fresh.segs = trace(quads);
            } catch (RuntimeException e) {
                fresh.segs = new float[0];
            }
        });
        return null;
    }

    static final class Quad {
        final float[] x = new float[4], y = new float[4], z = new float[4], pu = new float[4], pv = new float[4];
        float nx, ny, nz;
        int normal;
        double plane;
        boolean[] mask;
        int mx0, my0, mw, mh;

        static Quad of(BakedQuad q) {
            int[] v = q.getVertices();
            int stride = v.length / 4;
            if (stride < 6) {
                return null;
            }
            Quad o = new Quad();
            TextureAtlasSprite s = q.getSprite();
            float su = s.getU1() - s.getU0(), sv = s.getV1() - s.getV0();
            SpriteContents sc = s.contents();
            for (int i = 0; i < 4; i++) {
                o.x[i] = Float.intBitsToFloat(v[i * stride]);
                o.y[i] = Float.intBitsToFloat(v[i * stride + 1]);
                o.z[i] = Float.intBitsToFloat(v[i * stride + 2]);
                o.pu[i] = su == 0 ? 0 : (Float.intBitsToFloat(v[i * stride + 4]) - s.getU0()) / su * sc.width();
                o.pv[i] = sv == 0 ? 0 : (Float.intBitsToFloat(v[i * stride + 5]) - s.getV0()) / sv * sc.height();
            }
            o.finishShape();
            if (!o.onBlockSide()) {
                o.readMask(sc);
            }
            return o;
        }

        static Quad plain(float[] px, float[] py, float[] pz) {
            Quad o = new Quad();
            System.arraycopy(px, 0, o.x, 0, 4);
            System.arraycopy(py, 0, o.y, 0, 4);
            System.arraycopy(pz, 0, o.z, 0, 4);
            o.finishShape();
            return o;
        }

        void finishShape() {
            float ax = x[1] - x[0], ay = y[1] - y[0], az = z[1] - z[0];
            float bx = x[3] - x[0], by = y[3] - y[0], bz = z[3] - z[0];
            nx = ay * bz - az * by;
            ny = az * bx - ax * bz;
            nz = ax * by - ay * bx;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 1e-9f) {
                nx /= l;
                ny /= l;
                nz /= l;
            }
            normal = (Math.round(nx * 64) + 128) << 16 | (Math.round(ny * 64) + 128) << 8 | (Math.round(nz * 64) + 128);
            plane = nx * x[0] + ny * y[0] + nz * z[0];
        }

        boolean onBlockSide() {
            float e = 1e-3f;
            float minX = Math.min(Math.min(x[0], x[1]), Math.min(x[2], x[3])), maxX = Math.max(Math.max(x[0], x[1]), Math.max(x[2], x[3]));
            float minY = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3])), maxY = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
            float minZ = Math.min(Math.min(z[0], z[1]), Math.min(z[2], z[3])), maxZ = Math.max(Math.max(z[0], z[1]), Math.max(z[2], z[3]));
            return (maxX - minX < e && (Math.abs(minX) < e || Math.abs(minX - 1) < e))
                    || (maxY - minY < e && (Math.abs(minY) < e || Math.abs(minY - 1) < e))
                    || (maxZ - minZ < e && (Math.abs(minZ) < e || Math.abs(minZ - 1) < e));
        }

        void readMask(SpriteContents s) {
            float minU = Math.min(Math.min(pu[0], pu[1]), Math.min(pu[2], pu[3])), maxU = Math.max(Math.max(pu[0], pu[1]), Math.max(pu[2], pu[3]));
            float minV = Math.min(Math.min(pv[0], pv[1]), Math.min(pv[2], pv[3])), maxV = Math.max(Math.max(pv[0], pv[1]), Math.max(pv[2], pv[3]));
            int x0 = Math.max(0, (int) Math.floor(minU + 1e-3f)), x1 = Math.min(s.width(), (int) Math.ceil(maxU - 1e-3f));
            int y0 = Math.max(0, (int) Math.floor(minV + 1e-3f)), y1 = Math.min(s.height(), (int) Math.ceil(maxV - 1e-3f));
            if (x1 <= x0 || y1 <= y0 || (long) (x1 - x0) * (y1 - y0) > 128 * 128) {
                return;
            }
            boolean[] m = new boolean[(x1 - x0) * (y1 - y0)];
            boolean clear = false;
            for (int j = y0; j < y1; j++) {
                for (int i = x0; i < x1; i++) {
                    boolean solid = !s.isTransparent(0, i, j);
                    m[(j - y0) * (x1 - x0) + (i - x0)] = solid;
                    clear |= !solid;
                }
            }
            if (clear) {
                mask = m;
                mx0 = x0;
                my0 = y0;
                mw = x1 - x0;
                mh = y1 - y0;
            }
        }

        boolean solidAt(float a, float b) {
            if (mask == null) {
                return true;
            }
            float u = pu[0] + a * (pu[1] - pu[0]) + b * (pu[3] - pu[0]);
            float v = pv[0] + a * (pv[1] - pv[0]) + b * (pv[3] - pv[0]);
            int i = Math.min(mw - 1, Math.max(0, (int) Math.floor(u) - mx0));
            int j = Math.min(mh - 1, Math.max(0, (int) Math.floor(v) - my0));
            return mask[j * mw + i];
        }
    }

    static float[] trace(List<Quad> quads) {
        Map<Long, List<Quad>> planes = new HashMap<>();
        for (Quad q : quads) {
            long key = ((long) q.normal << 32) ^ (Math.round(q.plane * 512) & 0xFFFFFFFFL);
            planes.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }
        Edges edges = new Edges();
        for (List<Quad> group : planes.values()) {
            surface(group, edges);
        }
        return edges.finish();
    }

    private static void surface(List<Quad> group, Edges edges) {
        Quad first = group.get(0);
        float nx = first.nx, ny = first.ny, nz = first.nz;
        float ax = Math.abs(nx) < 0.9f ? 1 : 0, ay = Math.abs(nx) < 0.9f ? 0 : 1;
        float dot = ax * nx + ay * ny;
        float ux = ax - nx * dot, uy = ay - ny * dot, uz = -nz * dot;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        double d = first.plane;
        float minS = Float.MAX_VALUE, minT = Float.MAX_VALUE, maxS = -Float.MAX_VALUE, maxT = -Float.MAX_VALUE;
        int n = group.size();
        float[][] s = new float[n][4], t = new float[n][4];
        for (int k = 0; k < n; k++) {
            Quad q = group.get(k);
            for (int i = 0; i < 4; i++) {
                s[k][i] = q.x[i] * ux + q.y[i] * uy + q.z[i] * uz;
                t[k][i] = q.x[i] * vx + q.y[i] * vy + q.z[i] * vz;
                minS = Math.min(minS, s[k][i]);
                maxS = Math.max(maxS, s[k][i]);
                minT = Math.min(minT, t[k][i]);
                maxT = Math.max(maxT, t[k][i]);
            }
        }
        boolean masked = false;
        for (Quad q : group) {
            masked |= q.mask != null;
        }
        if (!masked) {
            exact(group, s, t, edges);
            return;
        }
        int w = (int) Math.ceil((maxS - minS) * RES - 1e-3f), h = (int) Math.ceil((maxT - minT) * RES - 1e-3f);
        if (w <= 0 || h <= 0 || (long) w * h > 512 * 512) {
            for (Quad q : group) {
                border(q, edges);
            }
            return;
        }
        boolean[] fill = new boolean[w * h];
        for (int k = 0; k < n; k++) {
            Quad q = group.get(k);
            float e1s = s[k][1] - s[k][0], e1t = t[k][1] - t[k][0], e2s = s[k][3] - s[k][0], e2t = t[k][3] - t[k][0];
            float det = e1s * e2t - e1t * e2s;
            if (Math.abs(det) < 1e-9f) {
                continue;
            }
            float qs0 = Math.min(Math.min(s[k][0], s[k][1]), Math.min(s[k][2], s[k][3])), qs1 = Math.max(Math.max(s[k][0], s[k][1]), Math.max(s[k][2], s[k][3]));
            float qt0 = Math.min(Math.min(t[k][0], t[k][1]), Math.min(t[k][2], t[k][3])), qt1 = Math.max(Math.max(t[k][0], t[k][1]), Math.max(t[k][2], t[k][3]));
            int i0 = Math.max(0, (int) Math.floor((qs0 - minS) * RES)), i1 = Math.min(w, (int) Math.ceil((qs1 - minS) * RES));
            int j0 = Math.max(0, (int) Math.floor((qt0 - minT) * RES)), j1 = Math.min(h, (int) Math.ceil((qt1 - minT) * RES));
            boolean hit = false;
            for (int j = j0; j < j1; j++) {
                for (int i = i0; i < i1; i++) {
                    float cs = minS + (i + 0.5f) / RES - s[k][0], ct = minT + (j + 0.5f) / RES - t[k][0];
                    float a = (cs * e2t - ct * e2s) / det, b = (e1s * ct - e1t * cs) / det;
                    if (a >= -1e-4f && a <= 1.0001f && b >= -1e-4f && b <= 1.0001f && q.solidAt(a, b)) {
                        fill[j * w + i] = true;
                        hit = true;
                    }
                }
            }
            if (!hit && q.mask == null) {
                border(q, edges);
            }
        }
        float[] p = new float[3], r = new float[3];
        for (int j = 0; j <= h; j++) {
            int start = -1;
            for (int i = 0; i <= w; i++) {
                boolean edge = i < w && (j > 0 && fill[(j - 1) * w + i]) != (j < h && fill[j * w + i]);
                if (edge && start < 0) {
                    start = i;
                } else if (!edge && start >= 0) {
                    float tt = minT + (float) j / RES;
                    point(d, nx, ny, nz, ux, uy, uz, vx, vy, vz, minS + (float) start / RES, tt, p);
                    point(d, nx, ny, nz, ux, uy, uz, vx, vy, vz, minS + (float) i / RES, tt, r);
                    edges.add(p, r);
                    start = -1;
                }
            }
        }
        for (int i = 0; i <= w; i++) {
            int start = -1;
            for (int j = 0; j <= h; j++) {
                boolean edge = j < h && (i > 0 && fill[j * w + i - 1]) != (i < w && fill[j * w + i]);
                if (edge && start < 0) {
                    start = j;
                } else if (!edge && start >= 0) {
                    float ss = minS + (float) i / RES;
                    point(d, nx, ny, nz, ux, uy, uz, vx, vy, vz, ss, minT + (float) start / RES, p);
                    point(d, nx, ny, nz, ux, uy, uz, vx, vy, vz, ss, minT + (float) j / RES, r);
                    edges.add(p, r);
                    start = -1;
                }
            }
        }
    }

    private static void exact(List<Quad> group, float[][] s, float[][] t, Edges edges) {
        int n = group.size();
        float[] det = new float[n];
        for (int k = 0; k < n; k++) {
            det[k] = (s[k][1] - s[k][0]) * (t[k][3] - t[k][0]) - (t[k][1] - t[k][0]) * (s[k][3] - s[k][0]);
        }
        float step = 0.01f;
        for (int k = 0; k < n; k++) {
            Quad q = group.get(k);
            float cs = (s[k][0] + s[k][1] + s[k][2] + s[k][3]) / 4, ct = (t[k][0] + t[k][1] + t[k][2] + t[k][3]) / 4;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                float ds = s[k][j] - s[k][i], dt = t[k][j] - t[k][i];
                float len = (float) Math.sqrt(ds * ds + dt * dt);
                if (len < 1e-6f) {
                    continue;
                }
                float os = dt / len, ot = -ds / len;
                float ms = (s[k][i] + s[k][j]) / 2, mt = (t[k][i] + t[k][j]) / 2;
                if ((cs - ms) * os + (ct - mt) * ot > 0) {
                    os = -os;
                    ot = -ot;
                }
                boolean inner = true;
                for (float f : new float[]{0.2f, 0.5f, 0.8f}) {
                    float ps = s[k][i] + ds * f + os * step, pt = t[k][i] + dt * f + ot * step;
                    boolean covered = false;
                    for (int o = 0; o < n && !covered; o++) {
                        covered = o != k && inside(s[o], t[o], det[o], ps, pt);
                    }
                    if (!covered) {
                        inner = false;
                        break;
                    }
                }
                if (!inner) {
                    edges.add(new float[]{q.x[i], q.y[i], q.z[i]}, new float[]{q.x[j], q.y[j], q.z[j]});
                }
            }
        }
    }

    private static boolean inside(float[] s, float[] t, float det, float ps, float pt) {
        if (Math.abs(det) < 1e-9f) {
            return false;
        }
        float e1s = s[1] - s[0], e1t = t[1] - t[0], e2s = s[3] - s[0], e2t = t[3] - t[0];
        float cs = ps - s[0], ct = pt - t[0];
        float a = (cs * e2t - ct * e2s) / det, b = (e1s * ct - e1t * cs) / det;
        return a >= -1e-4f && a <= 1.0001f && b >= -1e-4f && b <= 1.0001f;
    }

    private static void border(Quad q, Edges edges) {
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            edges.add(new float[]{q.x[i], q.y[i], q.z[i]}, new float[]{q.x[j], q.y[j], q.z[j]});
        }
    }

    private static void point(double d, float nx, float ny, float nz, float ux, float uy, float uz, float vx, float vy, float vz,
                              float s, float t, float[] out) {
        out[0] = (float) (nx * d) + ux * s + vx * t;
        out[1] = (float) (ny * d) + uy * s + vy * t;
        out[2] = (float) (nz * d) + uz * s + vz * t;
    }

    static final class Edges {
        private record Key(int a, int b, int c, int d, int e, int f) {}

        private final Map<Key, float[]> map = new HashMap<>();

        void add(float[] p, float[] r) {
            int a = q(p[0]), b = q(p[1]), c = q(p[2]), d = q(r[0]), e = q(r[1]), f = q(r[2]);
            if ((a == d && b == e && c == f) || map.size() >= MAX_SEGS) {
                return;
            }
            boolean swap = a > d || (a == d && (b > e || (b == e && c > f)));
            map.putIfAbsent(swap ? new Key(d, e, f, a, b, c) : new Key(a, b, c, d, e, f), new float[]{p[0], p[1], p[2], r[0], r[1], r[2]});
        }

        private static int q(float v) {
            return Math.round(v * 256);
        }

        float[] finish() {
            float[] out = new float[map.size() * 6];
            int i = 0;
            for (float[] s : map.values()) {
                System.arraycopy(s, 0, out, i, 6);
                i += 6;
            }
            return out;
        }
    }

    private static float[] machineSegs(Minecraft mc, ClientLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            machine = null;
            machineSegs = null;
            return null;
        }
        BlockEntityRenderer<BlockEntity> r = mc.getBlockEntityRenderDispatcher().getRenderer(be);
        if (r == null || FAILED.contains(r.getClass())) {
            return null;
        }
        boolean same = be == machine;
        int every = SLOW.contains(r.getClass()) ? Integer.MAX_VALUE : 10;
        if (machineBusy || (same && frame - machineFrame < every)) {
            return same ? machineSegs : null;
        }
        if (!same) {
            machineSegs = null;
        }
        Recorder rec = new Recorder();
        long t0 = System.nanoTime();
        try {
            r.render(be, mc.getFrameTime(), new PoseStack(), rec, LevelRenderer.getLightColor(level, pos), OverlayTexture.NO_OVERLAY);
        } catch (Throwable t) {
            FAILED.add(r.getClass());
            khi.pentecost.boot.Log.warn("Block outline: " + r.getClass().getName() + " could not be traced, its model is used: " + t);
            return null;
        }
        if (System.nanoTime() - t0 > 1_000_000L) {
            SLOW.add(r.getClass());
        }
        machine = be;
        machineFrame = frame;
        if (rec.quads.isEmpty()) {
            machineSegs = null;
            return null;
        }
        machineBusy = true;
        List<Quad> quads = rec.quads;
        WORK.execute(() -> {
            try {
                float[] segs = trace(quads);
                Minecraft.getInstance().execute(() -> {
                    if (machine == be) {
                        machineSegs = segs;
                    }
                    machineBusy = false;
                });
            } catch (RuntimeException e) {
                Minecraft.getInstance().execute(() -> machineBusy = false);
            }
        });
        return same ? machineSegs : null;
    }

    private static final class Recorder implements MultiBufferSource {
        final List<Quad> quads = new ArrayList<>();
        private final Map<RenderType, Sink> sinks = new HashMap<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return sinks.computeIfAbsent(type, k -> new Sink(quads, k.mode() == VertexFormat.Mode.QUADS));
        }
    }

    private static final class Sink implements VertexConsumer {
        private final List<Quad> quads;
        private final boolean quadsMode;
        private final float[] vx = new float[4], vy = new float[4], vz = new float[4];
        private int n;
        private double lx, ly, lz;

        Sink(List<Quad> quads, boolean quadsMode) {
            this.quads = quads;
            this.quadsMode = quadsMode;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            lx = x;
            ly = y;
            lz = z;
            return this;
        }

        @Override
        public void endVertex() {
            if (!quadsMode || quads.size() > MAX_SEGS) {
                return;
            }
            vx[n] = (float) lx;
            vy[n] = (float) ly;
            vz[n] = (float) lz;
            if (++n == 4) {
                n = 0;
                quads.add(Quad.plain(vx, vy, vz));
            }
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
