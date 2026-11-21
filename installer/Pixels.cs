using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Pentecost.Installer;

static class Pixels
{
    public const int LogoW = 16;
    public const int LogoH = 13;

    static readonly Dictionary<char, string[]> Glyphs = new()
    {
        ['A'] = new[] { "01110", "10001", "10001", "11111", "10001", "10001", "10001" },
        ['B'] = new[] { "11110", "10001", "10001", "11110", "10001", "10001", "11110" },
        ['C'] = new[] { "01111", "10000", "10000", "10000", "10000", "10000", "01111" },
        ['D'] = new[] { "11110", "10001", "10001", "10001", "10001", "10001", "11110" },
        ['E'] = new[] { "11111", "10000", "10000", "11110", "10000", "10000", "11111" },
        ['F'] = new[] { "11111", "10000", "10000", "11110", "10000", "10000", "10000" },
        ['G'] = new[] { "01111", "10000", "10000", "10011", "10001", "10001", "01111" },
        ['H'] = new[] { "10001", "10001", "10001", "11111", "10001", "10001", "10001" },
        ['I'] = new[] { "11111", "00100", "00100", "00100", "00100", "00100", "11111" },
        ['J'] = new[] { "00111", "00010", "00010", "00010", "00010", "10010", "01100" },
        ['K'] = new[] { "10001", "10010", "10100", "11000", "10100", "10010", "10001" },
        ['L'] = new[] { "10000", "10000", "10000", "10000", "10000", "10000", "11111" },
        ['M'] = new[] { "10001", "11011", "10101", "10101", "10001", "10001", "10001" },
        ['N'] = new[] { "10001", "11001", "10101", "10101", "10011", "10001", "10001" },
        ['O'] = new[] { "01110", "10001", "10001", "10001", "10001", "10001", "01110" },
        ['P'] = new[] { "11110", "10001", "10001", "11110", "10000", "10000", "10000" },
        ['Q'] = new[] { "01110", "10001", "10001", "10001", "10101", "10010", "01101" },
        ['R'] = new[] { "11110", "10001", "10001", "11110", "10100", "10010", "10001" },
        ['S'] = new[] { "01111", "10000", "10000", "01110", "00001", "00001", "11110" },
        ['T'] = new[] { "11111", "00100", "00100", "00100", "00100", "00100", "00100" },
        ['U'] = new[] { "10001", "10001", "10001", "10001", "10001", "10001", "01110" },
        ['V'] = new[] { "10001", "10001", "10001", "10001", "10001", "01010", "00100" },
        ['W'] = new[] { "10001", "10001", "10001", "10101", "10101", "10101", "01010" },
        ['X'] = new[] { "10001", "10001", "01010", "00100", "01010", "10001", "10001" },
        ['Y'] = new[] { "10001", "10001", "01010", "00100", "00100", "00100", "00100" },
        ['Z'] = new[] { "11111", "00001", "00010", "00100", "01000", "10000", "11111" },
        ['0'] = new[] { "01110", "10001", "10011", "10101", "11001", "10001", "01110" },
        ['1'] = new[] { "00100", "01100", "00100", "00100", "00100", "00100", "01110" },
        ['2'] = new[] { "01110", "10001", "00001", "00010", "00100", "01000", "11111" },
        ['3'] = new[] { "11110", "00001", "00001", "01110", "00001", "00001", "11110" },
        ['4'] = new[] { "00010", "00110", "01010", "10010", "11111", "00010", "00010" },
        ['5'] = new[] { "11111", "10000", "11110", "00001", "00001", "10001", "01110" },
        ['6'] = new[] { "00110", "01000", "10000", "11110", "10001", "10001", "01110" },
        ['7'] = new[] { "11111", "00001", "00010", "00100", "01000", "01000", "01000" },
        ['8'] = new[] { "01110", "10001", "10001", "01110", "10001", "10001", "01110" },
        ['9'] = new[] { "01110", "10001", "10001", "01111", "00001", "00010", "01100" },
    };

    static readonly string[] Boat =
    {
        ".....#......",
        ".....##.....",
        ".....#.#....",
        ".....#..#...",
        "..#..#...#..",
        ".##..#....#.",
        "#.#..#######",
        "###..#......",
        ".....#......",
        "############",
        ".##########.",
    };

    static readonly Dictionary<char, bool[,]> Fine = new();

    static bool On(string[] g, int r, int c) => r >= 0 && r < 7 && c >= 0 && c < 5 && g[r][c] == '1';

    public static bool[,]? FineGlyph(char ch)
    {
        ch = char.ToUpperInvariant(ch);
        if (Fine.TryGetValue(ch, out var cached)) return cached;
        if (!Glyphs.TryGetValue(ch, out var g)) return null;
        var f = new bool[14, 10];
        for (var r = 0; r < 7; r++)
            for (var c = 0; c < 5; c++)
                if (g[r][c] == '1')
                    f[r * 2, c * 2] = f[r * 2, c * 2 + 1] = f[r * 2 + 1, c * 2] = f[r * 2 + 1, c * 2 + 1] = true;
        for (var r = 0; r < 7; r++)
            for (var c = 0; c < 5; c++)
            {
                if (On(g, r, c)) continue;
                for (var dr = -1; dr <= 1; dr += 2)
                    for (var dc = -1; dc <= 1; dc += 2)
                        if (On(g, r + dr, c) && On(g, r, c + dc))
                            f[r * 2 + (dr > 0 ? 1 : 0), c * 2 + (dc > 0 ? 1 : 0)] = true;
            }
        Fine[ch] = f;
        return f;
    }

    public static IEnumerable<(int Row, int Col)> BoatCells()
    {
        for (var r = 0; r < Boat.Length; r++)
            for (var c = 0; c < Boat[r].Length; c++)
                if (Boat[r][c] == '#') yield return (r, c);
    }
}

public class Wordmark : FrameworkElement
{
    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(nameof(Text), typeof(string), typeof(Wordmark),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly DependencyProperty PixelProperty = DependencyProperty.Register(nameof(Pixel), typeof(double), typeof(Wordmark),
        new FrameworkPropertyMetadata(3.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly DependencyProperty FillProperty = DependencyProperty.Register(nameof(Fill), typeof(Brush), typeof(Wordmark),
        new FrameworkPropertyMetadata(new SolidColorBrush(Color.FromRgb(0xF2, 0xF2, 0xF2)), FrameworkPropertyMetadataOptions.AffectsRender));

    public string Text { get => (string)GetValue(TextProperty); set => SetValue(TextProperty, value); }
    public double Pixel { get => (double)GetValue(PixelProperty); set => SetValue(PixelProperty, value); }
    public Brush Fill { get => (Brush)GetValue(FillProperty); set => SetValue(FillProperty, value); }

    public Wordmark()
    {
        SnapsToDevicePixels = true;
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
    }

    protected override Size MeasureOverride(Size availableSize) =>
        new(Math.Max(0, Text.Length * 12 - 2 + 4) * Pixel, 14 * Pixel);

    protected override void OnRender(DrawingContext dc)
    {
        var px = Pixel;
        var x = 0.0;
        foreach (var ch in Text)
        {
            var f = Pixels.FineGlyph(ch);
            if (f != null)
            {
                for (var r = 0; r < 14; r++)
                {
                    var shift = (13 - r) / 3;
                    var run = -1;
                    for (var c = 0; c <= 10; c++)
                    {
                        var lit = c < 10 && f[r, c];
                        if (lit && run < 0) run = c;
                        else if (!lit && run >= 0)
                        {
                            dc.DrawRectangle(Fill, null, new Rect(x + (run + shift) * px, r * px, (c - run) * px, px));
                            run = -1;
                        }
                    }
                }
            }
            x += 12 * px;
        }
    }
}

public class PixelLogo : FrameworkElement
{
    public static readonly DependencyProperty PixelProperty = DependencyProperty.Register(nameof(Pixel), typeof(double), typeof(PixelLogo),
        new FrameworkPropertyMetadata(3.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly DependencyProperty FillProperty = DependencyProperty.Register(nameof(Fill), typeof(Brush), typeof(PixelLogo),
        new FrameworkPropertyMetadata(new SolidColorBrush(Color.FromRgb(0xF2, 0xF2, 0xF2)), FrameworkPropertyMetadataOptions.AffectsRender));

    public double Pixel { get => (double)GetValue(PixelProperty); set => SetValue(PixelProperty, value); }
    public Brush Fill { get => (Brush)GetValue(FillProperty); set => SetValue(FillProperty, value); }

    readonly DateTime start = DateTime.UtcNow;

    public PixelLogo()
    {
        SnapsToDevicePixels = true;
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
        Loaded += (_, _) => CompositionTarget.Rendering += Tick;
        Unloaded += (_, _) => CompositionTarget.Rendering -= Tick;
    }

    void Tick(object? sender, EventArgs e) => InvalidateVisual();

    protected override Size MeasureOverride(Size availableSize) => new(Pixels.LogoW * Pixel, Pixels.LogoH * Pixel);

    protected override void OnRender(DrawingContext dc)
    {
        var px = Pixel;
        var t = (DateTime.UtcNow - start).TotalSeconds;
        foreach (var (r, c) in Pixels.BoatCells())
            dc.DrawRectangle(Fill, null, new Rect((c + 2) * px, r * px, px, px));
        var soft = Fill.Clone();
        soft.Opacity = 0.4;
        for (var line = 0; line < 2; line++)
        {
            var baseRow = 10 + line * 2;
            var brush = line == 0 ? Fill : soft;
            for (var c = 0; c < Pixels.LogoW; c++)
            {
                var wave = Math.Sin(c * 0.9 - t * 0.9 + line * 1.7);
                var dy = wave > 0.55 ? -1 : 0;
                dc.DrawRectangle(brush, null, new Rect(c * px, (baseRow + dy) * px, px, px));
            }
        }
    }
}

public class DitherBackdrop : FrameworkElement
{
    const int Cell = 3;
    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    WriteableBitmap? bitmap;
    int cols, rows, frame;
    int[] buffer = Array.Empty<int>();
    readonly DateTime start = DateTime.UtcNow;

    public DitherBackdrop()
    {
        IsHitTestVisible = false;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        Loaded += (_, _) => CompositionTarget.Rendering += Tick;
        Unloaded += (_, _) => CompositionTarget.Rendering -= Tick;
    }

    void Tick(object? sender, EventArgs e)
    {
        if (ActualWidth < 1 || ActualHeight < 1 || frame++ % 2 != 0) return;
        var c = Math.Max(1, (int)Math.Ceiling(ActualWidth / Cell));
        var r = Math.Max(1, (int)Math.Ceiling(ActualHeight / Cell));
        if (bitmap == null || c != cols || r != rows)
        {
            cols = c;
            rows = r;
            bitmap = new WriteableBitmap(cols, rows, 96, 96, PixelFormats.Pbgra32, null);
            buffer = new int[cols * rows];
        }
        var t = (DateTime.UtcNow - start).TotalSeconds * 1.2;
        for (var y = 0; y < rows; y++)
            for (var x = 0; x < cols; x++)
            {
                var v = Math.Pow((double)x / cols, 2.2) * 0.40 + 0.05 * Math.Sin(x * 0.5 + y * 0.3 - t);
                buffer[y * cols + x] = v * 16 > Bayer[y & 3, x & 3] ? 0x10101010 : 0;
            }
        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buffer, cols * 4, 0);
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (bitmap != null) dc.DrawImage(bitmap, new Rect(0, 0, cols * Cell, rows * Cell));
    }
}
