using System.Globalization;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class Plate : PixelArt
{
    static readonly Dictionary<string, (byte[] Px, int W, int H)> Cache = new();
    static readonly Dictionary<string, Task<string[]>> Shots = new();
    string? url;
    readonly int seed;
    string label;
    readonly string kind;
    public event Action<int>? Counted;
    public int? Count { get; private set; }
    byte[]? px;
    int pw, ph;
    double readyAt = -1;
    double[] lum = Array.Empty<double>();
    int lumCols, lumRows;
    bool lumImage;
    Brush back = Brushes.Black;

    bool looking;

    public Plate(string projectId, string[] gallery, string kind) : base(2)
    {
        this.kind = kind;
        foreach (var ch in projectId) seed = seed * 31 + ch;
        seed = Math.Abs(seed % 100000);
        label = "looking";
        Loaded += (_, _) =>
        {
            if (TryFindResource("Panel2") is Brush b) back = b;
            readyAt = looking || (url != null && px == null) ? -1 : Now;
        };
        if (gallery.Length > 0) Use(gallery);
        else _ = Look(projectId);
    }

    void Use(string[] shots)
    {
        Count = shots.Length;
        Counted?.Invoke(shots.Length);
        if (shots.Length == 0) { label = "no pictures"; readyAt = Now; return; }
        label = $"plate 1 of {shots.Length}";
        url = shots[0];
        Fetch();
    }

    async Task Look(string id)
    {
        looking = true;
        Task<string[]> job;
        lock (Shots)
            if (!Shots.TryGetValue(id, out job!))
                Shots[id] = job = Task.Run(async () =>
                {
                    try { return (await Modrinth.Project(id, CancellationToken.None)).Gallery; }
                    catch { lock (Shots) Shots.Remove(id); return Array.Empty<string>(); }
                });
        var shots = await job;
        looking = false;
        Use(shots);
        InvalidateVisual();
    }

    void Fetch()
    {
        lock (Cache)
            if (Cache.TryGetValue(url!, out var hit)) { (px, pw, ph) = hit; return; }
        var bi = Ui.Picture(url, 440);
        if (bi == null) return;
        if (bi.IsDownloading) bi.DownloadCompleted += (_, _) => Grab(bi);
        else Grab(bi);
    }

    void Grab(BitmapSource bi)
    {
        try
        {
            var g = new FormatConvertedBitmap(bi, PixelFormats.Gray8, null, 0);
            var data = new byte[g.PixelWidth * g.PixelHeight];
            g.CopyPixels(data, g.PixelWidth, 0);
            px = data; pw = g.PixelWidth; ph = g.PixelHeight;
            lock (Cache) Cache[url!] = (data, pw, ph);
            lumCols = 0;
            readyAt = Now;
            InvalidateVisual();
        }
        catch { }
    }

    void Build()
    {
        lumCols = cols; lumRows = rows; lumImage = px != null;
        lum = new double[cols * rows];
        if (px != null)
        {
            var scale = Math.Max((double)cols / pw, (double)rows / ph);
            var ox = (pw * scale - cols) / 2;
            var oy = (ph * scale - rows) / 2;
            for (var y = 0; y < rows; y++)
                for (var x = 0; x < cols; x++)
                {
                    var sx = Math.Clamp((int)((x + ox) / scale), 0, pw - 1);
                    var sy = Math.Clamp((int)((y + oy) / scale), 0, ph - 1);
                    var v = px[sy * pw + sx] / 255.0;
                    lum[y * cols + x] = Math.Pow(v, 1.25) * 0.82;
                }
            return;
        }
        var cx = cols * (0.62 + (seed % 23) / 100.0);
        var cy = rows * (0.38 + (seed % 7) / 40.0);
        var r = rows * (0.28 + (seed % 11) / 60.0);
        var tilt = ((seed % 5) - 2) * 0.08;
        for (var y = 0; y < rows; y++)
            for (var x = 0; x < cols; x++)
            {
                var v = 0.0;
                if (Frac(x + seed, y) > 0.993) v = 0.5 + Frac(y, x) * 0.5;
                var dx = (x - cx) / r;
                var dy = (y - cy) / r;
                var d = dx * dx + dy * dy;
                if (d < 1)
                {
                    var z = Math.Sqrt(1 - d);
                    var light = Math.Max(0, -dx * 0.6 - dy * 0.35 + z * 0.75);
                    var band = 0.82 + 0.18 * Math.Sin((dy + dx * tilt) * 11 + seed);
                    v = 0.06 + 0.8 * light * band;
                }
                var ring = Math.Abs(dy * 3.2 - dx * 0.9 + tilt);
                if (ring < 0.12 && d > 1 && d < 4.2) v = Math.Max(v, 0.35 * (1 - ring / 0.12));
                var hill = rows * 0.8 + Math.Sin(x * 0.045 + seed) * rows * 0.06 + Math.Sin(x * 0.13 + seed * 0.3) * rows * 0.03;
                if (y > hill) v = 0.12 + ((x + y) % 7 == 0 ? 0.1 : 0);
                lum[y * cols + x] = v;
            }
    }

    protected override void Paint(double t, double dt)
    {
        if (lumCols != cols || lumRows != rows || lumImage != (px != null)) Build();
        var since = readyAt < 0 ? -1 : Settings.Motion ? t - readyAt : 99;
        if (since < 0)
        {
            var dots = (int)(t * 3) % 4;
            for (var i = 0; i < 3; i++) Put(cols / 2 - 4 + i * 4, rows / 2, W(i < dots ? 200 : 60));
            return;
        }
        var reveal = Math.Clamp(since / 0.85, 0, 1);
        var scan = (int)(reveal * rows);
        var fadeAt = rows * 0.5;
        for (var y = 0; y < rows && y <= scan; y++)
        {
            var fade = y < fadeAt ? 1 : Math.Max(0, 1 - (y - fadeAt) / (rows - fadeAt));
            for (var x = 0; x < cols; x++)
            {
                var v = lum[y * cols + x] * fade * 3;
                var lvl = (int)v;
                if ((v - lvl) * 16 > Bayer[y & 3, x & 3]) lvl++;
                if (lvl > 0) Put(x, y, W(Math.Min(3, lvl) * 70));
            }
        }
        if (reveal < 1)
        {
            for (var x = 0; x < cols; x++) { Max(x, scan, 235); Max(x, scan - 1, 110); }
            return;
        }
        var sweep = (int)(((since - 0.85) % 6.5) / 6.5 * rows * 1.8);
        if (sweep < rows) for (var x = 0; x < cols; x += 2) Max(x, sweep, 70);
        for (var i = 0; i < 5; i++)
        {
            var sx = (int)(Frac(i, seed) * cols);
            var sy = (int)(Frac(seed, i) * rows * 0.4);
            var tw = Math.Sin(t * (1.3 + i * 0.4) + i * 2);
            if (tw > 0.6) { Max(sx, sy, 255); if (tw > 0.9) { Max(sx - 1, sy, 120); Max(sx + 1, sy, 120); Max(sx, sy - 1, 120); Max(sx, sy + 1, 120); } }
        }
    }

    protected override void Overlay(DrawingContext dc, double t)
    {
        var w = ActualWidth;
        var h = ActualHeight;
        const double inset = 10, len = 12;
        foreach (var (x, y, sx, sy) in new[] { (inset, inset, 1, 1), (w - inset, inset, -1, 1), (inset, h - inset - 26, 1, -1), (w - inset, h - inset - 26, -1, -1) })
        {
            dc.DrawRectangle(text, null, new Rect(sx > 0 ? x : x - len, y, len, 1));
            dc.DrawRectangle(text, null, new Rect(sx > 0 ? x : x - 1, sy > 0 ? y : y - len, 1, len));
        }
        var dip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        Badge(dc, label, inset + 6, inset + 6, false, dip);
        Badge(dc, kind, w - inset - 6, inset + 6, true, dip);
    }

    void Badge(DrawingContext dc, string s, double x, double y, bool right, double dip)
    {
        var ft = new FormattedText(s.ToUpperInvariant(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 9.5, text, dip);
        var left = right ? x - ft.Width : x;
        dc.DrawRectangle(back, null, new Rect(left - 4, y - 2, ft.Width + 8, ft.Height + 4));
        dc.DrawText(ft, new Point(left, y));
    }
}

public static class Peek
{
    public static FrameworkElement Card(Hit h, bool installed, IList<FrameworkElement> pills, string? warn)
    {
        const double width = 440;
        var kind = h.Type switch { "modpack" => "modpack", "shader" => "shader", "resourcepack" => "resource pack", _ => "mod" };
        var plate = new Plate(h.ProjectId, h.Gallery, kind) { Height = 170 };

        var tile = Ui.Tile(h.IconUrl, h.Title, 68);
        var frame = new PixelBox { Background = Ui.Br("Panel"), BorderBrush = Ui.Br("Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(4), Notch = 2, Child = tile, VerticalAlignment = VerticalAlignment.Bottom };
        var who = new StackPanel { Margin = new Thickness(14, 0, 0, 2), VerticalAlignment = VerticalAlignment.Bottom };
        who.Children.Add(Ui.T(h.Title, 18, "Text", bold: true, wrap: true));
        who.Children.Add(Ui.T("by " + h.Author, 11.5, "Faint", trim: true).With(new Thickness(0, 2, 0, 0)));
        var idRow = new Grid { Margin = new Thickness(18, -52, 18, 0) };
        idRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        idRow.ColumnDefinitions.Add(new ColumnDefinition());
        idRow.Children.Add(frame);
        Grid.SetColumn(who, 1);
        idRow.Children.Add(who);

        var body = new StackPanel { Margin = new Thickness(18, 16, 18, 18) };

        if (!string.IsNullOrWhiteSpace(h.Desc))
        {
            var d = Ui.T(h.Desc, 13, "Soft", wrap: true);
            d.MaxHeight = 74;
            d.TextTrimming = TextTrimming.WordEllipsis;
            d.LineHeight = 18.5;
            body.Children.Add(d);
        }
        if (!string.IsNullOrWhiteSpace(warn))
            body.Children.Add(Ui.T(warn!, 12.5, "Warn", wrap: true).With(new Thickness(0, 10, 0, 0)));

        var runs = h.Loaders.Length == 0 ? "any loader" : string.Join(" ", h.Loaders);
        var facts = new List<string> { Ui.Num(h.Downloads) + " downloads", Ui.Num(h.Follows) + " follows" };
        if (h.Updated is { } u) facts.Add("updated " + Since(u));
        var line1 = Ui.Cap(string.Join("   ", facts));
        line1.TextTrimming = TextTrimming.CharacterEllipsis;
        body.Children.Add(line1.With(new Thickness(0, 14, 0, 0)));
        var tags = h.Categories.Where(c => c.Length > 0).Take(4).Select(c => c.Replace('-', ' '));
        var line2 = Ui.Cap(string.Join("   ", new[] { runs }.Concat(tags)));
        line2.TextTrimming = TextTrimming.CharacterEllipsis;
        body.Children.Add(line2.With(new Thickness(0, 5, 0, 0)));

        string here;
        try { here = ProfileStore.Active.Name; } catch { here = "this profile"; }
        var where = Ui.T(installed ? "In " + here : "Not in " + here, 12, installed ? "Text" : "Faint");
        var pillRow = Ui.H();
        foreach (var p in pills.Take(3)) pillRow.Children.Add(p);
        var foot = Ui.Row(Ui.H(where, pillRow.With(new Thickness(10, 0, 0, 0))), Ui.T("Click to open", 12, "Faint"));
        foot.Margin = new Thickness(0, 14, 0, 0);
        var rule = new Border { Height = 1, Background = Ui.Br("Line"), Margin = new Thickness(0, 14, 0, 0) };
        body.Children.Add(rule);
        body.Children.Add(foot);
        foot.Margin = new Thickness(0, 12, 0, 0);

        var stack = new StackPanel();
        stack.Children.Add(plate);
        stack.Children.Add(idRow);
        stack.Children.Add(body);
        var card = new PixelBox
        {
            Width = width, Background = Ui.Br("Panel2"), BorderBrush = Ui.Br("Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(0), Notch = 4,
            Child = stack, ClipToBounds = true,
        };
        Ui.FadeIn(card, 140);
        return card;
    }

    static string Since(DateTime t)
    {
        var days = (DateTime.Now - t).TotalDays;
        if (days < 30) return Ui.Ago(t);
        if (days < 365) return (int)(days / 30) + " mo ago";
        return (int)(days / 365) + " yr ago";
    }

    static FrameworkElement Meter(long downloads)
    {
        var filled = (int)Math.Round(Math.Clamp(Math.Log10(Math.Max(1, downloads)) / 9.0, 0, 1) * 12);
        var row = Ui.H();
        for (var i = 0; i < 12; i++)
            row.Children.Add(new Border { Width = 4, Height = 4, Margin = new Thickness(0, 0, 2, 0), Background = Ui.Br(i < filled ? "Text" : "Line") });
        return row;
    }
}
