using System.Globalization;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class Harbor : FrameworkElement
{
    const int P = 3;
    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    sealed class Cloud
    {
        public double[] Edge = Array.Empty<double>();
        public double[] Folds = Array.Empty<double>();
        public int Period;
        public double Base, Scale, Speed, SwellA, SwellL, SwellW, Phase;
        public byte Line, Fold, Dot;
    }

    sealed class Vessel
    {
        public string Id = "", Title = "";
        public int Slot;
        public double X, Fade, Seed, Wy = double.NaN;
        public bool Leaving;
        public int Px0, Px1, Py0, Py1;
    }

    sealed class Flash { public double Start, Life, Power, X; public int Seed; }
    sealed class Bolt { public double Start, Life; public readonly List<(int X, int Y)> Path = new(); public readonly List<List<(int X, int Y)>> Branches = new(); }

    public event Action<string>? Picked;

    readonly List<Vessel> vessels = new();
    readonly Cloud[] sea;
    readonly Cloud storm;
    readonly List<Flash> flashes = new();
    readonly List<Bolt> bolts = new();
    readonly List<(double X, double Y, double Vx, double Vy, double Life)> spray = new();
    readonly Random rng = new();
    readonly DateTime start = DateTime.UtcNow;
    double lastT, nextFlash = 0.6, nextBolt = 5, nextSpray = 1, towerHit = -9;
    bool running, drawn;
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();
    int[] edge = Array.Empty<int>();
    double[] rainX = Array.Empty<double>(), rainY = Array.Empty<double>(), rainV = Array.Empty<double>();
    int bgPix = unchecked((int)0xFF0B0B0C), red = unchecked((int)0xFFE5484D);
    Brush text = Brushes.White, bg = Brushes.Black;
    Typeface face = new("Consolas");
    Vessel? hover;

    int quayX0, deckY, craneA, craneB, boomY, rodX, rodY;
    double craneStart = -1;
    int landed;

    readonly List<(double Lx, double Ly, double Tx, double Ty, double R)> cones = new();

    public Harbor()
    {
        ClipToBounds = true;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        sea = new[]
        {
            MakeCloud(seed: 19, period: 2600, scale: 16, speed: 5,  swellA: 2, swellL: 500, swellW: 0.40, line: 46,  dot: 13),
            MakeCloud(seed: 29, period: 3000, scale: 24, speed: 10, swellA: 3, swellL: 640, swellW: 0.50, line: 96,  dot: 16),
            MakeCloud(seed: 43, period: 3600, scale: 34, speed: 16, swellA: 5, swellL: 800, swellW: 0.60, line: 170, dot: 20),
        };
        storm = MakeCloud(seed: 71, period: 3600, scale: 40, speed: 4, swellA: 4, swellL: 900, swellW: 0.25, line: 40, dot: 12);
        Loaded += (_, _) =>
        {
            text = (Brush)FindResource("Text");
            if (TryFindResource("Bg") is SolidColorBrush b) { bg = b; bgPix = Argb(b.Color); }
            if (TryFindResource("Bad") is SolidColorBrush r) red = Argb(r.Color);
            if (TryFindResource("Mono") is FontFamily mono) face = new Typeface(mono, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            Start();
        };
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
        MouseMove += (_, e) => { hover = At(e.GetPosition(this)); Cursor = hover != null ? Cursors.Hand : null; };
        MouseLeave += (_, _) => { hover = null; Cursor = null; };
        MouseLeftButtonUp += (_, e) => { if (At(e.GetPosition(this)) is { } v && v.Id.Length > 0) Picked?.Invoke(v.Id); };
    }

    static int Argb(Color c) => unchecked((int)(0xFF000000u | ((uint)c.R << 16) | ((uint)c.G << 8) | c.B));
    void Start() { if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; } running = true; Anim.Add(Tick); }
    void Stop() { if (!running) return; running = false; Anim.Remove(Tick); }
    void Tick(object? s, EventArgs e) => InvalidateVisual();

    Vessel? At(Point p)
    {
        int x = (int)(p.X / P), y = (int)(p.Y / P);
        foreach (var v in vessels.Where(v => !v.Leaving && v.Fade > 0.5).OrderBy(v => v.Slot))
            if (x >= v.Px0 && x <= v.Px1 && y >= v.Py0 && y <= v.Py1) return v;
        return null;
    }

    public void SetCargo(IEnumerable<(string Id, string Title)> top)
    {
        var want = top.Take(3).ToList();
        for (var slot = 0; slot < 3; slot++)
        {
            var cur = vessels.FirstOrDefault(v => v.Slot == slot && !v.Leaving);
            if (slot >= want.Count) { if (cur != null) cur.Leaving = true; continue; }
            var (id, title) = want[slot];
            if (cur != null && cur.Id == id) { cur.Title = title; continue; }
            if (cur != null) cur.Leaving = true;
            vessels.Add(new Vessel
            {
                Id = id, Title = title, Slot = slot, Seed = Frac(id) * 1000,
                X = slot == 0 ? double.NaN : 0, Fade = slot == 0 ? 1 : 0,
            });
        }
    }

    public void Unload()
    {
        var t = (DateTime.UtcNow - start).TotalSeconds;
        if (craneStart >= 0 && t - craneStart < Crane.Total) return;
        craneStart = t;
    }

    static double Frac(string s) { uint h = 2166136261; foreach (var c in s) { h ^= c; h *= 16777619; } return (h & 0xFFFF) / 65536.0; }
    static double Frac(double a, double b) { var v = Math.Sin(a * 12.9898 + b * 78.233) * 43758.5453; return v - Math.Floor(v); }

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

    static double Swell(Cloud l, double x, double t) =>
        l.SwellA * Math.Sin(2 * Math.PI * x / l.SwellL - t * l.SwellW + l.Phase)
        + l.SwellA * 0.45 * Math.Sin(2 * Math.PI * x / (l.SwellL * 0.53) + t * l.SwellW * 0.7 + l.Phase * 1.7);

    static double Surface(Cloud l, double x, double t) => l.Base - Read(l.Edge, l.Period, x - l.Speed * t) + Swell(l, x, t);
    static double Hang(Cloud l, double x, double t) => l.Base + Read(l.Edge, l.Period, x + l.Speed * t) + Swell(l, x, t);
    int SeaCell(int k, double x, double t) => (int)Math.Floor(Surface(sea[k], (x + 0.5) * P, t) / P);

    static int W(int a) { a = Math.Clamp(a, 0, 255); return (a << 24) | (a << 16) | (a << 8) | a; }
    void Put(int x, int y, int v) { if (x >= 0 && y >= 0 && x < cols && y < rows) buf[y * cols + x] = v; }
    void Max(int x, int y, int a)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        var i = y * cols + x;
        if (((buf[i] >> 24) & 0xFF) < a) buf[i] = W(a);
    }
    void Fill(int x, int y, double fade) { if (fade >= 1 || Bayer[y & 3, x & 3] < fade * 16) Put(x, y, bgPix); }
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

    void DrawCloud(Cloud l, double t, bool hanging, double glowX, double glow)
    {
        for (var x = 0; x < cols; x++)
            edge[x] = (int)Math.Floor((hanging ? Hang(l, (x + 0.5) * P, t) : Surface(l, (x + 0.5) * P, t)) / P);
        var fade = Math.Max(3, l.Scale / P * 0.6);
        for (var x = 0; x < cols; x++)
        {
            int from = hanging ? 0 : Math.Max(0, edge[x] + 1), to = hanging ? Math.Min(rows, edge[x]) : rows;
            var gx = glow > 0 ? Math.Max(0, 1 - Math.Abs(x - glowX) / 30.0) * glow : 0;
            for (var y = from; y < to; y++)
            {
                var d = hanging ? edge[x] - y : y - edge[x];
                var v = bgPix;
                var g = gx > 0 ? gx * Math.Max(0, 1 - d / 14.0) : 0;
                if (d <= fade || g > 0.03)
                {
                    var dens = (d <= fade ? 0.36 * (1 - d / fade) : 0) + 0.04 * Math.Sin(x * 0.5 + y * 0.3 - t * 1.2) + g * 0.6;
                    if (dens * 16 > Bayer[y & 3, x & 3]) v = W((int)(l.Dot + g * 180));
                }
                buf[y * cols + x] = v;
            }
        }
        var depth = l.Scale * 0.62;
        var prev = int.MinValue;
        var foldC = W(l.Fold);
        for (var x = 0; x < cols; x++)
        {
            var sx = (x + 0.5) * P;
            var rise = Read(l.Folds, l.Period, hanging ? sx + l.Speed * t : sx - l.Speed * t);
            if (rise < 1.5) { prev = int.MinValue; continue; }
            var fy = (int)Math.Floor(((hanging ? l.Base - depth + rise : l.Base + depth - rise) + Swell(l, sx, t)) / P);
            var ok = hanging ? fy < edge[x] - 2 && fy >= 0 : fy > edge[x] + 2 && fy < rows;
            if (!ok) { prev = int.MinValue; continue; }
            Put(x, fy, foldC);
            if (prev != int.MinValue && Math.Abs(fy - prev) > 1 && Math.Abs(fy - prev) < 6)
            {
                if (prev < fy) for (var y = prev + 1; y < fy; y++) Put(x, y, foldC);
                else for (var y = fy + 1; y < prev; y++) Put(x - 1, y, foldC);
            }
            prev = fy;
        }
        for (var x = 0; x < cols; x++)
        {
            var gx = glow > 0 ? Math.Max(0, 1 - Math.Abs(x - glowX) / 30.0) * glow : 0;
            var c = W((int)Math.Max(l.Line, gx * 255));
            Put(x, edge[x], c);
            if (x == 0) continue;
            int a = edge[x - 1], b = edge[x];
            if (a < b - 1) for (var y = a + 1; y < b; y++) Put(x, y, c);
            else if (a > b + 1) for (var y = b + 1; y < a; y++) Put(x - 1, y, c);
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
                if (cur == bgPix || ((cur >> 24) & 0xFF) < alpha) buf[i] = W(alpha);
            }
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

    void AddBolt(double t)
    {
        var tower = rng.NextDouble() < 0.3;
        int ex = tower ? rodX : rng.Next(6, Math.Max(7, quayX0 - 10));
        int ey = tower ? rodY : SeaCell(0, ex, t);
        var sx = Math.Clamp(ex + rng.Next(-24, 25), 0, cols - 1);
        var sy = (int)Math.Floor(Hang(storm, (sx + 0.5) * P, t) / P) - 2;
        if (ey <= sy + 4) return;
        var b = new Bolt { Start = t, Life = 0.55 };
        double x = sx, drift = 0;
        var change = 0;
        for (var y = sy; y <= ey; y++)
        {
            if (--change <= 0) { drift = (rng.NextDouble() - 0.5) * 2.4; change = 2 + rng.Next(4); }
            x += drift + (ex - x) / (ey - y + 1) * 0.9;
            b.Path.Add(((int)Math.Round(x), y));
        }
        for (var i = 0; i < 1 + rng.Next(2); i++)
        {
            var from = b.Path[(int)(b.Path.Count * (0.15 + rng.NextDouble() * 0.5))];
            var d = rng.Next(2) == 0 ? -1 : 1;
            var br = new List<(int, int)>();
            double bx = from.X;
            for (var y = from.Y + 1; y < from.Y + 6 + rng.Next(14) && y < ey; y++) { bx += d * (0.3 + rng.NextDouble() * 1.2); br.Add(((int)Math.Round(bx), y)); }
            b.Branches.Add(br);
        }
        bolts.Add(b);
        flashes.Add(new Flash { Start = t, Life = 0.6, Power = 1, X = sx, Seed = rng.Next() });
        if (tower) towerHit = t;
    }

    static class Crane
    {
        public const double Out = 1.6, Down = 1.0, Up = 1.0, Back = 1.8, Set = 0.9, Rise = 0.8;
        public const double Total = Out + Down + Up + Back + Set + Rise;
    }

    (double Run, double Drop, bool Load, bool Set) CraneAt(double t)
    {
        if (craneStart < 0) return (0, 0.1, false, false);
        double E(double v) => v * v * (3 - 2 * v);
        var a = t - craneStart;
        if (a < Crane.Out) return (E(a / Crane.Out), 0.1, false, false);
        a -= Crane.Out;
        if (a < Crane.Down) return (1, 0.1 + 0.9 * E(a / Crane.Down), false, false);
        a -= Crane.Down;
        if (a < Crane.Up) return (1, 1 - 0.9 * E(a / Crane.Up), true, false);
        a -= Crane.Up;
        if (a < Crane.Back) return (1 - E(a / Crane.Back), 0.1, true, false);
        a -= Crane.Back;
        if (a < Crane.Set) return (0, 0.1 + 0.9 * E(a / Crane.Set), true, false);
        a -= Crane.Set;
        if (a < Crane.Rise) return (0, 1 - 0.9 * E(a / Crane.Rise), false, true);
        craneStart = -1;
        landed = Math.Min(6, landed + 1);
        return (0, 0.1, false, false);
    }

    (int X0, int X1, int Y0) Freighter(int cx, int wy, double L, double fade, int rim, double seed, double t, double lit)
    {
        var half = (int)(L / 2);
        var n = 2 * half + 1;
        var top = new int[n];
        var free = Math.Max(5, (int)Math.Round(L * 0.042));
        var bottomY = wy + 4;
        var stemRake = Math.Max(6, (int)(L * 0.06));
        var tier = 3;
        var stack = 5 * tier;

        int Deck(int dx)
        {
            var u = (dx + half) / (double)(2 * half);
            var bow = u > 0.85 ? Math.Pow((u - 0.85) / 0.15, 2) * free * 0.9 : 0;
            var stern = u < 0.06 ? 1 : 0;
            return wy - free - (int)Math.Round(bow) - stern;
        }
        var bridgeAt = (int)(half - L * 0.30);
        var bridgeW = Math.Max(8, (int)(L * 0.05));
        var bridgeH = (int)(L * 0.155);
        var casingAt = (int)(-half + L * 0.10);
        var casingW = Math.Max(7, (int)(L * 0.045));
        var casingH = (int)(L * 0.10);
        var boxesFrom = -half + (int)(L * 0.05);
        var boxesTo = half - (int)(L * 0.15);
        for (var i = 0; i < n; i++)
        {
            var dx = i - half;
            var d = Deck(dx);
            var h = d;
            if (dx >= boxesFrom && dx <= boxesTo)
            {
                var u = (dx - boxesFrom) / (double)(boxesTo - boxesFrom);
                var tiers = u > 0.88 ? 3 : u < 0.06 ? 4 : 5;
                h = Deck(dx) - tiers * tier;
                var bayNo = (dx - boxesFrom) / 16;
                if (bayNo % 7 == 3 || bayNo % 11 == 6) h += tier;
            }
            if (dx >= bridgeAt && dx < bridgeAt + bridgeW) h = d - bridgeH;
            if (dx >= bridgeAt - 4 && dx < bridgeAt + bridgeW + 4) h = Math.Min(h, d - bridgeH + 1);
            if (dx >= casingAt && dx < casingAt + casingW) h = Math.Min(h, d - casingH - (dx - casingAt) / 3);
            top[i] = h;
        }
        var wingY = Deck(bridgeAt) - bridgeH + 1;

        var rimC = W((int)(rim * 0.8 * fade + lit * 170));
        var texA = (int)(14 * fade + lit * 70);
        var minTop = wy;
        for (var i = 0; i < n; i++)
        {
            var dx = i - half;
            var x = cx + dx;
            var bottom = bottomY;
            if (dx > half - stemRake) bottom = top[i] + (int)Math.Round((bottomY - top[i]) * (1 - (dx - (half - stemRake)) / (double)stemRake));
            var wingOnly = (dx >= bridgeAt - 4 && dx < bridgeAt) || (dx >= bridgeAt + bridgeW && dx < bridgeAt + bridgeW + 4);
            for (var y = top[i]; y <= bottom; y++)
            {
                if (wingOnly && y > wingY + 1 && y < Deck(dx) - 5 * tier) continue;
                Fill(x, y, fade);
            }
            minTop = Math.Min(minTop, top[i]);
            Put(x, top[i], rimC);
            if (i > 0 && Math.Abs(top[i] - top[i - 1]) > 1)
                for (var y = Math.Min(top[i], top[i - 1]); y < Math.Max(top[i], top[i - 1]); y++) Put(top[i] < top[i - 1] ? x : x - 1, y, rimC);
            if (dx > half - stemRake) Put(x, bottom, rimC);
            if (dx >= boxesFrom && dx <= boxesTo && texA > 6)
                for (var y = top[i] + 1; y < Deck(dx); y++)
                    if (((dx - boxesFrom) % 4 == 0 || (y - top[i]) % tier == 0) && Bayer[y & 3, x & 3] < 6) Put(x, y, W(texA));
        }
        for (var y = top[0]; y <= bottomY; y++) Put(cx - half, y, rimC);
        for (var dx = -half + 2; dx < half - stemRake; dx++) if ((dx & 1) == 0) Put(cx + dx, wy - 1, W((int)(30 * fade + lit * 40)));

        var bx = cx + bridgeAt;
        var by = Deck(bridgeAt) - bridgeH + 3;
        for (var x = bx - 3; x < bx + bridgeW + 3; x++) if (((x - bx) & 1) == 0) Put(x, by, W((int)(190 * fade)));
        for (var k = 1; k < 7; k++)
            for (var x = bx + 1; x < bx + bridgeW - 1; x += 2)
                if (Frac(seed + (x - bx) * 0.37, k) > 0.62) Put(x, by + k * 3, W((int)((110 + 40 * Math.Sin(t * 0.3 + k + x - bx)) * fade)));
        for (var dx = boxesFrom + 8; dx < boxesTo; dx += 24) Put(cx + dx, Deck(dx) - 5 * tier - 1, W((int)(150 * fade)));
        var mastX = bx + bridgeW / 2;
        for (var y = by - 12; y < by - 3; y++) Put(mastX, y, rimC);
        Put(mastX - 2, by - 8, rimC); Put(mastX + 2, by - 8, rimC); Put(mastX - 1, by - 8, rimC); Put(mastX + 1, by - 8, rimC);
        Put(mastX, by - 13, W((int)(255 * fade)));
        var foreX = cx + half - stemRake - 6;
        for (var y = Deck(half - stemRake - 6) - 10; y < Deck(half - stemRake - 6); y++) Put(foreX, y, rimC);
        if (((int)(t * 1.2 + seed) & 1) == 0 && fade > 0.5) Put(foreX, Deck(half - stemRake - 6) - 11, red);
        Put(cx - half - 1, top[0] + 1, W((int)(200 * fade)));

        var sx = cx + casingAt + casingW / 2;
        var sy = Deck(casingAt) - casingH - 1;
        for (var i = 0; i < 26; i++)
        {
            var age = (t * 0.4 + i / 26.0) % 1.0;
            var px = sx - (int)(age * 44) + (int)(Math.Sin(i * 1.7 + t * 0.7) * 2);
            var py = sy - (int)(age * 12);
            if (Bayer[py & 3, px & 3] < (1 - age) * 7) Max(px, py, (int)(44 * fade * (1 - age)));
        }
        return (cx - half - 2, cx + half + 2, minTop - 14);
    }

    void LightShapes()
    {
        foreach (var (lx, ly, tx, ty, R) in cones)
        {
            var x0 = (int)Math.Max(0, Math.Min(lx, tx) - R - 2);
            var x1 = (int)Math.Min(cols - 1, Math.Max(lx, tx) + R + 2);
            for (var y = (int)ly; y <= Math.Min(rows - 1, ty + 2); y++)
                for (var x = x0; x <= x1; x++)
                {
                    if (buf[y * cols + x] != bgPix) continue;
                    var a = Cone(x, y, lx, ly, tx, ty, R);
                    if (a > 0.12 && (x % 4 == 0 || y % 3 == 0) && Bayer[y & 3, x & 3] < a * 30) buf[y * cols + x] = W((int)(22 + 60 * a));
                }
        }
    }

    (int X0, int X1, int Y0) Galleon(int cx, int wy, double s, double fade, int rim, double seed, double t, double lit)
    {
        var half = (int)(36 * s);
        var rimC = W((int)(rim * fade + lit * 120));
        var dimC = W((int)(rim * 0.5 * fade + lit * 60));
        var hullH = (int)(9 * s);
        for (var dx = -half; dx <= half; dx++)
        {
            var x = cx + dx;
            var castle = dx > half * 0.45 ? (int)((dx - half * 0.45) / (half * 0.55) * 7 * s) : 0;
            var bowRise = dx < -half * 0.7 ? (int)((-half * 0.7 - dx) / (half * 0.3) * 2 * s) : 0;
            var top = wy - hullH - castle - bowRise;
            var curve = Math.Abs(dx) > half * 0.75 ? (int)((Math.Abs(dx) - half * 0.75) / (half * 0.25) * hullH * 0.8) : 0;
            var bottom = wy + 3 - curve;
            for (var y = top; y <= bottom; y++) Fill(x, y, fade);
            Put(x, top, rimC);
            Put(x, bottom, dimC);
            if (dx % 4 == 0 && Math.Abs(dx) < half * 0.7 && fade > 0.3) Put(x, wy - hullH / 2, W((int)(80 * fade + 40 * Math.Sin(t + dx))));
        }
        Put(cx + half, wy - hullH - (int)(7 * s) - 1, W((int)(220 * fade)));
        Line(cx - half, wy - hullH - (int)(2 * s), cx - half - (int)(10 * s), wy - hullH - (int)(7 * s), dimC);
        var minTop = wy;
        foreach (var (mx, mh) in new[] { (-half * 0.4, 34.0), (0.0, 42.0), (half * 0.38, 30.0) })
        {
            var x = cx + (int)mx;
            var top = wy - hullH - (int)(mh * s);
            minTop = Math.Min(minTop, top);
            for (var y = top; y < wy - hullH; y++) Put(x, y, dimC);
            var w = (int)(9 * s);
            for (var k = 0; k < 3; k++)
            {
                var yTop = top + 3 + k * (int)(mh * s * 0.27);
                var h = (int)(mh * s * 0.2);
                for (var dx = -w; dx <= w; dx++) Put(x + dx, yTop, dimC);
                for (var dx = -w + 1; dx < w; dx++)
                {
                    var torn = (int)(Frac(seed + dx * 1.3, k + mx) * h * 0.7);
                    for (var y = yTop + 1; y <= yTop + h - torn; y++)
                    {
                        if (Frac(seed + dx * 0.7, (y - yTop) * 1.1 + k) > 0.9) continue;
                        Fill(x + dx + (int)(Math.Sin(t * 0.8 + y * 0.3) * 0.6), y, fade);
                    }
                    Put(x + dx, yTop + h - torn, dimC);
                }
                for (var y = yTop; y < yTop + h * 0.6; y++) { Put(x - w, y, dimC); Put(x + w, y, dimC); }
            }
            Line(x, top, cx - half - (int)(10 * s), wy - hullH - (int)(7 * s), W((int)(rim * 0.25 * fade)));
        }
        return (cx - half - (int)(10 * s), cx + half + 2, minTop);
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 80 || h < 60) return;
        var t = (DateTime.UtcNow - start).TotalSeconds;
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
            var n = cols * rows / 150;
            var rr = new Random(5);
            rainX = new double[n]; rainY = new double[n]; rainV = new double[n];
            for (var i = 0; i < n; i++) { rainX[i] = rr.NextDouble(); rainY[i] = rr.NextDouble(); rainV[i] = 0.7 + rr.NextDouble() * 0.6; }
        }
        Array.Clear(buf);
        double[] at = { 0.73, 0.84, 0.98 };
        for (var i = 0; i < sea.Length; i++) sea[i].Base = h * at[i] + sea[i].Scale * 0.35;
        storm.Base = -storm.Scale * 0.2;

        var stormOn = Settings.Storm;
        if (drawn && stormOn)
        {
            if (t > nextFlash) { flashes.Add(new Flash { Start = t, Life = 0.4 + rng.NextDouble() * 0.4, Power = 0.5 + rng.NextDouble() * 0.5, X = rng.Next(cols), Seed = rng.Next() }); nextFlash = t + 2.2 + rng.NextDouble() * 4.5; }
            if (t > nextBolt) { AddBolt(t); nextBolt = t + 12 + rng.NextDouble() * 16; }
        }
        flashes.RemoveAll(f => t - f.Start > f.Life);
        bolts.RemoveAll(b => t - b.Start > b.Life);
        var amb = 0.0;
        double glowX = 0, glow = 0;
        foreach (var b in bolts) amb = Math.Max(amb, Flicker(t - b.Start, b.Life) * 0.9);
        foreach (var f in flashes)
        {
            var i = Flicker(t - f.Start, f.Life) * f.Power;
            amb = Math.Max(amb, i * 0.3);
            if (i > glow) { glow = i; glowX = f.X; }
        }

        var shimmer = (int)(t * 0.6) & 3;
        for (var y = 0; y < rows; y++)
        {
            var v = Math.Pow(1 - y / (double)rows, 1.5) * 0.34 + amb * 0.10;
            var a = W((int)(12 + amb * 22));
            for (var x = 0; x < cols; x++)
                if (Bayer[(y + shimmer) & 3, (x + (shimmer >> 1)) & 3] / 16.0 < v * 0.5) Put(x, y, a);
        }
        DrawCloud(storm, t, true, glowX, glow);
        DrawCloud(sea[0], t, false, 0, 0);

        quayX0 = (int)(cols * 0.55);
        deckY = (int)(rows * 0.64);
        craneA = quayX0 + 14;
        craneB = Math.Min(cols - 60, quayX0 + 66);
        boomY = (int)(rows * 0.14);
        rodX = craneB; rodY = boomY - 14;
        var lit = amb;

        foreach (var v in vessels.Where(v => v.Slot > 0).ToList())
        {
            v.Fade = v.Leaving ? v.Fade - dt * 0.5 : Math.Min(1, v.Fade + dt * 0.35);
            if (v.Fade <= 0) { vessels.Remove(v); continue; }
            var rim = v == hover ? 220 : 140;
            if (v.Slot == 1)
            {
                var cx = (int)(cols * 0.20);
                var (x0, x1, y0) = Galleon(cx, SeaCell(0, cx, t) + 2, 0.75, v.Fade, rim, v.Seed, t, lit);
                v.Px0 = x0; v.Px1 = x1; v.Py0 = y0; v.Py1 = SeaCell(0, cx, t) + 4;
            }
            else
            {
                var cx = (int)(cols * 0.05);
                var (x0, x1, y0) = Freighter(cx, SeaCell(0, cx, t) + 1, cols * 0.12, v.Fade, rim - 20, v.Seed, t, lit * 0.6);
                v.Px0 = x0; v.Px1 = x1; v.Py0 = y0; v.Py1 = SeaCell(0, cx, t) + 3;
            }
        }
        Fog(rows * 0.62, rows * 0.07, 26, t, 4, 1.3, 0.55);

        var quayC = W((int)(180 + lit * 70));
        for (var x = quayX0 - 4; x < cols; x++)
        {
            for (var y = deckY + 1; y < rows; y++) Put(x, y, bgPix);
            Put(x, deckY, quayC);
            if ((x - quayX0) % 14 == 0) for (var y = deckY + 2; y < rows; y++) Put(x, y, W(50));
        }
        for (var y = deckY; y < rows; y++) Put(quayX0 - 4, y, quayC);
        for (var x = quayX0; x < cols; x += 10) { Put(x, deckY - 1, W(150)); Put(x + 1, deckY - 1, W(150)); }
        for (var x0 = quayX0 + 108; x0 + 7 < cols; x0 += 8)
        {
            var tiers = 1 + (int)(Frac((x0 - quayX0) * 0.31, 9.7) * 3.4);
            if (x0 == quayX0 + 108) tiers = Math.Min(5, 1 + landed);
            for (var k = 0; k < tiers; k++)
            {
                var y1 = deckY - 1 - k * 4;
                var y0 = y1 - 3;
                for (var y = y0; y <= y1; y++) for (var x = x0; x < x0 + 7; x++) Put(x, y, bgPix);
                for (var x = x0; x < x0 + 7; x++) Put(x, y0, W((int)(70 + lit * 90)));
                Put(x0, y0 + 1, W(60)); Put(x0, y0 + 2, W(60));
                for (var x = x0 + 2; x < x0 + 6; x += 2) Put(x, y0 + 2, W(28));
            }
        }
        cones.Clear();
        foreach (var (fx, tx) in new[] { (quayX0 + 36, quayX0 - 20.0), (cols - 8, quayX0 + 70.0) })
        {
            var top = (int)(rows * 0.20);
            for (var y = top; y < deckY; y++) { Put(fx, y, W(120)); if ((y - top) % 5 == 0) { Put(fx - 1, y, W(80)); Put(fx + 1, y, W(80)); } }
            for (var x = fx - 3; x <= fx + 3; x++) { Put(x, top - 1, W(160)); Put(x, top - 2, W(255)); }
            cones.Add((fx, top, tx, deckY + 2, 22));
        }
        foreach (var (lx, ly, tx, ty, R) in cones)
        {
            var flick = 0.92 + 0.08 * Math.Sin(t * 13 + lx);
            var x0 = (int)Math.Max(0, Math.Min(lx, tx) - R - 2);
            var x1 = (int)Math.Min(cols - 1, Math.Max(lx, tx) + R + 2);
            for (var y = (int)ly; y <= Math.Min(rows - 1, ty + 2); y++)
                for (var x = x0; x <= x1; x++)
                {
                    var a = Cone(x, y, lx, ly, tx, ty, R) * flick;
                    if (a > 0 && a * 16 > Bayer[y & 3, x & 3]) Max(x, y, (int)(40 + 110 * a));
                }
        }

        var (run, drop, load, set) = CraneAt(t);
        var hitFlare = t - towerHit is >= 0 and < 0.8 ? Flicker(t - towerHit, 0.8) : 0;
        foreach (var gx in new[] { craneA, craneB })
        {
            var legC = W((int)(170 + lit * 80));
            var legL = gx - 9;
            var legR = gx + 9;
            for (var y = boomY + 3; y < deckY; y++)
            {
                Put(legL, y, legC); Put(legR, y, legC);
                if ((y - boomY) % 7 == 0) Line(legL, y, legR, y, W(70));
                if ((y - boomY) % 14 == 3) { Line(legL, y, legR, y + 6, W(55)); }
            }
            var boomL = gx - 82;
            var boomR = gx + (gx == craneB ? 52 : 26);
            for (var x = boomL; x <= boomR; x++) { Put(x, boomY, legC); Put(x, boomY + 2, W(90)); if ((x - boomL) % 4 == 0) Put(x, boomY + 1, W(90)); }
            var apex = boomY - 12;
            Line(gx, apex, boomL + 6, boomY, W(110));
            Line(gx, apex, boomR, boomY, W(110));
            Put(gx, apex, legC); Put(gx, apex + 1, legC);
            for (var y = apex; y < boomY; y++) Put(gx, y, W(120));
            for (var y = boomY - 5; y < boomY; y++) for (var x = gx + 4; x < gx + 13; x++) Put(x, y, bgPix);
            for (var x = gx + 4; x < gx + 13; x++) Put(x, boomY - 5, W(140));
            Put(gx + 6, boomY - 3, W(200)); Put(gx + 9, boomY - 3, W(200));
            if (((int)(t * 1.1 + gx) & 1) == 0) Put(gx, apex - 1, red);
            if (gx == craneB && hitFlare > 0.2)
            {
                var sr = new Random((int)(t * 30));
                for (var i = 0; i < 14; i++) Put(gx + sr.Next(-6, 7), apex + sr.Next(-5, 8), W(255));
            }
        }

        var docked = vessels.FirstOrDefault(v => v.Slot == 0 && !v.Leaving);
        var L = Math.Clamp(cols * 0.54, 150, 300);
        var dockX = cols - 14 - L / 2;
        int shipDeckTop = deckY;
        foreach (var v in vessels.Where(v => v.Slot == 0).ToList())
        {
            if (double.IsNaN(v.X)) v.X = -L * 0.6;
            if (v.Leaving)
            {
                v.X -= 16 * dt;
                v.Fade -= dt * 0.25;
                if (v.Fade <= 0 || v.X < -L) { vessels.Remove(v); continue; }
            }
            else
            {
                v.X += Math.Clamp((dockX - v.X) * 0.45, 1.5, 34) * dt;
                if (v.X > dockX) v.X = dockX;
            }
            var cx = (int)Math.Round(v.X);
            var level = (Surface(sea[1], (cx - L / 3 + 0.5) * P, t) + Surface(sea[1], (cx + 0.5) * P, t) + Surface(sea[1], (cx + L / 3 + 0.5) * P, t)) / (3 * P) + 3;
            if (double.IsNaN(v.Wy)) v.Wy = Math.Round(level);
            else if (level > v.Wy + 0.8) v.Wy = Math.Floor(level + 0.2);
            else if (level < v.Wy - 0.8) v.Wy = Math.Ceiling(level - 0.2);
            var wy = (int)v.Wy;
            var (x0, x1, y0) = Freighter(cx, wy, L, Math.Max(0, v.Fade), v == hover ? 220 : 150, v.Seed, t, lit);
            v.Px0 = x0; v.Px1 = x1; v.Py0 = y0; v.Py1 = wy + 2;
            if (v == docked) shipDeckTop = wy - Math.Max(5, (int)Math.Round(L * 0.042)) - 16;
        }

        LightShapes();

        {
            var overShip = craneB - 24;
            var overStack = quayX0 + 111;
            var tx = (int)Math.Round(overStack + (overShip - overStack) * run);
            var low = run > 0.5 ? shipDeckTop : deckY - 1 - Math.Min(5, 1 + landed) * 4;
            var sy = boomY + 3 + (int)Math.Round((low - boomY - 3) * drop);
            for (var x = tx - 3; x <= tx + 3; x++) Put(x, boomY + 1, W(220));
            Put(tx - 2, boomY + 2, W(160)); Put(tx + 2, boomY + 2, W(160));
            for (var y = boomY + 3; y < sy; y++) { Put(tx - 2, y, W(110)); Put(tx + 2, y, W(110)); }
            for (var x = tx - 4; x <= tx + 4; x++) Put(x, sy, W(200));
            if (load)
                for (var y = sy + 1; y <= sy + 4; y++)
                    for (var x = tx - 3; x <= tx + 3; x++)
                        Put(x, y, y == sy + 1 || x == tx - 3 ? W(150) : bgPix);
            _ = set;
        }

        if (stormOn)
        {
            var fall = t * rows * 0.9;
            for (var i = 0; i < rainX.Length; i++)
            {
                var yy = (rainY[i] * (rows + 12) + fall * rainV[i]) % (rows + 12) - 6;
                var xx = ((rainX[i] * cols - yy * 0.3) % cols + cols) % cols;
                for (var s = 0; s < 3; s++)
                {
                    int x = (int)(xx + s * 0.3), y = (int)yy - s;
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                    var inCone = 0.0;
                    foreach (var (lx, ly, tx, ty, R) in cones) inCone = Math.Max(inCone, Cone(x, y, lx, ly, tx, ty, R));
                    Max(x, y, inCone > 0 ? (int)(110 + 130 * inCone) : (int)(17 + amb * 60));
                }
            }
        }

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
            foreach (var br in b.Branches) foreach (var p in br) Put(p.X, p.Y, W((int)(170 * fl)));
        }

        Fog(rows * 0.76, rows * 0.05, 30, t, 7, 4.1, 0.45);
        DrawCloud(sea[1], t, false, 0, 0);
        if (t > nextSpray && drawn)
        {
            var bx = quayX0 - 5;
            var by = SeaCell(1, bx, t);
            for (var i = 0; i < 16; i++) spray.Add((bx + rng.NextDouble() * 3, by, -(4 + rng.NextDouble() * 12), -(14 + rng.NextDouble() * 18), 1));
            nextSpray = t + 1.2 + rng.NextDouble() * 2.6;
        }
        for (var i = spray.Count - 1; i >= 0; i--)
        {
            var (x, y, vx, vy, life) = spray[i];
            vy += 40 * dt; x += vx * dt; y += vy * dt; life -= dt * 0.9;
            if (life <= 0) { spray.RemoveAt(i); continue; }
            spray[i] = (x, y, vx, vy, life);
            Max((int)x, (int)y, (int)(220 * life));
        }
        DrawCloud(sea[2], t, false, 0, 0);
        drawn = true;

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));

        if (hover is { } hv && hv.Title.Length > 0)
        {
            var ft = new FormattedText("CARRYING " + hv.Title.ToUpperInvariant(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 10.5, text, VisualTreeHelper.GetDpi(this).PixelsPerDip);
            var lx = Math.Min(w - ft.Width - 8, Math.Max(4.0, (hv.Px0 + hv.Px1) / 2.0 * P - ft.Width / 2));
            var ly = Math.Max(2.0, hv.Py0 * P - 18.0);
            dc.DrawRectangle(bg, null, new Rect(Math.Round(lx) - 3, Math.Round(ly), ft.Width + 6, ft.Height));
            dc.DrawText(ft, new Point(Math.Round(lx), Math.Round(ly)));
        }
    }

    static double Cone(int x, int y, double lx, double ly, double tx, double ty, double R)
    {
        double ax = tx - lx, ay = ty - ly;
        var l2 = ax * ax + ay * ay;
        double dx = x - lx, dy = y - ly;
        var s = (dx * ax + dy * ay) / l2;
        if (s < 0.04 || s > 1.05) return 0;
        var perp = Math.Abs(dx * ay - dy * ax) / Math.Sqrt(l2);
        var half = 1.5 + s * R;
        if (perp > half) return 0;
        var q = perp / half;
        return (0.10 + 0.22 * (1 - q)) * (1 - 0.4 * s);
    }
}
