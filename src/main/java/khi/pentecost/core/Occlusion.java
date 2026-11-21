package khi.pentecost.core;

public final class Occlusion {
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    private Occlusion() {}

    public static boolean clear(double ax, double ay, double az, double bx, double by, double bz, Solid solid, int maxSteps) {
        int x = floor(ax), y = floor(ay), z = floor(az);
        int ex = floor(bx), ey = floor(by), ez = floor(bz);
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? (x + 1 - ax) : (ax - x)) * tdx;
        double tmy = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? (y + 1 - ay) : (ay - y)) * tdy;
        double tmz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? (z + 1 - az) : (az - z)) * tdz;
        for (int i = 0; i < maxSteps; i++) {
            if (x == ex && y == ey && z == ez) {
                return true;
            }
            if (tmx < tmy && tmx < tmz) {
                x += sx;
                tmx += tdx;
            } else if (tmy < tmz) {
                y += sy;
                tmy += tdy;
            } else {
                z += sz;
                tmz += tdz;
            }
            if (tmx > 1 && tmy > 1 && tmz > 1 && !(x == ex && y == ey && z == ez)) {
                return true;
            }
            if (!(x == ex && y == ey && z == ez) && solid.at(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    public static boolean visible(double ex, double ey, double ez, double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ, Solid solid) {
        int steps = (int) (Math.abs(ex - (minX + maxX) / 2) + Math.abs(ey - (minY + maxY) / 2) + Math.abs(ez - (minZ + maxZ) / 2)) + 8;
        if (clear(ex, ey, ez, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, solid, steps)) {
            return true;
        }
        double[] xs = {minX + 0.05, maxX - 0.05};
        double[] ys = {minY + 0.05, maxY - 0.05};
        double[] zs = {minZ + 0.05, maxZ - 0.05};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    if (clear(ex, ey, ez, x, y, z, solid, steps)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
