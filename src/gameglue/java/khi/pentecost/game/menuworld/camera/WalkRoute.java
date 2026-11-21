package khi.pentecost.game.menuworld.camera;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

public final class WalkRoute {
    private WalkRoute() {}

    public enum Mode { TUNNEL, STREET }

    public record Route(List<Vec> eye, List<Vec> look, double length) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double SPACING = 2.0;
    private static final int MIN_CELLS = 50;

    public static boolean standing(Voxels v, int x, int y, int z) {
        return v.solid(x, y - 1, z) && !v.solid(x, y, z) && !v.solid(x, y + 1, z) && !v.solid(x, y + 2, z);
    }

    public static Route plan(Voxels v, Mode mode, int cx, int cy, int cz, int rx, int ry, int rz, Random rnd, double targetLen) {
        Grid g = new Grid(v, mode, cx, cy, cz, rx, ry, rz);
        if (g.count < MIN_CELLS) {
            return null;
        }
        int[] comp = g.components();
        int best = g.bestComponent(comp, cx, cy, cz);
        if (best < 0) {
            return null;
        }
        List<Integer> members = new ArrayList<>();
        for (int i = 0; i < g.count; i++) {
            if (comp[i] == best) {
                members.add(i);
            }
        }
        int s0 = members.get(rnd.nextInt(members.size()));
        int a = g.farthest(s0, comp, best).cell;
        Sweep sweep = g.farthest(a, comp, best);
        List<Integer> path = g.path(sweep, a);
        if (path.size() < 24) {
            return null;
        }
        int want = (int) Math.max(24, targetLen);
        if (path.size() > want + 10) {
            int off = rnd.nextInt(path.size() - want + 1);
            path = new ArrayList<>(path.subList(off, off + want));
        }
        if (rnd.nextBoolean()) {
            Collections.reverse(path);
        }
        return g.camera(path, mode == Mode.TUNNEL ? 1.6 : 2.4, mode == Mode.TUNNEL ? 8 : 10);
    }

    private record Sweep(double[] dist, int[] parent, int cell) {}

    private static final class Grid {
        final Voxels v;
        final Mode mode;
        final int x0, y0, z0, w, d, h;
        final Map<Long, Integer> index = new HashMap<>();
        final List<int[]> cells = new ArrayList<>();
        int count;
        float[] penalty;

        Grid(Voxels v, Mode mode, int cx, int cy, int cz, int rx, int ry, int rz) {
            this.v = v;
            this.mode = mode;
            this.x0 = cx - rx;
            this.z0 = cz - rz;
            this.y0 = Math.max(v.minY() + 1, cy - ry);
            int y1 = Math.min(v.maxY() - 4, cy + ry);
            this.w = 2 * rx + 1;
            this.d = 2 * rz + 1;
            this.h = Math.max(1, y1 - y0 + 1);
            collect();
            penalty = new float[count];
            java.util.Arrays.fill(penalty, Float.NaN);
        }

        long key(int x, int y, int z) {
            return ((long) (x - x0) * d + (z - z0)) * h + (y - y0);
        }

        boolean inBox(int x, int y, int z) {
            return x >= x0 && x < x0 + w && z >= z0 && z < z0 + d && y >= y0 && y < y0 + h;
        }

        int find(int x, int y, int z) {
            if (!inBox(x, y, z)) {
                return -1;
            }
            Integer i = index.get(key(x, y, z));
            return i == null ? -1 : i;
        }

        private void collect() {
            List<int[]> all = new ArrayList<>();
            for (int x = x0; x < x0 + w; x++) {
                for (int z = z0; z < z0 + d; z++) {
                    for (int y = y0; y < y0 + h; y++) {
                        if (!standing(v, x, y, z)) {
                            continue;
                        }
                        if (mode == Mode.STREET && !openSky(x, y, z)) {
                            continue;
                        }
                        all.add(new int[] {x, y, z});
                    }
                }
            }
            if (mode == Mode.STREET && !all.isEmpty()) {
                int[] ys = new int[all.size()];
                for (int i = 0; i < ys.length; i++) {
                    ys[i] = all.get(i)[1];
                }
                java.util.Arrays.sort(ys);
                int level = ys[ys.length * 3 / 10];
                all.removeIf(c -> c[1] > level + 4);
            }
            for (int[] c : all) {
                index.put(key(c[0], c[1], c[2]), cells.size());
                cells.add(c);
            }
            count = cells.size();
        }

        private boolean openSky(int x, int y, int z) {
            int top = Math.min(v.maxY() - 1, y + 22);
            for (int yy = y + 3; yy <= top; yy++) {
                if (v.solid(x, yy, z)) {
                    return false;
                }
            }
            return true;
        }

        int[] neighbours(int i) {
            int[] c = cells.get(i);
            int[] out = new int[12];
            int n = 0;
            for (int[] dir : DIRS) {
                int nx = c[0] + dir[0];
                int nz = c[2] + dir[1];
                for (int dy = -1; dy <= 1; dy++) {
                    int j = find(nx, c[1] + dy, nz);
                    if (j < 0) {
                        continue;
                    }
                    if (dy == 1 && v.solid(c[0], c[1] + 3, c[2])) {
                        continue;
                    }
                    out[n++] = j;
                }
            }
            return java.util.Arrays.copyOf(out, n);
        }

        float cost(int i) {
            if (!Float.isNaN(penalty[i])) {
                return penalty[i];
            }
            int[] c = cells.get(i);
            double p = 0;
            for (int[] dir : DIRS) {
                if (v.solid(c[0] + dir[0], c[1] + 1, c[2] + dir[1])) {
                    p += mode == Mode.STREET ? 1.6 : 1.2;
                } else if (v.solid(c[0] + 2 * dir[0], c[1] + 1, c[2] + 2 * dir[1])) {
                    p += mode == Mode.STREET ? 0.9 : 0.3;
                }
            }
            penalty[i] = (float) p;
            return penalty[i];
        }

        int[] components() {
            int[] comp = new int[count];
            java.util.Arrays.fill(comp, -1);
            int next = 0;
            for (int s = 0; s < count; s++) {
                if (comp[s] >= 0) {
                    continue;
                }
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(s);
                comp[s] = next;
                while (!q.isEmpty()) {
                    int i = q.poll();
                    for (int j : neighbours(i)) {
                        if (comp[j] < 0) {
                            comp[j] = next;
                            q.add(j);
                        }
                    }
                }
                next++;
            }
            return comp;
        }

        int bestComponent(int[] comp, int cx, int cy, int cz) {
            int n = 0;
            for (int c : comp) {
                n = Math.max(n, c + 1);
            }
            int[] size = new int[n];
            double[] near = new double[n];
            java.util.Arrays.fill(near, 1e18);
            for (int i = 0; i < count; i++) {
                int[] c = cells.get(i);
                size[comp[i]]++;
                double dx = c[0] - cx, dy = (c[1] - cy) * 0.5, dz = c[2] - cz;
                near[comp[i]] = Math.min(near[comp[i]], Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            int best = -1;
            double bestScore = 0;
            for (int k = 0; k < n; k++) {
                if (size[k] < MIN_CELLS) {
                    continue;
                }
                double score = size[k] * Math.exp(-near[k] / 24.0);
                if (score > bestScore) {
                    bestScore = score;
                    best = k;
                }
            }
            return best;
        }

        Sweep farthest(int src, int[] comp, int which) {
            double[] dist = new double[count];
            int[] parent = new int[count];
            java.util.Arrays.fill(dist, Double.MAX_VALUE);
            java.util.Arrays.fill(parent, -1);
            PriorityQueue<double[]> pq = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
            dist[src] = 0;
            pq.add(new double[] {0, src});
            int far = src;
            while (!pq.isEmpty()) {
                double[] top = pq.poll();
                int i = (int) top[1];
                if (top[0] > dist[i]) {
                    continue;
                }
                if (dist[i] > dist[far]) {
                    far = i;
                }
                for (int j : neighbours(i)) {
                    if (comp[j] != which) {
                        continue;
                    }
                    int[] a = cells.get(i);
                    int[] b = cells.get(j);
                    double step = 1 + cost(j) + (a[1] != b[1] ? 0.6 : 0);
                    double nd = dist[i] + step;
                    if (nd < dist[j]) {
                        dist[j] = nd;
                        parent[j] = i;
                        pq.add(new double[] {nd, j});
                    }
                }
            }
            return new Sweep(dist, parent, far);
        }

        List<Integer> path(Sweep s, int from) {
            List<Integer> out = new ArrayList<>();
            for (int i = s.cell(); i >= 0; i = s.parent()[i]) {
                out.add(i);
            }
            Collections.reverse(out);
            if (!out.isEmpty() && out.get(0) != from) {
                Collections.reverse(out);
            }
            return out;
        }

        Route camera(List<Integer> path, double eyeHeight, double lead) {
            int n = path.size();
            double[] rx = new double[n], ry = new double[n], rz = new double[n];
            for (int i = 0; i < n; i++) {
                int[] c = cells.get(path.get(i));
                rx[i] = c[0] + 0.5;
                ry[i] = c[1] + eyeHeight;
                rz[i] = c[2] + 0.5;
            }
            double[] sx = average(rx, 4), sy = average(ry, 5), sz = average(rz, 4);
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 5 && !free(sx[i], sy[i], sz[i]); k++) {
                    sx[i] = (sx[i] + rx[i]) / 2;
                    sy[i] = (sy[i] + ry[i]) / 2;
                    sz[i] = (sz[i] + rz[i]) / 2;
                }
            }
            List<Vec> eye = new ArrayList<>();
            double carry = 0;
            eye.add(new Vec(sx[0], sy[0], sz[0]));
            double total = 0;
            for (int i = 1; i < n; i++) {
                Vec p = new Vec(sx[i - 1], sy[i - 1], sz[i - 1]);
                Vec q = new Vec(sx[i], sy[i], sz[i]);
                double seg = p.dist(q);
                total += seg;
                double at = SPACING - carry;
                while (at <= seg) {
                    eye.add(Vec.lerp(p, q, at / seg));
                    at += SPACING;
                }
                carry = seg - (at - SPACING);
            }
            Vec last = new Vec(sx[n - 1], sy[n - 1], sz[n - 1]);
            if (eye.get(eye.size() - 1).dist(last) > 0.5) {
                eye.add(last);
            }
            int m = eye.size();
            int ahead = (int) Math.ceil(lead / SPACING);
            Vec tail = eye.get(m - 1).sub(eye.get(Math.max(0, m - 4))).normalized();
            List<Vec> look = new ArrayList<>();
            for (int k = 0; k < m; k++) {
                int j = k + ahead;
                Vec target = j < m ? eye.get(j) : eye.get(m - 1).add(tail.scale((j - m + 1) * SPACING));
                look.add(target.add(0, -0.3, 0));
            }
            return new Route(eye, look, total);
        }

        private static double[] average(double[] a, int radius) {
            int n = a.length;
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = 0;
                int cnt = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = i + k;
                    if (j >= 0 && j < n) {
                        sum += a[j];
                        cnt++;
                    }
                }
                out[i] = sum / cnt;
            }
            return out;
        }

        private boolean free(double x, double y, double z) {
            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    for (int dy = -1; dy <= 1; dy += 2) {
                        if (v.solid((int) Math.floor(x + dx * 0.3), (int) Math.floor(y + dy * 0.3), (int) Math.floor(z + dz * 0.3))) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }
}
