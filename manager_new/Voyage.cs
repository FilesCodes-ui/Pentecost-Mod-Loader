using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class Voyage : FrameworkElement
{
    const int P = 5;
    internal const double SailFor = 20;

    internal static double Progress(double st)
    {
        static double D(double s)
        {
            s = Math.Clamp(s, 0, SailFor);
            if (s < 2.5) return s * s / 5;
            if (s < SailFor - 3) return 1.25 + (s - 2.5);
            var u = s - (SailFor - 3);
            return 1.25 + (SailFor - 5.5) + u - u * u / 6;
        }
        return D(st) / D(SailFor);
    }
    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    sealed class Cloud
    {
        public double[] Edge = Array.Empty<double>();
        public double[] Folds = Array.Empty<double>();
        public int Period;
        public double Base, Scale, Speed, SwellA, SwellL, SwellW, Phase, Drift;
        public byte Line, Fold, Dot;
    }

    sealed class Town
    {
        public readonly List<(int X, int W, int H, bool Gable, bool Church, int Chimney)> Houses = new();
        public readonly List<(int X, int Y, double Phase)> Windows = new();
        public readonly List<(int X, int H)> Masts = new();
        public int Width;
    }

    sealed class Flash { public double Start, Life, Power, X, Y; public int Layer, Seed; }

    public double Veil { get; set; }

    readonly DateTime start = DateTime.UtcNow;
    readonly Random rng = new();
    readonly Cloud[] sea;
    readonly Cloud storm;
    readonly Dictionary<string, Town> towns = new();
    readonly List<Flash> flashes = new();
    double lastT;
    bool running;
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();
    int[] edge = Array.Empty<int>();
    (int X, int Y, double Phase)[] stars = Array.Empty<(int, int, double)>();
    (double X, double Y, double V)[] rain = Array.Empty<(double, double, double)>();
    int bgPix = unchecked((int)0xFF0B0B0C), amber = unchecked((int)0xFFE0A030);
    int profiles = 1;
    string here = "", from = "";
    double sailStart = -1, sailFill, amb, nextFar = 8, boltUntil = -1, boltX;
    bool storm2;
    int monster;
    bool ghost;
    double shake;
    readonly List<(int X, int Y, int W, int H)> ports = new();
    readonly List<(double X, double Y, double Vx, double Vy, double R, double Life)> smoke = new();
    readonly List<(int X, int Y, double At)> fired = new();

    public Voyage()
    {
        ClipToBounds = true;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        sea = new[]
        {
            MakeCloud(seed: 17, period: 2600, scale: 30, speed: 3,  swellA: 3, swellL: 520, swellW: 0.30, line: 50,  dot: 13),
            MakeCloud(seed: 27, period: 3000, scale: 46, speed: 6,  swellA: 5, swellL: 660, swellW: 0.40, line: 100, dot: 16),
            MakeCloud(seed: 41, period: 3600, scale: 64, speed: 10, swellA: 7, swellL: 820, swellW: 0.50, line: 170, dot: 20),
        };
        storm = MakeCloud(seed: 73, period: 3200, scale: 60, speed: 3, swellA: 4, swellL: 900, swellW: 0.25, line: 44, dot: 12);
        Loaded += (_, _) =>
        {
            if (TryFindResource("Bg") is SolidColorBrush b) bgPix = Argb(b.Color);
            if (TryFindResource("Warn") is SolidColorBrush w) amber = Argb(w.Color);
            Start();
        };
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
        MouseMove += (_, e) => Cursor = Port(e.GetPosition(this)) != null ? Cursors.Hand : null;
        MouseLeftButtonUp += (_, e) =>
        {
            if (Port(e.GetPosition(this)) is not { } p) return;
            fired.Add((p.X + p.W / 2, p.Y + p.H / 2, Now));
            shake = 1;
            for (var i = 0; i < 14; i++)
                smoke.Add((p.X + p.W / 2 + rng.NextDouble() * 2, p.Y + p.H / 2, 2 + rng.NextDouble() * 7, -1.5 - rng.NextDouble() * 4, 1.2 + rng.NextDouble() * 1.5, 1));
        };
    }

    double Now => (DateTime.UtcNow - start).TotalSeconds;
    static int Argb(Color c) => unchecked((int)(0xFF000000u | ((uint)c.R << 16) | ((uint)c.G << 8) | c.B));
    void Start() { if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; } running = true; Anim.Add(Tick); }
    void Stop() { if (!running) return; running = false; Anim.Remove(Tick); }
    void Tick(object? s, EventArgs e) => InvalidateVisual();

    (int X, int Y, int W, int H)? Port(Point p)
    {
        int x = (int)(p.X / P), y = (int)(p.Y / P);
        foreach (var r in ports) if (x >= r.X - 1 && x <= r.X + r.W && y >= r.Y - 1 && y <= r.Y + r.H) return r;
        return null;
    }

    public void SetVoyage(int count, string active)
    {
        profiles = Math.Max(1, count);
        if (here.Length > 0 && active != here) Sail(active, Settings.Storm && rng.NextDouble() < 0.6, 1 + rng.Next(2));
        here = active;
    }

    internal void Sail(string to, bool withStorm, int creature)
    {
        from = sailStart >= 0 ? from : here;
        here = to;
        sailStart = Now;
        storm2 = withStorm;
        monster = creature;
        ghost = rng.NextDouble() < 0.45;
    }

    static uint Mix(uint h) { h ^= h >> 16; h *= 0x7feb352d; h ^= h >> 15; h *= 0x846ca68b; h ^= h >> 16; return h; }
    static double Frac(string s) { uint h = 2166136261; foreach (var c in s) { h ^= c; h *= 16777619; } return (Mix(h) & 0xFFFF) / 65536.0; }
    static double Frac(int a, int b) => (Mix((uint)(a * 73856093) ^ (uint)(b * 19349663)) & 0xFFFF) / 65536.0;
    static double Ease(double v) { v = Math.Clamp(v, 0, 1); return v * v * (3 - 2 * v); }
    static int W(int a) { a = Math.Clamp(a, 0, 255); return (a << 24) | (a << 16) | (a << 8) | a; }
    int Rim(int a) => W(a + (int)(amb * 90));
    void Put(int x, int y, int v) { if (x >= 0 && y >= 0 && x < cols && y < rows) buf[y * cols + x] = v; }
    void Max(int x, int y, int a)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        var i = y * cols + x;
        if (((buf[i] >> 24) & 0xFF) < a) buf[i] = W(a);
    }
    void Line(int x0, int y0, int x1, int y1, int v)
    {
        int dx = Math.Abs(x1 - x0), dy = -Math.Abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (var k = 0; k < 4000; k++)
        {
            if (y0 >= rows && sy > 0) return;
            Put(x0, y0, v);
            if (x0 == x1 && y0 == y1) return;
            var e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }
    void Rect(int x0, int y0, int x1, int y1, int v) { for (var y = Math.Max(0, y0); y <= Math.Min(rows - 1, y1); y++) for (var x = Math.Max(0, x0); x <= Math.Min(cols - 1, x1); x++) buf[y * cols + x] = v; }

    static Cloud MakeCloud(int seed, int period, double scale, double speed, double swellA, double swellL, double swellW, byte line, byte dot)
    {
        var rnd = new Random(seed);
        return new Cloud
        {
            Edge = Billows(period, scale, rnd, 0.85, 0.55),
            Folds = Billows(period, scale * 0.5, rnd, 1.9, 1.6),
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
        return profile[i] + (profile[(i + 1) % period] - profile[i]) * f;
    }

    static double Swell(Cloud l, double x, double t, double boost) =>
        boost * (l.SwellA * Math.Sin(2 * Math.PI * x / l.SwellL - t * l.SwellW + l.Phase)
        + l.SwellA * 0.45 * Math.Sin(2 * Math.PI * x / (l.SwellL * 0.53) + t * l.SwellW * 0.7 + l.Phase * 1.7));

    double heave = 1;
    double Surface(Cloud l, double x, double t) => l.Base - Read(l.Edge, l.Period, x - l.Drift) + Swell(l, x, t, heave);
    double Hang(Cloud l, double x, double t) => l.Base + Read(l.Edge, l.Period, x - l.Drift) + Swell(l, x, t, 1);
    int SeaCell(int k, double x, double t) => (int)Math.Floor(Surface(sea[k], (x + 0.5) * P, t) / P);

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

    void DrawCloud(Cloud l, int k, double t, bool hanging)
    {
        for (var x = 0; x < cols; x++)
            edge[x] = (int)Math.Floor((hanging ? Hang(l, (x + 0.5) * P, t) : Surface(l, (x + 0.5) * P, t)) / P);
        var act = new List<(Flash F, double I)>();
        foreach (var f in flashes) if (f.Layer == k) { var i = Flicker(t - f.Start, f.Life) * f.Power; if (i > 0.02) act.Add((f, i)); }
        double Glow(int x, int y)
        {
            var g = 0.0;
            foreach (var (f, i) in act)
            {
                var cx = Math.Clamp((int)f.X, 0, cols - 1);
                var cy = hanging ? edge[cx] - f.Y : edge[cx] + f.Y;
                var r = 12 + 6 * f.Power;
                var d = Math.Sqrt((x - f.X) * (x - f.X) + (y - cy) * (y - cy) * 2.2);
                if (d < r) g += i * (1 - d / r);
            }
            return g;
        }
        var fade = Math.Max(3, l.Scale / P * 0.6);
        for (var x = 0; x < cols; x++)
        {
            int fromY = hanging ? 0 : Math.Max(0, edge[x] + 1), toY = hanging ? Math.Min(rows, edge[x]) : rows;
            for (var y = fromY; y < toY; y++)
            {
                var d = hanging ? edge[x] - y : y - edge[x];
                var g = act.Count > 0 ? Glow(x, y) : 0;
                var v = bgPix;
                if (d <= fade || g > 0.03)
                {
                    var dens = (d <= fade ? 0.36 * (1 - d / fade) : 0) + 0.04 * Math.Sin(x * 0.5 + y * 0.3 - t * 1.2) + g * 0.6;
                    if (dens * 16 > Bayer[y & 3, x & 3]) v = W((int)(l.Dot + g * 170));
                }
                buf[y * cols + x] = v;
            }
        }
        var depth = l.Scale * 0.62;
        var prev = int.MinValue;
        for (var x = 0; x < cols; x++)
        {
            var sx = (x + 0.5) * P;
            var rise = Read(l.Folds, l.Period, sx - l.Drift);
            if (rise < 1.5) { prev = int.MinValue; continue; }
            var fy = (int)Math.Floor(((hanging ? l.Base - depth + rise : l.Base + depth - rise) + Swell(l, sx, t, hanging ? 1 : heave)) / P);
            var ok = hanging ? fy < edge[x] - 2 && fy >= 0 : fy > edge[x] + 2 && fy < rows;
            if (!ok) { prev = int.MinValue; continue; }
            var c = W((int)(l.Fold + (act.Count > 0 ? Glow(x, fy) * 200 : 0)));
            Put(x, fy, c);
            if (prev != int.MinValue && Math.Abs(fy - prev) > 1 && Math.Abs(fy - prev) < 6)
            {
                if (prev < fy) for (var y = prev + 1; y < fy; y++) Put(x, y, c);
                else for (var y = fy + 1; y < prev; y++) Put(x - 1, y, c);
            }
            prev = fy;
        }
        for (var x = 0; x < cols; x++)
        {
            var g = act.Count > 0 ? Glow(x, edge[x]) : 0;
            var c = W((int)Math.Max(l.Line + (hanging ? amb * 150 : amb * 50), g * 255));
            Put(x, edge[x], c);
            if (x == 0) continue;
            int a = edge[x - 1], b = edge[x];
            if (a < b - 1) for (var y = a + 1; y < b; y++) Put(x, y, c);
            else if (a > b + 1) for (var y = b + 1; y < a; y++) Put(x - 1, y, c);
        }
        foreach (var (f, i) in act)
        {
            if (i < 0.3) continue;
            var r = new Random(f.Seed * 31 + (int)((t - f.Start) * 24));
            var cx = Math.Clamp((int)f.X, 0, cols - 1);
            var cy = hanging ? edge[cx] - (int)f.Y : edge[cx] + (int)f.Y;
            var col = W((int)(255 * Math.Min(1, i + 0.25)));
            for (var a = 0; a < 2 + r.Next(3); a++)
            {
                int x = cx, y = cy, dir = r.Next(2) == 0 ? -1 : 1;
                for (var s = 0; s < 4 + r.Next(9); s++)
                {
                    x += dir; y += r.Next(3) - 1;
                    if (x < 0 || x >= cols) break;
                    if (!hanging && y < edge[x] + 2) y = edge[x] + 2;
                    if (hanging && y > edge[x] - 2) y = edge[x] - 2;
                    Put(x, y, col);
                }
            }
        }
    }

    Town TownFor(string name)
    {
        if (towns.TryGetValue(name, out var have)) return have;
        var town = new Town();
        var x = 4;
        var lot = 0;
        while (x < 220)
        {
            var key = name + "#" + lot++;
            var w = 5 + (int)(Frac(key + "w") * 6);
            var h = 4 + (int)(Frac(key + "h") * 10);
            var church = lot % 8 == 3;
            if (church) h += 4;
            var gable = Frac(key + "g") > 0.35;
            var chim = !church && Frac(key + "c") > 0.45 ? 1 + (int)(Frac(key + "x") * Math.Max(1, w - 3)) : -1;
            town.Houses.Add((x, w, h, gable, church, chim));
            for (var y = 2; y < h - 1; y += 3)
                for (var wx = x + 1; wx < x + w - 1; wx += 2)
                    if (Frac(key + wx + "_" + y) < 0.45) town.Windows.Add((wx, y, Frac(key + "p" + wx + "_" + y) * 40));
            x += w + (Frac(key + "gap") > 0.75 ? 4 + (int)(Frac(key + "gp") * 8) : 0);
        }
        for (var m = 0; m < 3; m++) town.Masts.Add((20 + m * 55 + (int)(Frac(name + "m" + m) * 20), 16 + (int)(Frac(name + "mh" + m) * 8)));
        town.Width = x;
        towns[name] = town;
        return town;
    }

    void DrawTown(string name, int shift, int baseY, double t, double fade)
    {
        if (name.Length == 0 || fade <= 0.01) return;
        var tf = fade;
        int Rim(int a) => W((int)((a + amb * 90) * tf));
        void Put(int x, int y, int v) { if (tf >= 1 || Bayer[y & 3, x & 3] < tf * 16) this.Put(x, y, v); }
        void Rect(int x0, int y0, int x1, int y1, int v) { for (var y = Math.Max(0, y0); y <= Math.Min(rows - 1, y1); y++) for (var x = Math.Max(0, x0); x <= Math.Min(cols - 1, x1); x++) Put(x, y, v); }
        var town = TownFor(name);
        var right = Math.Min(cols + 4, shift + town.Width);
        for (var x = Math.Max(0, shift - 6); x < right; x++)
        {
            var u = (x - shift) / (double)Math.Max(1, town.Width);
            var drop = 8 + (int)(Math.Sin(u * 9 + name.Length) * 3 + Math.Sin(u * 23) * 1.5);
            for (var y = baseY; y < Math.Min(rows, baseY + drop); y++) Put(x, y, bgPix);
            Put(x, baseY + drop, Rim(70));
            Put(x, baseY, Rim(120));
            if ((x - shift) % 5 == 0) for (var y = baseY + 2; y < baseY + drop - 1; y += 2) Put(x, y, W(30));
        }
        foreach (var (hx0, w, h, gable, church, chim) in town.Houses)
        {
            var x0 = shift + hx0;
            if (x0 > cols || x0 + w < 0) continue;
            var top = baseY - h;
            Rect(x0, top, x0 + w - 1, baseY - 1, bgPix);
            for (var y = top; y < baseY; y++) { Put(x0, y, Rim(80)); Put(x0 + w - 1, y, Rim(55)); }
            if (gable)
                for (var k = 0; k <= w / 2; k++)
                {
                    for (var x = x0 + k; x < x0 + w - k; x++) Put(x, top - k, bgPix);
                    Put(x0 + k, top - k, Rim(100)); Put(x0 + w - 1 - k, top - k, Rim(100));
                }
            else for (var x = x0; x < x0 + w; x++) Put(x, top, Rim(85));
            if (church)
            {
                var sx = x0 + w / 2;
                var sb = top - (gable ? w / 2 : 0);
                for (var k = 0; k < 10; k++) { Put(sx - (10 - k) / 4, sb - k, Rim(100)); Put(sx + (10 - k) / 4, sb - k, Rim(100)); }
                Put(sx, sb - 11, Rim(170)); Put(sx, sb - 12, Rim(170)); Put(sx - 1, sb - 11, Rim(170)); Put(sx + 1, sb - 11, Rim(170));
            }
            if (chim >= 0)
            {
                var cx = x0 + chim;
                var cy = top - (gable ? Math.Max(0, w / 2 - Math.Abs(chim - w / 2)) : 0);
                Put(cx, cy - 1, Rim(80)); Put(cx, cy - 2, Rim(80));
                for (var i = 0; i < 5; i++)
                {
                    var age = (t * 0.22 + i / 5.0 + chim * 0.13) % 1.0;
                    var px = cx + (int)(age * 7 + Math.Sin(age * 6 + i));
                    var py = cy - 3 - (int)(age * 9);
                    if (Bayer[py & 3, px & 3] < (1 - age) * 6) Max(px, py, (int)(34 * (1 - age)));
                }
            }
        }
        foreach (var (wx, wy, phase) in town.Windows)
        {
            var x = shift + wx;
            if (x < 0 || x >= cols) continue;
            var on = Math.Sin(t * 0.2 + phase) > -0.7;
            Put(x, baseY - wy - 1, on ? amber : W(28));
        }
        for (var lx = shift + 6; lx < right; lx += 15)
        {
            if (lx < 0) continue;
            for (var y = baseY - 4; y < baseY; y++) Put(lx, y, Rim(90));
            Put(lx, baseY - 5, amber);
            for (var y = baseY - 9; y <= baseY - 2; y++)
                for (var x = lx - 3; x <= lx + 3; x++)
                {
                    var d = Math.Sqrt((x - lx) * (x - lx) + (y - baseY + 5) * (y - baseY + 5) * 1.4);
                    if (d < 3.5 && (1 - d / 3.5) * 7 > Bayer[y & 3, x & 3] + 1) Max(x, y, 46);
                }
        }
        foreach (var (mx0, mh) in town.Masts)
        {
            var mx = shift + mx0;
            if (mx < -10 || mx > cols + 10) continue;
            for (var y = baseY - mh; y < baseY; y++) Put(mx, y, Rim(70));
            for (var k = 0; k < 3; k++) { var yy = baseY - mh + 2 + k * 5; for (var x = mx - 4 + k; x <= mx + 4 - k; x++) Put(x, yy, Rim(60)); }
        }
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 60 || h < 60) return;
        var t = Now;
        var dt = Math.Min(0.1, Math.Max(0, t - lastT));
        lastT = t;
        var c = (int)Math.Ceiling(w / P);
        var r = (int)Math.Ceiling(h / P);
        if (bitmap == null || c != cols || r != rows)
        {
            cols = c; rows = r;
            bitmap = new WriteableBitmap(cols, rows, 96, 96, PixelFormats.Pbgra32, null);
            buf = new int[cols * rows];
            edge = new int[cols];
            var rr = new Random(9);
            stars = Enumerable.Range(0, 30).Select(_ => (rr.Next(cols), rr.Next(Math.Max(1, (int)(rows * 0.45))), rr.NextDouble() * 9)).ToArray();
            rain = Enumerable.Range(0, cols * rows / 30).Select(_ => (rr.NextDouble(), rr.NextDouble(), 0.7 + rr.NextDouble() * 0.6)).ToArray();
        }
        Array.Clear(buf);

        var sailing = sailStart >= 0;
        var st = sailing ? t - sailStart : 0;
        if (sailing && st > SailFor) { sailStart = -1; sailing = false; from = ""; }
        var storming = sailing && storm2 ? Ease((st - 3.5) / 2) * (1 - Ease((st - 12.5) / 2)) : 0;
        sailFill += ((sailing && st < SailFor - 2.5 ? 1 : 0) - sailFill) * Math.Min(1, dt * 0.8);
        shake = Math.Max(0, shake - dt * 2.5);
        var speed = sailing ? (st < 2.5 ? st / 2.5 : st > SailFor - 3 ? Math.Max(0, (SailFor - st) / 3) : 1) : 0;
        heave = 1 + storming * 1.8 + speed * 0.3;
        foreach (var l in sea) l.Drift += (l.Speed * 0.9 + l.Speed * 4 * speed) * dt;
        storm.Drift += (storm.Speed + 6 * speed) * dt;

        if (storming > 0.25 && rng.NextDouble() < dt * 1.1)
            flashes.Add(new Flash { Start = t, Life = 0.4 + rng.NextDouble() * 0.4, Power = 0.6 + rng.NextDouble() * 0.4, X = rng.Next(cols), Y = 3 + rng.Next(6), Layer = rng.Next(4) == 0 ? 0 : 3, Seed = rng.Next() });
        if (storming > 0.5 && t > boltUntil + 2.5 && rng.NextDouble() < dt * 0.35) { boltUntil = t + 0.5; boltX = rng.Next(cols); flashes.Add(new Flash { Start = t, Life = 0.55, Power = 1, X = boltX, Y = 2, Layer = 3, Seed = rng.Next() }); }
        if (!sailing && t > nextFar) { flashes.Add(new Flash { Start = t, Life = 0.5, Power = 0.5, X = rng.Next(cols), Y = 4, Layer = 0, Seed = rng.Next() }); nextFar = t + 9 + rng.NextDouble() * 18; }
        flashes.RemoveAll(f => t - f.Start > f.Life);
        amb = 0;
        foreach (var f in flashes) amb = Math.Max(amb, Flicker(t - f.Start, f.Life) * f.Power * (f.Layer == 3 ? 0.55 : 0.15));
        if (t < boltUntil) amb = Math.Max(amb, Flicker(0.55 - (boltUntil - t), 0.55) * 0.9);

        var bob = Math.Sin(t * 0.9) * (0.6 + storming * 1.6 + speed * 0.4) + Math.Sin(t * 37) * shake;
        var mid = cols / 2.0;

        double[] at = { 0.50, 0.68, 0.90 };
        for (var i = 0; i < sea.Length; i++) sea[i].Base = h * at[i] + sea[i].Scale * 0.35;
        storm.Base = -storm.Scale * 0.15 + h * 0.06 * storming;

        var clear = 1 - storming;
        for (var y = 0; y < rows * 0.7; y++)
        {
            var v = Math.Pow(Math.Max(0, 1 - y / (rows * 0.6)), 1.3) * (0.30 + storming * 0.15) + amb * 0.18;
            var a = W(12 + (int)(amb * 26));
            for (var x = 0; x < cols; x++) if (Bayer[y & 3, x & 3] / 16.0 < v * 0.5) buf[y * cols + x] = a;
        }
        if (clear > 0.1)
        {
            foreach (var (sx, sy, ph) in stars) Put(sx, sy, W((int)((60 + 50 * Math.Sin(t * 0.6 + ph)) * clear)));
            var mx = (int)(cols * 0.80); var my = (int)(rows * 0.09); var mr = 5;
            for (var y = my - mr; y <= my + mr; y++)
                for (var x = mx - mr; x <= mx + mr; x++)
                {
                    var d = Math.Sqrt((x - mx) * (x - mx) + (y - my) * (y - my));
                    var lit = Math.Sqrt((x - mx - 2) * (x - mx - 2) + (y - my + 1) * (y - my + 1)) > mr - 1;
                    if (d <= mr && lit) Put(x, y, W((int)((d > mr - 1 ? 230 : 140) * clear)));
                }
        }
        DrawCloud(storm, 3, t, true);

        DrawCloud(sea[0], 0, t, false);
        var quay = (int)(rows * 0.44);
        if (sailing)
        {
            var pace = (sea[1].Speed * 0.9 + sea[1].Speed * 4) * 0.8 / P;
            double Run(double s) => s < 2.5 ? pace * s * s / 5 : pace * 1.25 + pace * (Math.Min(s, SailFor - 3) - 2.5) + (s > SailFor - 3 ? pace * (s - (SailFor - 3)) * (1 - (s - (SailFor - 3)) / 6) : 0);
            DrawTown(from, (int)Run(st), quay, t, 1 - Ease((st - 1) / 6));
            if (st > SailFor - 9) DrawTown(here, -(int)(Run(SailFor) - Run(st)), quay, t, Ease((st - (SailFor - 9)) / 6));
        }
        else DrawTown(here, 0, quay, t, 1);
        if (sailing && monster == 2 && st > 4.5 && st < 14)
        {
            var p = (st - 4.5) / 9.5;
            var hx = (int)(-40 + p * (cols + 80));
            for (var k = 0; k < 5; k++)
            {
                var cx = hx - k * 9;
                var rise = Math.Sin(t * 2.4 - k * 0.9) * 0.5 + 0.5;
                var rad = (int)(2 + rise * 3);
                var baseY = SeaCell(1, Math.Clamp(cx, 0, cols - 1), t) + 2;
                for (var dx = -rad; dx <= rad; dx++)
                {
                    var hgt = (int)Math.Round(Math.Sqrt(Math.Max(0, rad * rad - dx * dx)));
                    for (var y = baseY - hgt; y <= baseY; y++) Put(cx + dx, y, bgPix);
                    Put(cx + dx, baseY - hgt, Rim(190));
                    if (dx % 2 == 0 && hgt > 1) Put(cx + dx, baseY - hgt + 1, Rim(90));
                }
                if (k == 0)
                {
                    for (var y = baseY - 10; y < baseY - rad; y++) { Put(cx + rad - 1, y, Rim(190)); Put(cx + rad + 1, y, Rim(130)); Put(cx + rad, y, bgPix); }
                    Rect(cx + rad - 1, baseY - 13, cx + rad + 4, baseY - 10, bgPix);
                    for (var x = cx + rad - 1; x <= cx + rad + 4; x++) Put(x, baseY - 13, Rim(190));
                    Put(cx + rad + 4, baseY - 12, Rim(190)); Put(cx + rad + 4, baseY - 11, Rim(190));
                    Put(cx + rad + 2, baseY - 12, W(255));
                    Put(cx + rad - 2, baseY - 14, Rim(150)); Put(cx + rad - 3, baseY - 15, Rim(150));
                }
            }
        }
        if (sailing && ghost && st > 3 && st < 15)
        {
            var p = (st - 3) / 12;
            var gx = (int)(-40 + p * (cols + 80));
            var gy = SeaCell(0, Math.Clamp(gx, 0, cols - 1), t) + 1;
            var a = Ease(p * 4) * (1 - Ease((p - 0.75) * 4));
            int G(int v) => W((int)(v * a));
            for (var dx = -16; dx <= 16; dx++)
            {
                var top = gy - 4 - (dx > 8 ? (dx - 8) / 2 : 0);
                for (var y = top; y <= gy + 2; y++) if (Bayer[y & 3, (gx + dx) & 3] < a * 16) Put(gx + dx, y, bgPix);
                Put(gx + dx, top, G(110));
            }
            foreach (var (mx, mh) in new[] { (-8, 20), (2, 25), (11, 16) })
            {
                for (var y = gy - 4 - mh; y < gy - 4; y++) Put(gx + mx, y, G(80));
                for (var k = 0; k < 2; k++)
                {
                    var yt = gy - 4 - mh + 3 + k * 9;
                    for (var dx = -5; dx <= 5; dx++)
                    {
                        Put(gx + mx + dx, yt, G(70));
                        var torn = (int)(Frac(dx + mx * 7, k) * 5);
                        for (var y = yt + 1; y < yt + 7 - torn; y++) if (Frac(gx * 0 + dx * 3 + mx, y - yt + k * 11) > 0.25 && Bayer[y & 3, (gx + mx + dx) & 3] < a * 10) Put(gx + mx + dx, y, W((int)(26 * a)));
                    }
                }
            }
            if (Math.Sin(t * 2.2) > -0.3) Put(gx + 16, gy - 7 + (int)Math.Round(Math.Sin(t * 1.4)), G(220));
        }
        DrawCloud(sea[1], 1, t, false);
        for (var band = 0; band < 2; band++)
        {
            var yc = rows * (0.66 + band * 0.12);
            var th = rows * 0.035;
            for (var y = Math.Max(0, (int)(yc - th * 2)); y < Math.Min(rows, (int)(yc + th * 2)); y++)
            {
                var g0 = Math.Exp(-Math.Pow((y - yc) / th, 2));
                for (var x = 0; x < cols; x++)
                {
                    var n = 0.5 + 0.5 * Math.Sin((x - t * (2 + band) - sea[0].Drift / P) * 0.05 + band * 3) * Math.Sin((x - t * 1.3) * 0.023 + y * 0.2);
                    if (g0 * (0.35 + 0.65 * n) * (0.45 + storming * 0.3) * 16 <= Bayer[y & 3, (x + (int)t) & 3]) continue;
                    var k = y * cols + x;
                    if (buf[k] == bgPix || ((buf[k] >> 24) & 0xFF) < 26) buf[k] = W(26);
                }
            }
        }

        var decks = Math.Clamp((profiles + 1) / 2, 1, 3);
        var railY = (int)Math.Round(rows * (0.50 - 0.04 * (decks - 1)) + bob);
        var mastX = (int)(cols * 0.40);
        if (sailFill > 0.05)
        {
            for (var x = 0; x < cols; x++)
            {
                var u = x / (double)cols;
                var foot = (int)Math.Round(rows * 0.13 + Math.Sin(u * Math.PI) * rows * 0.06 * sailFill + Math.Sin(t * 5 + x * 0.3) * 0.6 * storming - (1 - sailFill) * rows * 0.12 + bob * 0.5);
                for (var y = 0; y < Math.Min(rows, foot); y++)
                {
                    var v = bgPix;
                    if (Bayer[y & 3, x & 3] < 2) v = W(30 + (int)(amb * 70));
                    if (x % 9 == 0) v = W(52);
                    buf[y * cols + x] = v;
                }
                Put(x, foot, Rim(210));
                if (x % 4 == 1) Put(x, foot - 3, Rim(110));
            }
        }
        else
        {
            var yardY = (int)Math.Round(rows * 0.05 + bob * 0.5);
            for (var x = 0; x < cols; x++)
            {
                Put(x, yardY, Rim(190));
                var lump = (int)(2 + Math.Sin(x * 0.45) + Math.Sin(x * 0.13) * 0.8);
                for (var y = yardY + 1; y <= yardY + lump; y++) Put(x, y, bgPix);
                Put(x, yardY + lump + 1, Rim(120));
                if (x % 7 == 3) for (var y = yardY; y <= yardY + lump + 1; y++) Put(x, y, Rim(90));
            }
        }
        var shroudX = new[] { mastX - 30, mastX - 20, mastX - 11, mastX + 11, mastX + 20, mastX + 30 };
        var headY = -rows * 1.4;
        foreach (var sx in shroudX) Line(sx, railY - 1, mastX + (sx < mastX ? -2 : 2), (int)headY, Rim(115));
        for (var y = railY - 4; y > 0; y -= 4)
        {
            var f = (railY - 1 - y) / (railY - 1 - headY);
            for (var i = 0; i < shroudX.Length - 1; i++)
            {
                if ((shroudX[i] < mastX) != (shroudX[i + 1] < mastX)) continue;
                var xa = (int)Math.Round(shroudX[i] + (mastX - shroudX[i]) * f);
                var xb = (int)Math.Round(shroudX[i + 1] + (mastX - shroudX[i + 1]) * f);
                for (var x = Math.Min(xa, xb) + 1; x < Math.Max(xa, xb); x++) Put(x, y, Rim(55));
            }
        }
        for (var y = 0; y < Math.Min(rows, railY); y++)
            for (var x = mastX - 2; x <= mastX + 2; x++)
            {
                var edgePx = x == mastX - 2 || x == mastX + 2;
                var band = (y + 400) % 18 < 2;
                Put(x, y, edgePx ? Rim(170) : band ? Rim(120) : Bayer[y & 3, x & 3] < 1 ? W(40) : bgPix);
            }
        {
            var lx = mastX + 16; var ly = railY - 12 + (int)Math.Round(Math.Sin(t * 1.3) * 0.6);
            Line(lx, ly - 5, lx, ly - 2, Rim(100));
            Rect(lx - 1, ly - 1, lx + 1, ly + 1, amber);
            Put(lx - 2, ly - 1, Rim(150)); Put(lx + 2, ly - 1, Rim(150)); Put(lx - 2, ly + 1, Rim(150)); Put(lx + 2, ly + 1, Rim(150));
            var flick = 0.8 + 0.2 * Math.Sin(t * 9 + Math.Sin(t * 3));
            for (var y = ly - 7; y <= ly + 7; y++)
                for (var x = lx - 8; x <= lx + 8; x++)
                {
                    var d = Math.Sqrt((x - lx) * (x - lx) + (y - ly) * (y - ly) * 1.2);
                    if (d < 8 && (1 - d / 8) * 7 * flick > Bayer[y & 3, x & 3] + 1) Max(x, y, 42);
                }
        }
        for (var x = 0; x < cols; x++)
        {
            Put(x, railY, Rim(210));
            Put(x, railY + 1, Rim(110));
            if (x % 3 == 0) for (var y = railY + 2; y < railY + 5; y++) Put(x, y, Rim(85));
            Put(x, railY + 5, Rim(150));
        }

        var hullTop = railY + 6;
        var waterBase = rows * 0.86;
        for (var y = Math.Max(0, hullTop); y < rows; y++)
        {
            var row = y - hullTop;
            for (var x = 0; x < cols; x++)
            {
                var v = bgPix;
                if (row % 3 == 0) v = W(30 + (int)(amb * 40));
                else if ((x + (row / 3) * 11) % 22 == 0) v = W(30);
                else if (Bayer[y & 3, x & 3] == 0 && Frac(x, y) > 0.6) v = W(20);
                buf[y * cols + x] = v;
            }
        }
        var nameY = hullTop + 2;
        var deckGap = Math.Max(11, (int)((waterBase - (nameY + 13) - 4) / decks));
        ports.Clear();
        for (var d = 0; d < decks; d++)
        {
            var py = nameY + 13 + d * deckGap;
            for (var x = 0; x < cols; x++) { Put(x, py + 9, Rim(150)); Put(x, py + 10, Rim(85)); Put(x, py + 11, Rim(60)); }
            var offs = (d % 2) * 11;
            for (var px = 3 + offs; px + 8 < cols; px += 22)
            {
                ports.Add((px, py, 8, 6));
                for (var x = px; x < px + 8; x++) { Put(x, py - 3, Rim(115)); Put(x, py - 2, bgPix); }
                Rect(px, py, px + 7, py + 5, bgPix);
                for (var x = px; x < px + 8; x++) { Put(x, py, Rim(170)); Put(x, py + 5, Rim(170)); }
                for (var y = py; y <= py + 5; y++) { Put(px, y, Rim(170)); Put(px + 7, y, Rim(170)); }
                for (var y = py + 1; y < py + 5; y++) for (var x = px + 1; x < px + 7; x++) if (Bayer[y & 3, x & 3] < 3) Put(x, y, W(42));
                var gx = px + 4; var gy = py + 3;
                Put(gx - 1, gy - 1, Rim(200)); Put(gx, gy - 1, Rim(200)); Put(gx - 2, gy, Rim(200)); Put(gx + 1, gy, Rim(200));
                Put(gx - 1, gy + 1, Rim(200)); Put(gx, gy + 1, Rim(200));
            }
        }
        {
            string shown; double fadeOld = 1, write = 99;
            if (sailing && from.Length > 0)
            {
                if (st < 7) shown = from;
                else if (st < 9) { shown = from; fadeOld = 1 - Ease((st - 7) / 2); }
                else { shown = here; write = (st - 9) / 0.28; }
            }
            else shown = here;
            var name = shown.ToUpperInvariant();
            var fit = Math.Max(1, (cols - 14) / 6);
            if (name.Length > fit) name = name[..fit];
            var bw = Math.Max(here.Length, from.Length > 0 ? Math.Min(fit, from.Length) : 0);
            bw = Math.Min(fit, Math.Max(name.Length, bw)) * 6 + 6;
            var bx = Math.Clamp((int)(cols * 0.62 - bw / 2.0), 3, Math.Max(3, cols - bw - 3));
            Rect(bx, nameY, bx + bw, nameY + 10, bgPix);
            for (var x = bx; x <= bx + bw; x++) { Put(x, nameY, Rim(150)); Put(x, nameY + 10, Rim(150)); }
            for (var y = nameY; y <= nameY + 10; y++) { Put(bx, y, Rim(150)); Put(bx + bw, y, Rim(150)); }
            Put(bx - 1, nameY + 2, Rim(120)); Put(bx - 2, nameY + 3, Rim(120)); Put(bx - 1, nameY + 4, Rim(120));
            Put(bx + bw + 1, nameY + 6, Rim(120)); Put(bx + bw + 2, nameY + 7, Rim(120)); Put(bx + bw + 1, nameY + 8, Rim(120));
            var lx = bx + (bw - name.Length * 6) / 2 + 1;
            for (var ci = 0; ci < name.Length; ci++)
            {
                var g = Pixels.FineGlyph(name[ci]);
                var letterIn = Math.Clamp(write - ci, 0, 1);
                var gleam = write - ci > 0 && write - ci < 1.6;
                if (g != null && letterIn > 0)
                    for (var gr = 0; gr < 7; gr++)
                        for (var gc = 0; gc < 5; gc++)
                        {
                            if (!g[gr * 2, gc * 2]) continue;
                            var x = lx + gc; var y = nameY + 2 + gr;
                            var keep = fadeOld * letterIn;
                            if (keep < 1 && Bayer[y & 3, x & 3] >= keep * 16) continue;
                            Put(x, y, gleam && gr <= (int)((write - ci) * 7) + 1 ? W(255) : amber);
                        }
                lx += 6;
            }
        }

        DrawCloud(sea[2], 2, t, false);
        if (sailing && monster == 1 && st > 3.8 && st < 6.5)
        {
            var open = Math.Sin(Math.Clamp((st - 3.8) / 2.7, 0, 1) * Math.PI);
            var ex = (int)(cols * 0.52);
            var ey = SeaCell(2, ex, t) + 6;
            foreach (var off in new[] { -7, 7 })
                for (var dx = -2; dx <= 2; dx++)
                    for (var dy = -1; dy <= 1; dy++)
                        if (Math.Abs(dy) * 2.5 < open * 3 - Math.Abs(dx) * 0.4) Put(ex + off + dx, ey + dy, dx == 0 && dy == 0 ? W(255) : amber);
        }
        if (sailing && monster == 1 && st > 5.5 && st < 13.5)
        {
            foreach (var (bxF, delay, size) in new[] { (0.74, 0.0, 1.0), (0.16, 0.9, 0.7) })
            {
                var a = st - 5.5 - delay;
                if (a < 0) continue;
                var up = Ease(a / 2) * (1 - Ease((a - 5) / 1.6));
                if (up <= 0) continue;
                var len = rows * 0.6 * size * up;
                var bx = (int)(cols * bxF);
                var baseY = SeaCell(2, bx, t) + 3;
                for (var s = 0.0; s < len; s += 0.5)
                {
                    var f = s / Math.Max(1, len);
                    var curl = f > 0.78 ? (f - 0.78) * 4.5 : 0;
                    var x = bx + Math.Sin(s * 0.11 - t * 2.1) * (3 + s * 0.12) * size + curl * curl * 9 * size;
                    var y = baseY - s + curl * curl * 6;
                    var wdt = Math.Max(1, 5 * size * (1 - f * 0.85));
                    for (var q = -wdt; q <= wdt; q += 1) Put((int)Math.Round(x + q), (int)Math.Round(y), Math.Abs(q) > wdt - 1 ? Rim(210) : bgPix);
                    if ((int)(s * 2) % 7 == 0 && f < 0.82) Put((int)Math.Round(x - wdt + 1.5), (int)Math.Round(y), Rim(150));
                }
            }
        }

        fired.RemoveAll(f => t - f.At > 0.25);
        foreach (var (fx, fy, fat) in fired)
        {
            var age = (t - fat) / 0.25;
            var rad = 2 + age * 5;
            for (var y = (int)(fy - rad); y <= fy + rad; y++)
                for (var x = (int)(fx - rad); x <= fx + rad; x++)
                {
                    var d = Math.Sqrt((x - fx) * (x - fx) + (y - fy) * (y - fy));
                    if (d < rad && (1 - d / rad) * (1 - age) * 18 > Bayer[y & 3, x & 3]) Put(x, y, d < rad * 0.4 ? W(255) : amber);
                }
        }
        for (var i = smoke.Count - 1; i >= 0; i--)
        {
            var (x, y, vx, vy, rad, life) = smoke[i];
            x += vx * dt; y += vy * dt; vx *= 0.97; vy -= 1.5 * dt; rad += dt * 2.5; life -= dt * 0.35;
            if (life <= 0) { smoke.RemoveAt(i); continue; }
            smoke[i] = (x, y, vx, vy, rad, life);
            for (var yy = (int)(y - rad); yy <= y + rad; yy++)
                for (var xx = (int)(x - rad); xx <= x + rad; xx++)
                {
                    var d = Math.Sqrt((xx - x) * (xx - x) + (yy - y) * (yy - y));
                    if (d < rad && life * (1 - d / rad) * 10 > Bayer[yy & 3, xx & 3]) Max(xx, yy, (int)(90 * life));
                }
        }

        if (t < boltUntil)
        {
            var fl = Flicker(0.55 - (boltUntil - t), 0.55);
            if (fl > 0.05)
            {
                var y = Math.Max(0, (int)Math.Floor(Hang(storm, (boltX + 0.5) * P, t) / P) - 1);
                var bx = boltX;
                var end = SeaCell(0, (int)Math.Clamp(boltX, 0, cols - 1), t);
                var bseed = (int)(boltUntil * 100);
                while (y < end)
                {
                    bx += (Frac(bseed, y) - 0.5) * 2.4;
                    Put((int)bx, y, W((int)(255 * fl)));
                    Max((int)bx + 1, y, (int)(80 * fl));
                    y++;
                }
            }
        }
        if (storming > 0.05)
        {
            var n = (int)(rain.Length * storming);
            for (var i = 0; i < n; i++)
            {
                var (rx, ry, rv) = rain[i];
                var yy = (ry * (rows + 20) + t * rows * (1.4 * rv)) % (rows + 20) - 10;
                var xx = ((rx * cols - yy * 0.45 - t * 20) % cols + cols) % cols;
                for (var k = 0; k < 3; k++) Max((int)(xx + k * 0.45), (int)yy - k, (int)(36 + amb * 120));
            }
        }

        for (var y = 0; y < rows; y++)
            for (var x = 0; x < cols; x++)
            {
                var dx = (x - mid) / mid; var dy = (y - rows * 0.55) / (rows * 0.6);
                var v = dx * dx * 0.6 + dy * dy;
                if (v < 0.75) continue;
                var k = y * cols + x;
                if (buf[k] != 0 && buf[k] != bgPix && Bayer[y & 3, x & 3] < (v - 0.75) * 30) buf[k] = bgPix;
            }

        var veilY = Veil * rows;
        for (var y = 0; y < Math.Min(rows, (int)veilY + 8); y++)
        {
            var f = y < veilY - 6 ? 0.42 : 0.42 + 0.58 * Ease((y - (veilY - 6)) / 14.0);
            if (f >= 0.99) continue;
            for (var x = 0; x < cols; x++)
            {
                var k = y * cols + x;
                var v = buf[k];
                if (v == 0 || v == bgPix) continue;
                var a = (int)(((v >> 24) & 0xFF) * f); var rr = (int)(((v >> 16) & 0xFF) * f); var gg = (int)(((v >> 8) & 0xFF) * f); var bb = (int)((v & 0xFF) * f);
                buf[k] = (a << 24) | (rr << 16) | (gg << 8) | bb;
            }
        }

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));
    }
}
