package khi.pentecost.game;

/**
 * Pentecost's mark: a triangle turning in three dimensions, a dot at each corner, the corners joined by its edges.
 * It is worked out on a small grid of square cells, so whatever size it is drawn at, the edges stay blocky.
 *
 * <p>The same shape is drawn by the manager (manager/Blocks.cs), the installer (installer/Pixels.cs) and
 * .tools/make_icon.py. Change them together.</p>
 */
final class TriMark {

    private TriMark() {}

    /** Brightness from 0 to 255 for each cell, row by row. Free running, so it never repeats the same way twice. */
    static int[] free(int w, int h, double t) {
        int[] buf = new int[w * h];
        double eye = 3.1 + 0.55 * Math.sin(t * 0.47);
        draw(buf, w, h, t * 0.91, t * 1.37, t * 0.58, eye, 70, 0.40, false);
        draw(buf, w, h, (t - 0.11) * 0.91, (t - 0.11) * 1.37, (t - 0.11) * 0.58, eye, 130, 0.40, false);
        draw(buf, w, h, t * 0.91, t * 1.37, t * 0.58, eye, 255, 0.40, false);
        return buf;
    }

    /** The same, but over a whole number of turns, so a run of frames joins back onto itself. */
    static int[] loop(int w, int h, int frame, int frames) {
        int[] buf = new int[w * h];
        double f = (double) frame / frames;
        double a = 2 * Math.PI * f;
        double eye = 3.1 + 0.5 * Math.sin(a);
        draw(buf, w, h, a, a * 2, a * 3, eye, 255, 0.40, false);
        return buf;
    }

    /** One fixed view, for an icon: held at an angle where all three corners stand apart. */
    static int[] still(int w, int h) {
        int[] buf = new int[w * h];
        draw(buf, w, h, 0.95, 0.62, 0.30, 3.0, 255, 0.52, true);
        return buf;
    }

    private static void draw(int[] buf, int w, int h, double ax, double ay, double az, double eye,
                             int level, double reachPart, boolean solid) {
        double[] px = new double[3];
        double[] py = new double[3];
        double[] pd = new double[3];
        double midX = w / 2.0 - 0.5;
        double midY = h / 2.0 - 0.5;
        double reach = Math.min(w, h) * reachPart;

        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 2 + i * 2 * Math.PI / 3;
            double x = Math.cos(a);
            double y = Math.sin(a);
            double z = 0;

            // Turn about x, then y, then z.
            double y1 = y * Math.cos(ax) - z * Math.sin(ax);
            double z1 = y * Math.sin(ax) + z * Math.cos(ax);
            double x2 = x * Math.cos(ay) + z1 * Math.sin(ay);
            double z2 = -x * Math.sin(ay) + z1 * Math.cos(ay);
            double x3 = x2 * Math.cos(az) - y1 * Math.sin(az);
            double y3 = x2 * Math.sin(az) + y1 * Math.cos(az);

            double k = 2.2 / (eye - z2);
            px[i] = midX + x3 * k * reach;
            py[i] = midY - y3 * k * reach;
            pd[i] = k;
        }

        if (solid) {
            double cx = (px[0] + px[1] + px[2]) / 3;
            double cy = (py[0] + py[1] + py[2]) / 3;
            for (int i = 0; i < 3; i++) {
                px[i] += midX - cx;
                py[i] += midY - cy;
            }
        }

        // The three edges. A corner further away draws fainter, which is what makes it read as solid.
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            int v = solid ? level : dim(level, (pd[i] + pd[j]) / 2);
            line(buf, w, h, px[i], py[i], px[j], py[j], v);
        }

        // A dot at each corner, bigger when it is nearer.
        for (int i = 0; i < 3; i++) {
            int r = Math.min(w, h) >= 26 ? (pd[i] > 0.95 ? 2 : 1) : (pd[i] > 0.95 ? 1 : 0);
            int v = solid ? 255 : dim(level, pd[i] + 0.25);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dy) == 2) {
                        continue;
                    }
                    put(buf, w, h, (int) Math.round(px[i]) + dx, (int) Math.round(py[i]) + dy, v);
                }
            }
        }
    }

    private static int dim(int level, double depth) {
        double f = Math.max(0.33, Math.min(1.0, depth * 0.78));
        return (int) Math.max(0, Math.min(255, level * f));
    }

    private static void put(int[] buf, int w, int h, int x, int y, int v) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }
        int i = y * w + x;
        if (buf[i] < v) {
            buf[i] = v;
        }
    }

    /** A straight line of cells, the plain way, so the staircase stays visible. */
    private static void line(int[] buf, int w, int h, double x0, double y0, double x1, double y1, int v) {
        int steps = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        if (steps <= 0) {
            put(buf, w, h, (int) Math.round(x0), (int) Math.round(y0), v);
            return;
        }
        for (int s = 0; s <= steps; s++) {
            double f = (double) s / steps;
            put(buf, w, h, (int) Math.round(x0 + (x1 - x0) * f), (int) Math.round(y0 + (y1 - y0) * f), v);
        }
    }
}
