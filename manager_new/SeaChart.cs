using System.Globalization;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class SeaChart : FrameworkElement
{
    int P => Compact ? 2 : 3;
    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    sealed class Isle
    {
        public string Name = "";
        public int Mods, Worlds, Packs;
        public bool Active, Placed;
        public double X, Y, R;
        public double TX, TY, TR;
        public double FR;
        public int HW, HH;
        public float[] Field = Array.Empty<float>();
        public double Glow;
        public int PkX, PkY;
        public double[] Coast = new double[64];
    }

    public event Action<string>? Picked;
    public bool Compact { get; set; }

    List<Isle> isles = new();
    string? highlighted;
    readonly DateTime start = DateTime.UtcNow;
    double lastT;
    bool running, dirty = true, layout = true, snap = true;
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();
    int[] chart = Array.Empty<int>();
    int bgPix = unchecked((int)0xFF0B0B0C);
    Brush text = Brushes.White, muted = Brushes.Gray, faint = Brushes.DimGray, bg = Brushes.Black;
    Typeface face = new("Consolas");
    Isle? hover;

    double boatX = double.NaN, boatY, fromX, fromY, sailStart = -1, heading;
    readonly List<(double X, double Y, double T)> wake = new();

    public SeaChart()
    {
        ClipToBounds = true;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        Loaded += (_, _) =>
        {
            text = (Brush)FindResource("Text");
            if (TryFindResource("Muted") is Brush m) muted = m;
            if (TryFindResource("Faint") is Brush f) faint = f;
            if (TryFindResource("Bg") is SolidColorBrush b) { bg = b; bgPix = Argb(b.Color); }
            if (TryFindResource("Mono") is FontFamily mono) face = new Typeface(mono, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            layout = true;
            Start();
        };
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
        SizeChanged += (_, _) => { layout = true; snap = true; };
        MouseMove += (_, e) =>
        {
            var h = At(e.GetPosition(this));
            if (h != hover) { hover = h; dirty = true; }
            Cursor = hover is { Active: false } ? Cursors.Hand : null;
        };
        MouseLeave += (_, _) => { if (hover != null) dirty = true; hover = null; Cursor = null; };
        MouseLeftButtonUp += (_, e) => { if (sinking == null && At(e.GetPosition(this)) is { Active: false } i) Picked?.Invoke(i.Name); };
    }

    static int Argb(Color c) => unchecked((int)(0xFF000000u | ((uint)c.R << 16) | ((uint)c.G << 8) | c.B));
    void Start() { if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; } running = true; Anim.Add(Tick); }
    void Stop() { if (!running) return; running = false; Anim.Remove(Tick); }
    void Tick(object? s, EventArgs e) => InvalidateVisual();

    public void Highlight(string? name) { highlighted = name; dirty = true; }

    Isle? At(Point p)
    {
        double x = p.X / P, y = p.Y / P;
        foreach (var i in isles)
        {
            var dx = x - i.X; var dy = y - i.Y;
            if (dx * dx + dy * dy < i.R * i.R * 1.3) return i;
        }
        return null;
    }

    public void SetIslands(IEnumerable<(string Name, int Mods, int Worlds, int Packs, bool Active)> list)
    {
        var before = isles.FirstOrDefault(i => i.Active)?.Name;
        var old = isles.ToDictionary(i => i.Name);
        if (isles.Count == 0) snap = true;
        isles = list.Select(x =>
        {
            var n = new Isle { Name = x.Name, Mods = x.Mods, Worlds = x.Worlds, Packs = x.Packs, Active = x.Active };
            if (old.TryGetValue(x.Name, out var o))
            {
                n.Glow = o.Glow; n.Placed = o.Placed;
                n.X = o.X; n.Y = o.Y; n.R = o.R; n.TX = o.TX; n.TY = o.TY; n.TR = o.TR;
                n.FR = o.FR; n.HW = o.HW; n.HH = o.HH; n.Field = o.Field; n.PkX = o.PkX; n.PkY = o.PkY; n.Coast = o.Coast;
            }
            else n.Glow = x.Active ? 1 : 0;
            return n;
        }).ToList();
        layout = true;
        var now = isles.FirstOrDefault(i => i.Active)?.Name;
        if (before != null && now != null && before != now) sailStart = -2;
    }

    static double Frac(string s)
    {
        uint h = 2166136261;
        foreach (var c in s) { h ^= c; h *= 16777619; }
        h ^= h >> 16; h *= 0x7feb352d; h ^= h >> 15; h *= 0x846ca68b; h ^= h >> 16;
        return (h & 0xFFFF) / 65536.0;
    }
    static double Ease(double v) { v = Math.Clamp(v, 0, 1); return v * v * (3 - 2 * v); }
    static int W(int a) { a = Math.Clamp(a, 0, 255); return (a << 24) | (a << 16) | (a << 8) | a; }
    void Put(int[] b, int x, int y, int v) { if (x >= 0 && y >= 0 && x < cols && y < rows) b[y * cols + x] = v; }
    void Max(int[] b, int x, int y, int a)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        var i = y * cols + x;
        if (b[i] != bgPix && ((b[i] >> 24) & 0xFF) < a) b[i] = W(a);
    }
    void Line(int[] b, int x0, int y0, int x1, int y1, int v, int every = 1)
    {
        int dx = Math.Abs(x1 - x0), dy = -Math.Abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy, n = 0;
        for (var k = 0; k < 5000; k++)
        {
            if (n++ % every == 0) Put(b, x0, y0, v);
            if (x0 == x1 && y0 == y1) return;
            var e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    void Place()
    {
        var keep = isles.Select(i => (i.X, i.Y, i.R)).ToList();
        var m = Compact ? 5.0 : 16.0;
        var rsv = Compact ? 0.0 : 30.0;
        var area = (cols - 2 * m) * (rows - 2 * m);
        var weights = isles.Select(i => 1 + Math.Log(1 + i.Mods)).ToList();
        var scale = Math.Sqrt(area / Math.Max(1, weights.Sum(w => w * w)) / 16);
        var placed = new List<Isle>();
        foreach (var (i, k) in isles.Select((i, k) => (i, k)).OrderByDescending(p => p.i.Mods))
        {
            var rmin = Compact ? 4.0 : 9.0;
            i.R = Math.Clamp(weights[k] * scale, rmin, Math.Max(rmin, Math.Min(cols, rows) * (Compact ? 0.3 : 0.17)));
            double best = double.NegativeInfinity, bx = cols / 2.0, by = rows / 2.0;
            for (var c = -1; c < 60; c++)
            {
                if (c < 0 && (!i.Placed || snap)) continue;
                var x = c < 0 ? Math.Clamp(i.TX, m + i.R, Math.Max(m + i.R, cols - rsv - i.R)) : m + i.R + Frac(i.Name + "x" + c) * (cols - 2 * m - 2 * i.R - rsv);
                var y = c < 0 ? Math.Clamp(i.TY, m + i.R, Math.Max(m + i.R, rows - m - i.R)) : m + i.R + Frac(i.Name + "y" + c) * (rows - 2 * m - 2 * i.R);
                var d = Math.Min(Math.Min(x - m, cols - rsv - x), Math.Min(y - m, rows - m - y)) * 1.6;
                foreach (var o in placed) d = Math.Min(d, Math.Sqrt((x - o.X) * (x - o.X) + (y - o.Y) * (y - o.Y)) - (o.R + i.R) * 1.5);
                if (i.Placed && !snap) d = Math.Min(d, 30) - Math.Sqrt((x - i.TX) * (x - i.TX) + (y - i.TY) * (y - i.TY)) * 0.25;
                if (d > best) { best = d; bx = x; by = y; }
            }
            i.X = bx; i.Y = by;
            placed.Add(i);
        }

        for (var round = 0; round < 8; round++)
        {
            var clash = false;
            for (var it = 0; it < 240; it++)
            {
                clash = false;
                for (var a = 0; a < isles.Count; a++)
                    for (var b = a + 1; b < isles.Count; b++)
                    {
                        var p = isles[a]; var q = isles[b];
                        var need = (p.R + q.R) * 1.45 + 6;
                        var dx = q.X - p.X; var dy = q.Y - p.Y;
                        var d = Math.Sqrt(dx * dx + dy * dy);
                        if (d >= need) continue;
                        clash = true;
                        if (d < 0.01) { dx = 1; dy = 0.3; d = 1; }
                        var push = (need - d) / 2 + 0.5;
                        p.X -= dx / d * push; p.Y -= dy / d * push;
                        q.X += dx / d * push; q.Y += dy / d * push;
                    }
                foreach (var i in isles)
                {
                    i.X = Math.Clamp(i.X, m + i.R * 1.3, Math.Max(m + i.R * 1.3, cols - rsv - i.R * 1.3));
                    i.Y = Math.Clamp(i.Y, m + i.R * 1.25, Math.Max(m + i.R * 1.25, rows - m - i.R * 1.25));
                }
                if (!clash) break;
            }
            if (!clash) break;
            foreach (var i in isles) i.R = Math.Max(6, i.R * 0.88);
        }

        for (var k = 0; k < isles.Count; k++)
        {
            var i = isles[k];
            i.TX = i.X; i.TY = i.Y; i.TR = i.R;
            (i.X, i.Y, i.R) = keep[k];
            if (snap) { i.X = i.TX; i.Y = i.TY; i.R = i.TR; }
            else if (!i.Placed) { i.X = i.TX; i.Y = i.TY; i.R = 0.5; }
            i.Placed = true;
        }
    }

    void Survey(Isle i)
    {
        var R = i.TR;
        i.FR = R;
        var seed = Frac(i.Name) * 100;
        var hills = new List<(double X, double Y, double S, double H)>();
        var count = 2 + (int)(Frac(i.Name + "n") * 3);
        for (var k = 0; k < count; k++)
        {
            var a = Frac(i.Name + "a" + k) * Math.PI * 2;
            var d = k == 0 ? 0 : R * (0.25 + Frac(i.Name + "d" + k) * 0.35);
            hills.Add((Math.Cos(a) * d, Math.Sin(a) * d * 0.8, R * (k == 0 ? 0.55 : 0.30 + Frac(i.Name + "s" + k) * 0.2), k == 0 ? 1 : 0.55 + Frac(i.Name + "h" + k) * 0.35));
        }
        i.HW = (int)(R * 1.6) + 1;
        i.HH = (int)(R * 1.5) + 1;
        var w = i.HW * 2 + 1;
        var h = i.HH * 2 + 1;
        i.Field = new float[w * h];
        var top = -1f;
        for (var y = 0; y < h; y++)
            for (var x = 0; x < w; x++)
            {
                double gx = x - i.HW, gy = y - i.HH, f = 0;
                foreach (var (hx, hy, s, hh) in hills)
                {
                    var dd = ((gx - hx) * (gx - hx) + (gy - hy) * (gy - hy) * 1.25) / (s * s);
                    f = Math.Max(f, hh * Math.Exp(-dd * 0.9));
                }
                f += 0.035 * Math.Sin(gx * 0.17 + seed) * Math.Sin(gy * 0.15 + seed * 1.7) + 0.008 * Math.Sin(gx * 0.6 + gy * 0.5 + seed);
                i.Field[y * w + x] = (float)f;
                if (f > top) { top = (float)f; i.PkX = (int)gx; i.PkY = (int)gy; }
            }
        for (var b = 0; b < 64; b++)
        {
            var ra = b * Math.PI * 2 / 64;
            var d = 0.0;
            for (var r = 0.0; r < Math.Max(i.HW, i.HH); r += 0.5)
            {
                int x = (int)Math.Round(Math.Cos(ra) * r) + i.HW, y = (int)Math.Round(Math.Sin(ra) * r) + i.HH;
                if (x < 0 || y < 0 || x >= w || y >= h) break;
                if (i.Field[y * w + x] >= 0.14f) d = r;
            }
            i.Coast[b] = d;
        }
    }

    void Draw()
    {
        chart = new int[cols * rows];
        var step = Compact ? 20 : 40;
        for (var x = step; x < cols; x += step) for (var y = 0; y < rows; y += 3) Put(chart, x, y, W(24));
        for (var y = step; y < rows; y += step) for (var x = 0; x < cols; x += 3) Put(chart, x, y, W(24));
        for (var x = 0; x < cols; x++) { Put(chart, x, 2, W(70)); Put(chart, x, rows - 3, W(70)); if (x % 8 == 0) { Put(chart, x, 3, W(70)); Put(chart, x, rows - 4, W(70)); } }
        for (var y = 0; y < rows; y++) { Put(chart, cols - 3, y, W(70)); if (y % 8 == 0) Put(chart, cols - 4, y, W(70)); }

        var rx = cols - 34; var ry = rows - 34; var rr = 20;
        for (var a = 0; a < (Compact ? 0 : 360); a += 3)
        {
            var ra = a * Math.PI / 180;
            Put(chart, rx + (int)Math.Round(Math.Cos(ra) * rr), ry + (int)Math.Round(Math.Sin(ra) * rr), W(a % 45 == 0 ? 120 : 60));
            if (a % 15 == 0) Put(chart, rx + (int)Math.Round(Math.Cos(ra) * (rr - 2)), ry + (int)Math.Round(Math.Sin(ra) * (rr - 2)), W(60));
        }
        for (var k = 0; k < (Compact ? 0 : 8); k++)
        {
            var ra = k * Math.PI / 4 - Math.PI / 2;
            var len = k % 2 == 0 ? rr - 3 : rr * 0.55;
            Line(chart, rx, ry, rx + (int)Math.Round(Math.Cos(ra) * len), ry + (int)Math.Round(Math.Sin(ra) * len), W(k == 0 ? 220 : k % 2 == 0 ? 130 : 70));
        }

        foreach (var i in isles) if (i.Name != sinking) Island(i, chart, 1, 1);
    }

    void Island(Isle i, int[] target, double scale, double fade)
    {
        if (i.Field.Length == 0 || i.FR <= 0) return;
        var s = scale * i.R / i.FR;
        if (s < 0.03) return;
        var g = Math.Max(i.Glow, i == hover || i.Name == highlighted ? 1 : 0);
        int Mix(int dark, int bright) => (int)Math.Round((dark + (bright - dark) * g) * fade);
        var levels = Math.Clamp(1 + (int)Math.Round(Math.Log(1 + i.Mods) * 1.3), 1, 8);
        var w = i.HW * 2 + 1;
        int Level(int x, int y)
        {
            var lx = (int)Math.Round((x - i.X) / s);
            var ly = (int)Math.Round((y - i.Y) / s);
            if (lx < -i.HW || ly < -i.HH || lx > i.HW || ly > i.HH) return -1;
            var f = i.Field[(ly + i.HH) * w + lx + i.HW];
            if (f < 0.14f) return -1;
            if (f < 0.22f) return 0;
            return 1 + Math.Min(levels, (int)((f - 0.22f) / (0.78f / levels)));
        }
        bool Show(int x, int y) => fade >= 1 || Bayer[y & 3, x & 3] < fade * 16;
        int x0 = (int)Math.Floor(i.X - (i.HW + 1) * s), x1 = (int)Math.Ceiling(i.X + (i.HW + 1) * s);
        int y0 = (int)Math.Floor(i.Y - (i.HH + 1) * s), y1 = (int)Math.Ceiling(i.Y + (i.HH + 1) * s);
        for (var y = y0; y <= y1; y++)
            for (var x = x0; x <= x1; x++)
            {
                var l = Level(x, y);
                if (l < 0 || !Show(x, y)) continue;
                if (l == 0)
                {
                    if (Bayer[y & 3, x & 3] < 2) Put(target, x, y, W(Mix(34, 60)));
                    continue;
                }
                var right = Level(x + 1, y);
                var down = Level(x, y + 1);
                var left = Level(x - 1, y);
                var up = Level(x, y - 1);
                var coast = right < 1 || down < 1 || left < 1 || up < 1;
                var contour = right != l && right >= 1 || down != l && down >= 1;
                int v;
                if (coast) v = W(Mix(150, 235));
                else if (contour) v = W(Mix(80, 150) - Math.Min(40, l * 4));
                else v = Bayer[y & 3, x & 3] < 1 ? W(Mix(18, 30)) : bgPix;
                Put(target, x, y, v);
            }
        if (scale < 0.999 || s < 0.6) return;
        int px = (int)Math.Round(i.X + i.PkX * s), py = (int)Math.Round(i.Y + i.PkY * s);
        Put(target, px, py - 1, W(Mix(170, 255)));
        Put(target, px - 1, py, W(Mix(170, 255)));
        Put(target, px + 1, py, W(Mix(170, 255)));
        for (var k = 0; k < Math.Min(6, i.Worlds); k++)
        {
            var a = Frac(i.Name + "hut" + k) * Math.PI * 2;
            var hx = (int)(i.X + Math.Cos(a) * i.R * 0.35);
            var hy = (int)(i.Y + Math.Sin(a) * i.R * 0.3);
            if (Level(hx, hy) < 1) continue;
            for (var y = hy - 1; y <= hy; y++) for (var x = hx - 1; x <= hx; x++) Put(target, x, y, W(Mix(120, 230)));
        }
    }

    string? sinking;
    double sinkStart;
    Action? sinkDone;
    const double DragFor = 3.4;

    public void Sink(string name, Action done)
    {
        if (sinking != null || !isles.Any(i => i.Name == name) || !Settings.Motion) { done(); return; }
        sinking = name;
        sinkStart = (DateTime.UtcNow - start).TotalSeconds;
        sinkDone = done;
        dirty = true;
    }

    static double DragFrom(Isle i) => 0.3;
    double SinkFor(Isle i) => DragFrom(i) + DragFor + 1.2;

    void DrawSinking(double t)
    {
        var i = isles.FirstOrDefault(x => x.Name == sinking);
        if (i == null) { sinking = null; return; }
        var a = t - sinkStart;
        var dragFrom = DragFrom(i);
        var g = Math.Clamp((a - dragFrom) / DragFor, 0, 1);
        var pull = g * g;
        var close = Ease((a - dragFrom - DragFor) / 1.2);
        var scale = 1 - pull * 0.92;
        var fade = 1 - Ease((g - 0.62) / 0.38);

        double cx = i.X, cy = i.Y;

        if (fade > 0.02)
        {
            var (sx, sy) = (i.X, i.Y);
            i.X = cx; i.Y = cy;
            Island(i, buf, scale, fade);
            i.X = sx; i.Y = sy;
        }
        if (g > 0 && g < 1)
        {
            var sc = (i.FR > 0 ? i.R / i.FR : 1) * scale;
            var foam = Math.Min(1, g * 4) * (1 - Ease((g - 0.8) / 0.2));
            for (var b = 0; b < 64; b++)
                for (var d = 0; d < 3; d++)
                {
                    var ra = (b + d / 3.0) * Math.PI * 2 / 64;
                    var r2 = i.Coast[b] * sc + 1 + Frac(sinking + "f" + b + d + (int)(t * 12)) * 2.5;
                    if (Frac(sinking + "o" + b + d + (int)(t * 9)) < 0.5 * foam) Max(buf, (int)Math.Round(cx + Math.Cos(ra) * r2), (int)Math.Round(cy + Math.Sin(ra) * r2), 190);
                }
        }

        if (close > 0)
            for (var ring = 0; ring < 3; ring++)
            {
                var rad = i.R * (0.3 + close * 1.4 + ring * 0.3);
                var al = (int)(140 * (1 - close) * (1 - ring * 0.25));
                for (var deg = 0; deg < 360; deg += 3)
                {
                    var ra = deg * Math.PI / 180;
                    var r2 = rad + Math.Sin(ra * 9 + ring) * 0.8;
                    Max(buf, (int)Math.Round(i.X + Math.Cos(ra) * r2), (int)Math.Round(i.Y + Math.Sin(ra) * r2 * 0.85), al);
                }
            }

        if (a >= SinkFor(i))
        {
            var done = sinkDone;
            sinking = null;
            sinkDone = null;
            dirty = true;
            done?.Invoke();
        }
    }

    (double X, double Y) Anchor(Isle i) => (i.X - i.R * 1.05 - 6, i.Y + i.R * 0.35);

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
            layout = true; snap = true;
        }
        if (layout)
        {
            Place();
            foreach (var i in isles) if (i.Field.Length == 0 || Math.Abs(i.FR - i.TR) > 0.25) Survey(i);
            layout = false; snap = false; dirty = true;
        }
        var k = Math.Min(1, dt * 2.2);
        if (!Settings.Motion) k = 1;
        foreach (var i in isles)
        {
            if (Math.Abs(i.TX - i.X) + Math.Abs(i.TY - i.Y) + Math.Abs(i.TR - i.R) < 0.05) { i.X = i.TX; i.Y = i.TY; i.R = i.TR; continue; }
            i.X += (i.TX - i.X) * k; i.Y += (i.TY - i.Y) * k; i.R += (i.TR - i.R) * k;
            dirty = true;
        }
        var arrived = sailStart < 0;
        foreach (var i in isles)
        {
            var want = i.Active && arrived ? 1.0 : 0.0;
            if (Math.Abs(i.Glow - want) < 0.01) { if (i.Glow != want) dirty = true; i.Glow = want; continue; }
            i.Glow += (want - i.Glow) * Math.Min(1, dt * 1.6);
            dirty = true;
        }
        if (dirty)
        {
            Draw();
            dirty = false;
        }
        Array.Copy(chart, buf, buf.Length);

        for (var y = 4; y < rows - 4; y++)
            for (var x = 0; x < cols - 4; x++)
            {
                var idx = y * cols + x;
                if (buf[idx] != 0) continue;
                var v = 0.07 + 0.06 * Math.Sin(x * 0.045 + t * 0.35 + Math.Sin(y * 0.06 + t * 0.1) * 2.2);
                if (v * 16 > Bayer[y & 3, (x + (int)(t * 2)) & 3]) buf[idx] = W(14);
            }

        var home = isles.FirstOrDefault(i => i.Active);
        if (home != null && home.Glow > 0.05 && home.Name != sinking)
            for (var ring = 0; ring < 2; ring++)
            {
                var age = ((t * 0.3) + ring / 2.0) % 1.0;
                var a = (int)(95 * (1 - age) * Math.Min(1, age * 5) * home.Glow);
                var sc = home.FR > 0 ? home.R / home.FR : 1;
                var steps = (int)(home.R * 6);
                for (var s = 0; s < steps; s++)
                {
                    var ra = s * Math.PI * 2 / steps;
                    if (Math.Sin(ra * 9 + ring * 2.1 + t * 0.5) < 0.35) continue;
                    var bf = ra / (Math.PI * 2) * 64;
                    var b0 = (int)bf % 64;
                    var shore = home.Coast[b0] + (home.Coast[(b0 + 1) % 64] - home.Coast[b0]) * (bf - (int)bf);
                    var r2 = shore * sc + 2 + age * Math.Min(4, home.R * 0.2) + Math.Sin(ra * 17 + t * 2 + ring) * 0.5;
                    Max(buf, (int)Math.Round(home.X + Math.Cos(ra) * r2), (int)Math.Round(home.Y + Math.Sin(ra) * r2), a);
                }
            }

        if (sinking != null) DrawSinking(t);

        if (home != null)
        {
            var (ax, ay) = Anchor(home);
            if (double.IsNaN(boatX)) { boatX = ax; boatY = ay; }
            if (sailStart == -2) { fromX = boatX; fromY = boatY; sailStart = t; }
            if (sailStart >= 0)
            {
                var st = t - sailStart;
                var e = Voyage.Progress(st);
                var nx = fromX + (ax - fromX) * e;
                var ny = fromY + (ay - fromY) * e;
                if (Math.Abs(nx - boatX) + Math.Abs(ny - boatY) > 0.001) heading = Math.Atan2(ny - boatY, nx - boatX);
                boatX = nx; boatY = ny;
                Line(buf, (int)fromX, (int)fromY, (int)ax, (int)ay, W(90), 3);
                if (st >= Voyage.SailFor) sailStart = -1;
                if (wake.Count == 0 || Math.Abs(wake[^1].X - boatX) + Math.Abs(wake[^1].Y - boatY) > 1.5) wake.Add((boatX, boatY, t));
            }
            else { boatX = ax; boatY = ay + Math.Sin(t * 0.8) * 0.4; }
            wake.RemoveAll(p => t - p.T > 6);
            foreach (var (wx, wy, wt) in wake)
            {
                var a = (int)(120 * (1 - (t - wt) / 6));
                Put(buf, (int)wx, (int)wy, W(a));
            }
            double hx = Math.Cos(heading), hy = Math.Sin(heading);
            if (sailStart < 0) { hx = 1; hy = 0; }
            for (var s = -4; s <= 4; s++)
            {
                var half = s > 2 ? (4 - s) * 0.6 : 1.6;
                for (var q = -half; q <= half; q += 0.5)
                {
                    var x = (int)Math.Round(boatX + hx * s - hy * q);
                    var y = (int)Math.Round(boatY + hy * s + hx * q);
                    Put(buf, x, y, Math.Abs(q) > half - 0.6 || Math.Abs(s) == 4 ? W(245) : bgPix);
                }
            }
            Put(buf, (int)Math.Round(boatX), (int)Math.Round(boatY), W(255));
        }

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));

        if (Compact) return;
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        foreach (var i in isles)
        {
            if (i.Name == sinking || i.R < i.TR * 0.6) continue;
            var lit = i.Active || i == hover || i.Name == highlighted;
            var name = new FormattedText(i.Name.ToUpperInvariant() + (i.Active ? "  (HERE)" : i == hover ? "  SAIL HERE" : ""), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 11, lit ? text : muted, dpi);
            var sub = new FormattedText(i.Mods + " MODS   " + i.Worlds + (i.Worlds == 1 ? " WORLD" : " WORLDS"), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 9.5, faint, dpi);
            var lw = Math.Max(name.Width, sub.Width);
            var ly = (i.Y - i.R * 0.75) * P;
            double Crowd(double x0)
            {
                var cx = (x0 + lw / 2) / P; var cy = ly / P + 6;
                var worst = 0.0;
                foreach (var o in isles) if (o != i) worst = Math.Max(worst, o.R * 1.2 - Math.Sqrt((cx - o.X) * (cx - o.X) + (cy - o.Y) * (cy - o.Y)) + lw / P / 2);
                if (x0 < 4 || x0 + lw > w - 20) worst += 1000;
                return worst;
            }
            var rightX = (i.X + i.R * 0.95) * P + 10;
            var leftX = (i.X - i.R * 0.95) * P - 10 - lw;
            var lx = Crowd(rightX) <= Crowd(leftX) ? rightX : leftX;
            ly = Math.Clamp(ly, 14, h - 40);
            dc.DrawRectangle(bg, null, new Rect(Math.Round(lx) - 3, Math.Round(ly), Math.Max(name.Width, sub.Width) + 6, name.Height + sub.Height + 2));
            dc.DrawText(name, new Point(Math.Round(lx), Math.Round(ly)));
            dc.DrawText(sub, new Point(Math.Round(lx), Math.Round(ly + name.Height + 1)));
        }
        var n = new FormattedText("N", CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 11, text, dpi);
        dc.DrawText(n, new Point(Math.Round((cols - 34) * P - n.Width / 2), Math.Round((rows - 34 - 20) * P - n.Height - 4)));
        for (var x = 40; x < cols - 10; x += 40)
        {
            var deg = new FormattedText((x / 4) + "°", CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 9, faint, dpi);
            dc.DrawText(deg, new Point(x * P + 4.0, 12));
        }
    }
}
