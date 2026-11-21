using System.Globalization;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class Lighthouse : FrameworkElement
{
    const int P = 4;
    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    public enum Buoy { Current, Due, Updating, Updated, Failed, Unknown }

    sealed class Mark
    {
        public string Key = "", Name = "";
        public Buoy State;
        public double X;
        public double Lit;
        public bool Seen;
        public int Px, Py;
    }

    sealed class Cloud
    {
        public double[] Edge = Array.Empty<double>();
        public double[] Folds = Array.Empty<double>();
        public (double At, double Up, int Len)[] Wisps = Array.Empty<(double, double, int)>();
        public int Period;
        public double Base, Scale, Speed, SwellA, SwellL, SwellW, Phase;
        public byte Line, Fold, Dot;
    }

    sealed class Flash
    {
        public double Start, Life, Power, X, Y;
        public int Layer, Seed;
    }

    sealed class Bolt
    {
        public double Start, Life;
        public readonly List<(int X, int Y)> Path = new();
        public readonly List<List<(int X, int Y)>> Branches = new();
    }

    public event Action<string>? Picked;

    List<Mark> marks = new();
    string? hover;
    readonly DateTime start = DateTime.UtcNow;
    double lastT;
    bool running, drawn;
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();
    int[] edge = Array.Empty<int>();
    double[] rainX = Array.Empty<double>(), rainY = Array.Empty<double>(), rainV = Array.Empty<double>();
    readonly Cloud[] sea;
    readonly Cloud storm;
    readonly List<Flash> flashes = new();
    readonly List<Bolt> bolts = new();
    readonly Random rng = new();
    double nextFlash = 0.8, nextBolt = 3.5, towerHit = -9;
    int? callAt;

    int rodX, rodY, seaLeft = 6, seaRight = 100, lampX, lampY;
    double beamTx, beamTy, poolR;

    int amber = unchecked((int)0xFFE0A030), red = unchecked((int)0xFFE5484D), bgPix = unchecked((int)0xFF0B0B0C);
    Brush text = Brushes.White, bg = Brushes.Black;
    Typeface face = new("Consolas");

    public Lighthouse()
    {
        ClipToBounds = true;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        sea = new[]
        {
            MakeCloud(seed: 11, period: 3000, scale: 30, speed: 6,  swellA: 4,  swellL: 560, swellW: 0.40, line: 50,  dot: 13),
            MakeCloud(seed: 23, period: 3400, scale: 44, speed: 11, swellA: 6,  swellL: 700, swellW: 0.50, line: 90,  dot: 16),
            MakeCloud(seed: 37, period: 3800, scale: 60, speed: 17, swellA: 9,  swellL: 840, swellW: 0.60, line: 140, dot: 19),
            MakeCloud(seed: 53, period: 4400, scale: 84, speed: 26, swellA: 12, swellL: 1000, swellW: 0.70, line: 200, dot: 23),
        };
        storm = MakeCloud(seed: 71, period: 3600, scale: 52, speed: 4, swellA: 5, swellL: 900, swellW: 0.25, line: 44, dot: 12);
        Loaded += (_, _) =>
        {
            text = (Brush)FindResource("Text");
            if (TryFindResource("Bg") is SolidColorBrush b) { bg = b; bgPix = Argb(b.Color); }
            if (TryFindResource("Warn") is SolidColorBrush w) amber = Argb(w.Color);
            if (TryFindResource("Bad") is SolidColorBrush r) red = Argb(r.Color);
            if (TryFindResource("Mono") is FontFamily mono) face = new Typeface(mono, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            Start();
        };
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
        MouseMove += (_, e) =>
        {
            var m = Near(e.GetPosition(this));
            hover = m?.Key;
            Cursor = m is { State: Buoy.Due or Buoy.Failed } ? Cursors.Hand : null;
        };
        MouseLeave += (_, _) => { hover = null; Cursor = null; };
        MouseLeftButtonUp += (_, e) =>
        {
            var p = e.GetPosition(this);
            if (Near(p) is { } m) { if (m.State is Buoy.Due or Buoy.Failed) Picked?.Invoke(m.Key); return; }
            callAt = (int)(p.X / P);
        };
    }

    static int Argb(Color c) => unchecked((int)(0xFF000000u | ((uint)c.R << 16) | ((uint)c.G << 8) | c.B));

    void Start() { if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; } running = true; Anim.Add(Tick); }
    void Stop() { if (!running) return; running = false; Anim.Remove(Tick); }
    void Tick(object? s, EventArgs e) => InvalidateVisual();

    public void SetBuoys(IEnumerable<(string Key, string Name, Buoy State)> list)
    {
        var items = list.ToList();
        var calm = items.Where(i => i.State is Buoy.Current or Buoy.Unknown).ToList();
        var chosen = items.Where(i => i.State is not (Buoy.Current or Buoy.Unknown)).Select(i => i.Key).ToHashSet();
        var take = Math.Min(6, calm.Count);
        for (var i = 0; i < take; i++) chosen.Add(calm[(int)((i + 0.5) * calm.Count / take)].Key);
        items = items.Where(i => chosen.Contains(i.Key)).ToList();

        var old = marks.ToDictionary(m => m.Key);
        var next = new List<Mark>();
        for (var i = 0; i < items.Count; i++)
        {
            var (k, n, s) = items[i];
            var m = old.TryGetValue(k, out var o) ? o : new Mark { Key = k };
            m.Name = n;
            if (m.State != s && s == Buoy.Updated) m.Lit = 0;
            m.State = s;
            m.X = (i + 0.5) / Math.Max(1, items.Count) + (Frac(k) - 0.5) * 0.5 / Math.Max(1, items.Count);
            next.Add(m);
        }
        marks = next;
    }

    static double Frac(string s) { uint h = 2166136261; foreach (var c in s) { h ^= c; h *= 16777619; } return (h & 0xFFFF) / 65536.0; }

    Mark? Near(Point p)
    {
        Mark? best = null;
        var bd = 16.0 * 16;
        foreach (var m in marks)
        {
            var dx = m.Px * P + 2 - p.X;
            var dy = m.Py * P - 6 - p.Y;
            var d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    static Cloud MakeCloud(int seed, int period, double scale, double speed, double swellA, double swellL, double swellW, byte line, byte dot)
    {
        var rnd = new Random(seed);
        var wisps = new (double, double, int)[period / 170];
        for (var i = 0; i < wisps.Length; i++) wisps[i] = (rnd.NextDouble() * period, 2 + rnd.Next(4), 2 + rnd.Next(4));
        return new Cloud
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
        return profile[i] + (profile[(i + 1) % period] - profile[i]) * f;
    }

    static double Swell(Cloud l, double x, double t) =>
        l.SwellA * Math.Sin(2 * Math.PI * x / l.SwellL - t * l.SwellW + l.Phase)
        + l.SwellA * 0.45 * Math.Sin(2 * Math.PI * x / (l.SwellL * 0.53) + t * l.SwellW * 0.7 + l.Phase * 1.7);

    static double Surface(Cloud l, double x, double t) => l.Base - Read(l.Edge, l.Period, x - l.Speed * t) + Swell(l, x, t);

    static double Hang(Cloud l, double x, double t) => l.Base + Read(l.Edge, l.Period, x + l.Speed * t) + Swell(l, x, t);

    int SeaCell(int k, int x, double t) => (int)Math.Floor(Surface(sea[k], (x + 0.5) * P, t) / P);

    static int W(int a) { a = Math.Clamp(a, 0, 255); return (a << 24) | (a << 16) | (a << 8) | a; }
    void Put(int x, int y, int v) { if (x >= 0 && y >= 0 && x < cols && y < rows) buf[y * cols + x] = v; }
    void Max(int x, int y, int a)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        var i = y * cols + x;
        if (((buf[i] >> 24) & 0xFF) < a) buf[i] = W(a);
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

    void AddFlash(int layer, double x, double y, double power, double t, double life) =>
        flashes.Add(new Flash { Layer = layer, X = x, Y = y, Power = power, Start = t, Life = life, Seed = rng.Next() });

    void AddBolt(double t, int? at)
    {
        var tower = at == null && rng.NextDouble() < 0.35;
        int ex, ey;
        if (tower) { ex = rodX; ey = rodY; }
        else
        {
            ex = Math.Clamp(at ?? rng.Next(seaLeft, Math.Max(seaLeft + 1, seaRight)), 0, cols - 1);
            ey = SeaCell(1, ex, t);
        }
        var sx = Math.Clamp(ex + rng.Next(-30, 31), 0, cols - 1);
        var sy = (int)Math.Floor(Hang(storm, (sx + 0.5) * P, t) / P) - 2;
        if (ey <= sy + 4) return;
        var b = new Bolt { Start = t, Life = 0.6 };
        double x = sx, drift = 0;
        var change = 0;
        for (var y = sy; y <= ey; y++)
        {
            if (--change <= 0) { drift = (rng.NextDouble() - 0.5) * 2.6; change = 2 + rng.Next(5); }
            x += drift + (ex - x) / (ey - y + 1) * 0.9;
            b.Path.Add(((int)Math.Round(x), y));
        }
        var branches = 1 + rng.Next(3);
        for (var i = 0; i < branches; i++)
        {
            var from = b.Path[(int)(b.Path.Count * (0.12 + rng.NextDouble() * 0.5))];
            var dir = rng.Next(2) == 0 ? -1 : 1;
            var br = new List<(int, int)>();
            double bx = from.X;
            var len = 6 + rng.Next(20);
            for (var y = from.Y + 1; y < from.Y + len && y < ey; y++)
            {
                bx += dir * (0.3 + rng.NextDouble() * 1.3);
                br.Add(((int)Math.Round(bx), y));
            }
            b.Branches.Add(br);
        }
        bolts.Add(b);
        AddFlash(4, sx, 3, 1, t, 0.6);
        if (tower) towerHit = t;
        else AddFlash(1, ex, 3, 1, t, 0.6);
    }

    double Glow((Flash F, double I)[] act, int x, int y, bool hanging)
    {
        var g = 0.0;
        foreach (var (f, i) in act)
        {
            var cx = Math.Clamp((int)f.X, 0, cols - 1);
            var cy = hanging ? edge[cx] - f.Y : edge[cx] + f.Y;
            var r = 14 + 10 * f.Power;
            var dx = x - f.X;
            var dy = (y - cy) * 1.5;
            var d = Math.Sqrt(dx * dx + dy * dy);
            if (d < r) g += i * (1 - d / r);
        }
        return g;
    }

    void Sparks((Flash F, double I)[] act, bool hanging, double t)
    {
        foreach (var (f, i) in act)
        {
            if (i < 0.3) continue;
            var r = new Random(f.Seed * 31 + (int)((t - f.Start) * 24));
            var cx = Math.Clamp((int)f.X, 0, cols - 1);
            var cy = hanging ? edge[cx] - (int)f.Y : edge[cx] + (int)f.Y;
            var col = W((int)(255 * Math.Min(1, i + 0.25)));
            var arms = 2 + r.Next(3);
            for (var a = 0; a < arms; a++)
            {
                int x = cx, y = cy, dir = r.Next(2) == 0 ? -1 : 1;
                var len = 5 + r.Next(13);
                for (var s = 0; s < len; s++)
                {
                    x += dir;
                    y += r.Next(3) - 1;
                    if (x < 0 || x >= cols) break;
                    if (!hanging && y < edge[x] + 2) y = edge[x] + 2;
                    if (hanging && y > edge[x] - 2) y = edge[x] - 2;
                    Put(x, y, col);
                    if (r.NextDouble() < 0.18) Put(x, y + (r.Next(2) == 0 ? -1 : 1), col);
                }
            }
        }
    }

    void DrawCloud(Cloud l, int k, double t, bool hanging, double amb)
    {
        for (var x = 0; x < cols; x++)
            edge[x] = (int)Math.Floor((hanging ? Hang(l, (x + 0.5) * P, t) : Surface(l, (x + 0.5) * P, t)) / P);
        var act = flashes.Select(f => (F: f, I: f.Layer == k ? Flicker(t - f.Start, f.Life) * f.Power : 0)).Where(p => p.I > 0.02).ToArray();

        var fade = Math.Max(3, l.Scale / P * 0.6);
        for (var x = 0; x < cols; x++)
        {
            int from = hanging ? 0 : Math.Max(0, edge[x] + 1), to = hanging ? Math.Min(rows, edge[x]) : rows;
            for (var y = from; y < to; y++)
            {
                var d = hanging ? edge[x] - y : y - edge[x];
                var g = act.Length > 0 ? Glow(act, x, y, hanging) : 0;
                var v = bgPix;
                if (d <= fade || g > 0.04)
                {
                    var dens = (d <= fade ? 0.36 * (1 - d / fade) : 0) + 0.04 * Math.Sin(x * 0.5 + y * 0.3 - t * 1.2) + g * 0.55;
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
            var rise = Read(l.Folds, l.Period, hanging ? sx + l.Speed * t : sx - l.Speed * t);
            if (rise < 1.5) { prev = int.MinValue; continue; }
            var fy = (int)Math.Floor((hanging ? l.Base - depth + rise : l.Base + depth - rise) / P + Swell(l, sx, t) / P);
            var ok = hanging ? fy < edge[x] - 2 && fy >= 0 : fy > edge[x] + 2 && fy < rows;
            if (!ok) { prev = int.MinValue; continue; }
            var g = act.Length > 0 ? Glow(act, x, fy, hanging) : 0;
            var c = W((int)(l.Fold + g * 200 + (hanging ? amb * 60 : 0)));
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
            var g = act.Length > 0 ? Glow(act, x, edge[x], hanging) : 0;
            var a = Math.Max(l.Line + (hanging ? amb * 150 : amb * 40), Math.Max(g * 255, hanging ? 0 : Pool(k, x)));
            var c = W((int)a);
            Put(x, edge[x], c);
            if (x == 0) continue;
            int p = edge[x - 1], q = edge[x];
            if (p < q - 1) for (var y = p + 1; y < q; y++) Put(x, y, c);
            else if (p > q + 1) for (var y = q + 1; y < p; y++) Put(x - 1, y, c);
        }

        var wisp = W((int)(l.Line * 0.5));
        foreach (var (at, up, len) in l.Wisps)
        {
            var sx = ((at + (hanging ? -1 : 1) * l.Speed * 1.25 * t) % l.Period + l.Period) % l.Period;
            var x0 = (int)(sx / P);
            if (x0 >= cols + len) continue;
            for (var i = 0; i < len; i++)
            {
                var x = x0 + i;
                if (x < 0 || x >= cols) continue;
                var e0 = edge[Math.Min(cols - 1, Math.Max(0, x0))];
                var y = hanging ? e0 + (int)up : e0 - (int)up;
                if (hanging ? y > edge[x] + 1 && y < rows : y >= 0 && y < edge[x] - 1) Put(x, y, wisp);
            }
        }

        Sparks(act, hanging, t);
    }

    double Pool(int k, int x)
    {
        if (k > 2) return 0;
        var dx = Math.Abs(x - beamTx) / (poolR * 1.3);
        if (dx >= 1) return 0;
        return (k == 1 ? 255 : k == 0 ? 120 : 150) * (1 - dx * dx);
    }

    double Beam(int x, int y)
    {
        double ax = beamTx - lampX, ay = beamTy - lampY;
        var l2 = ax * ax + ay * ay;
        if (l2 < 1) return 0;
        double dx = x - lampX, dy = y - lampY;
        var s = (dx * ax + dy * ay) / l2;
        if (s < 0.03 || s > 1.04) return 0;
        var perp = Math.Abs(dx * ay - dy * ax) / Math.Sqrt(l2);
        var half = 2.5 + s * poolR * 0.95;
        if (perp > half) return 0;
        var q = perp / half;
        var core = Math.Exp(-q * q / 0.07);
        var mist = 0.85 + 0.15 * Math.Sin(s * 38 - lastT * 3.5 + perp * 0.6);
        return (0.62 * core + 0.20 * (1 - q)) * mist * (0.95 - 0.25 * s);
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 80 || h < 80) return;
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
            var n = cols * rows / 170;
            var rr = new Random(5);
            rainX = new double[n]; rainY = new double[n]; rainV = new double[n];
            for (var i = 0; i < n; i++) { rainX[i] = rr.NextDouble(); rainY[i] = rr.NextDouble(); rainV[i] = 0.7 + rr.NextDouble() * 0.6; }
        }
        Array.Clear(buf);

        double[] at = { 0.70, 0.78, 0.87, 0.97 };
        for (var i = 0; i < sea.Length; i++) sea[i].Base = h * at[i] + sea[i].Scale * 0.35;
        storm.Base = -storm.Scale * 0.3;

        if (drawn && Settings.Storm)
        {
            if (t > nextFlash)
            {
                var layer = rng.NextDouble() < 0.4 ? 4 : rng.Next(3);
                AddFlash(layer, rng.Next(cols), 4 + rng.Next(8), 0.55 + rng.NextDouble() * 0.45, t, 0.45 + rng.NextDouble() * 0.4);
                nextFlash = t + 2.2 + rng.NextDouble() * 4.5;
            }
            if (t > nextBolt) { AddBolt(t, null); nextBolt = t + 13 + rng.NextDouble() * 15; }
            if (callAt is { } ca) { AddBolt(t, ca); callAt = null; }
        }
        flashes.RemoveAll(f => t - f.Start > f.Life);
        bolts.RemoveAll(b => t - b.Start > b.Life);
        var amb = 0.0;
        foreach (var b in bolts) amb = Math.Max(amb, Flicker(t - b.Start, b.Life) * 0.9);
        foreach (var f in flashes) if (f.Layer == 4) amb = Math.Max(amb, Flicker(t - f.Start, f.Life) * f.Power * 0.3);

        var shimmer = (int)(t * 0.6) & 3;
        for (var y = 0; y < rows; y++)
        {
            var v = Math.Pow(1 - y / (double)rows, 1.6) * 0.36 + amb * 0.10;
            var a = W((int)(12 + amb * 22));
            for (var x = 0; x < cols; x++)
                if (Bayer[(y + shimmer) & 3, (x + (shimmer >> 1)) & 3] / 16.0 < v * 0.5) Put(x, y, a);
        }

        DrawCloud(storm, 4, t, true, amb);

        DrawCloud(sea[0], 0, t, false, amb);

        var lhX = (int)(cols * 0.79);
        var cliffTop = (int)(rows * 0.69);
        var lit = 1 + amb * 0.6;
        int Left(int y)
        {
            var d = y - cliffTop;
            var crag = Math.Abs((d + 3) % 12 - 6) * 0.9;
            return lhX - 26 - (int)(d * 0.85 + crag + Math.Sin(d * 0.17) * 3 + Frac("L" + y / 2) * 1.5);
        }
        int Right(int y)
        {
            var d = y - cliffTop;
            return lhX + 24 + (int)(d * 0.30 + ((d / 6) % 2 == 1 ? 3 : 0) + Math.Sin(d * 0.21 + 2) * 2 + Frac("R" + y / 2) * 1.5);
        }
        var outline = W((int)(205 * lit));
        for (var y = cliffTop; y < rows; y++)
        {
            int l = Left(y), rr = Right(y);
            for (var x = l; x <= rr; x++)
            {
                var v = bgPix;
                var shade = (x - l) / (double)Math.Max(1, rr - l);
                if ((0.10 + shade * 0.22 + amb * 0.25) * 16 > Bayer[y & 3, x & 3]) v = W((int)(16 + amb * 50));
                if (Frac("K" + (x + y / 6) + "_" + y / 3) > 0.965) v = W((int)(70 * lit));
                if ((y - cliffTop) % 8 == 7 && Frac("S" + x / 4 + "_" + y) > 0.4) v = W((int)(48 * lit));
                Put(x, y, v);
            }
            if (y > cliffTop)
            {
                int pl = Left(y - 1), pr = Right(y - 1);
                for (var x = Math.Min(pl, l); x <= Math.Max(pl, l); x++) Put(x, y, outline);
                for (var x = Math.Min(pr, rr); x <= Math.Max(pr, rr); x++) Put(x, y, outline);
            }
            Put(l, y, outline);
            Put(rr, y, outline);
        }
        for (var x = Left(cliffTop); x <= Right(cliffTop); x++) Put(x, cliffTop, outline);
        for (var x = Left(cliffTop) + 1; x < Right(cliffTop); x += 2) if (Frac("G" + x) > 0.5) Put(x, cliffTop - 1, W(110));

        const int baseHalf = 16, topHalf = 11;
        var towerTop = cliffTop - (int)(rows * 0.44);
        var baseY = cliffTop - 1;
        var towerH = Math.Max(10, baseY - towerTop);
        int Half(int y)
        {
            var k = baseY - y;
            var h0 = topHalf + (baseHalf - topHalf) * (1 - k / (double)towerH);
            return (int)Math.Round(h0 + (k < 8 ? (8 - k) * 0.6 : 0));
        }
        var hit = t - towerHit;
        var flare = hit is >= 0 and < 0.8 ? Flicker(hit, 0.8) : 0;
        var edgeA = W((int)(225 * lit + flare * 30));
        for (var y = towerTop; y <= baseY; y++)
        {
            var half = Half(y);
            var row = y - towerTop;
            for (var x = lhX - half; x <= lhX + half; x++)
            {
                var v = bgPix;
                var shadow = x < lhX - half / 3;
                var course = row % 3 == 0;
                var joint = (row / 3) % 2 == 0 ? (x - lhX + 30) % 5 == 0 : (x - lhX + 32) % 5 == 0;
                if ((course || joint) && !(shadow && Bayer[y & 3, x & 3] < 9)) v = W((int)((shadow ? 30 : 52) * lit));
                Put(x, y, v);
            }
            Put(lhX - half, y, edgeA);
            Put(lhX + half, y, edgeA);
        }
        foreach (var dx in new[] { -10, 10 })
            for (var k = 0; k < 14; k++)
            {
                var y = baseY - k;
                var x = lhX + dx + Math.Sign(dx) * (int)((14 - k) * 0.55);
                Put(x, y, edgeA);
            }
        foreach (var f in new[] { 0.28, 0.55, 0.80 })
        {
            var y = baseY - (int)(towerH * f);
            var half = Half(y) + 1;
            for (var x = lhX - half; x <= lhX + half; x++) { Put(x, y, W((int)(150 * lit))); Put(x, y + 1, W((int)(70 * lit))); }
            for (var x = lhX - half + 1; x < lhX + half; x += 3) Put(x, y + 1, W((int)(190 * lit)));
        }
        var win = 0;
        foreach (var f in new[] { 0.16, 0.40, 0.66, 0.90 })
        {
            var y = baseY - (int)(towerH * f);
            var x = lhX + (win++ % 2 == 0 ? 4 : -4);
            var a = (int)(110 + 60 * Math.Sin(t * 1.3 + f * 7) + flare * 80);
            for (var i = 0; i < 5; i++) Put(x, y - i, W(a));
            Put(x - 1, y - 5, edgeA); Put(x, y - 6, edgeA); Put(x + 1, y - 5, edgeA);
        }
        for (var y = baseY - 8; y <= baseY; y++) for (var x = lhX - 3; x <= lhX + 3; x++) Put(x, y, bgPix);
        for (var y = baseY - 7; y <= baseY; y++) { Put(lhX - 4, y, edgeA); Put(lhX + 4, y, edgeA); }
        for (var x = lhX - 3; x <= lhX + 3; x++) Put(x, baseY - 8, edgeA);
        for (var y = baseY - 7; y <= baseY; y++) if (y % 2 == 0) Put(lhX, y, W(60));

        var gw = topHalf + 5;
        var gy = towerTop - 1;
        for (var x = lhX - gw; x <= lhX + gw; x++) { Put(x, gy, edgeA); Put(x, gy + 1, W((int)(110 * lit))); }
        for (var i = 0; i < 5; i++) { Put(lhX - topHalf - i, gy + 2 + (4 - i), edgeA); Put(lhX + topHalf + i, gy + 2 + (4 - i), edgeA); }
        for (var x = lhX - gw; x <= lhX + gw; x += 3)
        {
            for (var y = gy - 4; y < gy; y++) Put(x, y, W((int)(160 * lit)));
            Put(x, gy - 5, edgeA);
        }
        for (var x = lhX - gw; x <= lhX + gw; x++) Put(x, gy - 4, W((int)(130 * lit)));

        const int lw = 8, lh = 16;
        lampX = lhX;
        lampY = gy - 1 - lh / 2;
        var lampI = Math.Min(1, 0.85 + 0.15 * Math.Sin(t * 3.1) + flare);
        for (var y = gy - lh; y <= gy - 1; y++)
            for (var x = lhX - lw; x <= lhX + lw; x++)
            {
                var frame = x == lhX - lw || x == lhX + lw || y == gy - lh || y == gy - 1;
                var bar = x == lhX - 4 || x == lhX + 4 || y == gy - lh / 2 - 4;
                var v = frame ? edgeA : bar ? W(90) : W((int)((130 + 125 * lampI) * (Bayer[y & 3, x & 3] < 12 ? 1 : 0.6)));
                Put(x, y, v);
            }
        for (var y = lampY - 2; y <= lampY + 2; y++) for (var x = lhX - 2; x <= lhX + 2; x++) Put(x, y, W(255));
        var roofBase = gy - lh - 1;
        for (var x = lhX - lw - 2; x <= lhX + lw + 2; x++) Put(x, roofBase, edgeA);
        const int spire = 15;
        for (var k = 1; k <= spire; k++)
        {
            var half = lw + 1 - (int)(k * (lw + 1.0) / spire);
            var y = roofBase - k;
            for (var x = lhX - half + 1; x < lhX + half; x++) Put(x, y, bgPix);
            Put(lhX - half, y, edgeA);
            Put(lhX + half, y, edgeA);
        }
        rodX = lhX;
        rodY = roofBase - spire - 8;
        for (var y = rodY; y <= roofBase - spire; y++) Put(lhX, y, W((int)(200 * lit + flare * 55)));
        Put(lhX - 1, rodY, edgeA); Put(lhX + 1, rodY, edgeA);
        if (flare > 0.2)
        {
            var sr = new Random((int)(t * 30));
            for (var i = 0; i < 16; i++) Put(rodX + sr.Next(-6, 7), rodY + sr.Next(-4, 12), W(255));
        }

        for (var y = lampY - 24; y <= lampY + 24; y++)
            for (var x = lhX - 30; x <= lhX + 30; x++)
            {
                var d = Math.Sqrt((x - lhX) * (x - lhX) + (y - lampY) * (y - lampY) * 1.3);
                var a = (1 - d / 30) * (0.45 + flare * 0.5);
                if (a > 0 && a * 16 > Bayer[y & 3, x & 3] + 3) Max(x, y, (int)(30 + flare * 60));
            }
        var glare = 0.5 + 0.5 * Math.Pow(Math.Max(0, Math.Cos(t * 0.30 * 2)), 6) + flare * 0.5;
        for (var dx = -26; dx <= 26; dx++)
        {
            var a = (1 - Math.Abs(dx) / 26.0) * glare;
            Max(lhX + dx, lampY, (int)(255 * Math.Min(1, a * 1.4)));
            if (Math.Abs(dx) < 12) { Max(lhX + dx, lampY - 1, (int)(150 * a)); Max(lhX + dx, lampY + 1, (int)(150 * a)); }
        }
        for (var dy = -7; dy <= 7; dy++) Max(lhX, lampY + dy, (int)(255 * (1 - Math.Abs(dy) / 7.0) * glare));

        var seaRow = SeaCell(1, Math.Max(0, Left(cliffTop) - 2), t);
        seaRight = Math.Max(seaLeft + 10, Left(Math.Clamp(seaRow, cliffTop, rows - 1)) - 6);
        var u = 0.5 - 0.5 * Math.Cos(t * 0.30);
        beamTx = seaRight - u * (seaRight - seaLeft);
        beamTy = SeaCell(1, (int)Math.Clamp(beamTx, 0, cols - 1), t) + 1;
        poolR = 9 + u * 16;
        {
            var x0 = (int)Math.Max(0, Math.Min(lampX, beamTx) - poolR - 2);
            var x1 = (int)Math.Min(cols - 1, Math.Max(lampX, beamTx) + 2);
            var y1 = (int)Math.Min(rows - 1, beamTy + poolR);
            for (var y = Math.Max(0, lampY - 3); y <= y1; y++)
                for (var x = x0; x <= x1; x++)
                {
                    var a = Beam(x, y);
                    if (a > 0 && a * 16 > Bayer[y & 3, x & 3]) Max(x, y, (int)(80 + 175 * a));
                }
            for (var y = (int)beamTy - 7; y <= (int)beamTy + 1; y++)
                for (var x = (int)(beamTx - poolR * 1.4); x <= (int)(beamTx + poolR * 1.4); x++)
                {
                    var ex = (x - beamTx) / (poolR * 1.4);
                    var ey = (y - beamTy) / 7.0;
                    var a = (1 - Math.Sqrt(ex * ex + ey * ey)) * 0.6;
                    if (a > 0 && a * 16 > Bayer[y & 3, x & 3]) Max(x, y, (int)(70 + 150 * a));
                }
        }

        var fall = t * rows * 0.9;
        var rainN = Settings.Storm ? rainX.Length : 0;
        for (var i = 0; i < rainN; i++)
        {
            var yy = (rainY[i] * (rows + 12) + fall * rainV[i]) % (rows + 12) - 6;
            var xx = ((rainX[i] * cols - yy * 0.3) % cols + cols) % cols;
            for (var s = 0; s < 3; s++)
            {
                int x = (int)(xx + s * 0.3), y = (int)yy - s;
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                var bm = Beam(x, y);
                Max(x, y, bm > 0 ? (int)(120 + 120 * bm) : (int)(17 + amb * 60));
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
                if (p0 is { } q && Math.Abs(p.X - q.X) > 1)
                    for (var x = Math.Min(p.X, q.X) + 1; x < Math.Max(p.X, q.X); x++) Put(x, q.Y, core);
                Put(p.X, p.Y, core);
                Max(p.X - 1, p.Y, (int)(70 * fl)); Max(p.X + 1, p.Y, (int)(70 * fl));
                p0 = p;
            }
            foreach (var br in b.Branches)
            {
                (int X, int Y)? q0 = null;
                foreach (var p in br)
                {
                    if (q0 is { } q && Math.Abs(p.X - q.X) > 1)
                        for (var x = Math.Min(p.X, q.X) + 1; x < Math.Max(p.X, q.X); x++) Put(x, q.Y, W((int)(180 * fl)));
                    Put(p.X, p.Y, W((int)(180 * fl)));
                    q0 = p;
                }
            }
        }

        DrawCloud(sea[1], 1, t, false, amb);
        var span = Math.Max(10, seaRight - seaLeft - 8);
        foreach (var m in marks)
        {
            var x = seaLeft + 4 + (int)(m.X * span);
            var y = SeaCell(1, x, t) - 1;
            m.Px = x;
            m.Py = y;
            var inPool = Math.Abs(x - beamTx) < poolR + 1;
            if (inPool && m.State is Buoy.Due or Buoy.Failed) { m.Lit = 7; m.Seen = true; }
            m.Lit = Math.Max(0, m.Lit - dt);
            var body = W(inPool ? 230 : (int)(150 + amb * 80));
            Put(x - 1, y, body); Put(x, y, body); Put(x + 1, y, body);
            Put(x - 1, y - 1, body); Put(x + 1, y - 1, body);
            Put(x, y - 2, body);
            int lamp;
            switch (m.State)
            {
                case Buoy.Due:
                    var on = m.Seen;
                    lamp = on ? amber : W(70);
                    if (on && (m.Lit > 6.5 || Math.Sin(t * 2.6 + m.X * 9) > -0.3))
                    {
                        Put(x - 1, y - 3, amber); Put(x + 1, y - 3, amber); Put(x, y - 4, amber);
                        if (m.Lit > 6.5) { Put(x - 2, y - 3, amber); Put(x + 2, y - 3, amber); Put(x, y - 5, amber); }
                    }
                    break;
                case Buoy.Updating: lamp = ((int)(t * 6) & 1) == 0 ? W(255) : W(60); break;
                case Buoy.Updated: lamp = W(255); Put(x, y - 4, W(140)); break;
                case Buoy.Failed: lamp = ((int)(t * 3) & 1) == 0 ? red : W(60); break;
                case Buoy.Unknown: lamp = W(40); break;
                default: lamp = W(90); break;
            }
            Put(x, y - 3, lamp);
            if (m.Key == hover) { Max(x - 3, y - 3, 220); Max(x + 3, y - 3, 220); }
        }
        DrawCloud(sea[2], 2, t, false, amb);
        DrawCloud(sea[3], 3, t, false, amb);
        drawn = true;

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));

        if (hover != null && marks.FirstOrDefault(m => m.Key == hover) is { } hm)
        {
            var label = hm.Name.ToUpperInvariant() + hm.State switch
            {
                Buoy.Due => "  UPDATE",
                Buoy.Updating => "  UPDATING",
                Buoy.Updated => "  UPDATED",
                Buoy.Failed => "  FAILED, CLICK TO RETRY",
                Buoy.Unknown => "  NOT ON MODRINTH",
                _ => "  UP TO DATE",
            };
            var ft = new FormattedText(label, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 10.5, text, VisualTreeHelper.GetDpi(this).PixelsPerDip);
            var lx = Math.Min(w - ft.Width - 8, Math.Max(4.0, hm.Px * P - ft.Width / 2));
            var ly = Math.Max(2.0, hm.Py * P - 44.0);
            dc.DrawRectangle(bg, null, new Rect(Math.Round(lx) - 3, Math.Round(ly), ft.Width + 6, ft.Height));
            dc.DrawText(ft, new Point(Math.Round(lx), Math.Round(ly)));
        }
    }
}
