using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public class CloudSea : FrameworkElement
{
    const int P = 4;

    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    sealed class Layer
    {
        public double[] Edge = Array.Empty<double>();
        public double[] Folds = Array.Empty<double>();
        public (double At, double Up, int Len)[] Wisps = Array.Empty<(double, double, int)>();
        public int Period;
        public double Base, Scale, Speed, SwellA, SwellL, SwellW, Phase;
        public byte Line, Fold, Dot;
    }

    sealed class Flash { public double Start, Life, Power, X; }
    sealed class Bolt { public double Start, Life; public readonly List<(int X, int Y)> Path = new(); }
    sealed class Gull { public double X, Y, Vx, Phase; public bool Scared; }

    Layer[] layers;
    bool bare;
    public string BareBg { get; set; } = "Side";
    public double BareTop { get; set; } = 0.12;
    public bool Big
    {
        get => big;
        set
        {
            big = value;
            if (!value) return;
            bare = true;
            layers = new[]
            {
                MakeLayer(seed: 11, period: 3000, scale: 22, speed: 4, swellA: 3, swellL: 560, swellW: 0.30, line: 60, dot: 14),
                MakeLayer(seed: 23, period: 3400, scale: 32, speed: 7, swellA: 4, swellL: 700, swellW: 0.38, line: 100, dot: 16),
                MakeLayer(seed: 37, period: 3800, scale: 44, speed: 11, swellA: 6, swellL: 840, swellW: 0.46, line: 150, dot: 19),
                MakeLayer(seed: 53, period: 4400, scale: 58, speed: 16, swellA: 8, swellL: 1000, swellW: 0.54, line: 210, dot: 23),
            };
        }
    }
    bool big;
    public bool Bare
    {
        get => bare;
        set
        {
            bare = value;
            if (!value) return;
            layers = new[]
            {
                MakeLayer(seed: 61, period: 2200, scale: 26, speed: 3, swellA: 2, swellL: 360, swellW: 0.25, line: 50, dot: 11),
                MakeLayer(seed: 67, period: 2600, scale: 36, speed: 5, swellA: 3, swellL: 440, swellW: 0.3, line: 85, dot: 13),
                MakeLayer(seed: 71, period: 3000, scale: 48, speed: 8, swellA: 4, swellL: 520, swellW: 0.36, line: 130, dot: 15),
            };
        }
    }
    readonly DateTime start = DateTime.UtcNow;
    double lastT;
    bool running;

    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();
    int[] edge = Array.Empty<int>();
    int bg = unchecked((int)0xFF0B0B0C);

    readonly Random rng = new();
    readonly List<Flash> flashes = new();
    readonly List<Bolt> bolts = new();
    readonly List<Gull> gulls = new();
    double nextFlash = 3, nextBolt = 12, nextStar = 4, ringAt = -99, rodHit = -99, kick;
    (double X, double Y, double Vx, double Vy, double Life) star = (0, 0, 0, 0, 0);
    int spireX, spireTop, rodTopY;
    Rect bellBox = Rect.Empty;

    double Now => (DateTime.UtcNow - start).TotalSeconds;

    public double SeaTop { get; set; }
    double top0, span;
    int TopC => (int)(top0 / P);
    int SpanC => Math.Max(1, (int)(span / P));

    public CloudSea()
    {
        ClipToBounds = true;
        Focusable = false;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        layers = new[]
        {
            MakeLayer(seed: 11, period: 3000, scale: 30, speed: 5,  swellA: 4,  swellL: 560,  swellW: 0.30, line: 52,  dot: 13),
            MakeLayer(seed: 23, period: 3400, scale: 46, speed: 9,  swellA: 6,  swellL: 700,  swellW: 0.38, line: 92,  dot: 16),
            MakeLayer(seed: 37, period: 3800, scale: 62, speed: 14, swellA: 9,  swellL: 840,  swellW: 0.46, line: 140, dot: 19),
            MakeLayer(seed: 53, period: 4400, scale: 86, speed: 22, swellA: 12, swellL: 1000, swellW: 0.54, line: 200, dot: 23),
        };
        for (var i = 0; i < 7; i++) gulls.Add(new Gull { X = rng.NextDouble(), Y = 0.12 + rng.NextDouble() * 0.2, Vx = 0.012 + rng.NextDouble() * 0.01, Phase = rng.NextDouble() * 6 });
        Loaded += (_, _) =>
        {
            if (TryFindResource(bare ? BareBg : "Bg") is SolidColorBrush b)
                bg = unchecked((int)(0xFF000000u | ((uint)b.Color.R << 16) | ((uint)b.Color.G << 8) | b.Color.B));
            Start();
        };
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
    }

    void Start()
    {
        if (running || !IsVisible) return;
        if (!Settings.Motion) { InvalidateVisual(); return; }
        running = true;
        Anim.Add(Tick);
    }

    void Stop()
    {
        if (!running) return;
        running = false;
        Anim.Remove(Tick);
    }

    void Tick(object? sender, EventArgs e) => InvalidateVisual();

    static int White(byte a) => (a << 24) | (a << 16) | (a << 8) | a;
    static int W(int a) { a = Math.Clamp(a, 0, 255); return (a << 24) | (a << 16) | (a << 8) | a; }
    static double Frac(double a, double b) { var v = Math.Sin(a * 12.9898 + b * 78.233) * 43758.5453; return v - Math.Floor(v); }
    static double Ease(double v) { v = Math.Clamp(v, 0, 1); return v * v * (3 - 2 * v); }

    static Layer MakeLayer(int seed, int period, double scale, double speed, double swellA, double swellL, double swellW, byte line, byte dot)
    {
        var rnd = new Random(seed);
        var wisps = new (double, double, int)[period / 150];
        for (var i = 0; i < wisps.Length; i++)
            wisps[i] = (rnd.NextDouble() * period, 2 + rnd.Next(6), 2 + rnd.Next(5));
        return new Layer
        {
            Edge = Billows(period, scale, rnd, 0.85, 0.55),
            Folds = Billows(period, scale * 0.5, rnd, 1.9, 1.6),
            Wisps = wisps,
            Period = period, Scale = scale, Speed = speed, SwellA = swellA, SwellL = swellL, SwellW = swellW,
            Phase = rnd.NextDouble() * Math.PI * 2,
            Line = line, Fold = (byte)(line * 0.55), Dot = dot,
        };
    }

    static double[] Billows(int period, double scale, Random rnd, double gap, double gapSpread)
    {
        var up = new double[period];
        void Round(double cx, double r, double top)
        {
            var cy = top - r;
            for (var dx = -(int)Math.Ceiling(r); dx <= (int)Math.Ceiling(r); dx++)
            {
                var h = r * r - dx * dx;
                if (h <= 0) continue;
                var y = cy + Math.Sqrt(h);
                if (y <= 0) continue;
                var p = (((int)Math.Round(cx) + dx) % period + period) % period;
                if (y > up[p]) up[p] = y;
            }
        }
        var x = rnd.NextDouble() * scale;
        while (x < period - scale * 0.5)
        {
            var r = scale * (0.70 + rnd.NextDouble() * 0.75);
            var top = r * (0.45 + rnd.NextDouble() * 0.45);
            Round(x, r, top);
            var cy = top - r;
            var small = 2 + rnd.Next(3);
            for (var k = 0; k < small; k++)
            {
                var a = (rnd.NextDouble() * 2 - 1) * 1.05;
                var sr = r * (0.26 + rnd.NextDouble() * 0.24);
                Round(x + Math.Sin(a) * r, sr, cy + Math.Cos(a) * r + sr * 0.42);
            }
            x += r * (gap + rnd.NextDouble() * gapSpread);
        }
        return up;
    }

    static double Read(double[] profile, int period, double p)
    {
        p = (p % period + period) % period;
        var i = (int)p;
        var f = p - i;
        var a = profile[i];
        var b = profile[(i + 1) % period];
        return a + (b - a) * f;
    }

    double Swell(Layer l, double x, double t) =>
        l.SwellA * Math.Sin(2 * Math.PI * x / l.SwellL - t * l.SwellW + l.Phase)
        + l.SwellA * 0.45 * Math.Sin(2 * Math.PI * x / (l.SwellL * 0.53) + t * l.SwellW * 0.7 + l.Phase * 1.7)
        + Ripple(x, t) * l.Scale / 60;

    double ringPx;

    double Ripple(double x, double t)
    {
        var a = t - ringAt;
        if (a < 0 || a > 6) return 0;
        var d = Math.Abs(x - ringPx) - a * 240;
        var env = Math.Exp(-d * d / (90.0 * 90.0)) * Ease(a / 0.5) * (1 - a / 6);
        return Math.Sin(d * 0.055) * 9 * env;
    }

    double Surface(Layer l, double x, double t) => l.Base - Read(l.Edge, l.Period, x - l.Speed * t) + Swell(l, x, t);
    int Crest(Layer l, int x, double t) => (int)Math.Floor(Surface(l, (x + 0.5) * P, t) / P);

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 4 || h < 4) return;
        var t = Now;
        var dt = Math.Min(0.1, Math.Max(0, t - lastT));
        lastT = t;

        var c = Math.Max(1, (int)Math.Ceiling(w / P));
        var r = Math.Max(1, (int)Math.Ceiling(h / P));
        if (bitmap == null || c != cols || r != rows)
        {
            cols = c;
            rows = r;
            bitmap = new WriteableBitmap(cols, rows, 96, 96, PixelFormats.Pbgra32, null);
            buf = new int[cols * rows];
            edge = new int[cols];
        }
        Array.Clear(buf);

        if (bare)
        {
            top0 = h * (big ? BareTop : 0.1); span = h - top0;
            double[] bat = big ? new[] { 0.0, 0.2, 0.42, 0.7 } : new[] { 0.0, 0.14, 0.3 };
            for (var i = 0; i < layers.Length; i++) layers[i].Base = top0 + span * bat[i] + layers[i].Scale * 0.35;
            for (var i = 0; i < layers.Length; i++)
            {
                DrawLayer(layers[i], t, 0, 0);
                if (i < layers.Length - 1) Fog(TopC + SpanC * (bat[i] + bat[i + 1]) * 0.5, SpanC * 0.05, 16 + i * 2, t, 4 + i * 2, 1.2 + i, 0.45);
            }
            bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
            dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));
            return;
        }
        top0 = SeaTop > 0 ? Math.Min(SeaTop, h * 0.72) : h * 0.3;
        span = Math.Max(40, h - top0);
        double[] at = { 0.12, 0.36, 0.62, 0.92 };
        for (var i = 0; i < layers.Length; i++) layers[i].Base = top0 + span * at[i] + layers[i].Scale * 0.35;
        kick *= Math.Exp(-dt * 0.8);

        Step(w, h, t, dt);
        Events(t);
        double glow = 0, glowX = 0;
        foreach (var f in flashes) { var i = Flicker(t - f.Start, f.Life) * f.Power; if (i > glow) { glow = i; glowX = f.X; } }
        foreach (var b in bolts) glow = Math.Max(glow, Flicker(t - b.Start, b.Life) * 0.6);

        Sky(t, glow);
        DrawLayer(layers[0], t, glowX, glow * 0.6);
        if (far) DrawVoyager(h, t);
        Fog(TopC + SpanC * 0.30, SpanC * 0.06, 22, t, 5, 1.7, 0.5);
        DrawLayer(layers[1], t, glowX, glow * 0.8);
        Spire(t, glow);
        if (!far) DrawVoyager(h, t);
        DrawLayer(layers[2], t, glowX, glow);
        DrawSpray();
        Fog(TopC + SpanC * 0.80, SpanC * 0.05, 26, t, 8, 4.2, 0.4);
        DrawLayer(layers[3], t, glowX, glow * 0.7);
        Bolts(t);
        Rings(t);
        Gulls(t, dt);

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));
    }

    void Put(int x, int y, int v)
    {
        if (x >= 0 && x < cols && y >= 0 && y < rows) buf[y * cols + x] = v;
    }

    void Max(int x, int y, int a)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        var i = y * cols + x;
        if (((buf[i] >> 24) & 0xFF) < a) buf[i] = W(a);
    }

    void Line(int x0, int y0, int x1, int y1, int v)
    {
        int dx = Math.Abs(x1 - x0), dy = -Math.Abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (var n = 0; n < 4000; n++)
        {
            Put(x0, y0, v);
            if (x0 == x1 && y0 == y1) return;
            var e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    static double Flicker(double a, double life)
    {
        if (a < 0 || a > life) return 0;
        var f = a / life;
        if (f < 0.10) return 1;
        if (f < 0.20) return 0.12;
        if (f < 0.32) return 0.8;
        if (f < 0.44) return 0.08;
        if (f < 0.58) return 0.55;
        return 0.55 * (1 - (f - 0.58) / 0.42);
    }

    int MoonX => (int)(cols * 0.34);
    int PlanetY => TopC - (int)(PlanetR * 0.1);
    int PlanetR => Math.Clamp((int)(SpanC * 0.14), 10, 26);

    const double SunX = -0.72, SunY = -0.22, SunZ = 0.55;

    void Planet(double t)
    {
        int cx = MoonX, cy = PlanetY, R = PlanetR;
        const double tilt = -0.23;
        double ct = Math.Cos(tilt), st = Math.Sin(tilt);
        var pulse = t - ringAt;

        (double Dist, double Period, double Phase, double Size, double Flat)[] moons =
        {
            (1.12, 19, 0.4, 2.0, 0.75),
            (2.75, 46, 0.0, 1.1, 0.21),
            (3.4, 71, 2.6, 2.9, 0.3),
            (4.1, 113, 4.9, 1.6, 0.25),
        };
        var at = new (double X, double Y, double Size, bool Front, double Z)[moons.Length];
        for (var i = 0; i < moons.Length; i++)
        {
            var m = moons[i];
            var a = t * Math.PI * 2 / m.Period + m.Phase;
            double ex = Math.Cos(a) * R * m.Dist, ey = Math.Sin(a) * R * m.Dist * m.Flat;
            var z = Math.Sin(a) * R * m.Dist * Math.Sqrt(1 - m.Flat * m.Flat);
            at[i] = (cx + ex * ct - ey * st, cy + ex * st + ey * ct, m.Size, Math.Sin(a) >= 0, z);
        }

        var shade = new List<(double X, double Y, double R)>();
        foreach (var m in at)
        {
            if (!m.Front) continue;
            double sx = m.X - cx, sy = m.Y - cy;
            for (var k = 0; k < 3; k++)
            {
                var zs = Math.Sqrt(Math.Max(0, R * R - sx * sx - sy * sy));
                if (m.Z <= zs && k == 0) { sx = double.NaN; break; }
                var run = (m.Z - zs) / SunZ;
                sx = m.X - cx - SunX * run;
                sy = m.Y - cy - SunY * run;
            }
            if (double.IsNaN(sx) || sx * sx + sy * sy > (R + m.Size) * (R + m.Size)) continue;
            shade.Add((sx, sy, m.Size * 1.15));
        }

        void RingPass(bool back)
        {
            var steps = (int)(R * 2.4 * Math.PI * 3);
            for (var k = 0; k < steps; k++)
            {
                var th = k * Math.PI * 2 / steps;
                var sn = Math.Sin(th);
                if (back != sn < 0) continue;
                for (var rr = R * 1.3; rr <= R * 2.35; rr += 0.5)
                {
                    var f = (rr - R * 1.3) / (R * 1.05);
                    if (f > 0.62 && f < 0.70) continue;
                    var dens = 0.45 + 0.35 * Math.Sin(f * 31) * Math.Sin(f * 7 + 1) + (f < 0.62 ? 0.2 : 0);
                    var ex = Math.Cos(th) * rr;
                    var ey = sn * rr * 0.21;
                    var x = (int)Math.Round(cx + ex * ct - ey * st);
                    var y = (int)Math.Round(cy + ex * st + ey * ct);
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                    var behind = back && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= R * R;
                    if (behind) continue;
                    var lit = 0.6 + 0.4 * Math.Cos(th - (Math.PI - 0.4));
                    var ez = sn * rr;
                    var along = -SunX * ex - SunZ * ez;
                    var across = Math.Abs(SunZ * ex - SunX * ez) / Math.Sqrt(SunX * SunX + SunZ * SunZ);
                    var inShadow = along > 0 && across < R * 0.97;
                    var dark = inShadow ? 0.22 : 1.0;
                    var shimmer = inShadow ? 0 : 22 * Math.Sin(t * 0.7 + th * 7 + f * 19);
                    var wave = 0.0;
                    if (pulse >= 0 && pulse < 4.5)
                    {
                        var d = ((th - pulse * 1.9) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
                        wave = Math.Max(0, 1 - d / 0.9) * Ease(pulse / 0.6) * (1 - pulse / 4.5) * 0.7;
                    }
                    var rimEdge = rr < R * 1.3 + 0.6 || rr > R * 2.35 - 0.6 || Math.Abs(f - 0.62) < 0.02 || Math.Abs(f - 0.70) < 0.02;
                    if (rimEdge) { Put(x, y, W((int)((150 * lit + 40) * dark + wave * 70))); continue; }
                    if (dens * 13 * (inShadow ? 0.55 : 1) <= Bayer[y & 3, x & 3] + 2) continue;
                    Put(x, y, W((int)(((60 + 110 * dens) * lit + shimmer) * dark + wave * 140)));
                }
            }
        }

        for (var i = 0; i < at.Length; i++)
        {
            var m = at[i];
            if (m.Front) continue;
            if ((m.X - cx) * (m.X - cx) + (m.Y - cy) * (m.Y - cy) <= (R + m.Size) * (R + m.Size)) continue;
            Orb(m.X, m.Y, m.Size, SunX, SunY, 190, moons[i].Phase, t);
        }
        RingPass(true);
        for (var dy = -R; dy <= R; dy++)
            for (var dx = -R; dx <= R; dx++)
            {
                var d2 = dx * dx + dy * dy;
                if (d2 > R * R) continue;
                int x = cx + dx, y = cy + dy;
                double nx = dx / (double)R, ny = dy / (double)R, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - ny * ny));
                var lam = Math.Clamp(SunX * nx + SunY * ny + SunZ * nz, 0, 1);
                var v = -dx * st + dy * ct;
                var u = dx * ct + dy * st;
                var band = 0.5 + 0.5 * Math.Sin(v / R * 10 + Math.Sin(u / R * 2.5 + t * 0.12) * 0.9);
                var storm = Math.Exp(-(Math.Pow((u + R * 0.28) / (R * 0.18), 2) + Math.Pow((v + R * 0.32) / (R * 0.1), 2)));
                var ringShadow = v > R * 0.16 && v < R * 0.30 ? 0.45 : 1;
                var val = (30 + 175 * lam) * (0.72 + 0.28 * band) * ringShadow + storm * 60 * lam;
                if (Bayer[y & 3, x & 3] < (1 - lam) * 11) val *= 0.45;
                if (d2 > (R - 1.2) * (R - 1.2) && lam > 0.25) val = 225;
                foreach (var sh in shade)
                {
                    var sd = Math.Sqrt((dx - sh.X) * (dx - sh.X) + (dy - sh.Y) * (dy - sh.Y));
                    if (sd < sh.R - 0.6) val = Math.Min(val, 12);
                    else if (sd < sh.R + 0.6 && Bayer[y & 3, x & 3] < 9) val = Math.Min(val, 26);
                }
                Put(x, y, W((int)val));
            }
        RingPass(false);
        for (var i = 0; i < at.Length; i++)
        {
            var m = at[i];
            if (!m.Front) continue;
            Orb(m.X, m.Y, m.Size, SunX, SunY, 190, moons[i].Phase, t);
        }
        for (var k = 0; k < 3; k++)
        {
            var a = t * 0.3 + k * 2.1;
            var ax = (int)Math.Round(cx + Math.Cos(a) * R * 0.5);
            var ay = cy - R - 1 - k;
            if (Math.Sin(t * 1.3 + k) > 0.2) Max(ax, ay, 40 + k * 10);
        }
    }

    void Sky(double t, double glow)
    {
        var shimmer = (int)(t * 0.5) & 3;
        for (var y = 0; y < rows; y++)
        {
            var v = Math.Pow(1 - y / (double)rows, 1.6) * 0.30 + glow * 0.08;
            var a = W((int)(12 + glow * 20));
            for (var x = 0; x < cols; x++)
                if (Bayer[(y + shimmer) & 3, (x + (shimmer >> 1)) & 3] / 16.0 < v * 0.5) buf[y * cols + x] = a;
        }
        for (var i = 0; i < cols * rows / 240; i++)
        {
            var sx = (int)(Frac(i, 1.7) * cols);
            var sy = (int)(Frac(i, 3.1) * (TopC + SpanC * 0.3));
            var tw = 0.5 + 0.5 * Math.Sin(t * (0.5 + Frac(i, 5.3) * 2) + i);
            var a = (int)(30 + 140 * tw * Frac(i, 7.7));
            Put(sx, sy, W(a));
            if (a > 150 && Frac(i, 9.1) > 0.8) { Max(sx - 1, sy, a / 3); Max(sx + 1, sy, a / 3); Max(sx, sy - 1, a / 3); Max(sx, sy + 1, a / 3); }
        }
        if (star.Life > 0)
        {
            for (var k = 0; k < 14; k++)
            {
                var x = (int)(star.X - star.Vx * k * 0.012);
                var y = (int)(star.Y - star.Vy * k * 0.012);
                Max(x, y, (int)(240 * star.Life * (1 - k / 14.0)));
            }
        }
        Distant(t);
        HomeMoon(t);
        Planet(t);
    }

    void Orb(double cx, double cy, double r, double lx, double ly, int bright, double seed, double t)
    {
        var ir = (int)Math.Ceiling(r);
        for (var dy = -ir; dy <= ir; dy++)
            for (var dx = -ir; dx <= ir; dx++)
            {
                double fx = dx + 0.5 - (cx - Math.Floor(cx)), fy = dy + 0.5 - (cy - Math.Floor(cy));
                var d2 = fx * fx + fy * fy;
                if (d2 > r * r) continue;
                int x = (int)Math.Floor(cx) + dx, y = (int)Math.Floor(cy) + dy;
                double nx = fx / r, ny = fy / r, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - ny * ny));
                var lam = Math.Clamp(lx * nx + ly * ny + 0.45 * nz, 0, 1);
                var tex = 0.8 + 0.2 * Math.Sin(nx * 7 + seed * 3 + Math.Sin(ny * 5 + seed) * 2);
                var val = 14 + bright * lam * tex;
                if (Bayer[y & 3, x & 3] < (1 - lam) * 12) val *= 0.4;
                Put(x, y, W((int)val));
            }
    }

    void Distant(double t)
    {
        var top = Math.Max(8, TopC);
        (double X, double Y, double R, int B, bool Ring)[] worlds =
        {
            (0.30, 0.13, 3.4, 150, true),
            (0.55, 0.19, 2.2, 120, false),
            (0.67, 0.07, 1.5, 100, false),
            (0.90, 0.74, 2.6, 130, false),
            (0.75, 0.70, 1.5, 90, false),
        };
        for (var i = 0; i < worlds.Length; i++)
        {
            var w = worlds[i];
            var cx = w.X * cols + Math.Sin(t * 0.01 + i) * 1.5;
            var cy = w.Y * top;
            if (w.Ring)
                for (var a = 0; a < 40; a++)
                {
                    var th = a * Math.PI * 2 / 40;
                    if (Math.Sin(th) < 0) continue;
                    Max((int)Math.Round(cx + Math.Cos(th) * w.R * 2.1), (int)Math.Round(cy + Math.Sin(th) * w.R * 0.45 - Math.Cos(th) * 0.6), 70);
                }
            Orb(cx, cy, w.R, SunX, SunY, w.B, i * 1.7, t);
            if (w.Ring)
                for (var a = 0; a < 40; a++)
                {
                    var th = a * Math.PI * 2 / 40;
                    if (Math.Sin(th) >= 0) continue;
                    Max((int)Math.Round(cx + Math.Cos(th) * w.R * 2.1), (int)Math.Round(cy + Math.Sin(th) * w.R * 0.45 - Math.Cos(th) * 0.6), 90);
                }
            if (i == 3)
            {
                var a = t * Math.PI * 2 / 30;
                Max((int)Math.Round(cx + Math.Cos(a) * 6), (int)Math.Round(cy + Math.Sin(a) * 2), Math.Sin(a) < 0 && Math.Abs(Math.Cos(a)) < 0.45 ? 0 : 110);
            }
        }
    }

    void HomeMoon(double t)
    {
        var top = Math.Max(10, TopC);
        var r = Math.Clamp(PlanetR * 0.4, 5, 10);
        var cx = cols * 0.66;
        var cy = Math.Max(r + 3, top * 0.42);
        var ir = (int)Math.Ceiling(r);
        (double X, double Y, double R)[] craters = { (-0.3, -0.25, 0.22), (0.25, 0.2, 0.16), (-0.05, 0.45, 0.12), (0.4, -0.35, 0.1) };
        for (var dy = -ir - 1; dy <= ir + 1; dy++)
            for (var dx = -ir - 1; dx <= ir + 1; dx++)
            {
                var d = Math.Sqrt(dx * dx + dy * dy);
                int x = (int)cx + dx, y = (int)cy + dy;
                if (d > r)
                {
                    if (d < r + 1.6 && dx < 0 && Bayer[y & 3, x & 3] < 4) Max(x, y, 26);
                    continue;
                }
                double nx = dx / r, ny = dy / r, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - ny * ny));
                var lam = Math.Clamp(SunX * nx + SunY * ny + SunZ * 0.6 * nz, 0, 1);
                var val = 18 + 200 * lam;
                foreach (var c in craters)
                {
                    var cd = Math.Sqrt((nx - c.X) * (nx - c.X) + (ny - c.Y) * (ny - c.Y));
                    if (cd < c.R) val *= cd > c.R * 0.65 && nx > c.X ? 1.15 : 0.72;
                }
                if (lam < 0.05) val = 18 + 10 * (1 - d / r) + 6;
                else if (Bayer[y & 3, x & 3] < (1 - lam) * 10) val *= 0.55;
                Put(x, y, W((int)val));
            }
    }

    void DrawLayer(Layer l, double t, double glowX, double glow)
    {
        for (var x = 0; x < cols; x++)
            edge[x] = (int)Math.Floor(Surface(l, (x + 0.5) * P, t) / P);

        var fade = Math.Max(3, l.Scale / P * 0.6);
        var mx = MoonX;
        for (var x = 0; x < cols; x++)
        {
            var from = Math.Max(0, edge[x] + 1);
            var gx = glow > 0 ? Math.Max(0, 1 - Math.Abs(x - glowX) / 40.0) * glow : 0;
            for (var y = from; y < rows; y++)
            {
                var k = y - edge[x];
                var v = bg;
                var g = gx > 0 ? gx * Math.Max(0, 1 - k / 18.0) : 0;
                if (k <= fade || g > 0.03)
                {
                    var dens = (k <= fade ? 0.36 * (1 - k / fade) : 0) + 0.04 * Math.Sin(x * 0.5 + y * 0.3 - t * 1.2) + g * 0.7;
                    if (dens * 16 > Bayer[y & 3, x & 3]) v = W((int)(l.Dot + g * 170));
                }
                buf[y * cols + x] = v;
            }
        }

        var foldLine = White(l.Fold);
        var depth = l.Scale * 0.62;
        var prev = int.MinValue;
        for (var x = 0; x < cols; x++)
        {
            var sx = (x + 0.5) * P;
            var rise = Read(l.Folds, l.Period, sx - l.Speed * t);
            if (rise < 1.5) { prev = int.MinValue; continue; }
            var fy = (int)Math.Floor((l.Base + depth - rise + Swell(l, sx, t)) / P);
            if (fy <= edge[x] + 2 || fy >= rows) { prev = int.MinValue; continue; }
            Put(x, fy, foldLine);
            if (Bayer[(fy + 1) & 3, x & 3] < 5) Put(x, fy + 1, W(l.Fold / 3));
            if (prev != int.MinValue && Math.Abs(fy - prev) > 1 && Math.Abs(fy - prev) < 6)
            {
                if (prev < fy) for (var y = prev + 1; y < fy; y++) Put(x, y, foldLine);
                else for (var y = fy + 1; y < prev; y++) Put(x - 1, y, foldLine);
            }
            prev = fy;
        }

        for (var x = 0; x < cols; x++)
        {
            var moon = Math.Max(0, 1 - Math.Abs(x - mx) / (cols * 0.16));
            var gx = glow > 0 ? Math.Max(0, 1 - Math.Abs(x - glowX) / 40.0) * glow : 0;
            var line = W((int)Math.Max(l.Line * (1 + moon * 0.6), gx * 255));
            Put(x, edge[x], line);
            if (moon > 0.3 && edge[x] < edge[Math.Max(0, x - 1)] && Bayer[edge[x] & 3, x & 3] < 6) Put(x, edge[x] + 1, W((int)(l.Line * 0.5 * moon)));
            if (x == 0) continue;
            var a = edge[x - 1];
            var b = edge[x];
            if (a < b - 1) for (var y = a + 1; y < b; y++) Put(x, y, line);
            else if (a > b + 1) for (var y = b + 1; y < a; y++) Put(x - 1, y, line);
        }

        var wisp = White((byte)(l.Line * 0.5));
        foreach (var (atp, up, len) in l.Wisps)
        {
            var sx = ((atp + l.Speed * 1.25 * t) % l.Period + l.Period) % l.Period;
            var x0 = (int)(sx / P);
            if (x0 >= cols + len) continue;
            for (var i = 0; i < len; i++)
            {
                var x = x0 + i;
                if (x < 0 || x >= cols) continue;
                var y = edge[Math.Min(cols - 1, Math.Max(0, x0))] - (int)up;
                if (y >= 0 && y < edge[x] - 1) Put(x, y, wisp);
            }
        }
    }

    void Fog(double yc, double thick, int alpha, double t, double speed, double seed, double dens)
    {
        for (var y = Math.Max(0, (int)(yc - thick * 2)); y < Math.Min(rows, (int)(yc + thick * 2)); y++)
        {
            var g0 = Math.Exp(-Math.Pow((y - yc) / thick, 2));
            for (var x = 0; x < cols; x++)
            {
                var n = 0.5 + 0.5 * Math.Sin((x + t * speed) * 0.035 + seed) * Math.Sin((x - t * speed * 0.6) * 0.017 + y * 0.21 + seed * 2);
                var g = g0 * (0.35 + 0.65 * n);
                if (g * dens * 16 <= Bayer[y & 3, (x + (int)(t * speed * 0.5)) & 3]) continue;
                var i = y * cols + x;
                var cur = buf[i];
                if (cur == bg || ((cur >> 24) & 0xFF) < alpha) buf[i] = W(alpha);
            }
        }
    }

    enum Mode { Sailing, Held, Flying, Away, Sinking, Hidden, Rising }

    Mode mode = Mode.Sailing;
    double bx = double.NaN, by, vx, vy, ang, angV, modeTime, awayFor, exitSpeed, exitX, homeX = double.NaN;
    double blow, capsizedFor, heldFor, lastMoveT;
    bool far, cameFromAbove, capsized, enterFromLeft, sideExit;
    int skips;
    Point grab, mouse, lastMouse;
    Vector grabLocal;
    readonly List<(double T, Point P)> trail = new();
    readonly List<(double X, double Y, double Vx, double Vy, double Life, double Max)> spray = new();
    readonly Random splash = new(3);
    int[] boatPix = Array.Empty<int>();

    const double Gravity = 1500;
    const double Rad = Math.PI / 180;

    double Size(double h) => Math.Max(7.0, Math.Min(16.0, (span > 0 ? span : h) * 0.034 + 2.0));
    double Scale => far ? 0.58 : 1.0;
    Layer Sea => far ? layers[1] : layers[2];
    double DefaultHome(double w, double t) => w * 0.34 + Math.Sin(t * 0.07) * w * 0.025;

    (double Y, double Lean) Rest(double x, double u, double t)
    {
        var s = Sea;
        double Highest(double from, double to)
        {
            var best = double.MaxValue;
            for (var q = from; q <= to; q += 2) best = Math.Min(best, Surface(s, q, t));
            return best;
        }
        var bow = Highest(x - 5.5 * u, x - 0.5 * u);
        var stern = Highest(x + 0.5 * u, x + 5.5 * u);
        return ((bow + stern) / 2 + 1.15 * u, Math.Atan2(stern - bow, 6 * u) * 180 / Math.PI * 0.85);
    }

    static double Wrap(double a) { a %= 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; }

    void Step(double w, double h, double t, double dt)
    {
        if (double.IsNaN(bx)) { homeX = bx = DefaultHome(w, t); (by, ang) = Rest(bx, Size(h), t); }
        var n = Math.Max(1, (int)Math.Ceiling(dt / 0.004));
        for (var i = 0; i < n; i++) Sub(w, h, t, dt / n);
        blow *= Math.Exp(-dt * 1.6);
        for (var i = spray.Count - 1; i >= 0; i--)
        {
            var p = spray[i];
            p.Vy += Gravity * 0.55 * dt;
            p.Vx *= Math.Exp(-dt * 1.2);
            p.X += p.Vx * dt;
            p.Y += p.Vy * dt;
            p.Life -= dt;
            if (p.Life <= 0) spray.RemoveAt(i); else spray[i] = p;
        }
    }

    void Sub(double w, double h, double t, double dt)
    {
        var u = Size(h) * Scale;
        homeX += (DefaultHome(w, t) - homeX) * (1 - Math.Exp(-dt * 0.025));
        modeTime += dt;
        switch (mode)
        {
            case Mode.Sailing:
            {
                var (ty, lean) = Rest(bx, u, t);
                lean += Math.Sin(t * 0.83) * 1.1 + Math.Sin(t * 1.9 + 0.6) * 0.35;
                vy += (24 * (ty - by) - 3.4 * vy) * dt;
                vx += (-1.4 * vx + 0.16 * (homeX - bx) + blow * 140) * dt;
                double target;
                if (capsized)
                {
                    capsizedFor += dt;
                    target = 180;
                    if (capsizedFor > 1.8) { capsized = false; angV += ang > 0 ? -60 : 60; }
                }
                else target = lean - blow * 9;
                angV += (14 * Wrap(target - ang) - 2.3 * angV) * dt;
                break;
            }
            case Mode.Held:
            {
                heldFor += dt;
                var r = Rotate(grabLocal, ang);
                var gx = bx + r.X;
                var gy = by + r.Y;
                var w0 = angV * Rad;
                var pvx = vx - w0 * r.Y;
                var pvy = vy + w0 * r.X;
                var fx = 900 * (mouse.X - gx) - 46 * pvx;
                var fy = 900 * (mouse.Y - gy) - 46 * pvy;
                vx += fx * dt;
                vy += (fy + Gravity * 0.9) * dt;
                var inertia = 22 * u * u;
                var torque = r.X * fy - r.Y * fx;
                angV += (torque / inertia / Rad - 1.2 * angV) * dt;
                break;
            }
            case Mode.Flying:
            {
                var g = Gravity * Scale;
                vy += g * dt;
                vx *= Math.Exp(-0.18 * dt);
                vy *= Math.Exp(-0.06 * dt);
                angV *= Math.Exp(-0.35 * dt);
                var (ty, _) = Rest(bx, u, t);
                var wall = 7 * u;
                if (!far && bx < wall && vx < 0)
                {
                    if (-vx > 2600) { SideExit(true); return; }
                    bx = wall; vx = -vx * 0.55; angV += vy * 0.04 + 120; Spray(bx, by - 6 * u, 6, 260, 1);
                }
                else if (!far && bx > w - wall && vx > 0)
                {
                    if (vx > 2600) { SideExit(false); return; }
                    bx = w - wall; vx = -vx * 0.55; angV -= vy * 0.04 + 120; Spray(bx, by - 6 * u, 6, 260, -1);
                }
                if (vy > 0 && by >= ty && by < h + 20 * u)
                {
                    var upright = Math.Abs(Wrap(ang)) < 60;
                    if (far) { Go(Mode.Sinking); vy = Math.Min(vy, 60); vx = 0; Spray(bx, ty, 10, 220, 0); }
                    else if (upright && Math.Abs(vx) > 650 && vy < Math.Abs(vx) * 0.45 && skips < 4)
                    {
                        skips++;
                        by = ty;
                        vy = -vy * 0.5 - 90;
                        vx *= 0.82;
                        angV += -Wrap(ang) * 2;
                        Spray(bx, ty, 8 + (int)(Math.Abs(vx) / 120), Math.Abs(vx) * 0.25, Math.Sign(vx));
                    }
                    else
                    {
                        var speed = Math.Sqrt(vx * vx + vy * vy);
                        Spray(bx, ty, Math.Min(46, 6 + (int)(speed / 45)), 120 + speed * 0.22, Math.Sign(vx) * 0.3);
                        capsized = Math.Abs(Wrap(ang)) > 100;
                        capsizedFor = 0;
                        ang = Wrap(ang);
                        vy *= 0.55;
                        vx *= 0.6;
                        homeX = Math.Max(w * 0.12, Math.Min(w * 0.88, bx));
                        Go(Mode.Sailing);
                    }
                }
                else if (!far && by < -16 * u && vy < 0)
                {
                    Go(Mode.Away);
                    cameFromAbove = true;
                    sideExit = false;
                    exitSpeed = -vy;
                    exitX = bx;
                    awayFor = Math.Max(0.5, Math.Min(6.0, 2 * exitSpeed / Gravity * 0.9));
                }
                else if (bx < -12 * u || bx > w + 12 * u || by > h + 30 * u)
                {
                    Go(Mode.Away);
                    cameFromAbove = false;
                    sideExit = false;
                    awayFor = 1.0;
                }
                break;
            }
            case Mode.Away:
                if (modeTime >= awayFor)
                {
                    if (cameFromAbove)
                    {
                        far = true;
                        u = Size(h) * Scale;
                        bx = Math.Max(w * 0.1, Math.Min(w * 0.9, exitX * 0.85 + w * 0.08));
                        by = -14 * u;
                        vx = 0;
                        vy = 80 + exitSpeed * 0.2;
                        angV = (exitX % 2 > 1 ? 1 : -1) * 40;
                        Go(Mode.Flying);
                    }
                    else if (sideExit)
                    {
                        far = false;
                        capsized = false;
                        u = Size(h);
                        bx = enterFromLeft ? -9 * u : w + 9 * u;
                        by = Rest(bx, u, t).Y;
                        vx = enterFromLeft ? 520 : -520;
                        vy = 0;
                        ang = 0;
                        angV = 0;
                        homeX = enterFromLeft ? w * 0.22 : w * 0.78;
                        Go(Mode.Sailing);
                    }
                    else StartRising(w, h, t);
                }
                return;
            case Mode.Sinking:
            {
                var (ty, lean) = Rest(bx, u, t);
                vy = 10 * u;
                angV += (30 * (lean + 14 - ang) - 6 * angV) * dt;
                if (by > ty + 15 * u) Go(Mode.Hidden);
                break;
            }
            case Mode.Hidden:
                if (modeTime > 0.9) StartRising(w, h, t);
                return;
            case Mode.Rising:
            {
                var (ty, lean) = Rest(bx, u, t);
                by += (ty - by) * (1 - Math.Exp(-dt * 1.4));
                vy = 0;
                vx = (homeX - bx) * 0.8;
                angV += (30 * (lean - ang) - 7 * angV) * dt;
                if (Math.Abs(ty - by) < 1.5) Go(Mode.Sailing);
                break;
            }
        }
        bx += vx * dt;
        by += vy * dt;
        ang += angV * dt;
    }

    static Vector Rotate(Vector v, double deg)
    {
        var c = Math.Cos(deg * Rad);
        var s = Math.Sin(deg * Rad);
        return new Vector(v.X * c - v.Y * s, v.X * s + v.Y * c);
    }

    void SideExit(bool left)
    {
        Go(Mode.Away);
        cameFromAbove = false;
        sideExit = true;
        enterFromLeft = !left;
        awayFor = 1.4;
    }

    void Spray(double x, double y, int n, double speed, double dir)
    {
        for (var i = 0; i < n; i++)
        {
            var a = -Math.PI / 2 + (splash.NextDouble() - 0.5) * 2.2 + dir * 0.5;
            var s = speed * (0.35 + splash.NextDouble() * 0.65);
            var life = 0.45 + splash.NextDouble() * 0.6;
            spray.Add((x + (splash.NextDouble() - 0.5) * 40, y, Math.Cos(a) * s, Math.Sin(a) * s, life, life));
        }
        if (spray.Count > 220) spray.RemoveRange(0, spray.Count - 220);
    }

    void DrawSpray()
    {
        foreach (var p in spray)
        {
            var k = p.Life / p.Max;
            var x = (int)(p.X / P);
            var y = (int)(p.Y / P);
            var a = (byte)(60 + 190 * k);
            Put(x, y, White(a));
            if (k > 0.6) { Put(x + 1, y, White((byte)(a / 2))); Put(x, y + 1, White((byte)(a / 3))); }
        }
    }

    void StartRising(double w, double h, double t)
    {
        far = false;
        capsized = false;
        var u = Size(h);
        bx = homeX;
        by = Rest(bx, u, t).Y + 15 * u;
        vx = vy = angV = 0;
        ang = 0;
        Go(Mode.Rising);
    }

    void Go(Mode m)
    {
        if (m == Mode.Flying || m == Mode.Held) skips = m == Mode.Held ? 0 : skips;
        mode = m;
        modeTime = 0;
    }

    bool Shown => mode is not (Mode.Away or Mode.Hidden);

    bool OnBoat(Point p)
    {
        if (!Shown || far || double.IsNaN(bx)) return false;
        var u = Size(ActualHeight);
        var local = Rotate(new Vector(p.X - bx, p.Y - by), -ang);
        return local.X > -8 * u && local.X < 8 * u && local.Y > -14 * u && local.Y < 3 * u;
    }

    protected override HitTestResult? HitTestCore(PointHitTestParameters hit) =>
        mode == Mode.Held || OnBoat(hit.HitPoint) || OnBell(hit.HitPoint) ? new PointHitTestResult(this, hit.HitPoint) : null;

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        var p = e.GetPosition(this);
        if (!OnBoat(p))
        {
            if (OnBell(p)) { Ring(); e.Handled = true; }
            return;
        }
        mouse = p;
        grab = new Point(p.X - bx, p.Y - by);
        grabLocal = Rotate(new Vector(grab.X, grab.Y), -ang);
        heldFor = 0;
        capsized = false;
        trail.Clear();
        trail.Add(((DateTime.UtcNow - start).TotalSeconds, p));
        Go(Mode.Held);
        CaptureMouse();
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        var p = e.GetPosition(this);
        var now = (DateTime.UtcNow - start).TotalSeconds;
        Cursor = mode == Mode.Held || OnBoat(p) || OnBell(p) ? Cursors.Hand : null;
        if (mode == Mode.Held)
        {
            mouse = p;
            trail.Add((now, mouse));
            trail.RemoveAll(s => now - s.T > 0.12);
            return;
        }
        var dtm = now - lastMoveT;
        if (mode == Mode.Sailing && !far && dtm > 0 && dtm < 0.1 && !double.IsNaN(bx))
        {
            var mvx = (p.X - lastMouse.X) / dtm;
            var u = Size(ActualHeight);
            var near = Math.Abs(p.X - bx) < 14 * u && p.Y > by - 16 * u && p.Y < by + 2 * u;
            if (near && Math.Abs(mvx) > 700)
            {
                blow = Math.Max(-1, Math.Min(1, blow + mvx / 6000));
                angV += mvx * 0.006;
            }
        }
        lastMouse = p;
        lastMoveT = now;
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        if (mode != Mode.Held) return;
        ReleaseMouseCapture();
        var now = (DateTime.UtcNow - start).TotalSeconds;
        trail.RemoveAll(s => now - s.T > 0.12);
        var moved = trail.Count >= 2 ? (trail[^1].P - trail[0].P).Length : 0;
        if (heldFor < 0.2 && moved < 6)
        {
            vx = (splash.NextDouble() - 0.5) * 120;
            vy = -520;
            angV = (splash.NextDouble() < 0.5 ? -1 : 1) * (90 + splash.NextDouble() * 120);
            Go(Mode.Flying);
            e.Handled = true;
            return;
        }
        if (trail.Count >= 2 && trail[^1].T - trail[0].T > 0.005)
        {
            var span = trail[^1].T - trail[0].T;
            var mvx = (trail[^1].P.X - trail[0].P.X) / span;
            var mvy = (trail[^1].P.Y - trail[0].P.Y) / span;
            var r = Rotate(grabLocal, ang);
            var spin = (r.X * mvy - r.Y * mvx) / (r.LengthSquared + 400) / Rad;
            vx = mvx;
            vy = mvy;
            angV = Math.Max(-900, Math.Min(900, angV * 0.5 + spin * 0.8));
        }
        var speed = Math.Sqrt(vx * vx + vy * vy);
        if (speed > 5000) { vx *= 5000 / speed; vy *= 5000 / speed; }
        Go(Mode.Flying);
        e.Handled = true;
    }

    protected override void OnLostMouseCapture(MouseEventArgs e)
    {
        if (mode == Mode.Held) Go(Mode.Flying);
    }

    void Events(double t)
    {
        var dt = Math.Min(0.1, Math.Max(0, t - lastEvents));
        lastEvents = t;
        if (Settings.Storm && t > nextFlash)
        {
            flashes.Add(new Flash { Start = t, Life = 0.4 + rng.NextDouble() * 0.4, Power = 0.4 + rng.NextDouble() * 0.6, X = rng.Next(Math.Max(1, cols)) });
            nextFlash = t + 3 + rng.NextDouble() * 6;
        }
        if (Settings.Storm && t > nextBolt) { AddBolt(t); nextBolt = t + 13 + rng.NextDouble() * 17; }
        flashes.RemoveAll(f => t - f.Start > f.Life);
        bolts.RemoveAll(b => t - b.Start > b.Life);
        if (t > nextStar)
        {
            var dir = rng.NextDouble() < 0.5 ? -1 : 1;
            star = (cols * (0.15 + rng.NextDouble() * 0.7), TopC + SpanC * (0.01 + rng.NextDouble() * 0.08), dir * (60 + rng.NextDouble() * 50), 18 + rng.NextDouble() * 14, 1);
            nextStar = t + 7 + rng.NextDouble() * 12;
        }
        if (star.Life > 0) star = (star.X + star.Vx * dt, star.Y + star.Vy * dt, star.Vx, star.Vy, star.Life - dt * 1.4);
    }

    double lastEvents;

    void AddBolt(double t)
    {
        var toRod = spireTop > 0 && rng.NextDouble() < 0.45;
        int ex = toRod ? spireX : rng.Next(Math.Max(1, cols));
        int ey = toRod ? rodTopY : Crest(layers[0], ex, t);
        var sx = Math.Clamp(ex + rng.Next(-30, 31), 0, Math.Max(0, cols - 1));
        var sy = TopC;
        if (ey <= sy + 6) return;
        var b = new Bolt { Start = t, Life = 0.6 };
        double x = sx, drift = 0;
        var change = 0;
        for (var y = sy; y <= ey; y++)
        {
            if (--change <= 0) { drift = (rng.NextDouble() - 0.5) * 2.4; change = 2 + rng.Next(4); }
            x += drift + (ex - x) / (ey - y + 1) * 0.9;
            b.Path.Add(((int)Math.Round(x), y));
        }
        bolts.Add(b);
        flashes.Add(new Flash { Start = t, Life = 0.6, Power = 1, X = ex });
        if (toRod) rodHit = t;
    }

    void Bolts(double t)
    {
        foreach (var b in bolts)
        {
            var fl = Flicker(t - b.Start, b.Life);
            if (fl < 0.05) continue;
            var core = W((int)(255 * fl));
            (int X, int Y)? p0 = null;
            foreach (var p in b.Path)
            {
                if (p0 is { } q && Math.Abs(p.X - q.X) > 1) for (var x = Math.Min(p.X, q.X) + 1; x < Math.Max(p.X, q.X); x++) Put(x, q.Y, core);
                Put(p.X, p.Y, core);
                Max(p.X - 1, p.Y, (int)(70 * fl)); Max(p.X + 1, p.Y, (int)(70 * fl));
                p0 = p;
            }
        }
    }

    void Flame(int cx, int cy, int hgt, double pow, double t, double seed)
    {
        for (var dy = 0; dy < hgt; dy++)
        {
            var f = dy / (double)hgt;
            var half = Math.Pow(1 - f, 0.7) * 1.7 * pow;
            var sway = Math.Sin(t * 8 + dy * 0.7 + seed) * dy * 0.14;
            for (var dx = -(int)Math.Ceiling(half) - 1; dx <= (int)Math.Ceiling(half) + 1; dx++)
            {
                if (Math.Abs(dx - sway) > half) continue;
                if (Frac(Math.Floor(t * 14) + dx * 3.1 + seed, dy) < 0.2 + f * 0.4) continue;
                Put(cx + dx + (int)Math.Round(sway), cy - dy, W((int)(255 - f * 140)));
            }
        }
        if (Frac(Math.Floor(t * 3), seed) > 0.6)
        {
            var e = (t * 3) % 1;
            Max(cx + (int)(Math.Sin(seed + t) * 3), cy - hgt - (int)(e * 8), (int)(200 * (1 - e)));
        }
    }

    void Spire(double t, double glow)
    {
        spireX = (int)(cols * 0.84);
        var crest = (int)(layers[2].Base / P - layers[2].Scale / P * 0.45);
        var baseY = Math.Min(rows - 1, crest + 8);
        var top = TopC + 3;
        var H = baseY - top;
        if (H < 22) { spireTop = 0; bellBox = Rect.Empty; return; }
        spireTop = top;
        rodTopY = top - 7;
        var half0 = Math.Clamp(H * 0.085, 5, 15);
        var yBelfry = top + (int)(H * 0.44);
        var yCrown = yBelfry - (int)(H * 0.15);
        var flare = t - ringAt < 3 ? 1 - (t - ringAt) / 3 : 0;
        var hit = t - rodHit < 0.7 ? Flicker(t - rodHit, 0.7) : 0;
        var rimL = W((int)(175 + glow * 60 + hit * 80));
        var rimR = W((int)(58 + glow * 50 + hit * 120));
        var ledge = W((int)(110 + glow * 40 + hit * 100));

        foreach (var side in new[] { -1, 1 })
        {
            var y0 = baseY - (int)(H * 0.24);
            var x0 = spireX + side * (int)(half0 * 0.95);
            var x1 = spireX + side * (int)(half0 + H * 0.17);
            for (var k = 0; k < 3; k++) Line(x0, y0 + k, x1, baseY + k, k == 0 ? (side < 0 ? rimL : rimR) : bg);
            Line(x0, y0 + 3, x1, baseY + 3, W(60));
        }

        var step = Math.Max(10, (int)(H * 0.14));
        for (var y = yBelfry; y <= baseY; y++)
        {
            var f = (y - yBelfry) / (double)Math.Max(1, baseY - yBelfry);
            var hw = (int)Math.Round(half0 * (0.72 + 0.28 * f));
            var isLedge = (baseY - y) % step == 0 && y < baseY - 2;
            if (isLedge) hw += 1;
            for (var x = -hw; x <= hw; x++)
            {
                var v = bg;
                if (y % 3 == 0 && (x + y / 3 * 2) % 5 == 0) v = W(26);
                var sun = (-x / (double)hw - 0.25) / 0.75;
                if (sun > 0 && Bayer[y & 3, (spireX + x) & 3] < sun * 7) v = W((int)(44 + 40 * sun));
                if (x == -hw) v = rimL;
                else if (x == hw) v = rimR;
                else if (isLedge) v = x < 0 ? ledge : W((int)(60 + glow * 30 + hit * 80));
                Put(spireX + x, y, v);
            }
            var row = (baseY - y) % step;
            if (row >= 3 && row <= 7 && y > yBelfry + 2)
            {
                var band = (baseY - y) / step;
                foreach (var wx in new[] { -0.45, 0.45 })
                {
                    var x = spireX + (int)Math.Round(hw * wx);
                    var lit = Frac(band, wx) > 0.4;
                    var flick = 0.7 + 0.3 * Math.Sin(t * (2 + Frac(band, wx) * 3) + band);
                    var a = lit ? (int)(110 + 110 * flick + flare * 100) : (int)(34 + flare * 180);
                    if (row == 3) { Put(x, y, W(a / 2)); continue; }
                    Put(x, y, W(a));
                    if (half0 > 9) Put(x + 1, y, W(a));
                }
            }
        }

        var bhw = (int)Math.Round(half0 * 0.74);
        var swing = flare > 0 ? Math.Sin((t - ringAt) * 11) * flare * 2.2 : 0;
        for (var y = yCrown; y < yBelfry; y++)
        {
            var depth = y - yCrown;
            for (var x = -bhw; x <= bhw; x++)
            {
                var pillar = Math.Abs(x) >= bhw - 1 || Math.Abs(x) <= 0;
                var archTop = depth < 4 && Math.Abs(Math.Abs(x) - bhw / 2.0) > (bhw / 2.0 - 1) * Math.Sqrt(Math.Max(0, depth / 4.0));
                var v = pillar || archTop ? bg : bg;
                if (pillar && (x == -bhw)) v = rimL;
                else if (pillar && x == bhw) v = rimR;
                else if (pillar) v = W(70);
                else if (archTop) v = W(depth == 3 ? 120 : 30);
                Put(spireX + x, y, v);
            }
        }
        var bellTop = yCrown + 4;
        var bellH = Math.Max(5, yBelfry - bellTop - 1);
        foreach (var side in new[] { -1, 1 })
        {
            var bc = spireX + side * (int)Math.Round(bhw / 2.0) + (int)Math.Round(swing);
            for (var k = 0; k < bellH; k++)
            {
                var bw = (int)Math.Round(0.6 + k / (double)bellH * Math.Max(1.5, bhw / 2.0 - 1.2));
                for (var x = -bw; x <= bw; x++) Put(bc + x, bellTop + k, Math.Abs(x) == bw || k == bellH - 1 ? W((int)(170 + flare * 85)) : W((int)(40 + flare * 120)));
            }
            Line(bc, yCrown + 1, bc, bellTop, W(90));
        }
        bellBox = new Rect((spireX - bhw - 1) * P, (yCrown - 1) * P, (bhw * 2 + 3) * P, (yBelfry - yCrown + 2) * P);

        for (var x = -bhw - 2; x <= bhw + 2; x++)
        {
            Put(spireX + x, yCrown, x < 0 ? rimL : rimR);
            Put(spireX + x, yCrown + 1, bg);
            if (((x + bhw) & 1) == 0) { Put(spireX + x, yCrown - 1, x < 0 ? rimL : rimR); Put(spireX + x, yCrown - 2, ledge); }
        }
        foreach (var side in new[] { -1, 1 })
        {
            var px = spireX + side * (bhw + 2);
            for (var k = 0; k < 8; k++)
            {
                var pw = k < 3 ? 1 : 0;
                for (var x = -pw; x <= pw; x++) Put(px + x, yCrown - 1 - k, x == -pw ? rimL : x == pw ? rimR : bg);
            }
            Flame(px, yCrown - 10, (int)(6 + flare * 6 + Math.Sin(t * 3 + side) * 1.5), 1 + flare * 0.8, t, side * 7.3);
        }

        var nTop = top;
        var nBase = yCrown - 3;
        for (var y = nTop; y <= nBase; y++)
        {
            var f = (y - nTop) / (double)Math.Max(1, nBase - nTop);
            var nhw = (int)Math.Round(half0 * 0.52 * f);
            var broken = f > 0.38 && f < 0.6;
            var cut = broken ? (int)Math.Round(nhw * (0.2 + 0.8 * Frac(y, 3.3))) : nhw;
            for (var x = -nhw; x <= cut; x++)
            {
                var v = bg;
                var sun = nhw > 1 ? (-x / (double)nhw - 0.2) / 0.8 : 0;
                if (sun > 0 && Bayer[y & 3, (spireX + x) & 3] < sun * 6) v = W((int)(40 + 36 * sun));
                if (x == -nhw) v = rimL;
                else if (x == cut) v = rimR;
                else if ((y + x) % 4 == 0 && Bayer[y & 3, x & 3] < 4) v = W(24);
                Put(spireX + x, y, v);
            }
        }
        for (var y = rodTopY; y < top; y++) Put(spireX, y, W((int)(140 + hit * 115)));
        Put(spireX - 1, rodTopY + 2, W(120)); Put(spireX + 1, rodTopY + 2, W(120));
        if (hit > 0.2)
        {
            var sr = new Random((int)(t * 30));
            for (var i = 0; i < 16; i++) Put(spireX + sr.Next(-6, 7), rodTopY + sr.Next(-4, 9), W(255));
        }

        for (var i = 0; i < 4; i++)
        {
            var dx = (i % 2 == 0 ? -1 : 1) * (int)(half0 + 6 + i * 3);
            var cy = yCrown - 6 - i * (int)(H * 0.07) + (int)Math.Round(Math.Sin(t * 0.7 + i * 1.9) * 1.5);
            var cx = spireX + dx;
            var sz = 2 + (i & 1);
            for (var y = 0; y < sz; y++)
                for (var x = 0; x < sz + 1; x++)
                    Put(cx + x, cy + y, y == 0 ? W(130) : x == 0 ? W(90) : bg);
            if (Bayer[(cy + sz) & 3, cx & 3] < 6) Put(cx + 1, cy + sz, W(40));
        }
        foreach (var (ox, oy, size, seed) in new[]
        {
            (-half0 - 19, yCrown + 6, 7.0, 1.3), (half0 + 15, yCrown - H * 0.20, 6.0, 2.7),
            (-half0 - 31, yCrown - H * 0.30, 5.0, 4.1), (half0 + 26, yBelfry + H * 0.16, 8.5, 5.9), (-half0 - 42, yBelfry + H * 0.22, 4.0, 7.3),
        })
            Rock(spireX + (int)ox, (int)oy + (int)Math.Round(Math.Sin(t * 0.45 + seed * 2) * 2.2), size, seed, t, flare, glow);
    }

    void Rock(int cx, int cy, double size, double seed, double t, double flare, double glow)
    {
        var shape = new (double X, double Y)[]
        {
            (-1.0, -0.30), (-0.55, -0.52), (0.15, -0.48), (0.85, -0.36), (1.02, -0.08), (0.62, 0.38),
            (0.30, 0.78), (0.06, 1.30), (-0.28, 0.72), (-0.72, 0.30),
        };
        var pts = shape.Select((p, k) => (p.X * size * (0.85 + 0.3 * Frac(seed, k)), p.Y * size * (0.85 + 0.3 * Frac(seed + 1, k)))).ToArray();
        var reach = (int)Math.Ceiling(size * 1.5);
        bool In(int dx, int dy) => InPoly(dx, dy, pts);
        for (var dy = -reach; dy <= reach + (int)size; dy++)
            for (var dx = -reach; dx <= reach; dx++)
            {
                if (!In(dx, dy)) continue;
                int x = cx + dx, y = cy + dy;
                int v;
                if (!In(dx, dy - 1)) v = W((int)(170 + glow * 60 + flare * 70));
                else if (!In(dx, dy - 2) && Bayer[y & 3, x & 3] < 9) v = W((int)(90 + flare * 40));
                else if (!In(dx - 1, dy)) v = W((int)(115 + glow * 30));
                else if (!In(dx + 1, dy)) v = W(60);
                else if (dy > 0 && (dx + dy * 2) % 4 == 0 && Bayer[y & 3, x & 3] < 8) v = W(38);
                else v = bg;
                Put(x, y, v);
            }
        for (var k = 0; k < 2; k++)
        {
            var hx = cx + (int)Math.Round((Frac(seed, k + 9) - 0.5) * size * 0.8);
            var len = 2 + (int)(Frac(seed, k + 13) * size * 0.6);
            var from = cy + (int)(size * (0.5 + 0.3 * k));
            for (var j = 1; j <= len; j++)
                if (Bayer[(from + j) & 3, hx & 3] < 10) Put(hx + (int)Math.Round(Math.Sin(t * 1.1 + j * 0.6 + k) * 0.6), from + j, W(70 - j * 6));
        }
        var dust = (t * 0.5 + seed) % 1;
        Max(cx + (int)((Frac(seed, 3) - 0.5) * size * 0.4), cy + (int)(size * 1.3) + (int)(dust * 12), (int)(110 * (1 - dust)));
    }

    public void Ring()
    {
        var t = Now;
        if (t - ringAt < 1.2) return;
        ringAt = t;
        ringPx = bellBox.IsEmpty ? ActualWidth * 0.84 : bellBox.X + bellBox.Width / 2;
        if (!double.IsNaN(bx) && mode == Mode.Sailing) blow = Math.Clamp(blow + (bx / P < spireX ? -0.7 : 0.7), -1, 1);
        foreach (var g in gulls) g.Scared = true;
    }

    void Rings(double t)
    {
        var a0 = t - ringAt;
        if (a0 < 0 || a0 > 3.4 || bellBox.IsEmpty) return;
        var cx = (bellBox.X + bellBox.Width / 2) / P;
        var cy = (bellBox.Y + bellBox.Height / 2) / P;
        for (var k = 0; k < 3; k++)
        {
            var age = a0 - k * 0.3;
            if (age <= 0) continue;
            var rad = age * cols * 0.32;
            var al = (int)(240 * Math.Max(0, 1 - age / 2.8));
            if (al < 8) continue;
            var steps = (int)(rad * 4) + 8;
            for (var s = 0; s < steps; s++)
            {
                var ra = s * Math.PI * 2 / steps;
                if (Math.Sin(ra * 13 + k * 2) < -0.4) continue;
                var rx = (int)Math.Round(cx + Math.Cos(ra) * rad);
                var ry = (int)Math.Round(cy + Math.Sin(ra) * rad * 0.34);
                Max(rx, ry, al);
                Max(rx, ry + 1, al / 2);
            }
        }
    }

    bool OnBell(Point p) => !bellBox.IsEmpty && bellBox.Contains(p);

    void Gulls(double t, double dt)
    {
        foreach (var g in gulls)
        {
            if (g.Scared) { g.Vx = Math.Min(0.09, g.Vx + dt * 0.08); g.Y -= dt * 0.06; }
            g.X += g.Vx * dt;
            g.Y += Math.Sin(t * 0.6 + g.Phase) * dt * 0.004;
            if (g.X > 1.06 || g.Y < -0.12)
            {
                g.X = -0.06; g.Y = 0.1 + rng.NextDouble() * 0.2; g.Vx = 0.012 + rng.NextDouble() * 0.01; g.Scared = false;
            }
            var x = (int)(g.X * cols);
            var y = TopC + (int)(g.Y * SpanC);
            var up = Math.Sin(t * (g.Scared ? 16 : 7) + g.Phase) > 0;
            var v = W(160);
            Put(x, y, v); Put(x - 1, y, v); Put(x + 1, y, v);
            if (up) { Put(x - 2, y - 1, v); Put(x + 2, y - 1, v); }
            else { Put(x - 2, y + 1, v); Put(x + 2, y + 1, v); }
        }
    }

    int[] sprite = Array.Empty<int>();
    int sw, sh;
    double sc, sox, soy;

    void SPut(double ux, double uy, int v)
    {
        var x = (int)Math.Round(sox + ux * sc);
        var y = (int)Math.Round(soy + uy * sc);
        if (x >= 0 && y >= 0 && x < sw && y < sh) sprite[y * sw + x] = v;
    }

    void SLine(double x0, double y0, double x1, double y1, int v)
    {
        var n = Math.Max(1, (int)Math.Ceiling(Math.Max(Math.Abs(x1 - x0), Math.Abs(y1 - y0)) * sc * 1.6));
        for (var i = 0; i <= n; i++) SPut(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, v);
    }

    void Shape(double x0, double y0, double x1, double y1, Func<double, double, bool> inside, int line, int tex, int leech = 0)
    {
        int cx0 = Math.Max(0, (int)Math.Floor(sox + x0 * sc) - 1), cx1 = Math.Min(sw - 1, (int)Math.Ceiling(sox + x1 * sc) + 1);
        int cy0 = Math.Max(0, (int)Math.Floor(soy + y0 * sc) - 1), cy1 = Math.Min(sh - 1, (int)Math.Ceiling(soy + y1 * sc) + 1);
        bool In(int x, int y) => inside((x - sox) / sc, (y - soy) / sc);
        for (var y = cy0; y <= cy1; y++)
            for (var x = cx0; x <= cx1; x++)
            {
                if (!In(x, y)) continue;
                var l = !In(x - 1, y);
                var r = !In(x + 1, y);
                var u = !In(x, y - 1);
                var d = !In(x, y + 1);
                int v;
                if (l || u || d) v = line;
                else if (r) v = leech != 0 ? leech : line;
                else v = tex > 0 && Bayer[y & 3, x & 3] < tex ? W(30) : bg;
                sprite[y * sw + x] = v;
            }
    }

    static bool InPoly(double x, double y, (double X, double Y)[] p)
    {
        var inside = false;
        for (int i = 0, j = p.Length - 1; i < p.Length; j = i++)
            if ((p[i].Y > y) != (p[j].Y > y) && x < (p[j].X - p[i].X) * (y - p[i].Y) / (p[j].Y - p[i].Y) + p[i].X) inside = !inside;
        return inside;
    }

    static double Deck(double x) => -1.6 - (x > 2.8 ? (x - 2.8) / 3.3 * 1.5 : 0) - (x < -5.0 ? (-5.0 - x) / 1.6 * 0.7 : 0);
    static double Keel(double x) { var q = (x + 0.3) / 6.4; return Math.Max(Deck(x) + 1.0, 1.45 * (1 - q * q) - 0.15); }

    void BuildBoat(double u, double t)
    {
        sc = u / P;
        sw = (int)Math.Ceiling(17.6 * sc) + 4;
        sh = (int)Math.Ceiling(15.2 * sc) + 4;
        sox = 9.8 * sc + 2;
        soy = 12.8 * sc + 2;
        if (sprite.Length != sw * sh) sprite = new int[sw * sh]; else Array.Clear(sprite);
        var k = far ? 0.62 : 1.0;
        var hi = W((int)(250 * k));
        var mid = W((int)(170 * k));
        var lo = W((int)(95 * k));

        var breath = 0.5 + 0.5 * Math.Sin(t * 1.15);
        var gust = 0.5 + 0.5 * Math.Sin(t * 3.7 + Math.Sin(t * 0.9) * 2);
        var fill = Math.Min(1.5, 0.5 + breath * 0.35 + gust * 0.12 + Math.Abs(blow) * 0.7);
        var bel = 0.3 + fill * 0.5;
        const double fore = -2.6, main = 1.7;

        SLine(-6.3, -2.2, -9.6, -3.7, mid);
        SLine(fore, -10.0, -9.6, -3.7, lo);
        SLine(main, -11.4, fore, -10.0, lo);
        SLine(main, -11.4, 6.1, -3.1, lo);
        SLine(fore, Deck(fore), fore, -10.0, mid);
        SLine(main, Deck(main), main, -11.4, mid);

        var jib = new (double, double)[] { (fore - 0.1, -9.4), (-6.4 - bel, -6.6), (-9.2, -3.9), (-4.7, -3.3) };
        Shape(-10.6, -10, -2.4, -3, (x, y) => InPoly(x, y, jib), hi, 0, lo);

        foreach (var (y0, y1, hw) in new[] { (-9.3, -7.1, 1.4), (-6.7, -3.7, 2.0) })
        {
            Shape(fore - hw - bel - 0.4, y0 - 0.2, fore + hw + 0.4, y1 + 0.2, (x, y) =>
            {
                if (y < y0 || y > y1) return false;
                var v = (y - y0) / (y1 - y0);
                var b = bel * Math.Sin(Math.PI * Math.Min(1, v * 1.1)) * (0.5 + 0.5 * v);
                return x >= fore - hw - b && x <= fore + hw - b * 0.3;
            }, hi, 0, lo);
            SLine(fore - hw - 0.35, y0, fore + hw + 0.35, y0, hi);
        }

        var gaffEnd = (X: 5.2, Y: -8.7);
        var boomEnd = (X: 6.5, Y: -3.3);
        Shape(main, -10.6, 7.6, -3, (x, y) =>
        {
            if (x < main + 0.15) return false;
            var luffTop = -10.2;
            var top = luffTop + (gaffEnd.Y - luffTop) * Math.Clamp((x - main) / (gaffEnd.X - main), 0, 1);
            var bottom = -3.5 + (boomEnd.Y + 3.5) * Math.Clamp((x - main) / (boomEnd.X - main), 0, 1);
            if (y < top || y > bottom) return false;
            var v = (y - gaffEnd.Y) / (boomEnd.Y - gaffEnd.Y);
            var leechX = gaffEnd.X + (boomEnd.X - gaffEnd.X) * Math.Clamp(v, 0, 1) + Math.Sin(Math.PI * Math.Clamp(v, 0, 1)) * bel * 0.9;
            return x <= leechX;
        }, hi, 0, mid);
        SLine(main, -10.2, gaffEnd.X, gaffEnd.Y, hi);
        SLine(main, -3.4, boomEnd.X + 0.3, boomEnd.Y, hi);

        Shape(-6.9, -3.6, 6.4, 1.6, (x, y) => x >= -6.6 && x <= 6.1 && y >= Deck(x) && y <= Keel(x), hi, 0, hi);
        for (var x = -6.1; x <= 5.9; x += 0.5 / sc) { SPut(x, Deck(x) + 0.55, mid); if (Bayer[(int)(x * 7) & 3, 1] < 6) SPut(x, Deck(x) + 1.55, lo); }
        for (var x = -4.2; x <= 2.4; x += 1.1)
        {
            SPut(x, Deck(x) + 1.05, hi);
            if (sc > 2.5) SPut(x + 0.28, Deck(x) + 1.05, hi);
        }
        for (var x = -6.4; x <= 6.0; x += 0.45 / sc) SPut(x, Deck(x) - 0.4, ((int)(x * sc) & 1) == 0 ? mid : 0);
        var win = (int)((170 + 60 * Math.Sin(t * 2.3)) * k);
        SPut(4.9, -2.6, W(win)); SPut(5.5, -2.75, W(win));
        SLine(6.0, -3.3, 6.5, -4.0, mid);
        SPut(6.5, -4.15, W(255)); SPut(6.5, -4.45, W(230));
        SPut(fore, -10.3, mid); SPut(main, -11.7, mid);

        for (var i = 0; i < 10; i++)
        {
            var f = i / 9.0;
            var x = main - 3.4 * f;
            var y = -11.5 + 0.25 * f + Math.Sin(t * 7.5 - f * 4.5) * 0.4 * f;
            SPut(x, y, hi);
            if (f < 0.6) SPut(x, y + 0.32, hi);
        }
    }

    void DrawVoyager(double h, double t)
    {
        if (!Shown) return;
        var u = Size(h) * Scale;
        BuildBoat(u, t);
        var cx = bx / P;
        var cy = by / P;
        var R = (int)Math.Ceiling(Math.Sqrt(sw * sw + sh * sh)) + 2;
        var c = Math.Cos(-ang * Rad);
        var s = Math.Sin(-ang * Rad);
        for (var gy = (int)cy - R; gy <= (int)cy + R; gy++)
        {
            if (gy < 0 || gy >= rows) continue;
            for (var gx = (int)cx - R; gx <= (int)cx + R; gx++)
            {
                if (gx < 0 || gx >= cols) continue;
                double dx = gx + 0.5 - cx, dy = gy + 0.5 - cy;
                var lx = dx * c - dy * s;
                var ly = dx * s + dy * c;
                var px = (int)Math.Floor(sox + lx);
                var py = (int)Math.Floor(soy + ly);
                if (px < 0 || py < 0 || px >= sw || py >= sh) continue;
                var v = sprite[py * sw + px];
                if (v != 0) buf[gy * cols + gx] = v;
            }
        }
        var lv = Rotate(new Vector(6.5 * u, -4.2 * u), ang);
        var lxw = (bx + lv.X) / P;
        var lyw = (by + lv.Y) / P;
        var glowA = (far ? 0.5 : 1) * (0.85 + 0.15 * Math.Sin(t * 5.3));
        for (var dy = -6; dy <= 6; dy++)
            for (var dx = -6; dx <= 6; dx++)
            {
                var d = Math.Sqrt(dx * dx + dy * dy);
                if (d < 1 || d > 6) continue;
                var a = (1 - d / 6) * glowA;
                int x = (int)lxw + dx, y = (int)lyw + dy;
                if (a * 20 > Bayer[y & 3, x & 3]) Max(x, y, (int)(40 + 140 * a));
            }
        if (mode == Mode.Sailing && !capsized && Shown)
        {
            var bow = Rotate(new Vector(-6.6 * u, 0.2 * u), ang);
            var fx = (int)((bx + bow.X) / P);
            var fy = (int)((by + bow.Y) / P);
            for (var i = 0; i < 3; i++) Max(fx - rng.Next(0, 3), fy + rng.Next(-1, 2), 120 + rng.Next(100));
            var stern = Rotate(new Vector(6.0 * u, 0.6 * u), ang);
            for (var i = 0; i < 4; i++) Max((int)((bx + stern.X) / P) + rng.Next(0, 10), (int)((by + stern.Y) / P) + rng.Next(0, 2), 60 + rng.Next(60));
        }
    }
}
