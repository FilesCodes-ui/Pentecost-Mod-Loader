using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public class PixelBox : Border
{
    public static readonly DependencyProperty NotchProperty = DependencyProperty.Register(nameof(Notch), typeof(double), typeof(PixelBox),
        new FrameworkPropertyMetadata(2.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty SheetProperty = DependencyProperty.Register(nameof(Sheet), typeof(bool), typeof(PixelBox),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public double Notch { get => (double)GetValue(NotchProperty); set => SetValue(NotchProperty, value); }

    public bool Sheet { get => (bool)GetValue(SheetProperty); set => SetValue(SheetProperty, value); }

    Point? seen;

    public PixelBox()
    {
        SnapsToDevicePixels = true;
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        Loaded += (_, _) => LayoutUpdated += Moved;
        Unloaded += (_, _) => LayoutUpdated -= Moved;
    }

    void Moved(object? s, EventArgs e)
    {
        if (!Sheet || !IsVisible) return;
        var p = Where();
        if (p == null || seen == null) return;
        if (Math.Abs(p.Value.X - seen.Value.X) >= 1 || Math.Abs(p.Value.Y - seen.Value.Y) >= 1) InvalidateVisual();
    }

    Point? Where()
    {
        try
        {
            var root = Window.GetWindow(this);
            return root == null ? null : TransformToAncestor(root).Transform(new Point(0, 0));
        }
        catch { return null; }
    }

    static Geometry Outline(double l, double t, double r, double b, int k)
    {
        k = Math.Min(k, (int)Math.Min((r - l) / 3, (b - t) / 3));
        var g = new StreamGeometry();
        using (var c = g.Open())
        {
            if (k <= 0)
            {
                c.BeginFigure(new Point(l, t), true, true);
                c.LineTo(new Point(r, t), true, false);
                c.LineTo(new Point(r, b), true, false);
                c.LineTo(new Point(l, b), true, false);
            }
            else
            {
                void Corner(double x, double y, int sx, int sy, bool first)
                {
                    var pts = new List<Point>();
                    for (var i = 0; i < k; i++)
                    {
                        pts.Add(new Point(x + sx * (k - i), y + sy * i));
                        pts.Add(new Point(x + sx * (k - i), y + sy * (i + 1)));
                    }
                    pts.Add(new Point(x, y + sy * k));
                    _ = pts;
                }
                var path = new List<Point>();
                void Add(double x, double y, int sx, int sy, bool clockwise)
                {
                    var pts = new List<Point>();
                    for (var i = 0; i <= k; i++)
                    {
                        pts.Add(new Point(x + sx * (k - i), y + sy * i));
                        if (i < k) pts.Add(new Point(x + sx * (k - i - 1), y + sy * i));
                    }
                    if (!clockwise) pts.Reverse();
                    path.AddRange(pts);
                }
                _ = (Action<double, double, int, int, bool>)((x, y, sx, sy, f) => Corner(x, y, sx, sy, f));
                Add(l, t, 1, 1, false);
                Add(r, t, -1, 1, true);
                Add(r, b, -1, -1, false);
                Add(l, b, 1, -1, true);
                c.BeginFigure(path[0], true, true);
                for (var i = 1; i < path.Count; i++) c.LineTo(path[i], true, false);
            }
        }
        g.Freeze();
        return g;
    }

    protected override void OnRender(DrawingContext dc)
    {
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 1 || h < 1) return;
        var line = BorderBrush != null && BorderThickness.Left > 0 ? BorderThickness.Left : 0;
        var o = line / 2;
        var k = Notch >= 3 ? 3 : Notch >= 1 ? 2 : 0;
        var path = Outline(o, o, w - o, h - o, k);
        dc.DrawGeometry(Background, null, path);

        if (Sheet && Where() is { } at && w > 40 && h > 24)
        {
            seen = at;
            var inner = new Rect(line, line, Math.Max(0, w - 2 * line), Math.Max(0, h - 2 * line));
            var view = new Rect((at.X + line) / Field.Cell, (at.Y + line) / Field.Cell, inner.Width / Field.Cell, inner.Height / Field.Cell);
            dc.PushClip(Outline(line, line, w - line, h - line, k));
            dc.DrawRectangle(new ImageBrush(Field.Image) { ViewboxUnits = BrushMappingMode.Absolute, Viewbox = view, Stretch = Stretch.Fill }, null, inner);
            dc.Pop();
        }

        if (line > 0) dc.DrawGeometry(null, new Pen(BorderBrush, line) { LineJoin = PenLineJoin.Miter }, path);
    }
}

public static class TriMark
{
    public static byte[] Frame(int cells, double t)
    {
        var buf = new byte[cells * cells];
        var eye = 3.1 + 0.55 * Math.Sin(t * 0.47);
        Draw(buf, cells, Angles(t - 0.22), eye, 70, 0.40);
        Draw(buf, cells, Angles(t - 0.11), eye, 130, 0.40);
        Draw(buf, cells, Angles(t), eye, 255, 0.40);
        return buf;
    }

    static (double, double, double) Angles(double t) => (t * 0.91, t * 1.37, t * 0.58);

    static void Draw(byte[] buf, int n, (double X, double Y, double Z) turn, double eye, byte level, double reachPart)
    {
        var (ax, ay, az) = turn;
        var pts = new (double X, double Y, double Z)[3];
        for (var i = 0; i < 3; i++)
        {
            var a = Math.PI / 2 + i * 2 * Math.PI / 3;
            double x = Math.Cos(a), y = Math.Sin(a), z = 0;

            (y, z) = (y * Math.Cos(ax) - z * Math.Sin(ax), y * Math.Sin(ax) + z * Math.Cos(ax));
            (x, z) = (x * Math.Cos(ay) + z * Math.Sin(ay), -x * Math.Sin(ay) + z * Math.Cos(ay));
            (x, y) = (x * Math.Cos(az) - y * Math.Sin(az), x * Math.Sin(az) + y * Math.Cos(az));
            pts[i] = (x, y, z);
        }

        var mid = n / 2.0 - 0.5;
        var reach = n * reachPart;
        var flat = new (double X, double Y, double Depth)[3];
        for (var i = 0; i < 3; i++)
        {
            var (x, y, z) = pts[i];
            var k = 2.2 / (eye - z);
            flat[i] = (mid + x * k * reach, mid - y * k * reach, k);
        }

        for (var i = 0; i < 3; i++)
        {
            var a = flat[i];
            var b = flat[(i + 1) % 3];
            Line(buf, n, a.X, a.Y, b.X, b.Y, Dim(level, (a.Depth + b.Depth) / 2));
        }

        foreach (var (x, y, depth) in flat)
        {
            var r = n >= 26 ? (depth > 0.95 ? 2 : 1) : (depth > 0.95 ? 1 : 0);
            var bright = Dim(level, depth + 0.25);
            for (var dy = -r; dy <= r; dy++)
                for (var dx = -r; dx <= r; dx++)
                {
                    if (r == 2 && Math.Abs(dx) == 2 && Math.Abs(dy) == 2) continue;
                    Put(buf, n, (int)Math.Round(x) + dx, (int)Math.Round(y) + dy, bright);
                }
        }
    }
    static byte Dim(byte level, double depth) => (byte)Math.Max(0, Math.Min(255, level * Math.Max(0.33, Math.Min(1.0, depth * 0.78))));

    static void Put(byte[] buf, int n, int x, int y, byte v)
    {
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        var i = y * n + x;
        if (buf[i] < v) buf[i] = v;
    }

    static void Line(byte[] buf, int n, double x0, double y0, double x1, double y1, byte v)
    {
        var steps = (int)Math.Ceiling(Math.Max(Math.Abs(x1 - x0), Math.Abs(y1 - y0)));
        if (steps <= 0) { Put(buf, n, (int)Math.Round(x0), (int)Math.Round(y0), v); return; }
        for (var s = 0; s <= steps; s++)
        {
            var f = (double)s / steps;
            Put(buf, n, (int)Math.Round(x0 + (x1 - x0) * f), (int)Math.Round(y0 + (y1 - y0) * f), v);
        }
    }
}

public class TriSpinner : FrameworkElement
{
    public static readonly DependencyProperty CellsProperty = DependencyProperty.Register(nameof(Cells), typeof(int), typeof(TriSpinner),
        new FrameworkPropertyMetadata(44, FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly DependencyProperty FillProperty = DependencyProperty.Register(nameof(Fill), typeof(Color), typeof(TriSpinner),
        new FrameworkPropertyMetadata(Color.FromRgb(0xF2, 0xF2, 0xF2), FrameworkPropertyMetadataOptions.AffectsRender));

    public int Cells { get => (int)GetValue(CellsProperty); set => SetValue(CellsProperty, value); }
    public Color Fill { get => (Color)GetValue(FillProperty); set => SetValue(FillProperty, value); }

    WriteableBitmap? bitmap;
    int[] pixels = Array.Empty<int>();
    int cells;
    readonly DateTime start = DateTime.UtcNow;
    int frame;

    public TriSpinner()
    {
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
        IsHitTestVisible = false;
        Loaded += (_, _) => Anim.Add(Tick);
        Unloaded += (_, _) => Anim.Remove(Tick);
    }

    void Tick(object? sender, EventArgs e)
    {
        if (frame++ % 2 == 0) InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        if (ActualWidth < 2 || ActualHeight < 2) return;
        var n = Math.Max(8, Cells);
        if (bitmap == null || n != cells)
        {
            cells = n;
            bitmap = new WriteableBitmap(n, n, 96, 96, PixelFormats.Bgra32, null);
            pixels = new int[n * n];
        }
        var t = (DateTime.UtcNow - start).TotalSeconds;
        var grid = TriMark.Frame(n, t);
        var c = Fill;
        for (var i = 0; i < grid.Length; i++)
        {
            var a = grid[i];
            pixels[i] = a == 0 ? 0 : (a << 24) | (c.R << 16) | (c.G << 8) | c.B;
        }
        bitmap.WritePixels(new Int32Rect(0, 0, n, n), pixels, n * 4, 0);
        var side = Math.Min(ActualWidth, ActualHeight);
        dc.DrawImage(bitmap, new Rect((ActualWidth - side) / 2, (ActualHeight - side) / 2, side, side));
    }
}

public static class IconMaker
{
    static readonly int[] Sizes = { 16, 24, 32, 48, 64, 128, 256 };

    public static void Write(string path)
    {
        if (path.EndsWith(".png", StringComparison.OrdinalIgnoreCase))
        {
            File.WriteAllBytes(path, Png(256));
            return;
        }
        var frames = Sizes.Select(Png).ToList();
        using var ms = new MemoryStream();
        using var w = new BinaryWriter(ms);
        w.Write((ushort)0);
        w.Write((ushort)1);
        w.Write((ushort)Sizes.Length);
        var offset = 6 + 16 * Sizes.Length;
        for (var i = 0; i < Sizes.Length; i++)
        {
            var s = Sizes[i];
            w.Write((byte)(s >= 256 ? 0 : s));
            w.Write((byte)(s >= 256 ? 0 : s));
            w.Write((byte)0);
            w.Write((byte)0);
            w.Write((ushort)1);
            w.Write((ushort)32);
            w.Write((uint)frames[i].Length);
            w.Write((uint)offset);
            offset += frames[i].Length;
        }
        foreach (var f in frames) w.Write(f);
        w.Flush();
        File.WriteAllBytes(path, ms.ToArray());
    }

    const double StillAt = 11.0;

    static int CellsFor(int size) => size >= 64 ? 32 : size >= 48 ? 24 : size >= 32 ? 16 : size >= 24 ? 12 : 16;

    public static BitmapSource FrameAt(int size, double seconds)
    {
        var cells = CellsFor(size);
        return Paint(size, cells, TriMark.Frame(cells, seconds));
    }

    static byte[] Png(int size)
    {
        var cells = CellsFor(size);
        var bmp = Paint(size, cells, TriMark.Frame(cells, StillAt));
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(bmp));
        using var ms = new MemoryStream();
        enc.Save(ms);
        return ms.ToArray();
    }

    static BitmapSource Paint(int size, int cells, byte[] grid)
    {
        var px = new int[size * size];
        var notch = Math.Max(1, size / 16);
        var plate = unchecked((int)0xFF0B0B0C);
        for (var y = 0; y < size; y++)
            for (var x = 0; x < size; x++)
            {
                var corner = (x < notch || x >= size - notch) && (y < notch || y >= size - notch);
                if (!corner) px[y * size + x] = plate;
            }

        var inset = 0.0;
        var span = size - 2 * inset;
        for (var cy = 0; cy < cells; cy++)
            for (var cx = 0; cx < cells; cx++)
            {
                var a = grid[cy * cells + cx];
                if (a < 16) continue;
                var x0 = (int)Math.Floor(inset + cx * span / cells);
                var y0 = (int)Math.Floor(inset + cy * span / cells);
                var x1 = Math.Max(x0 + 1, (int)Math.Floor(inset + (cx + 1) * span / cells));
                var y1 = Math.Max(y0 + 1, (int)Math.Floor(inset + (cy + 1) * span / cells));
                var v = (byte)(0x0B + (0xF2 - 0x0B) * a / 255);
                var colour = (0xFF << 24) | (v << 16) | (v << 8) | v;
                for (var y = y0; y < y1 && y < size; y++)
                    for (var x = x0; x < x1 && x < size; x++)
                        if (x >= 0 && y >= 0) px[y * size + x] = colour;
            }

        var bmp = BitmapSource.Create(size, size, 96, 96, PixelFormats.Bgra32, null, px, size * 4);
        bmp.Freeze();
        return bmp;
    }
}
