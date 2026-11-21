package khi.pentecost.cape;

public final class CapeArt {
    public static final int SCALE = 4;
    public static final int TEX_W = 64 * SCALE;
    public static final int TEX_H = 32 * SCALE;
    public static final int W = 10 * SCALE;
    public static final int H = 16 * SCALE;

    private static final int[][] DITH = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    private static final double SUN_X = -0.72, SUN_Y = -0.22, SUN_Z = 0.55;

    private static final int BG = 11;

    public static final class Motion {
        public double t;
        public double strafe;
        public double speed;
        public double vy;
        public boolean onGround;
    }

    public static final class State {
        double boatX = W / 2.0, boatXv, lean, hop, hopV, lastT = -1, landPulse;
        boolean wasGround = true;
        final double[] dropX = new double[24], dropY = new double[24], dropVX = new double[24], dropVY = new double[24],
                dropLife = new double[24];
        double shotAt = -99, shotX, shotY, boltAt = -99, boltX, boltY;
    }

    private final int[] px = new int[W * H];

    public int[] frame(State s, Motion m) {
        double dt = s.lastT < 0 ? 1 / 20.0 : Math.max(0, Math.min(0.2, m.t - s.lastT));
        s.lastT = m.t;
        step(s, m, dt);
        double t = m.t;
        java.util.Arrays.fill(px, 0xFF000000 | (BG << 16) | (BG << 8) | BG);
        sky(t);
        stars(t);
        shootingStar(s, t);
        farMoon(t);
        giant(t, s);
        layer(0, t, s);
        fog(HORIZON + 4, t, 0.8);
        layer(1, t, s);
        ship(s, t);
        layer(2, t, s);
        drops(s);
        layer(3, t, s);
        frameLine();
        return px;
    }

    private static void step(State s, Motion m, double dt) {
        double home = W / 2.0 + 2.5 * Math.sin(m.t * 0.35) + m.strafe * (W * 0.26);
        s.boatXv += ((home - s.boatX) * 6 - s.boatXv * 4.2) * dt;
        s.boatX += s.boatXv * dt;
        s.boatX = Math.max(10, Math.min(W - 10, s.boatX));
        double leanWant = -m.strafe * 0.7 + m.speed * 0.2 + (m.onGround ? 0 : Math.max(-0.7, Math.min(0.7, -m.vy * 1.4)));
        s.lean += (leanWant - s.lean) * (1 - Math.exp(-dt / 0.18));

        boolean jumped = s.wasGround && !m.onGround && m.vy > 0.2;
        boolean landed = !s.wasGround && m.onGround;
        if (jumped) {
            s.hopV = 24;
            splash(s, m.t, 10, 1.0);
        }
        if (landed) {
            s.landPulse = 1;
            splash(s, m.t, 14, 1.3);
        }
        s.wasGround = m.onGround;
        double lift = !m.onGround && m.vy < -0.3 ? Math.min(8, -m.vy * 5) : 0;
        s.hopV -= 70 * dt;
        s.hop += s.hopV * dt;
        if (s.hop < lift) {
            s.hop = s.hop + (lift - s.hop) * (1 - Math.exp(-dt / 0.12));
            if (s.hopV < 0) {
                s.hopV = 0;
            }
        }
        s.landPulse *= Math.exp(-dt * 3);
        for (int i = 0; i < s.dropLife.length; i++) {
            if (s.dropLife[i] <= 0) {
                continue;
            }
            s.dropLife[i] -= dt;
            s.dropVY[i] += 48 * dt;
            s.dropX[i] += s.dropVX[i] * dt;
            s.dropY[i] += s.dropVY[i] * dt;
        }
    }

    private static void splash(State s, double t, int count, double power) {
        int made = 0;
        for (int i = 0; i < s.dropLife.length && made < count; i++) {
            if (s.dropLife[i] > 0) {
                continue;
            }
            double a = frac(t * 7.3 + i * 1.7);
            double b = frac(t * 3.1 + i * 2.9);
            s.dropX[i] = s.boatX + (a - 0.5) * 12;
            s.dropY[i] = top(2, s.boatX, t) - 1;
            s.dropVX[i] = (a - 0.5) * 22 * power;
            s.dropVY[i] = -(10 + 16 * b) * power;
            s.dropLife[i] = 0.5 + 0.4 * b;
            made++;
        }
    }

    private void put(int x, int y, int v) {
        if (x < 0 || y < 0 || x >= W || y >= H) {
            return;
        }
        v = Math.max(0, Math.min(255, v));
        px[y * W + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    private void max(int x, int y, int v) {
        if (x < 0 || y < 0 || x >= W || y >= H) {
            return;
        }
        if (v > (px[y * W + x] & 0xFF)) {
            put(x, y, v);
        }
    }

    private int get(int x, int y) {
        return x < 0 || y < 0 || x >= W || y >= H ? 0 : px[y * W + x] & 0xFF;
    }

    private static double frac(double v) {
        double s = Math.sin(v * 12.9898 + 78.233) * 43758.5453;
        return s - Math.floor(s);
    }

    private static final int HORIZON = 38;

    private void sky(double t) {
        int shimmer = (int) (t * 0.5) & 3;
        for (int y = 0; y < HORIZON + 6; y++) {
            double v = Math.max(0, (y - (HORIZON - 8)) / 14.0) * 0.4;
            for (int x = 0; x < W; x++) {
                if (DITH[(y + shimmer) & 3][x & 3] / 16.0 < v * 0.5) {
                    put(x, y, 22);
                }
            }
        }
    }

    private void stars(double t) {
        for (int i = 0; i < 22; i++) {
            int sx = (int) (frac(i * 2.3) * W);
            int sy = (int) (frac(i * 4.7) * (HORIZON - 4));
            double tw = 0.5 + 0.5 * Math.sin(t * (0.6 + frac(i * 6.1) * 2.2) + i * 1.3);
            double mag = frac(i * 8.3);
            int a = (int) ((40 + 150 * mag * mag) * (0.35 + 0.65 * tw));
            max(sx, sy, a);
            if (mag > 0.88 && tw > 0.7) {
                max(sx - 1, sy, a / 3);
                max(sx + 1, sy, a / 3);
                max(sx, sy - 1, a / 3);
                max(sx, sy + 1, a / 3);
            }
        }
    }

    private void shootingStar(State s, double t) {
        if (t - s.shotAt > 6 + frac(Math.floor(t / 6)) * 9) {
            s.shotAt = t;
            s.shotX = W * (0.5 + frac(t * 1.1) * 0.5);
            s.shotY = 2 + frac(t * 2.2) * 8;
        }
        double age = t - s.shotAt;
        if (age >= 0.7) {
            return;
        }
        for (int k = 0; k < 8; k++) {
            double p = age * 46 - k;
            if (p < 0) {
                continue;
            }
            max((int) (s.shotX - p), (int) (s.shotY + p * 0.45), (int) (200 * (1 - age / 0.7) * (1 - k / 8.0)));
        }
    }

    private void farMoon(double t) {
        double cx = 32.5, cy = 9.5, r = 3.2;
        double[][] craters = {{-0.3, -0.2, 0.3}, {0.3, 0.3, 0.25}};
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                double fx = dx + 0.5 - (cx - Math.floor(cx)), fy = dy + 0.5 - (cy - Math.floor(cy));
                double d = Math.sqrt(fx * fx + fy * fy);
                int x = (int) Math.floor(cx) + dx, y = (int) Math.floor(cy) + dy;
                if (d > r) {
                    continue;
                }
                double nx = fx / r, ny = fy / r, nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                double lam = Math.max(0, Math.min(1, SUN_X * nx + SUN_Y * ny + SUN_Z * 0.6 * nz));
                double v = 16 + 190 * lam;
                for (double[] c : craters) {
                    if (Math.hypot(nx - c[0], ny - c[1]) < c[2]) {
                        v *= 0.72;
                    }
                }
                if (lam < 0.05) {
                    v = 18;
                } else if (DITH[y & 3][x & 3] < (1 - lam) * 10) {
                    v *= 0.55;
                }
                put(x, y, (int) v);
            }
        }
    }

    private void giant(double t, State s) {
        double gx = 14.5, gy = 16.5, r = 10.4;
        double tilt = 0.30, ct = Math.cos(tilt), st = Math.sin(tilt);

        double ma = t * Math.PI * 2 / 14;
        double dist = r * 1.45, flat = 0.42;
        double ex = Math.cos(ma) * dist, ey = Math.sin(ma) * dist * flat;
        double mx = gx + ex * ct - ey * st, my = gy + ex * st + ey * ct;
        double mz = Math.sin(ma) * dist * Math.sqrt(1 - flat * flat);
        boolean front = Math.sin(ma) >= 0;
        double mr = 1.8;

        double shX = Double.NaN, shY = 0;
        if (front) {
            double sx = mx - gx, sy = my - gy;
            for (int k = 0; k < 3; k++) {
                double zs = Math.sqrt(Math.max(0, r * r - sx * sx - sy * sy));
                double run = Math.max(0, mz - zs) / SUN_Z;
                sx = mx - gx - SUN_X * run;
                sy = my - gy - SUN_Y * run;
            }
            shX = sx;
            shY = sy;
        }

        if (!front) {
            orb(mx, my, mr, gx, gy, r);
        }

        if (t - s.boltAt > 2.2 + frac(Math.floor(t)) * 4) {
            s.boltAt = t;
            double ang = -0.6 + frac(t * 5.5) * 1.2;
            double rad = r * (0.35 + 0.35 * frac(t * 6.6));
            s.boltX = Math.cos(ang) * rad;
            s.boltY = Math.sin(ang) * rad * 0.9;
        }
        double since = t - s.boltAt;
        double bolt = since < 0.5 ? (since < 0.08 || (since > 0.16 && since < 0.24) ? 1.0 : 0.35) * (1 - since / 0.5) : 0;

        for (int dy = -(int) (r + 2); dy <= r + 2; dy++) {
            for (int dx = -(int) (r + 2); dx <= r + 2; dx++) {
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d <= r || d > r + 2) {
                    continue;
                }
                double lit = Math.max(0, -(dx / d * SUN_X + dy / d * SUN_Y) * -1);
                double g = (1 - (d - r) / 2) * lit * lit;
                int x = (int) gx + dx, y = (int) gy + dy;
                if (g * 16 > DITH[y & 3][x & 3] + 4) {
                    max(x, y, (int) (22 + 60 * g));
                }
            }
        }

        int ir = (int) Math.ceil(r);
        for (int dy = -ir; dy <= ir; dy++) {
            for (int dx = -ir; dx <= ir; dx++) {
                double dd = dx * dx + dy * dy;
                if (dd > r * r) {
                    continue;
                }
                int x = (int) gx + dx, y = (int) gy + dy;
                double nx = dx / r, ny = dy / r, nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                double ppx = nx * ct + ny * st;
                double ppy = -nx * st + ny * ct;
                double lat = Math.asin(Math.max(-1, Math.min(1, ppy)));
                double lon = Math.atan2(ppx, nz) + t * (0.09 + 0.05 * Math.cos(lat * 3));
                double warp = 0.12 * Math.sin(lon * 3 + lat * 9) + 0.06 * Math.sin(lon * 7 - lat * 21 + t * 0.2);
                double b = 0.5 + 0.26 * Math.sin(lat * 9 + warp * 4) + 0.14 * Math.sin(lat * 19 + 1.3 + warp * 7);
                b += 0.14 * Math.exp(-lat * lat * 28);
                b -= 0.2 * Math.pow(Math.abs(ppy), 6);
                double slon = Math.atan2(ppx, nz) + 0.35 - 0.2 * Math.sin(t * 0.08);
                slon -= Math.round(slon / (Math.PI * 2)) * Math.PI * 2;
                double ox = slon / 0.45, oy = (lat - 0.30) / 0.16;
                double od = Math.sqrt(ox * ox + oy * oy);
                if (od < 1.5) {
                    double spin = Math.atan2(oy, ox) + (1.5 - od) * 2.6 + t * 1.2;
                    double swirl = 0.5 + 0.5 * Math.sin(od * 8 - spin * 2);
                    double core = od < 1.0 ? 0.78 + 0.18 * swirl : od < 1.2 ? 0.3 : b;
                    double mix = Math.max(0, Math.min(1, 1.5 - od));
                    b = b * (1 - mix) + core * mix;
                }
                double lam = nx * SUN_X + ny * SUN_Y + nz * SUN_Z;
                double limb = 0.4 + 0.6 * Math.pow(nz, 0.5);
                double val = Math.max(0, Math.min(1.1, b)) * Math.max(0, lam) * limb;
                double q = Math.floor(val * 6 + DITH[y & 3][x & 3] / 16.0) / 6;
                double v = 12 + 215 * q;
                if (lam < 0.06) {
                    v = Math.max(v, 12 + 12 * b * (lam > -0.15 ? 1 : 0.4));
                    if (bolt > 0) {
                        double bd = Math.hypot(dx - s.boltX, dy - s.boltY);
                        double gl = bolt * Math.max(0, 1 - bd / (r * 0.25));
                        if (gl * 16 > DITH[y & 3][x & 3]) {
                            v = Math.max(v, 50 + 170 * gl);
                        }
                    }
                }
                if (dd > (r - 1.1) * (r - 1.1) && lam > 0.3) {
                    v = Math.max(v, 165 + 70 * lam);
                }
                if (!Double.isNaN(shX)) {
                    double sd = Math.hypot(dx - shX, dy - shY);
                    if (sd < mr * 1.1 - 0.4) {
                        v = Math.min(v, 12);
                    } else if (sd < mr * 1.1 + 0.6 && DITH[y & 3][x & 3] < 9) {
                        v = Math.min(v, 30);
                    }
                }
                put(x, y, (int) v);
            }
        }
        if (front) {
            orb(mx, my, mr, gx, gy, -1);
        }
    }

    private void orb(double cx, double cy, double r, double gx, double gy, double skipR) {
        int ir = (int) Math.ceil(r);
        for (int dy = -ir; dy <= ir; dy++) {
            for (int dx = -ir; dx <= ir; dx++) {
                double fx = dx + 0.5 - (cx - Math.floor(cx)), fy = dy + 0.5 - (cy - Math.floor(cy));
                if (fx * fx + fy * fy > r * r) {
                    continue;
                }
                int x = (int) Math.floor(cx) + dx, y = (int) Math.floor(cy) + dy;
                if (skipR > 0 && (x - gx) * (x - gx) + (y - gy) * (y - gy) <= skipR * skipR) {
                    continue;
                }
                double nx = fx / r, ny = fy / r, nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                double lam = Math.max(0, Math.min(1, SUN_X * nx + SUN_Y * ny + 0.45 * nz));
                double v = 14 + 200 * lam;
                if (DITH[y & 3][x & 3] < (1 - lam) * 12) {
                    v *= 0.4;
                }
                put(x, y, (int) v);
            }
        }
    }

    private static final double[] BASE = {HORIZON + 2, HORIZON + 9, HORIZON + 17, H - 2};
    private static final double[] AMP = {2.4, 3.2, 3.8, 3.4};
    private static final double[] BUMP = {10.0, 13.0, 16.0, 19.0};
    private static final double[] DRIFT = {0.8, 1.4, 2.2, 3.2};
    private static final int[] LINE = {70, 120, 205, 245};

    static double top(int k, double x, double t) {
        double u = (x + t * DRIFT[k] + k * 7.3) / BUMP[k] * Math.PI;
        double bumps = Math.sqrt(Math.abs(Math.sin(u)));
        double swell = 0.9 * Math.sin(x * 0.17 + t * (0.5 + 0.15 * k) + k * 1.9);
        return BASE[k] - AMP[k] * bumps + swell;
    }

    private void layer(int k, double t, State s) {
        int line = LINE[k];
        for (int x = 0; x < W; x++) {
            double tp = top(k, x + 0.5, t);
            int ty = (int) Math.floor(tp);
            double pulse = k == 2 ? s.landPulse * Math.max(0, 1 - Math.abs(x - s.boatX) / 12) : 0;
            for (int y = Math.max(0, ty); y < H; y++) {
                int d = y - ty;
                int v = BG;
                if (d == 0) {
                    v = (int) (line + pulse * 60);
                } else if (d <= 2 && DITH[y & 3][x & 3] < (3 - d) * 2) {
                    v = (int) (line * 0.22 + pulse * 30);
                }
                put(x, y, v);
            }
            int prev = (int) Math.floor(top(k, x - 0.5, t));
            for (int y = prev + 1; y < ty; y++) {
                put(x, y, (int) (line * 0.85));
            }
            for (int y = ty + 1; y < prev; y++) {
                put(x, y, (int) (line * 0.85));
            }
            double fold = top(k, x * 1.6 + 11, t * 1.3) + 4.5 + k;
            int fy = (int) Math.floor(fold);
            if (fy > ty + 2 && fy < H && DITH[fy & 3][x & 3] < 7) {
                put(x, fy, (int) (line * 0.38));
            }
        }
    }

    private void fog(double yc, double t, double dens) {
        for (int y = (int) yc - 2; y <= yc + 2; y++) {
            double f = 1 - Math.abs(y - yc) / 3.0;
            for (int x = 0; x < W; x++) {
                double n = 0.5 + 0.5 * Math.sin(x * 0.4 + t * 0.6 + y * 1.3) * Math.sin(x * 0.13 - t * 0.3);
                if (n * f * dens * 16 > DITH[y & 3][(x + (int) (t * 2)) & 3] + 6) {
                    max(x, y, 30);
                }
            }
        }
    }

    private static final String[] SHIP = {
            "............M......",
            ".......M....MFF....",
            "......g....WWWS....",
            ".....gWWS..WWWS....",
            "....g.WWS...M......",
            "...W...M...WWWWS...",
            "..WW.WWWWS.WWWWS...",
            ".WWW.WWWWS.WWWWS...",
            "WWWW.WWWWS.WWWWS...",
            "g......M....M......",
            ".WWWWWWWWWWWWWWWWWL",
            "..WDDPDDDPDDDPDDDS.",
            "...WDDDDDDDDDDDDS..",
            "....SSSSSSSSSSSS...",
    };

    private void ship(State s, double t) {
        int sw = SHIP[0].length(), sh = SHIP.length;
        double bx = s.boatX;
        double water = top(2, bx, t);
        double bob = 0.5 * Math.sin(t * 2.4);
        double base = water - 1.2 + bob - s.hop;
        double slope = (top(2, bx + 5, t) - top(2, bx - 5, t)) / 10;
        double lean = s.lean + slope * 1.4;
        double flick = 0.75 + 0.25 * Math.sin(t * 9) * Math.sin(t * 3.7);
        boolean flap = Math.sin(t * (s.hop > 2 ? 14 : 4)) > 0;

        for (int r = 0; r < sh; r++) {
            String row = SHIP[r];
            for (int c = 0; c < sw; c++) {
                char ch = row.charAt(c);
                int v;
                switch (ch) {
                    case 'W' -> v = 238;
                    case 'S' -> v = 150;
                    case 'M' -> v = 190;
                    case 'D' -> v = 16;
                    case 'P' -> v = (int) (110 + 110 * flick);
                    case 'L' -> v = (int) (200 + 55 * flick);
                    case 'g' -> v = 90;
                    case 'F' -> v = 230;
                    default -> v = -1;
                }
                if (v < 0) {
                    continue;
                }
                int up = sh - 1 - r;
                int rr = r;
                if (ch == 'F' && flap && c == 14) {
                    rr = r + 1;
                }
                double x = bx - sw / 2.0 + c + lean * up * 0.22;
                double y = base - (sh - 1) + rr;
                put((int) Math.round(x), (int) Math.round(y), v);
            }
        }
        double lx = bx - sw / 2.0 + sw - 1 + lean * 3 * 0.22;
        double ly = base - 3;
        double glow = flick * 0.8;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                double d = Math.hypot(dx, dy);
                if (d < 1 || d > 3.2) {
                    continue;
                }
                int x = (int) Math.round(lx) + dx, y = (int) Math.round(ly) + dy;
                double a = (1 - d / 3.2) * glow;
                if (a * 20 > DITH[y & 3][x & 3] + 3) {
                    max(x, y, (int) (50 + 120 * a));
                }
            }
        }
        if (s.hop < 1.5 && Math.abs(s.boatXv) > 2.5) {
            int dir = s.boatXv > 0 ? -1 : 1;
            for (int k = 1; k < 8; k++) {
                double wx = bx + dir * (sw / 2.0 + k);
                int wy = (int) Math.floor(top(2, wx, t)) - (k % 3 == 0 ? 1 : 0);
                if (DITH[wy & 3][(int) wx & 3] < 14 - k * 1.7) {
                    max((int) Math.round(wx), wy, 240 - k * 16);
                }
            }
        }
    }

    private void drops(State s) {
        for (int i = 0; i < s.dropLife.length; i++) {
            if (s.dropLife[i] <= 0) {
                continue;
            }
            max((int) Math.round(s.dropX[i]), (int) Math.round(s.dropY[i]), (int) (130 + 120 * Math.min(1, s.dropLife[i] * 2)));
        }
    }

    private void frameLine() {
        for (int x = 0; x < W; x++) {
            put(x, 0, get(x, 0) / 2);
            put(x, H - 1, get(x, H - 1) / 2);
        }
        for (int y = 0; y < H; y++) {
            put(0, y, get(0, y) / 2);
            put(W - 1, y, get(W - 1, y) / 2);
        }
    }

    public void texture(int[] tex, State s, Motion m) {
        int[] f = frame(s, m);
        java.util.Arrays.fill(tex, 0);
        int cloth = 0xFF0C0C0C;
        fill(tex, 0, 0, 22 * SCALE, 17 * SCALE, cloth);
        for (int y = 0; y < H; y++) {
            System.arraycopy(f, y * W, tex, (SCALE + y) * TEX_W + SCALE, W);
            int left = dim(f[y * W + 1]), right = dim(f[y * W + W - 2]);
            for (int k = 0; k < SCALE; k++) {
                tex[(SCALE + y) * TEX_W + k] = left;
                tex[(SCALE + y) * TEX_W + 11 * SCALE + k] = right;
            }
        }
        for (int x = 0; x < W; x++) {
            int top = dim(f[W + x]), bottom = dim(f[(H - 2) * W + x]);
            for (int k = 0; k < SCALE; k++) {
                tex[k * TEX_W + SCALE + x] = top;
                tex[k * TEX_W + 11 * SCALE + x] = bottom;
            }
        }
        for (int y = 0; y < 16 * SCALE; y += 2) {
            for (int x = 0; x < 10 * SCALE; x++) {
                if (((x + y) & 7) == 0) {
                    tex[(SCALE + y) * TEX_W + 12 * SCALE + x] = 0xFF141414;
                }
            }
        }
    }

    private static int dim(int argb) {
        int v = (argb & 0xFF) * 6 / 10;
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    private static void fill(int[] tex, int x0, int y0, int w, int h, int c) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                tex[y * TEX_W + x] = c;
            }
        }
    }
}
