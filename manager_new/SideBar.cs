using System.Globalization;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public abstract class PixelArt : FrameworkElement
{
    protected static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };
    protected readonly int P;
    protected int cols, rows;
    protected int[] buf = Array.Empty<int>();
    protected int bgPix = unchecked((int)0xFF0B0B0C);
    WriteableBitmap? bitmap;
    readonly DateTime start = DateTime.UtcNow;
    double lastT;
    bool running;
    protected Typeface face = new("Consolas");
    protected Brush text = Brushes.White, faint = Brushes.Gray;

    protected PixelArt(int p)
    {
        P = p;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        Loaded += (_, _) =>
        {
            if (TryFindResource(BgKey) is SolidColorBrush b) bgPix = unchecked((int)(0xFF000000u | ((uint)b.Color.R << 16) | ((uint)b.Color.G << 8) | b.Color.B));
            if (TryFindResource("Mono") is FontFamily mono) face = new Typeface(mono, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            if (TryFindResource("Text") is Brush t) text = t;
            if (TryFindResource("Faint") is Brush f) faint = f;
            Begin();
        };
        Unloaded += (_, _) => End();
        IsVisibleChanged += (_, _) => { if (IsVisible) Begin(); else End(); };
    }

    void Begin() { if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; } running = true; Anim.Add(Tick); }
    void End() { if (!running) return; running = false; Anim.Remove(Tick); }
    void Tick(object? s, EventArgs e) => InvalidateVisual();

    protected double Now => (DateTime.UtcNow - start).TotalSeconds;
    protected static int W(int a) { a = Math.Clamp(a, 0, 255); return (a << 24) | (a << 16) | (a << 8) | a; }
    protected static double Frac(double a, double b) { var v = Math.Sin(a * 12.9898 + b * 78.233) * 43758.5453; return v - Math.Floor(v); }
    protected void Put(int x, int y, int v) { if (x >= 0 && y >= 0 && x < cols && y < rows) buf[y * cols + x] = v; }
    protected void Max(int x, int y, int a)
    {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return;
        var i = y * cols + x;
        if (((buf[i] >> 24) & 0xFF) < a) buf[i] = W(a);
    }

    protected virtual string BgKey => "Side";
    protected abstract void Paint(double t, double dt);
    protected virtual void Overlay(DrawingContext dc, double t) { }

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 4 || h < 4) return;
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
        }
        Array.Clear(buf);
        Paint(t, dt);
        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));
        Overlay(dc, t);
    }
}

public sealed class Beacon : PixelArt
{
    public bool Lit { get; set; }
    public bool Enabled { get; set; } = true;
    public event Action? Clicked;
    bool hover;
    double fire = 0.1;
    readonly List<(double X, double Y, double Vx, double Vy, double Life)> sparks = new();
    readonly Random rng = new();

    public Beacon() : base(3)
    {
        Cursor = Cursors.Hand;
        MouseEnter += (_, _) => hover = true;
        MouseLeave += (_, _) => hover = false;
        MouseLeftButtonUp += (_, _) => { if (Enabled) Clicked?.Invoke(); };
    }

    protected override void Paint(double t, double dt)
    {
        var since = SideMast.Clock - SideMast.StartedAt;
        var burst = since < 2.6 ? 1 - since / 2.6 : 0;
        var want = Lit ? 0.75 : hover && Enabled ? 0.65 : 0.0;
        want += burst * 0.8;
        fire += (want - fire) * Math.Min(1, dt * (want > fire ? 4 : 1.6));

        var frame = W(hover && Enabled ? 235 : Lit ? 170 : 110);
        for (var x = 0; x < cols; x++) { Put(x, 0, frame); Put(x, rows - 1, frame); }
        for (var y = 0; y < rows; y++) { Put(0, y, frame); Put(cols - 1, y, frame); }

        var bed = rows - 3;
        for (var x = 2; x < cols - 2; x++)
        {
            var heat = 0.45 + 0.55 * Math.Sin(x * 0.7 + t * 1.3 + Math.Sin(x * 0.23 + t * 0.5) * 2);
            var coal = (int)(40 + (60 + 120 * fire) * Math.Max(0, heat));
            Put(x, bed, W(coal));
            if (Bayer[(bed - 1) & 3, x & 3] < 4 + fire * 10) Put(x, bed - 1, W(coal / 2));
        }
        for (var y = 1; y < bed - 1; y++)
            for (var x = 1; x < cols - 1; x++)
            {
                var up = (bed - y) / (double)(bed - 1);
                var g = (1 - up) * (0.18 + fire * 0.55);
                if (g * 9 > Bayer[y & 3, x & 3] + 1) Max(x, y, (int)(14 + 28 * g));
            }
        if (fire > 0.02)
            for (var x = 2; x < cols - 2; x++)
            {
                var n = 0.5 + 0.5 * Math.Sin(x * 0.55 + t * 6 + Math.Sin(t * 2.3 + x * 0.3) * 2.2);
                var hgt = fire * (rows - 5) * (0.35 + 0.65 * n * n);
                for (var k = 0; k < (int)hgt; k++)
                {
                    var f = k / Math.Max(1, hgt);
                    if (Frac(Math.Floor(t * 14) + x * 3.1, k) < 0.25 + f * 0.5) continue;
                    var sway = (int)Math.Round(Math.Sin(t * 5 + k * 0.45 + x * 0.2) * f * 1.5);
                    Max(x + sway, bed - 2 - k, (int)((215 - f * 150) * (0.55 + 0.45 * fire)));
                }
            }

        if (rng.NextDouble() < (0.4 + fire * 14) * dt)
            sparks.Add((2 + rng.NextDouble() * (cols - 4), bed - 2, (rng.NextDouble() - 0.5) * 6, -(8 + rng.NextDouble() * 16) * (0.5 + fire), 1));
        for (var i = sparks.Count - 1; i >= 0; i--)
        {
            var (x, y, vx, vy, life) = sparks[i];
            x += vx * dt + Math.Sin(t * 5 + i) * dt * 3; y += vy * dt; life -= dt * 0.8;
            if (life <= 0 || y < 2) { sparks.RemoveAt(i); continue; }
            sparks[i] = (x, y, vx, vy, life);
            Max((int)x, (int)y, (int)(240 * life));
        }
    }
}

public sealed class TideGauge : PixelArt
{
    public double Value { get; set; }
    public double Mark { get; set; } = 0.8;
    public TideGauge() : base(2) { }

    protected override void Paint(double t, double dt)
    {
        var fill = (int)Math.Round(Math.Clamp(Value, 0, 1) * (cols - 2));
        for (var x = 0; x < cols; x++)
        {
            var on = x >= 1 && x <= fill;
            var top = rows / 2 - 2 + (on ? (int)Math.Round(Math.Sin(x * 0.4 - t * 3) * 0.8) : 0);
            for (var y = 0; y < rows; y++)
            {
                if (on && y >= top) Put(x, y, Bayer[y & 3, x & 3] < 9 || y == top ? W(y == top ? 230 : 120) : bgPix);
                else if (y == rows - 1) Put(x, y, W(70));
            }
            if (x % 6 == 0) Put(x, rows - 2, W(70));
        }
        var mx = (int)(Mark * (cols - 2)) + 1;
        for (var y = 0; y < rows; y++) if ((y & 1) == 0) Put(mx, y, W(200));
    }
}

public sealed class MiniChart : PixelArt
{
    sealed class Isle { public string Name = ""; public bool Active; public double X, Y, R; public int Mods; }
    List<Isle> isles = new();
    public event Action<string>? Picked;
    Isle? hover;
    double boatX = double.NaN, boatY, fromX, fromY, sailAt = -1;
    readonly List<(double X, double Y, double T)> wake = new();

    public MiniChart() : base(2)
    {
        MouseMove += (_, e) => { var h = At(e.GetPosition(this)); hover = h; Cursor = h is { Active: false } ? Cursors.Hand : null; };
        MouseLeave += (_, _) => { hover = null; Cursor = null; };
        MouseLeftButtonUp += (_, e) => { if (At(e.GetPosition(this)) is { Active: false } i) Picked?.Invoke(i.Name); };
    }

    Isle? At(Point p)
    {
        foreach (var i in isles)
        {
            double dx = p.X / P - i.X, dy = p.Y / P - i.Y;
            if (dx * dx + dy * dy < (i.R + 3) * (i.R + 3)) return i;
        }
        return null;
    }

    static double Hash(string s) { uint h = 2166136261; foreach (var c in s) { h ^= c; h *= 16777619; } h ^= h >> 15; h *= 0x846ca68b; h ^= h >> 16; return (h & 0xFFFF) / 65536.0; }

    public void SetIslands(IEnumerable<(string Name, int Mods, bool Active)> list)
    {
        var before = isles.FirstOrDefault(i => i.Active)?.Name;
        isles = list.Select(x => new Isle { Name = x.Name, Mods = x.Mods, Active = x.Active }).ToList();
        var now = isles.FirstOrDefault(i => i.Active)?.Name;
        if (before != null && now != before) sailAt = -2;
    }

    void Layout()
    {
        var n = Math.Max(1, isles.Count);
        for (var k = 0; k < isles.Count; k++)
        {
            var i = isles[k];
            i.R = Math.Clamp(3.5 + Math.Log(1 + i.Mods) * 0.9, 4, 8);
            i.X = 8 + (cols - 16) * (k + 0.5) / n;
            i.Y = rows * (0.32 + 0.36 * Hash(i.Name));
        }
    }

    protected override void Paint(double t, double dt)
    {
        Layout();
        for (var y = 0; y < rows; y++)
            for (var x = 0; x < cols; x++)
            {
                if (x % 16 == 0 && y % 2 == 0 || y % 16 == 0 && x % 2 == 0) { Put(x, y, W(22)); continue; }
                var v = 0.05 + 0.05 * Math.Sin(x * 0.12 + t * 0.6 + Math.Sin(y * 0.2 + t * 0.2) * 2);
                if (v * 16 > Bayer[y & 3, (x + (int)(t * 3)) & 3]) Put(x, y, W(15));
            }

        foreach (var i in isles)
        {
            var lit = i.Active || i == hover;
            var r = i.R;
            for (var dy = -(int)r - 2; dy <= (int)r + 2; dy++)
                for (var dx = -(int)r - 2; dx <= (int)r + 2; dx++)
                {
                    var d = Math.Sqrt(dx * dx + dy * dy * 1.4);
                    int x = (int)i.X + dx, y = (int)i.Y + dy;
                    if (d <= r)
                    {
                        var coast = d > r - 1;
                        var contour = Math.Abs(d - r * 0.55) < 0.5;
                        Put(x, y, coast ? W(lit ? 240 : 140) : contour ? W(lit ? 120 : 70) : bgPix);
                    }
                    else if (d <= r + 1.5 && Bayer[y & 3, x & 3] < 3) Put(x, y, W(lit ? 80 : 45));
                }
            Put((int)i.X, (int)i.Y - 1, W(lit ? 255 : 160));
            Put((int)i.X - 1, (int)i.Y, W(lit ? 220 : 120));
            Put((int)i.X + 1, (int)i.Y, W(lit ? 220 : 120));
            if (i.Active)
            {
                var age = (t * 0.35) % 1.0;
                var rr = r + 2 + age * 5;
                for (var a = 0; a < 56; a++)
                {
                    var ra = a * Math.PI * 2 / 56;
                    if (Math.Sin(ra * 6 + t) < 0.2) continue;
                    Max((int)Math.Round(i.X + Math.Cos(ra) * rr), (int)Math.Round(i.Y + Math.Sin(ra) * rr * 0.8), (int)(110 * (1 - age)));
                }
            }
        }

        var home = isles.FirstOrDefault(i => i.Active);
        if (home != null)
        {
            double ax = home.X - home.R - 5, ay = home.Y + 2;
            if (double.IsNaN(boatX)) { boatX = ax; boatY = ay; }
            if (sailAt == -2) { fromX = boatX; fromY = boatY; sailAt = t; }
            if (sailAt >= 0)
            {
                var f = Math.Min(1, (t - sailAt) / 1.6);
                var e = f * f * (3 - 2 * f);
                boatX = fromX + (ax - fromX) * e;
                boatY = fromY + (ay - fromY) * e;
                wake.Add((boatX, boatY, t));
                if (f >= 1) sailAt = -1;
            }
            else { boatX = ax; boatY = ay + Math.Sin(t) * 0.3; }
            wake.RemoveAll(p => t - p.T > 1.5);
            foreach (var (wx, wy, wt) in wake) Max((int)wx + 2, (int)wy + 1, (int)(130 * (1 - (t - wt) / 1.5)));
            int bxi = (int)Math.Round(boatX), byi = (int)Math.Round(boatY);
            for (var x = -3; x <= 3; x++) Put(bxi + x, byi, W(240));
            for (var x = -2; x <= 2; x++) Put(bxi + x, byi + 1, W(150));
            for (var k = 1; k <= 5; k++) Put(bxi, byi - k, W(220));
            for (var k = 1; k <= 4; k++) for (var x = 1; x <= 5 - k; x++) Put(bxi - x, byi - k, k == 1 || x == 5 - k ? W(230) : bgPix);
        }
    }

    protected override void Overlay(DrawingContext dc, double t)
    {
        if (hover == null) return;
        var ft = new FormattedText(hover.Name.ToUpperInvariant() + (hover.Active ? "  (HERE)" : "  SAIL HERE"), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 9.5, text, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        var x = Math.Clamp(hover.X * P - ft.Width / 2, 3, ActualWidth - ft.Width - 3);
        var y = Math.Max(2, (hover.Y - hover.R - 3) * P - ft.Height);
        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb((byte)(bgPix >> 16), (byte)(bgPix >> 8), (byte)bgPix)), null, new Rect(x - 3, y, ft.Width + 6, ft.Height));
        dc.DrawText(ft, new Point(Math.Round(x), Math.Round(y)));
    }
}

public sealed class Lever : FrameworkElement
{
    readonly string[] labels;
    int index;
    double pos;
    bool animating;
    public event Action<int>? Changed;
    Typeface face = new("Consolas");
    Brush text = Brushes.White, faint = Brushes.Gray, edge = Brushes.DimGray;

    public Lever(string[] labels, int index)
    {
        this.labels = labels;
        this.index = index;
        pos = index;
        Height = 40;
        Cursor = Cursors.Hand;
        SnapsToDevicePixels = true;
        Loaded += (_, _) =>
        {
            if (TryFindResource("Mono") is FontFamily mono) face = new Typeface(mono, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            if (TryFindResource("Text") is Brush t) text = t;
            if (TryFindResource("Faint") is Brush f) faint = f;
            if (TryFindResource("Edge") is Brush e) edge = e;
        };
        MouseLeftButtonUp += (_, e) =>
        {
            var x = e.GetPosition(this).X;
            var k = (int)Math.Clamp(Math.Round(x / Slot - 0.5), 0, labels.Length - 1);
            if (k == index) return;
            index = k;
            Changed?.Invoke(k);
            if (!animating) { animating = true; Anim.Add(Step); }
        };
    }

    double Slot => ActualWidth / labels.Length;

    void Step(object? s, EventArgs e)
    {
        pos += (index - pos) * 0.22;
        if (Math.Abs(index - pos) < 0.01) { pos = index; animating = false; Anim.Remove(Step); }
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        var w = ActualWidth;
        if (w < 10) return;
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var bg = new SolidColorBrush(Color.FromRgb(0x0B, 0x0B, 0x0C));
        var trackY = 8.0;
        dc.DrawRectangle(edge, null, new Rect(0, trackY, w, 1));
        dc.DrawRectangle(edge, null, new Rect(0, trackY + 7, w, 1));
        dc.DrawRectangle(edge, null, new Rect(0, trackY, 1, 8));
        dc.DrawRectangle(edge, null, new Rect(w - 1, trackY, 1, 8));
        for (var k = 0; k < labels.Length; k++)
        {
            var cx = Slot * (k + 0.5);
            var on = k == index;
            var ft = new FormattedText(labels[k].ToUpperInvariant(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 10, on ? text : faint, dpi);
            dc.DrawText(ft, new Point(Math.Round(cx - ft.Width / 2), 24));
        }
        var px = Math.Round(Slot * pos);
        var sw = Math.Round(Slot);
        dc.DrawRectangle(text, null, new Rect(px + 2, trackY + 2, sw - 4, 4));
        var tilt = (index - pos) * 8;
        var hx = Math.Round(px + sw / 2 + tilt);
        dc.DrawRectangle(text, null, new Rect(hx - 1, trackY - 6, 2, 8));
        dc.DrawRectangle(text, null, new Rect(hx - 4, trackY - 9, 8, 4));
        dc.DrawRectangle(bg, null, new Rect(hx - 2, trackY - 8, 4, 2));
    }
}

public sealed class Heartbeat : PixelArt
{
    public string State { get; set; } = "ok";
    public Heartbeat() : base(2) { }

    protected override void Paint(double t, double dt)
    {
        var mid = rows / 2;
        var speed = State == "run" ? 40 : 24;
        var period = State == "run" ? 34 : 52;
        for (var x = 0; x < cols; x++)
        {
            var y = mid;
            if (State != "bad")
            {
                var ph = ((x - t * speed) % period + period) % period;
                if (ph < 10)
                {
                    int[] shape = { 0, 1, 0, -1, -5, 3, 1, 0, -1, 0 };
                    y = mid + (State == "warn" ? shape[(int)ph] / 2 : shape[(int)ph]);
                }
            }
            var fade = Math.Min(1, (x + 1) / 14.0) * Math.Min(1, (cols - x) / 14.0);
            var a = (int)((State == "bad" ? 80 + 30 * Math.Sin(t * 3) : 150) * fade);
            Put(x, Math.Clamp(y, 0, rows - 1), W(a));
        }
    }
}

public sealed class SideMast : PixelArt
{
    public static bool Running { get; set; }
    public static double StartedAt { get; set; } = -99;
    static readonly DateTime Epoch = DateTime.UtcNow;
    public static double Clock => (DateTime.UtcNow - Epoch).TotalSeconds;
    readonly List<(double X, double Y, double Vx, double Vy, double Life)> embers = new();
    readonly Random rng = new();

    public bool Mast { get; set; } = true;
    public SideMast() : base(3) { IsHitTestVisible = false; }

    double shotAt = -99, shotX, shotY;

    protected override void Paint(double t, double dt)
    {
        Space(t);
        var mx = (int)(cols * 0.86);
        var top = (int)(rows * 0.035);
        if (Mast) DrawMast(mx, top);
        Embers(dt);
    }

    void Space(double t)
    {
        if (skyCache.Length != buf.Length) BuildSky();
        Array.Copy(skyCache, buf, buf.Length);
        StarsAndMore(t);
    }

    int[] skyCache = Array.Empty<int>();

    void BuildSky()
    {
        Array.Clear(buf);
        for (var y = 0; y < rows; y++)
            for (var x = 0; x < cols; x++)
            {
                var wx = x + 9 * Math.Sin(y * 0.045);
                var wy = y + 7 * Math.Sin(x * 0.06 + 1.1);
                var a = Math.Sin(wx * 0.07 + wy * 0.025) * Math.Sin(wy * 0.032 - wx * 0.021 + 0.8);
                var b = Math.Sin(wx * 0.19 + wy * 0.11 + 2.3) * Math.Sin(wy * 0.15 - wx * 0.07);
                var cloud = Math.Max(0, a - 0.2) * (0.8 + 0.4 * b);
                var fil = Math.Abs(Math.Sin(wx * 0.11 + wy * 0.09 + a * 3)) < 0.08 && a > 0.1 ? 0.5 : 0;
                var fade = 1 - Math.Max(0, (y / (double)rows - 0.55) * 1.8);
                var v = (cloud * 1.2 + fil) * Math.Max(0, fade);
                if (v * 16 > Bayer[y & 3, x & 3] + 1) Put(x, y, W((int)(10 + v * 16)));
            }

        for (var i = 0; i < cols * rows / 26; i++)
        {
            var u = Frac(i, 13.1);
            var bx = u * cols;
            var by = rows * 0.08 + u * rows * 0.55 + (Frac(i, 17.3) - 0.5) * (Frac(i, 19.7) * 18 + 4);
            Max((int)bx, (int)by, (int)((8 + 24 * Frac(i, 23.9))));
        }
        skyCache = (int[])buf.Clone();
    }

    void StarsAndMore(double t)
    {

        var count = cols * rows / 60;
        for (var i = 0; i < count; i++)
        {
            var sx = (int)(Frac(i, 2.3) * cols);
            var sy = (int)(Frac(i, 4.7) * rows * 0.86);
            var tw = 0.5 + 0.5 * Math.Sin(t * (0.4 + Frac(i, 6.1) * 1.8) + i * 1.3);
            var mag = Frac(i, 8.3);
            var a = (int)((20 + 80 * mag * mag) * (0.4 + 0.6 * tw));
            Max(sx, sy, a);
            if (mag > 0.93)
            {
                var spike = 1 + (int)(tw * 2.5);
                for (var k = 1; k <= spike; k++)
                {
                    var s2 = a * (spike - k + 1) / (spike + 2);
                    Max(sx - k, sy, s2); Max(sx + k, sy, s2); Max(sx, sy - k, s2); Max(sx, sy + k, s2);
                }
                Max(sx, sy, 150);
            }
        }

        Galaxy(cols * 0.70, rows * 0.585, Math.Max(6, cols * 0.11), t);

        for (var i = 0; i < 26; i++)
        {
            var ang = Frac(i, 31.7) * Math.PI * 2;
            var rr = Math.Pow(Frac(i, 37.1), 1.6) * cols * 0.06;
            var tw = 0.6 + 0.4 * Math.Sin(t * 1.1 + i * 2.1);
            Max((int)(cols * 0.80 + Math.Cos(ang) * rr), (int)(rows * 0.075 + Math.Sin(ang) * rr * 0.8), (int)((35 + 70 * Frac(i, 41.3)) * tw));
        }

        RingedWorld(cols * 0.72, rows * 0.705, Math.Max(5, cols * 0.085), t);

        var cp = (t % 70) / 70;
        if (cp < 0.6)
        {
            var f = cp / 0.6;
            var hx = cols * (1.1 - f * 1.3);
            var hy = rows * (0.04 + f * 0.1);
            var fade = Math.Sin(f * Math.PI);
            for (var k = 0; k < 26; k++)
            {
                var spread = k * 0.18;
                for (var s2 = -1; s2 <= 1; s2++)
                {
                    var x = (int)Math.Round(hx + k * 0.95);
                    var y = (int)Math.Round(hy - k * 0.12 + s2 * spread * 0.5);
                    if (s2 != 0 && Bayer[y & 3, x & 3] > 8 - k / 4) continue;
                    Max(x, y, (int)(fade * (s2 == 0 ? 110 : 50) * (1 - k / 26.0)));
                }
            }
            Max((int)hx, (int)hy, (int)(200 * fade));
            Max((int)hx - 1, (int)hy, (int)(90 * fade));
        }

        var sat = (t % 34) / 34;
        if (sat < 0.7)
        {
            var x = (int)(cols * (sat / 0.7));
            var y = (int)(rows * 0.52 - sat * rows * 0.08);
            Max(x, y, Math.Sin(t * 9) > 0.6 ? 150 : 45);
        }

        if (t - shotAt > 9 + Frac(Math.Floor(t / 9), 3.3) * 14) { shotAt = t; shotX = Frac(t, 1.1) * cols * 0.6 + cols * 0.3; shotY = Frac(t, 2.2) * rows * 0.5; }
        var age = t - shotAt;
        if (age < 0.9)
            for (var k = 0; k < 12; k++)
            {
                var p = age * 60 - k * 1.1;
                if (p < 0) continue;
                Max((int)(shotX - p), (int)(shotY + p * 0.45), (int)(150 * (1 - age / 0.9) * (1 - k / 12.0)));
            }
    }

    void Galaxy(double cx, double cy, double r, double t)
    {
        const double tilt = 0.5, flat = 0.42;
        double ct = Math.Cos(tilt), st = Math.Sin(tilt);
        var spin = t * 0.015;
        for (var arm = 0; arm < 2; arm++)
            for (var k = 0; k < 160; k++)
            {
                var f = k / 160.0;
                var th = arm * Math.PI + f * Math.PI * 2.6 + spin;
                var rad = f * r;
                var jit = (Frac(k + arm * 300, 51.1) - 0.5) * (1.2 + f * 2.2);
                var ex = Math.Cos(th) * (rad + jit);
                var ey = Math.Sin(th) * (rad + jit) * flat;
                var x = (int)Math.Round(cx + ex * ct - ey * st);
                var y = (int)Math.Round(cy + ex * st + ey * ct);
                Max(x, y, (int)((70 - f * 50) * (0.6 + 0.4 * Frac(k, 57.7))));
            }
        for (var dy = -3; dy <= 3; dy++)
            for (var dx = -5; dx <= 5; dx++)
            {
                var d = Math.Sqrt(dx * dx / 2.2 + dy * dy);
                if (d > 2.6) continue;
                Max((int)cx + dx, (int)cy + dy, (int)(120 * (1 - d / 2.6) + 20));
            }
        Max((int)cx, (int)cy, 190);
    }

    void RingedWorld(double cx, double cy, double r, double t)
    {
        const double tilt = -0.32;
        double ct = Math.Cos(tilt), st = Math.Sin(tilt);
        void Ring(bool back)
        {
            var steps = (int)(r * 30);
            for (var k = 0; k < steps; k++)
            {
                var th = k * Math.PI * 2 / steps;
                if (back != Math.Sin(th) < 0) continue;
                for (var rr = r * 1.35; rr <= r * 2.1; rr += 0.6)
                {
                    var f = (rr - r * 1.35) / (r * 0.75);
                    if (f > 0.55 && f < 0.65) continue;
                    double ex = Math.Cos(th) * rr, ey = Math.Sin(th) * rr * 0.24;
                    var x = (int)Math.Round(cx + ex * ct - ey * st);
                    var y = (int)Math.Round(cy + ex * st + ey * ct);
                    if (back && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) continue;
                    var lit = 0.55 + 0.45 * Math.Cos(th + 2.4);
                    var dens = 0.5 + 0.4 * Math.Sin(f * 23);
                    if (dens * 14 < Bayer[y & 3, x & 3]) continue;
                    Max(x, y, (int)((26 + 46 * dens) * lit));
                }
            }
        }
        Ring(true);
        var ir = (int)Math.Ceiling(r);
        for (var dy = -ir; dy <= ir; dy++)
            for (var dx = -ir; dx <= ir; dx++)
            {
                if (dx * dx + dy * dy > r * r) continue;
                double nx = dx / r, ny = dy / r, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - ny * ny));
                var lam = Math.Clamp(-0.65 * nx - 0.45 * ny + 0.4 * nz, 0, 1);
                var v = -dx * st + dy * ct;
                var band = 0.5 + 0.5 * Math.Sin(v / r * 9 + Math.Sin(dx * ct / r * 3 + t * 0.05));
                int x = (int)cx + dx, y = (int)cy + dy;
                var val = 8 + 70 * lam * (0.7 + 0.3 * band);
                if (Bayer[y & 3, x & 3] < (1 - lam) * 12) val *= 0.45;
                if (dx * dx + dy * dy > (r - 1.1) * (r - 1.1) && lam > 0.3) val = 95;
                Put(x, y, W((int)val));
            }
        Ring(false);
        var ma = t * Math.PI * 2 / 40;
        double mx = Math.Cos(ma) * r * 2.6, my = Math.Sin(ma) * r * 2.6 * 0.3;
        var px = (int)Math.Round(cx + mx * ct - my * st);
        var py = (int)Math.Round(cy + mx * st + my * ct);
        if (!(Math.Sin(ma) < 0 && (px - cx) * (px - cx) + (py - cy) * (py - cy) <= r * r))
        {
            Max(px, py, 110); Max(px + 1, py, 60); Max(px, py + 1, 40);
        }
    }

    void DrawMast(int mx, int top)
    {
        var dim = W(26);
        for (var y = top; y < rows; y++) Put(mx, y, dim);
        foreach (var (fy, half) in new[] { (0.10, 9), (0.30, 13), (0.52, 11) })
        {
            var y = top + (int)(rows * fy);
            for (var x = -half; x <= half; x++) if ((x & 1) == 0 || Math.Abs(x) < 2) Put(mx + x, y, W(24));
            Put(mx - half, y + 1, W(20)); Put(mx + half, y + 1, W(20));
        }

        var strength = Running ? 1.0 : 0.45;
        for (var i = 0; i < 14; i++)
        {
            var f = i / 13.0;
            var x = mx - 1 - (int)Math.Round(f * 12);
            var y = top + (int)Math.Round(Math.Sin(Clock * (Running ? 6.5 : 3.2) - f * 4.5) * f * 2.2 * strength + f * (1 - strength) * 3);
            Put(x, y, W(Running ? 120 : 70));
            if (f < 0.6) Put(x, y + 1, W(Running ? 90 : 50));
        }
    }

    void Embers(double dt)
    {
        var starting = Clock - StartedAt < 25 ? 1 - (Clock - StartedAt) / 25 : 0;
        var rate = 0.6 + starting * 10 + (Running ? 1.2 : 0);
        if (rng.NextDouble() < rate * dt)
            embers.Add((rng.NextDouble() * cols, rows - 1 - rng.NextDouble() * rows * 0.15, (rng.NextDouble() - 0.5) * 3, -(6 + rng.NextDouble() * 10), 1));
        for (var i = embers.Count - 1; i >= 0; i--)
        {
            var (x, y, vx, vy, life) = embers[i];
            x += (vx + Math.Sin(Clock * 1.5 + i) * 2) * dt; y += vy * dt; life -= dt * 0.12;
            if (life <= 0 || y < 0) { embers.RemoveAt(i); continue; }
            embers[i] = (x, y, vx, vy, life);
            Max((int)x, (int)y, (int)((60 + starting * 150) * life));
        }
    }
}
