using System.Globalization;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class Fleet : FrameworkElement
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

    sealed class Ship
    {
        public string Id = "", Name = "", Status = "";
        public bool Online, Request, Leaving;
        public int Unread;
        public double Slot, X = double.NaN, Fade, Seed, Lamp;
        public int Px0, Px1, Py0, Py1, LampX, LampY, Water;
        public readonly List<(double On, double Off)> Signal = new();
        public int[]? Flag;
        public int FlagW, FlagH;
    }

    sealed class Flash { public double Start, Life, Power, X; }

    public event Action<string>? Picked;

    readonly List<Ship> ships = new();
    readonly Dictionary<string, (int[] Gray, int W, int H)> flags = new();
    readonly Ship flag = new() { Id = "", Name = "YOU", Seed = 0.37 };
    readonly Cloud[] sea;
    readonly Cloud sky;
    readonly List<Flash> flashes = new();
    readonly Random rng = new();
    readonly DateTime start = DateTime.UtcNow;
    double lastT, nextFlash = 3, nextSignal = 2.5;
    bool running, meOnline;
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();
    int[] edge = Array.Empty<int>();
    int bgPix = unchecked((int)0xFF0B0B0C);
    Brush text = Brushes.White, faint = Brushes.Gray, bg = Brushes.Black;
    Typeface face = new("Consolas");
    Ship? hover;

    public Fleet()
    {
        ClipToBounds = true;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        sea = new[]
        {
            MakeCloud(seed: 23, period: 2600, scale: 12, speed: 3, swellA: 2, swellL: 520, swellW: 0.30, line: 40, dot: 12),
            MakeCloud(seed: 31, period: 3000, scale: 22, speed: 6, swellA: 3, swellL: 660, swellW: 0.40, line: 90, dot: 15),
            MakeCloud(seed: 47, period: 3600, scale: 32, speed: 10, swellA: 5, swellL: 820, swellW: 0.50, line: 170, dot: 19),
        };
        sky = MakeCloud(seed: 61, period: 3400, scale: 38, speed: 2, swellA: 4, swellL: 900, swellW: 0.20, line: 36, dot: 11);
        Loaded += (_, _) =>
        {
            text = (Brush)FindResource("Text");
            if (TryFindResource("Faint") is Brush f) faint = f;
            if (TryFindResource("Bg") is SolidColorBrush b) { bg = b; bgPix = Argb(b.Color); }
            if (TryFindResource("Mono") is FontFamily mono) face = new Typeface(mono, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            Start();
        };
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
        MouseMove += (_, e) => { hover = At(e.GetPosition(this)); Cursor = hover is { Id.Length: > 0, Request: false } ? Cursors.Hand : null; };
        MouseLeave += (_, _) => { hover = null; Cursor = null; };
        MouseLeftButtonUp += (_, e) => { if (At(e.GetPosition(this)) is { Id.Length: > 0, Request: false } s) Picked?.Invoke(s.Id); };
    }

    static int Argb(Color c) => unchecked((int)(0xFF000000u | ((uint)c.R << 16) | ((uint)c.G << 8) | c.B));
    void Start() { if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; } running = true; Anim.Add(Tick); }
    void Stop() { if (!running) return; running = false; Anim.Remove(Tick); }
    void Tick(object? s, EventArgs e) => InvalidateVisual();

    Ship? At(Point p)
    {
        int x = (int)(p.X / P), y = (int)(p.Y / P);
        if (x >= flag.Px0 && x <= flag.Px1 && y >= flag.Py0 && y <= flag.Py1) return flag;
        foreach (var s in ships.Where(s => !s.Leaving && s.Fade > 0.4).OrderByDescending(s => s.Online))
            if (x >= s.Px0 && x <= s.Px1 && y >= s.Py0 && y <= s.Py1) return s;
        return null;
    }

    public void SetFleet(IEnumerable<(string Id, string Name, string Status, bool Online, int Unread)> friends, bool online, int requests)
    {
        meOnline = online;
        flag.Status = online ? "ONLINE" : "OFFLINE";
        var want = friends.OrderByDescending(f => f.Online).ThenBy(f => f.Name, StringComparer.OrdinalIgnoreCase).Take(12).ToList();
        var all = want.Select(f => (f.Id, f.Name, f.Status, f.Online, f.Unread, Request: false))
            .Concat(Enumerable.Range(0, Math.Min(3, requests)).Select(i => ("request:" + i, "", "FRIEND REQUEST", true, 0, Request: true))).ToList();
        foreach (var s in ships) if (!all.Any(a => a.Item1 == s.Id)) s.Leaving = true;
        var nOn = Math.Max(1, want.Count(f => f.Online));
        var nOff = Math.Max(1, want.Count(f => !f.Online));
        int k = 0, iOn = 0, iOff = 0;
        foreach (var (id, name, status, on, unread, req) in all)
        {
            var s = ships.FirstOrDefault(x => x.Id == id && !x.Leaving);
            if (s == null) { s = new Ship { Id = id, Seed = Frac(id + "s") }; ships.Add(s); }
            s.Name = name; s.Status = status; s.Online = on; s.Unread = unread; s.Request = req;
            if (flags.TryGetValue(id, out var fl)) { s.Flag = fl.Gray; s.FlagW = fl.W; s.FlagH = fl.H; }
            s.Slot = req ? 0.97 + (k - want.Count) * 0.012
                : on ? 0.38 + 0.50 * (iOn++ + 0.5) / nOn + (Frac(id) - 0.5) * 0.12 / nOn
                : 0.30 + 0.64 * (iOff++ + 0.5) / nOff + (Frac(id) - 0.5) * 0.16 / nOff;
            k++;
        }
    }

    public void SetFlag(string id, int[] gray, int w, int h)
    {
        flags[id] = (gray, w, h);
        foreach (var s in ships.Where(s => s.Id == id)) { s.Flag = gray; s.FlagW = w; s.FlagH = h; }
    }

    static double Frac(string s) { uint h = 2166136261; foreach (var c in s) { h ^= c; h *= 16777619; } h ^= h >> 15; h *= 0x846ca68b; h ^= h >> 16; return (h & 0xFFFF) / 65536.0; }
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
            var gx = glow > 0 ? Math.Max(0, 1 - Math.Abs(x - glowX) / 34.0) * glow : 0;
            for (var y = from; y < to; y++)
            {
                var d = hanging ? edge[x] - y : y - edge[x];
                var v = bgPix;
                var g = gx > 0 ? gx * Math.Max(0, 1 - d / 16.0) : 0;
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
            var gx = glow > 0 ? Math.Max(0, 1 - Math.Abs(x - glowX) / 34.0) * glow : 0;
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
        if (f < 0.12) return 1;
        if (f < 0.24) return 0.15;
        if (f < 0.40) return 0.7;
        return 0.7 * (1 - (f - 0.40) / 0.60);
    }

    void Galleon(Ship ship, int cx, int wy, double s, double fade, int rim, double t, bool sails)
    {
        var seed = ship.Seed * 1000;
        var half = (int)(36 * s);
        var rimC = W((int)(rim * fade));
        var dimC = W((int)(rim * 0.55 * fade));
        var hullH = Math.Max(3, (int)(9 * s));
        var castleH = Math.Max(2, (int)(7 * s));
        for (var dx = -half; dx <= half; dx++)
        {
            var x = cx + dx;
            var castle = dx > half * 0.45 ? (int)((dx - half * 0.45) / (half * 0.55) * castleH) : 0;
            var bowRise = dx < -half * 0.7 ? (int)((-half * 0.7 - dx) / (half * 0.3) * 2 * s) : 0;
            var top = wy - hullH - castle - bowRise;
            var curve = Math.Abs(dx) > half * 0.75 ? (int)((Math.Abs(dx) - half * 0.75) / (half * 0.25) * hullH * 0.8) : 0;
            var bottom = wy + 3 - curve;
            for (var y = top; y <= bottom; y++)
            {
                Fill(x, y, fade);
                var row = y - (wy - hullH);
                if (row > 0 && row % 3 == 0 && ((x + row) & 1) == 0) Put(x, y, W((int)(rim * 0.22 * fade)));
                else if (row > 0 && Bayer[y & 3, x & 3] < 2) Put(x, y, W((int)(rim * 0.12 * fade)));
            }
            Put(x, top, rimC);
            Put(x, bottom, dimC);
            if (Math.Abs(dx) < half * 0.8) Put(x, wy - hullH + 2, W((int)(rim * 0.55 * fade)));
            if (s > 0.55 && Math.Abs(dx) < half * 0.62 && dx % 5 == 0)
            {
                var gy = wy - hullH + 4;
                Put(x, gy, W((int)(rim * 0.7 * fade)));
                if (s > 0.8) Put(x + 1, gy, W((int)(rim * 0.7 * fade)));
            }
        }
        var spritX = cx - half - (int)(12 * s);
        var spritY = wy - hullH - (int)(7 * s);
        Line(cx - half, wy - hullH - (int)(2 * s), spritX, spritY, dimC);
        var minTop = wy;
        foreach (var (mx, mh) in new[] { (-half * 0.4, 34.0), (0.0, 42.0), (half * 0.38, 30.0) })
        {
            var x = cx + (int)mx;
            var top = wy - hullH - (int)(mh * s);
            minTop = Math.Min(minTop, top);
            for (var y = top; y < wy - hullH; y++) Put(x, y, dimC);
            for (var k = 0; k < 3; k++)
            {
                var yTop = top + 2 + k * (int)(mh * s * 0.28);
                var h = Math.Max(2, (int)(mh * s * 0.25));
                var w = Math.Max(2, (int)((6 + k * 2.2) * s));
                for (var dx = -w - 1; dx <= w + 1; dx++) Put(x + dx, yTop, dimC);
                for (var y = yTop + 1; y <= yTop + h; y++)
                {
                    var v = (y - yTop) / (double)h;
                    if (sails)
                    {
                        var belly = (int)Math.Round(Math.Sin(v * Math.PI) * w * 0.45);
                        var x0 = x - w - belly;
                        var x1 = x + w - belly / 3;
                        for (var xx = x0; xx <= x1; xx++) Fill(xx, y, fade);
                        Put(x0, y, rimC);
                        if (Bayer[y & 3, x1 & 3] < 8) Put(x1, y, dimC);
                        if (y == yTop + h) for (var xx = x0; xx <= x1; xx++) if ((xx & 1) == 0) Put(xx, y, dimC);
                    }
                    else
                    {
                        for (var dx = -w + 1; dx < w; dx++)
                        {
                            var torn = Frac(seed + dx * 1.3, k + mx) * 0.8;
                            if (v > 1 - torn || Frac(seed + dx * 0.7, (y - yTop) * 1.1 + k) > 0.86) continue;
                            Fill(x + dx + (int)(Math.Sin(t * 0.8 + y * 0.3) * 0.6), y, fade);
                        }
                        if (v < 0.6) { Put(x - w, y, dimC); Put(x + w, y, dimC); }
                    }
                }
            }
            Line(x, top, spritX, spritY, W((int)(rim * 0.22 * fade)));
            if (sails) Put(x, top - 1, rimC);
            if (mx == 0.0 && ship.Flag is { } flag)
            {
                for (var y = top - ship.FlagH - 2; y < top; y++) Put(x, y, dimC);
                for (var fy = 0; fy < ship.FlagH; fy++)
                    for (var fx = 0; fx < ship.FlagW; fx++)
                    {
                        var wave = (int)Math.Round(Math.Sin(t * 3.2 - fx * 0.7) * fx * 0.12);
                        Put(x + 1 + fx, top - ship.FlagH - 1 + fy + wave, W((int)(flag[fy * ship.FlagW + fx] * fade)));
                    }
            }
        }
        ship.LampX = cx + half + 1;
        ship.LampY = wy - hullH - castleH - 2;
        Put(ship.LampX, ship.LampY + 1, dimC);
        ship.Water = wy + 3;
        ship.Px0 = spritX - 2; ship.Px1 = cx + half + 3; ship.Py0 = minTop - 4; ship.Py1 = wy + 4;
    }

    void Lamp(Ship s, double t, double fade)
    {
        var on = s.Lamp * fade;
        if (on < 0.03) { Put(s.LampX, s.LampY, W((int)(70 * fade))); return; }
        Put(s.LampX, s.LampY, W((int)(140 + 115 * on)));
        if (on > 0.5) { Put(s.LampX + 1, s.LampY, W(200)); Put(s.LampX, s.LampY - 1, W(200)); Put(s.LampX + 1, s.LampY - 1, W(160)); }
        var r = 3 + on * 5;
        for (var dy = -(int)r; dy <= (int)r; dy++)
            for (var dx = -(int)r; dx <= (int)r; dx++)
            {
                var d = Math.Sqrt(dx * dx + dy * dy);
                if (d < 0.5 || d > r) continue;
                var a = (1 - d / r) * on;
                int x = s.LampX + dx, y = s.LampY + dy;
                if (a * 22 > Bayer[y & 3, x & 3]) Max(x, y, (int)(50 + 150 * a));
            }
        for (var k = 1; k < (int)(10 + 16 * on); k++)
        {
            var y = s.Water + k;
            var wob = (int)Math.Round(Math.Sin(t * 2.2 + k * 0.9 + s.Seed * 7) * (1 + k * 0.12));
            if (Frac(k + s.Seed, Math.Floor(t * 6)) < 0.35) continue;
            var len = Math.Max(1, (int)(3 * on * (1 - k / 30.0)));
            for (var dx = -len; dx <= len; dx++) Max(s.LampX + wob + dx, y, (int)(150 * on * (1 - k / 28.0)));
        }
    }

    static List<(double On, double Off)> Morse(double from, Random r, int symbols, double unit)
    {
        var list = new List<(double, double)>();
        var at = from;
        for (var i = 0; i < symbols; i++)
        {
            var len = r.NextDouble() < 0.4 ? unit * 3 : unit;
            list.Add((at, at + len));
            at += len + unit * (r.NextDouble() < 0.25 ? 3 : 1);
        }
        return list;
    }

    static bool Lit(List<(double On, double Off)> sig, double t)
    {
        sig.RemoveAll(p => p.Off < t - 1);
        foreach (var (on, off) in sig) if (t >= on && t < off) return true;
        return false;
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
        }
        Array.Clear(buf);
        double[] at = { 0.60, 0.78, 0.98 };
        for (var i = 0; i < sea.Length; i++) sea[i].Base = h * at[i] + sea[i].Scale * 0.35;
        sky.Base = -sky.Scale * 0.45;

        if (Settings.Storm && t > nextFlash)
        {
            flashes.Add(new Flash { Start = t, Life = 0.5 + rng.NextDouble() * 0.4, Power = 0.4 + rng.NextDouble() * 0.5, X = rng.Next(cols) });
            nextFlash = t + 5 + rng.NextDouble() * 9;
        }
        flashes.RemoveAll(f => t - f.Start > f.Life);
        double glow = 0, glowX = 0;
        foreach (var f in flashes) { var i = Flicker(t - f.Start, f.Life) * f.Power; if (i > glow) { glow = i; glowX = f.X; } }

        var online = ships.Where(s => s.Online && !s.Request && !s.Leaving && s.Fade > 0.8).ToList();
        if (t > nextSignal)
        {
            nextSignal = t + 6 + rng.NextDouble() * 6;
            if (meOnline && online.Count > 0)
            {
                var to = hover is { Online: true, Request: false } hv && hv != flag ? hv : online[rng.Next(online.Count)];
                var sent = Morse(t, rng, 3 + rng.Next(4), 0.16);
                flag.Signal.AddRange(sent);
                to.Signal.AddRange(Morse(sent[^1].Off + 0.7, rng, 2 + rng.Next(4), 0.16));
            }
        }

        var shimmer = (int)(t * 0.5) & 3;
        for (var y = 0; y < rows; y++)
        {
            var v = Math.Pow(1 - y / (double)rows, 1.6) * 0.30;
            for (var x = 0; x < cols; x++)
                if (Bayer[(y + shimmer) & 3, (x + (shimmer >> 1)) & 3] / 16.0 < v * 0.5) Put(x, y, W(12));
        }
        for (var i = 0; i < cols * rows / 220; i++)
        {
            var sx = (int)(Frac(i, 1.7) * cols);
            var sy = (int)(Frac(i, 3.1) * rows * 0.55);
            var tw = 0.5 + 0.5 * Math.Sin(t * (0.6 + Frac(i, 5.3) * 1.8) + i);
            Put(sx, sy, W((int)(30 + 110 * tw * Frac(i, 7.7))));
        }
        int mx = (int)(cols * 0.86), my = (int)(rows * 0.22), mr = 6;
        for (var dy = -mr - 6; dy <= mr + 6; dy++)
            for (var dx = -mr - 6; dx <= mr + 6; dx++)
            {
                var d = Math.Sqrt(dx * dx + dy * dy);
                var d2 = Math.Sqrt((dx - 3) * (dx - 3) + (dy + 1) * (dy + 1));
                int x = mx + dx, y = my + dy;
                if (d <= mr && d2 > mr - 0.5) Put(x, y, W(d > mr - 1.2 ? 235 : 160));
                else if (d > mr && d < mr + 6 && (1 - (d - mr) / 6) * 5 > Bayer[y & 3, x & 3]) Max(x, y, 34);
            }

        DrawCloud(sky, t, true, glowX, glow);
        foreach (var s in ships.ToList())
        {
            s.Fade = s.Leaving ? s.Fade - dt * 0.6 : Math.Min(1, s.Fade + dt * 0.5);
            if (s.Fade <= 0 && s.Leaving) { ships.Remove(s); continue; }
            var tx = s.Slot * cols;
            if (double.IsNaN(s.X)) s.X = s.Request ? cols + 30 : tx;
            s.X += (tx - s.X) * Math.Min(1, dt * (s.Request ? 0.5 : 0.8));
            var want = s.Request ? (((int)(t * 4 + s.Seed * 9)) % 2 == 0 ? 1 : 0.15)
                : s.Online && meOnline ? (Lit(s.Signal, t) ? 1 : 0.7) : 0;
            s.Lamp += (want - s.Lamp) * Math.Min(1, dt * 14);
        }
        foreach (var s in ships.Where(s => !s.Online || s.Request).OrderBy(s => s.X))
        {
            var cx = (int)Math.Round(s.X + Math.Sin(t * 0.15 + s.Seed * 9) * 3);
            Galleon(s, cx, SeaCell(0, cx, t) + 3, s.Request ? 0.5 : 0.42, s.Fade * (s.Request ? 1 : 0.85), s == hover ? 200 : s.Request ? 130 : 70, t, s.Request);
        }
        DrawCloud(sea[0], t, false, 0, 0);
        var horizon = SeaCell(0, mx, t);
        for (var y = horizon + 2; y < rows; y++)
        {
            var k = y - horizon;
            if (Frac(k, Math.Floor(t * 4)) < 0.45) continue;
            var len = 1 + k / 7;
            var wob = (int)Math.Round(Math.Sin(t * 1.4 + k * 0.7) * (1 + k * 0.08));
            for (var dx = -len; dx <= len; dx++) Max(mx + wob + dx, y, (int)(70 * (1 - k / (double)(rows - horizon))));
        }

        Fog(rows * 0.58, rows * 0.06, 24, t, 4, 2.1, 0.6);
        foreach (var s in ships.Where(s => s.Online && !s.Request).OrderBy(s => s.X))
        {
            var cx = (int)Math.Round(s.X + Math.Sin(t * 0.2 + s.Seed * 9) * 4);
            Galleon(s, cx, SeaCell(1, cx, t) + 3, 0.62, s.Fade, s == hover ? 235 : 165, t, true);
        }
        DrawCloud(sea[1], t, false, 0, 0);
        Fog(rows * 0.78, rows * 0.05, 28, t, 6, 4.4, 0.45);
        var fx = (int)(cols * 0.17);
        flag.Lamp += ((meOnline ? (Lit(flag.Signal, t) ? 1 : 0.7) : 0) - flag.Lamp) * Math.Min(1, dt * 14);
        Galleon(flag, fx, SeaCell(2, fx, t) + 3, 1.05, 1, flag == hover ? 255 : 205, t, meOnline);
        DrawCloud(sea[2], t, false, 0, 0);
        foreach (var s in ships) Lamp(s, t, s.Fade);
        Lamp(flag, t, 1);

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));

        if (hover is { } hv2)
        {
            var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
            var top = hv2 == flag ? "YOUR SHIP" : hv2.Request ? "A SHIP APPROACHES" : hv2.Name.ToUpperInvariant();
            var line2 = hv2.Request ? hv2.Status : hv2.Status.ToUpperInvariant() + (hv2.Unread > 0 ? "   " + hv2.Unread + " NEW" : "");
            var a = new FormattedText(top, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 11, text, dpi);
            var b2 = new FormattedText(line2, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 9.5, faint, dpi);
            var lw = Math.Max(a.Width, b2.Width);
            var lx = Math.Min(w - lw - 8, Math.Max(4.0, (hv2.Px0 + hv2.Px1) / 2.0 * P - lw / 2));
            var ly = Math.Max(2.0, hv2.Py0 * P - a.Height - b2.Height - 6);
            dc.DrawRectangle(bg, null, new Rect(Math.Round(lx) - 4, Math.Round(ly) - 2, lw + 8, a.Height + b2.Height + 5));
            dc.DrawText(a, new Point(Math.Round(lx), Math.Round(ly)));
            dc.DrawText(b2, new Point(Math.Round(lx), Math.Round(ly + a.Height + 1)));
        }
    }
}
