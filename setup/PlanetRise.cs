using System.Diagnostics;
using System.Windows.Media.Imaging;

namespace Pentecost.Setup;

public enum SceneMode { Idle, Working, Done, Failed }

public sealed class PlanetRise : FrameworkElement
{
    const int P = 3;
    const int Bg = 9;
    const double Tilt = 0.22;
    const double RingE = -0.12;
    const double Frame = 1 / 30.0;
    static readonly int[,] B = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    readonly Stopwatch clock = Stopwatch.StartNew();
    readonly Random rng = new Random(11);
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = new int[0];
    int[] sky = new int[0];
    float[] post = new float[0];
    double last = -1, drawn = -1;
    SceneMode mode = SceneMode.Idle;
    double modeAt, sunP, failP;
    double boltAt = -99, boltNext = 2, boltU, boltV;
    double cx, cy, R;

    int[] pIdx = new int[0];
    float[] pNx = new float[0], pNv = new float[0], pNz = new float[0], pLat = new float[0], pLon = new float[0], pLimb = new float[0], pPolar = new float[0];
    int[] rIdx = new int[0];
    float[] rF = new float[0], rU = new float[0], rV = new float[0];
    int[] aIdx = new int[0];
    float[] aK = new float[0], aDx = new float[0], aDy = new float[0];

    public double Progress { get; set; }

    public SceneMode Mode
    {
        get => mode;
        set
        {
            if (mode == value) return;
            mode = value;
            modeAt = Now;
        }
    }

    double Now => clock.Elapsed.TotalSeconds;

    public PlanetRise()
    {
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        IsHitTestVisible = false;
        Loaded += (_, _) => CompositionTarget.Rendering += Tick;
        Unloaded += (_, _) => CompositionTarget.Rendering -= Tick;
    }

    void Tick(object? s, EventArgs e)
    {
        if (Now - drawn >= Frame - 0.002) InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 8 || h < 8) return;
        var c = (int)Math.Ceiling(w / P);
        var r = (int)Math.Ceiling(h / P);
        if (bitmap == null || c != cols || r != rows)
        {
            cols = c;
            rows = r;
            bitmap = new WriteableBitmap(cols, rows, 96, 96, PixelFormats.Pbgra32, null);
            buf = new int[cols * rows];
            Build();
        }
        var t = Now;
        if (t - drawn >= Frame - 0.002 || drawn < 0)
        {
            drawn = t;
            var dt = last < 0 ? Frame : Math.Min(0.1, Math.Max(0, t - last));
            last = t;
            Draw(t, dt);
            bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        }
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));
    }

    static int Gray(int v)
    {
        v = v < 0 ? 0 : v > 255 ? 255 : v;
        return unchecked((int)0xFF000000) | (v << 16) | (v << 8) | v;
    }

    void Max(int x, int y, int v)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        if ((buf[y * cols + x] & 0xFF) < v) buf[y * cols + x] = Gray(v);
    }

    static double H(double v)
    {
        var s = Math.Sin(v * 12.9898 + 78.233) * 43758.5453;
        return s - Math.Floor(s);
    }

    static double Ease(double f) => f <= 0 ? 0 : f >= 1 ? 1 : f * f * (3 - 2 * f);

    void Rot(double dx, double dy, out double u, out double v)
    {
        double ct = Math.Cos(Tilt), st = Math.Sin(Tilt);
        u = dx * ct + dy * st;
        v = -dx * st + dy * ct;
    }

    void Unrot(double u, double v, out double dx, out double dy)
    {
        double ct = Math.Cos(Tilt), st = Math.Sin(Tilt);
        dx = u * ct - v * st;
        dy = u * st + v * ct;
    }

    void Build()
    {
        R = rows * 0.86;
        cx = cols * 0.85;
        cy = rows * 1.08;

        sky = new int[cols * rows];
        for (var y = 0; y < rows; y++)
            for (var x = 0; x < cols; x++)
            {
                var d = (x * 0.55 - y + rows * 0.15) / (rows * 0.22);
                var band = Math.Exp(-d * d);
                var v = Bg;
                if (band > 0.05)
                {
                    var n = 0.5 + 0.3 * Math.Sin(x * 0.045 + y * 0.03) * Math.Sin(x * 0.017 - y * 0.06) + 0.2 * Math.Sin(x * 0.13 + y * 0.11);
                    var a = band * n;
                    if (a * 16 > B[y & 3, x & 3] + 5) v = a > 0.62 ? 26 : 17;
                }
                sky[y * cols + x] = Gray(v);
            }

        var pi = new List<int>();
        var px = new List<float>(); var pv = new List<float>(); var pz = new List<float>();
        var pl = new List<float>(); var po = new List<float>(); var pm = new List<float>(); var pp = new List<float>();
        var ri = new List<int>(); var rf = new List<float>(); var ru = new List<float>(); var rv = new List<float>();
        var ai = new List<int>(); var ak = new List<float>(); var ax = new List<float>(); var ay = new List<float>();
        const double thick = 14;
        var se = Math.Sin(RingE);
        for (var y = 0; y < rows; y++)
        {
            var dy = y + 0.5 - cy;
            for (var x = 0; x < cols; x++)
            {
                var dx = x + 0.5 - cx;
                var d2 = dx * dx + dy * dy;
                var i = y * cols + x;
                if (d2 <= R * R)
                {
                    Rot(dx, dy, out var u, out var v);
                    double nx = u / R, nv = v / R, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - nv * nv));
                    pi.Add(i); px.Add((float)nx); pv.Add((float)nv); pz.Add((float)nz);
                    pl.Add((float)Math.Asin(Math.Max(-1, Math.Min(1, nv))));
                    po.Add((float)Math.Atan2(nx, nz));
                    pm.Add((float)(0.45 + 0.55 * Math.Pow(nz, 0.45)));
                    pp.Add((float)(0.25 * Math.Pow(Math.Abs(nv), 4)));
                    continue;
                }
                var d = Math.Sqrt(d2);
                if (d <= R + thick)
                {
                    ai.Add(i); ak.Add((float)(1 - (d - R) / thick)); ax.Add((float)(dx / d)); ay.Add((float)(dy / d));
                }
                {
                    Rot(dx, dy, out var u, out var v);
                    var r = Math.Sqrt(u * u + (v / se) * (v / se));
                    var f = (r - R * 1.28) / (R * 0.62);
                    if (f >= 0 && f <= 1) { ri.Add(i); rf.Add((float)f); ru.Add((float)u); rv.Add((float)v); }
                }
            }
        }
        pIdx = pi.ToArray(); pNx = px.ToArray(); pNv = pv.ToArray(); pNz = pz.ToArray(); pLat = pl.ToArray(); pLon = po.ToArray(); pLimb = pm.ToArray(); pPolar = pp.ToArray();
        rIdx = ri.ToArray(); rF = rf.ToArray(); rU = ru.ToArray(); rV = rv.ToArray();
        aIdx = ai.ToArray(); aK = ak.ToArray(); aDx = ax.ToArray(); aDy = ay.ToArray();

        post = new float[cols * rows];
        double hx = cols / 2.0, hy = rows / 2.0;
        var edge = cols * 0.5;
        var low = rows * 0.82;
        for (var y = 0; y < rows; y++)
            for (var x = 0; x < cols; x++)
            {
                double nx = (x - hx) / hx, ny = (y - hy) / hy;
                var rr = Math.Sqrt(nx * nx * 0.8 + ny * ny);
                var keep = 1 - 0.4 * Math.Max(0, rr - 0.7) / 0.6;
                if (x < edge)
                {
                    var s = Math.Pow(1 - x / edge, 1.3) * 0.86;
                    keep *= s * 16 > B[y & 3, x & 3] + 2 ? 1 - s : 1 - s * 0.6;
                }
                if (y > low && x < cols * 0.62)
                {
                    var s = Math.Min(1, (y - low) / (rows * 0.12)) * 0.7 * Math.Min(1, (cols * 0.62 - x) / (cols * 0.12));
                    keep *= 1 - s;
                }
                post[y * cols + x] = (float)Math.Max(0.08, keep);
            }
    }

    void Draw(double t, double dt)
    {
        var target = mode == SceneMode.Done ? 0.62 : mode == SceneMode.Working ? 0.1 + 0.42 * Math.Max(0, Math.Min(1, Progress)) : 0.0;
        sunP += (target - sunP) * (1 - Math.Exp(-dt / (mode == SceneMode.Done ? 1.6 : 0.9)));
        failP += ((mode == SceneMode.Failed ? 1.0 : 0.0) - failP) * (1 - Math.Exp(-dt / 0.5));
        var rise = mode == SceneMode.Done ? 1 - Math.Pow(1 - Math.Max(0, Math.Min(1, (t - modeAt - 0.2) / 3.5)), 3) : 0;

        double lx = -0.3, ly = -0.92, lz = -0.5 + 0.95 * sunP;
        var ln = Math.Sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ln; ly /= ln; lz /= ln;

        Array.Copy(sky, buf, buf.Length);
        Stars(t);
        Moon(t, lx, ly, lz, false);
        Planet(t, lx, ly, lz);
        Ring(t, lx, ly, lz);
        Moon(t, lx, ly, lz, true);
        Atmosphere(lx, ly, rise);
        Flare(t, lx, ly, rise);
        Dust(t);
        if (failP > 0.05) Glitch(t);
        Post(t);
    }

    void Stars(double t)
    {
        var n = cols * rows / 95;
        for (var i = 0; i < n; i++)
        {
            var speed = 0.06 + (i % 3) * 0.09;
            var x = (int)(((H(i * 1.7) * cols - t * speed) % cols + cols) % cols);
            var y = (int)(H(i * 3.1) * rows);
            double dx = x - cx, dy = y - cy;
            if (dx * dx + dy * dy < (R + 3) * (R + 3)) continue;
            var tw = 0.5 + 0.5 * Math.Sin(t * (0.4 + H(i * 5.3) * 2) + i);
            var mag = H(i * 7.7);
            var a = (int)((22 + 170 * mag * mag * mag) * (0.45 + 0.55 * tw) * (1 - failP * 0.5));
            Max(x, y, a);
            if (mag > 0.94 && tw > 0.6) { Max(x - 1, y, a / 3); Max(x + 1, y, a / 3); Max(x, y - 1, a / 3); Max(x, y + 1, a / 3); }
        }
    }

    (double U, double V, double Z, double R) MoonAt(double t)
    {
        var a = t * Math.PI * 2 / 120 + 2.4;
        var d = R * 1.45;
        const double e = 0.2;
        return (Math.Cos(a) * d, Math.Sin(a) * d * Math.Sin(e), Math.Sin(a) * d * Math.Cos(e), R * 0.07);
    }

    void Planet(double t, double lx, double ly, double lz)
    {
        Rot(lx, ly, out var lu, out var lv);
        var moon = MoonAt(t);
        var night = 1 - failP * 0.5;
        if (t - boltAt > boltNext)
        {
            boltAt = t;
            boltNext = (mode == SceneMode.Failed ? 0.4 : 1.8) + rng.NextDouble() * 2.5;
            var a = Math.PI + 0.9 + rng.NextDouble() * 1.2;
            var rr = R * (0.55 + rng.NextDouble() * 0.35);
            boltU = Math.Cos(a) * rr;
            boltV = Math.Sin(a) * rr * 0.9;
        }
        var ba = t - boltAt;
        var bolt = ba < 0.5 ? (ba < 0.07 || (ba > 0.14 && ba < 0.22) ? 1.0 : 0.3) * (1 - ba / 0.5) : 0;
        var spin = t * 0.01;
        var drift = t * 0.03;

        for (var k = 0; k < pIdx.Length; k++)
        {
            var i = pIdx[k];
            int x = i % cols, y = i / cols;
            double nx = pNx[k], nv = pNv[k], nz = pNz[k];
            var lam = nx * lu + nv * lv + nz * lz;
            var lat = pLat[k];
            var lon = pLon[k] + spin;
            var latp = lat + 0.014 * Math.Sin(lon * 13 + lat * 38 + drift) + 0.006 * Math.Sin(lon * 29 - lat * 66 - drift * 1.6);
            var b = 0.52 + 0.15 * Math.Sin(latp * 15) + 0.1 * Math.Sin(latp * 34 + 1.1) + 0.06 * Math.Sin(latp * 73 + 2.3);
            b += 0.08 * Math.Exp(-latp * latp * 40) - pPolar[k];
            b += 0.025 * Math.Sin(lon * 55 + latp * 140);

            var shadow = 1.0;
            var pz = nz * R;
            double wu = moon.U - nx * R, wv = moon.V - nv * R, wz = moon.Z - pz;
            var s = wu * lu + wv * lv + wz * lz;
            if (s > 0)
            {
                var dd = wu * wu + wv * wv + wz * wz - s * s;
                var mr = moon.R * 1.05;
                if (dd < mr * mr) shadow = Math.Sqrt(dd) > mr - 1 && B[y & 3, x & 3] < 8 ? 0.5 : 0.08;
            }

            var diffuse = Math.Max(0, lam);
            diffuse *= 1.15 - 0.15 * diffuse;
            var val = Math.Max(0, b) * diffuse * pLimb[k] * shadow;
            var rimZone = Math.Max(0, 1 - nz / 0.2);
            val += rimZone * rimZone * Math.Max(0, nx * lu + nv * lv) * (0.3 + 0.5 * (1 - sunP));
            var q = Math.Floor(val * 8 + B[y & 3, x & 3] / 16.0) / 8;
            var g = 11 + 225 * q;
            if (lam < 0.03)
            {
                g = Math.Max(g, (8 + 8 * b * nz) * night);
                if (bolt > 0)
                {
                    var bd = Math.Sqrt((nx * R - boltU) * (nx * R - boltU) + (nv * R - boltV) * (nv * R - boltV));
                    var gl = bolt * Math.Max(0, 1 - bd / (R * 0.06)) * (0.6 + 0.4 * failP);
                    if (gl * 16 > B[y & 3, x & 3]) g = Math.Max(g, 40 + 150 * gl);
                }
            }
            buf[i] = Gray((int)g);
        }
    }

    static double RingDensity(double f)
    {
        if (f < 0.03) return 0.25;
        if (f < 0.46) return 0.62 + 0.12 * Math.Sin(f * 60);
        if (f < 0.52) return 0;
        if (f < 0.86) return 0.38 + 0.08 * Math.Sin(f * 45);
        if (f < 0.93) return 0;
        if (f < 0.95) return 0.55;
        return 0;
    }

    void Ring(double t, double lx, double ly, double lz)
    {
        Rot(lx, ly, out var lu, out var lv);
        double se = Math.Sin(RingE), ce = Math.Cos(RingE);
        var light = (0.55 + 0.45 * sunP) * (1 - failP * 0.4);
        for (var k = 0; k < rIdx.Length; k++)
        {
            var dens = RingDensity(rF[k]);
            if (dens <= 0) continue;
            var i = rIdx[k];
            int x = i % cols, y = i / cols;
            double u = rU[k], v = rV[k];
            var z = v * ce / se;
            var bq = u * lu + v * lv + z * lz;
            var cq = u * u + v * v + z * z - R * R;
            var disc = bq * bq - cq;
            var shaded = disc > 0 && -bq - Math.Sqrt(disc) > 0;
            var lit = light * (shaded ? 0.15 : 1);
            var f = rF[k];
            var edge = f < 0.012 || Math.Abs(f - 0.46) < 0.008 || Math.Abs(f - 0.52) < 0.008 || Math.Abs(f - 0.86) < 0.008;
            if (edge) { buf[i] = Gray((int)(28 + 160 * lit)); continue; }
            if (dens * 16 <= B[y & 3, x & 3]) continue;
            buf[i] = Gray((int)(20 + 150 * dens * lit));
        }
    }

    void Moon(double t, double lx, double ly, double lz, bool frontPass)
    {
        var m = MoonAt(t);
        var front = m.Z > 0;
        if (front != frontPass) return;
        Unrot(m.U, m.V, out var mx, out var my);
        mx += cx;
        my += cy;
        Rot(lx, ly, out var lu, out var lv);
        var ir = (int)Math.Ceiling(m.R);
        for (var dy = -ir; dy <= ir; dy++)
            for (var dx = -ir; dx <= ir; dx++)
            {
                if (dx * dx + dy * dy > m.R * m.R) continue;
                int x = (int)mx + dx, y = (int)my + dy;
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                if (!front && (x - cx) * (x - cx) + (y - cy) * (y - cy) < R * R) continue;
                Rot(dx, dy, out var u, out var v);
                double nx = u / m.R, nv = v / m.R, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - nv * nv));
                var lam = nx * lu + nv * lv + nz * lz;
                var val = Math.Max(0, lam) * (0.85 + 0.15 * Math.Sin(nx * 9 + 1) * Math.Sin(nv * 7 + 2));
                val += Math.Max(0, 1 - nz / 0.3) * Math.Max(0, nx * lu + nv * lv) * (1 - sunP) * 0.7;
                var q = Math.Floor(val * 6 + B[y & 3, x & 3] / 16.0) / 6;
                buf[y * cols + x] = Gray((int)Math.Max(13, 12 + 210 * q));
            }
    }

    void Atmosphere(double lx, double ly, double rise)
    {
        var sl = Math.Sqrt(lx * lx + ly * ly);
        double sx = lx / sl, sy = ly / sl;
        var back = 1 - sunP;
        var dim = 1 - failP * 0.6;
        for (var k = 0; k < aIdx.Length; k++)
        {
            var i = aIdx[k];
            var facing = Math.Max(0, aDx[k] * sx + aDy[k] * sy);
            var kk = aK[k];
            var glow = kk * kk * (0.08 + Math.Pow(facing, 2.4 - 1.2 * rise) * (0.45 + 0.7 * back + 0.6 * rise)) * dim;
            int x = i % cols, y = i / cols;
            if (glow * 18 > B[y & 3, x & 3] + 1)
            {
                var v = (int)(18 + 140 * Math.Min(1, glow));
                if ((buf[i] & 0xFF) < v) buf[i] = Gray(v);
            }
        }
    }

    void Flare(double t, double lx, double ly, double rise)
    {
        var power = Math.Max(sunP * 0.22 * (1 - failP), rise * 0.85);
        if (power < 0.02) return;
        var sl = Math.Sqrt(lx * lx + ly * ly);
        double ux = lx / sl, uy = ly / sl;
        var fx = cx + ux * (R + 0.5);
        var fy = cy + uy * (R + 0.5);
        var breathe = 0.95 + 0.05 * Math.Sin(t * 1.4);
        var bloom = 6 + 18 * power;
        for (var dy = -(int)bloom; dy <= bloom; dy++)
            for (var dx = -(int)bloom; dx <= bloom; dx++)
            {
                var d = Math.Sqrt(dx * dx + dy * dy);
                if (d > bloom) continue;
                var a = Math.Pow(1 - d / bloom, 3.2) * power * breathe;
                int x = (int)fx + dx, y = (int)fy + dy;
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                if (a * 20 > B[y & 3, x & 3]) Max(x, y, (int)(30 + 225 * Math.Min(1, a * 1.5)));
            }
        var core = 0.6 + 1.6 * power;
        for (var dy = -3; dy <= 3; dy++)
            for (var dx = -3; dx <= 3; dx++)
                if (dx * dx + dy * dy <= core * core) Max((int)fx + dx, (int)fy + dy, 255);
        if (rise < 0.05) return;
        var streak = cols * 0.12 * rise;
        for (var i = 2; i < streak; i++)
        {
            var a = Math.Pow(1 - i / streak, 2.6) * rise * breathe;
            var v = (int)(40 + 200 * a);
            Max((int)fx + i, (int)fy, v);
            Max((int)fx - i, (int)fy, v);
        }
    }

    void Dust(double t)
    {
        for (var i = 0; i < 36; i++)
        {
            var sp = 1.2 + H(i * 2.1) * 3;
            var x = (int)(((H(i * 1.9) * cols - t * sp) % cols + cols) % cols);
            var y = (int)((H(i * 4.3) * rows + Math.Sin(t * 0.3 + i) * 3 + rows) % rows);
            Max(x, y, (int)(34 + 60 * H(i * 6.1) * (0.6 + 0.4 * Math.Sin(t * 1.7 + i))));
        }
    }

    void Glitch(double t)
    {
        if ((t * 3.1) % 1 >= 0.22 * failP) return;
        var r = new Random((int)(t * 18));
        var row = new int[cols];
        for (var k = 0; k < 4; k++)
        {
            var y0 = r.Next(0, rows);
            var hgt = 1 + r.Next(0, 3);
            var shift = r.Next(-8, 9);
            for (var y = y0; y < Math.Min(rows, y0 + hgt); y++)
            {
                Array.Copy(buf, y * cols, row, 0, cols);
                for (var x = 0; x < cols; x++)
                {
                    var sx = x - shift;
                    buf[y * cols + x] = sx >= 0 && sx < cols ? row[sx] : Gray(Bg);
                }
            }
        }
    }

    void Post(double t)
    {
        var frame = (int)(t * 24);
        for (var i = 0; i < buf.Length; i++)
        {
            var v = buf[i] & 0xFF;
            var g = v * post[i];
            if (g < 36 && H(i * 0.0137 + frame * 3.1) > 0.988) g += 6;
            buf[i] = Gray((int)g);
        }
    }
}
