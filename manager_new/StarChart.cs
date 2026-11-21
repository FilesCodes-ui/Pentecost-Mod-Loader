using System.Globalization;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class StarChart : FrameworkElement
{
    const int P = 3;

    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    public sealed class Star
    {
        public string Key = "", Name = "", Loader = "";
        public bool On, Update;
        public double X, Y, Phase, Rate;
        public string[] Needs = Array.Empty<string>(), NeededBy = Array.Empty<string>();
    }

    public event Action<string>? Picked;

    List<Star> stars = new();
    Dictionary<string, Star> byKey = new();
    string? open, hover;
    readonly DateTime start = DateTime.UtcNow;
    bool running;
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();
    double[] bank = Array.Empty<double>();
    int warn = unchecked((int)0xFFE0A030);
    Brush text = Brushes.White, faint = Brushes.Gray, bg = Brushes.Black;
    Typeface face = new("Consolas");

    double fallAt = 6, fallX, fallY, fallDx, fallDy;
    readonly Random rnd = new(7);
    readonly (double X, double Y, int A, double P)[] dust = Enumerable.Range(0, 160)
        .Select(i => { var r = new Random(1000 + i); return (r.NextDouble(), r.NextDouble(), 26 + r.Next(36), r.NextDouble() * 6.3); }).ToArray();

    public StarChart()
    {
        ClipToBounds = true;
        Focusable = false;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        Loaded += (_, _) =>
        {
            text = (Brush)FindResource("Text");
            faint = (Brush)FindResource("Muted");
            bg = (Brush)FindResource("Bg");
            if (TryFindResource("Warn") is SolidColorBrush w)
                warn = unchecked((int)(0xFF000000u | ((uint)w.Color.R << 16) | ((uint)w.Color.G << 8) | w.Color.B));
            if (TryFindResource("Mono") is FontFamily mono) face = new Typeface(mono, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            Start();
        };
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
        SizeChanged += (_, e) =>
        {
            if (stars.Count == 0 || (Math.Abs(e.NewSize.Width - laidW) < laidW * 0.12 && e.PreviousSize.Height == e.NewSize.Height)) return;
            laidW = e.NewSize.Width;
            Layout(stars, e.NewSize.Width, e.NewSize.Height);
        };
        MouseMove += (_, e) =>
        {
            var s = Nearest(e.GetPosition(this));
            var key = s?.Key;
            if (key == hover) return;
            hover = key;
            Cursor = key != null ? Cursors.Hand : null;
        };
        MouseLeave += (_, _) => { hover = null; Cursor = null; };
        MouseLeftButtonUp += (_, e) => { if (Nearest(e.GetPosition(this)) is { } s) Picked?.Invoke(s.Key); };
        Bank();
    }

    void Start()
    {
        if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; }
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

    public string? Open { get => open; set => open = value; }

    public int Hidden { get; private set; }

    string laidOut = "";
    double laidW;

    public void SetStars(List<Star> list)
    {
        Hidden = 0;
        var cap = Math.Max(10, Settings.Stars);
        if (list.Count > cap)
        {
            var keys = list.Select(s => s.Key).ToHashSet();
            var deg = list.ToDictionary(s => s.Key, s => s.Needs.Count(keys.Contains) + s.NeededBy.Count(keys.Contains));
            var root = list.ToDictionary(s => s.Key, s => s.Key);
            string Find(string k) { while (root[k] != k) k = root[k] = root[root[k]]; return k; }
            foreach (var s in list) foreach (var k in s.Needs) if (keys.Contains(k)) root[Find(s.Key)] = Find(k);
            var chosen = new HashSet<string>(list.Where(s => s.Update).Select(s => s.Key).Take(cap));
            var groups = list.GroupBy(s => Find(s.Key)).Select(g => g.ToList()).Where(g => g.Count > 1).OrderByDescending(g => g.Count);
            foreach (var g in groups)
            {
                if (chosen.Count >= cap) break;
                var room = Math.Min(cap - chosen.Count, Math.Max(2, cap / 2));
                if (room < 2) break;
                foreach (var s in g.OrderByDescending(s => deg[s.Key]).ThenBy(s => s.Key, StringComparer.Ordinal).Take(room)) chosen.Add(s.Key);
            }
            foreach (var s in list.Where(s => deg[s.Key] == 0).OrderBy(s => Hash(s.Key)))
            {
                if (chosen.Count >= cap) break;
                chosen.Add(s.Key);
            }
            var kept = list.Where(s => chosen.Contains(s.Key)).ToList();
            Hidden = list.Count - kept.Count;
            list = kept;
        }
        var sig = string.Join("|", list.OrderBy(s => s.Key, StringComparer.Ordinal).Select(s => s.Key + ":" + string.Join(",", s.Needs.OrderBy(x => x))));
        var w = ActualWidth > 100 ? ActualWidth : 1300;
        var old = byKey;
        stars = list;
        byKey = list.GroupBy(s => s.Key).ToDictionary(g => g.Key, g => g.First());
        foreach (var s in list)
        {
            var hh = Hash(s.Key);
            s.Phase = Frac(hh >> 7) * Math.PI * 2;
            s.Rate = 0.6 + Frac(hh >> 13) * 1.4;
        }
        if (sig == laidOut && Math.Abs(w - laidW) < w * 0.12)
        {
            foreach (var s in list) if (old.TryGetValue(s.Key, out var o)) { s.X = o.X; s.Y = o.Y; }
            return;
        }
        laidOut = sig;
        laidW = w;
        Layout(list, w, ActualHeight > 60 ? ActualHeight : 236);
    }

    static void Layout(List<Star> list, double W, double H)
    {
        var n = list.Count;
        if (n == 0) return;
        var index = new Dictionary<string, int>();
        for (var i = 0; i < n; i++) index.TryAdd(list[i].Key, i);
        var edges = new HashSet<(int, int)>();
        for (var i = 0; i < n; i++)
            foreach (var k in list[i].Needs)
                if (index.TryGetValue(k, out var j) && j != i) edges.Add((Math.Min(i, j), Math.Max(i, j)));

        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x) { while (parent[x] != x) x = parent[x] = parent[parent[x]]; return x; }
        foreach (var (i, j) in edges) parent[Find(i)] = Find(j);
        var groups = Enumerable.Range(0, n).GroupBy(Find).Select(g => g.ToList()).ToList();
        var figures = groups.Where(g => g.Count > 1).OrderByDescending(g => g.Count).ThenBy(g => list[g[0]].Key, StringComparer.Ordinal).ToList();
        var loose = groups.Where(g => g.Count == 1).Select(g => g[0]).OrderBy(i => list[i].Key, StringComparer.Ordinal).ToList();

        var top = 16.0;
        var bottom = H - 52;
        var left = 18.0;
        var right = W - 18;
        bool InTitle(double x, double y) => x < 360 && y > H * 0.38;

        var x = new double[n];
        var y = new double[n];

        var usable = right - 380;
        var weight = Math.Max(1, figures.Sum(g => Math.Sqrt(g.Count)));
        var cursor = 380.0;
        var placed = new List<int>();
        var fi = 0;
        foreach (var g in figures)
        {
            var span = usable * Math.Sqrt(g.Count) / weight;
            var cx = cursor + span / 2;
            cursor += span;
            var narrow = span < 120 && figures.Count > 3;
            var mid = (top + bottom) / 2;
            double gTop = top, gBottom = bottom;
            if (narrow) { if (fi % 2 == 0) gBottom = mid + 8; else gTop = mid - 8; }
            fi++;
            var half = narrow ? Math.Max(span * 0.95, 46) : Math.Min(span * 0.45, 40 + 26 * Math.Sqrt(g.Count));
            foreach (var i in g)
            {
                var h = Hash(list[i].Key);
                x[i] = cx + (Frac(h) - 0.5) * half * 2;
                y[i] = gTop + 6 + Frac(h >> 21) * (gBottom - gTop - 12);
            }
            var kk = Math.Clamp(Math.Sqrt((half * 2) * (gBottom - gTop) / g.Count) * 0.8, 30, 64);
            var rest = Math.Min(84, kk * 1.15);
            var heat = kk * 0.6;
            var ex = edges.Where(e => g.Contains(e.Item1)).ToList();
            var hh = (gBottom - gTop) / 2;
            var gx = 0.2 * g.Count * kk * kk / Math.Pow(Math.Max(20, 0.65 * half), 2);
            var gy = 0.2 * g.Count * kk * kk / Math.Pow(Math.Max(12, 0.65 * hh), 2);
            for (var it = 0; it < 300; it++)
            {
                var fx = new Dictionary<int, double>();
                var fy = new Dictionary<int, double>();
                foreach (var i in g) { fx[i] = 0; fy[i] = 0; }
                for (var p = 0; p < g.Count; p++)
                    for (var q = p + 1; q < g.Count; q++)
                    {
                        int i = g[p], j = g[q];
                        var dx = x[i] - x[j];
                        var dy = y[i] - y[j];
                        var d2 = dx * dx + dy * dy + 0.01;
                        var f = kk * kk / d2;
                        fx[i] += dx * f; fy[i] += dy * f;
                        fx[j] -= dx * f; fy[j] -= dy * f;
                    }
                foreach (var (i, j) in ex)
                {
                    var dx = x[j] - x[i];
                    var dy = y[j] - y[i];
                    var d = Math.Sqrt(dx * dx + dy * dy) + 0.01;
                    var f = (d - rest) * 0.8;
                    fx[i] += dx / d * f; fy[i] += dy / d * f;
                    fx[j] -= dx / d * f; fy[j] -= dy / d * f;
                }
                foreach (var i in g)
                {
                    fx[i] += (cx - x[i]) * Math.Max(0.02, gx);
                    fy[i] += ((gTop + gBottom) / 2 - y[i]) * Math.Max(0.02, gy);
                    var len = Math.Sqrt(fx[i] * fx[i] + fy[i] * fy[i]) + 0.0001;
                    var step = Math.Min(len, heat);
                    x[i] = Math.Max(Math.Max(left, cx - half), Math.Min(Math.Min(right, cx + half), x[i] + fx[i] / len * step));
                    y[i] = Math.Max(gTop, Math.Min(gBottom, y[i] + fy[i] / len * step));
                }
                heat *= 0.985;
            }
            placed.AddRange(g);
        }

        foreach (var i in loose)
        {
            var bestD = -1.0;
            double bx = left, by = top;
            for (var c = 0; c < 9; c++)
            {
                var h = Hash(list[i].Key + "~" + c);
                var cxp = left + Frac(h) * (right - left);
                var cyp = top + Frac(h >> 21) * (bottom - top);
                if (InTitle(cxp, cyp)) continue;
                var d = double.MaxValue;
                foreach (var j in placed)
                {
                    var dx = x[j] - cxp;
                    var dy = (y[j] - cyp) * 1.8;
                    d = Math.Min(d, dx * dx + dy * dy);
                }
                var edgeGap = Math.Min(cyp - top, bottom - cyp) * 1.8 * 2;
                d = Math.Min(d, edgeGap * edgeGap);
                if (d > bestD) { bestD = d; bx = cxp; by = cyp; }
            }
            x[i] = bx;
            y[i] = by;
            placed.Add(i);
        }

        for (var i = 0; i < n; i++)
        {
            list[i].X = x[i] / W;
            list[i].Y = y[i] / H;
        }
    }

    public bool ShowTies { get; set; } = true;

    static ulong Hash(string s)
    {
        ulong h = 1469598103934665603;
        foreach (var c in s) { h ^= c; h *= 1099511628211; }
        h ^= h >> 29; h *= 0xBF58476D1CE4E5B9; h ^= h >> 32;
        return h;
    }

    static double Frac(ulong v) => (v & 0xFFFFF) / (double)0x100000;

    Star? Nearest(Point p)
    {
        Star? best = null;
        var bestD = 14.0 * 14;
        foreach (var s in stars)
        {
            var dx = s.X * ActualWidth - p.X;
            var dy = s.Y * ActualHeight - p.Y;
            var d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    void Bank()
    {
        const int period = 900;
        bank = new double[period];
        var r = new Random(91);
        double x = 0;
        while (x < period)
        {
            var rad = 8 + r.NextDouble() * 14;
            var top = rad * (0.4 + r.NextDouble() * 0.5);
            for (var dx = -(int)rad; dx <= (int)rad; dx++)
            {
                var hh = rad * rad - dx * dx;
                if (hh <= 0) continue;
                var y = top - rad + Math.Sqrt(hh);
                var i = (((int)x + dx) % period + period) % period;
                if (y > bank[i]) bank[i] = y;
            }
            x += rad * (0.9 + r.NextDouble() * 0.6);
        }
    }

    static int White(int a) { a = Math.Clamp(a, 0, 255); return (a << 24) | (a << 16) | (a << 8) | a; }

    void Put(int x, int y, int a)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows || a <= 0) return;
        var i = y * cols + x;
        var old = (buf[i] >> 24) & 0xFF;
        if (a > old) buf[i] = White(a);
    }

    void PutColor(int x, int y, int argb)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        buf[y * cols + x] = argb;
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 4 || h < 4) return;
        var t = (DateTime.UtcNow - start).TotalSeconds;
        var c = Math.Max(1, (int)Math.Ceiling(w / P));
        var r = Math.Max(1, (int)Math.Ceiling(h / P));
        if (bitmap == null || c != cols || r != rows)
        {
            cols = c;
            rows = r;
            bitmap = new WriteableBitmap(cols, rows, 96, 96, PixelFormats.Pbgra32, null);
            buf = new int[cols * rows];
        }
        Array.Clear(buf);

        var shimmer = (int)(t * 0.6) & 3;
        for (var y = 0; y < rows; y++)
        {
            var v = 1 - y / (double)rows;
            for (var x = 0; x < cols; x++)
            {
                var band = Math.Exp(-Math.Pow((y - (rows * 0.62 - x * rows * 0.0016)) / (rows * 0.13), 2)) * 0.22;
                var d = Math.Pow(v, 1.7) * 0.42 + band;
                if (Bayer[(y + shimmer) & 3, (x + (shimmer >> 1)) & 3] / 16.0 < d * 0.5) Put(x, y, 11 + (int)(band * 30));
            }
        }

        foreach (var (fx, fy, fa, fp) in dust)
        {
            var a = (int)(fa * (0.75 + 0.25 * Math.Sin(t * 0.7 + fp)));
            Put((int)(fx * cols), (int)(fy * rows * 0.86), a);
        }

        var baseY = rows - 3;
        var drift = t * 2.2;
        for (var x = 0; x < cols; x++)
        {
            var p = ((x * 1.0 + drift) % bank.Length + bank.Length) % bank.Length;
            var top = baseY - (int)Math.Round(bank[(int)p] * 0.55);
            for (var y = Math.Max(0, top + 1); y < rows; y++) buf[y * cols + x] = 0;
            Put(x, top, 58);
            for (var y = top + 2; y < Math.Min(rows, top + 7); y++)
                if (Bayer[y & 3, x & 3] < 3 - (y - top) / 2) Put(x, y, 18);
        }

        if (ShowTies)
            foreach (var s in stars)
                foreach (var k in s.Needs)
                    if (byKey.TryGetValue(k, out var o)) Faint(s, o);

        var focus = open != null && byKey.ContainsKey(open) ? byKey[open] : hover != null && byKey.ContainsKey(hover) ? byKey[hover] : null;
        var lit = new HashSet<string>();
        if (focus != null)
        {
            lit.Add(focus.Key);
            foreach (var k in focus.Needs) if (byKey.TryGetValue(k, out var s)) { Line(focus, s, t, 150, 2); lit.Add(k); }
            foreach (var k in focus.NeededBy) if (byKey.TryGetValue(k, out var s)) { Line(s, focus, t, 95, 3); lit.Add(k); }
        }

        foreach (var s in stars)
        {
            var x = (int)(s.X * cols);
            var y = (int)(s.Y * rows);
            if (!s.On) { Put(x, y, lit.Contains(s.Key) ? 150 : 92); continue; }
            var tw = 0.72 + 0.28 * Math.Sin(t * s.Rate + s.Phase);
            var core = (int)((s.Loader == "Forge" ? 255 : s.Loader == "Both" ? 235 : 200) * tw);
            if (s == focus || s.Key == hover)
            {
                var pulse = 0.8 + 0.2 * Math.Sin(t * 3.1);
                Put(x, y, 255);
                for (var k = 1; k <= 3; k++)
                {
                    var a = (int)(255 * pulse * (1 - k * 0.27));
                    Put(x + k, y, a); Put(x - k, y, a); Put(x, y + k, a); Put(x, y - k, a);
                }
                Put(x + 1, y + 1, 110); Put(x - 1, y - 1, 110); Put(x + 1, y - 1, 110); Put(x - 1, y + 1, 110);
            }
            else
            {
                if (lit.Contains(s.Key)) core = 255;
                Put(x, y, core);
                var arm = (int)(core * 0.42);
                Put(x + 1, y, arm); Put(x - 1, y, arm); Put(x, y + 1, arm); Put(x, y - 1, arm);
                if (Math.Sin(t * s.Rate * 0.37 + s.Phase * 3) > 0.985) { Put(x + 2, y, arm / 2); Put(x - 2, y, arm / 2); Put(x, y + 2, arm / 2); Put(x, y - 2, arm / 2); }
            }
            if (s.Update) PutColor(x + 2, y - 2, warn);
        }

        if (t > fallAt)
        {
            var age = t - fallAt;
            if (age > 1.1)
            {
                fallAt = t + 12 + rnd.NextDouble() * 22;
                fallX = cols * (0.35 + rnd.NextDouble() * 0.6);
                fallY = rows * (0.04 + rnd.NextDouble() * 0.2);
                var ang = Math.PI * (0.78 + rnd.NextDouble() * 0.12);
                fallDx = Math.Cos(ang) * cols * 0.28;
                fallDy = Math.Sin(ang) * rows * 0.5;
            }
            else
            {
                var hx = fallX + fallDx * age;
                var hy = fallY + fallDy * age;
                for (var k = 0; k < 14; k++)
                {
                    var f = k / 14.0;
                    Put((int)(hx - fallDx * 0.12 * f), (int)(hy - fallDy * 0.12 * f), (int)(230 * (1 - f) * (1 - age / 1.1)));
                }
            }
        }

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));

        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var taken = new List<Rect>();
        void Label(Star s, bool bright)
        {
            var ft = new FormattedText(s.Name.ToUpperInvariant(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 10.5, bright ? text : faint, dpi);
            var sx = (int)(s.X * cols) * P;
            var sy = (int)(s.Y * rows) * P;
            var spots = new[]
            {
                new Point(sx + 9, sy - ft.Height / 2), new Point(sx - 9 - ft.Width, sy - ft.Height / 2),
                new Point(sx + 9, sy - ft.Height - 4), new Point(sx - 9 - ft.Width, sy - ft.Height - 4),
                new Point(sx + 9, sy + 4), new Point(sx - 9 - ft.Width, sy + 4),
            };
            foreach (var at in spots)
            {
                var r = new Rect(Math.Round(at.X) - 3, Math.Round(at.Y), ft.Width + 6, ft.Height);
                if (r.Left < 2 || r.Right > w - 2 || r.Top < 0 || r.Bottom > h) continue;
                if (taken.Any(o => o.IntersectsWith(r))) continue;
                taken.Add(r);
                dc.DrawRectangle(bg, null, r);
                dc.DrawText(ft, new Point(r.Left + 3, r.Top));
                return;
            }
            if (!bright) return;
            foreach (var at in spots)
            {
                var r = new Rect(Math.Round(at.X) - 3, Math.Round(at.Y), ft.Width + 6, ft.Height);
                if (r.Left < 2 || r.Right > w - 2 || r.Top < 0 || r.Bottom > h) continue;
                dc.DrawRectangle(bg, null, r);
                dc.DrawText(ft, new Point(r.Left + 3, r.Top));
                return;
            }
        }
        if (focus != null)
        {
            Label(focus, true);
            foreach (var k in lit) if (k != focus.Key && byKey.TryGetValue(k, out var s)) Label(s, false);
        }
        if (hover != null && byKey.TryGetValue(hover, out var hs) && hs != focus) Label(hs, true);
    }

    void Faint(Star a, Star b)
    {
        int x0 = (int)(a.X * cols), y0 = (int)(a.Y * rows), x1 = (int)(b.X * cols), y1 = (int)(b.Y * rows);
        int dx = Math.Abs(x1 - x0), dy = -Math.Abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        var n = 0;
        while (true)
        {
            if (n > 2 && n % 4 == 0) Put(x0, y0, 34);
            if (x0 == x1 && y0 == y1) break;
            var e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
            n++;
        }
    }

    void Line(Star a, Star b, double t, int alpha, int every)
    {
        int x0 = (int)(a.X * cols), y0 = (int)(a.Y * rows), x1 = (int)(b.X * cols), y1 = (int)(b.Y * rows);
        int dx = Math.Abs(x1 - x0), dy = -Math.Abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        var n = 0;
        var flow = (int)(t * 9);
        while (true)
        {
            if (n > 3 && ((n - flow) % (every * 2) + every * 2) % (every * 2) < every) Put(x0, y0, alpha);
            if (x0 == x1 && y0 == y1) break;
            var e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
            n++;
        }
    }
}
