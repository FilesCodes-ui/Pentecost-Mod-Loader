using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public sealed class ModBanner : FrameworkElement
{
    const int P = 3;
    static readonly int[,] Bayer = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };

    byte[]? lum;
    byte[]? edges;
    int edgeCut = 80;
    int iw, ih;
    double lo = 0, hi = 255;
    readonly string seedName;
    string? loading;
    readonly DateTime start = DateTime.UtcNow;
    double shownAt = -1;
    bool running;
    WriteableBitmap? bitmap;
    int cols, rows;
    int[] buf = Array.Empty<int>();

    public ModBanner(string name)
    {
        seedName = name;
        ClipToBounds = true;
        SnapsToDevicePixels = true;
        IsHitTestVisible = false;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        Loaded += (_, _) => Start();
        Unloaded += (_, _) => Stop();
        IsVisibleChanged += (_, _) => { if (IsVisible) Start(); else Stop(); };
    }

    void Start() { if (running || !IsVisible) return; if (!Settings.Motion) { InvalidateVisual(); return; } running = true; Anim.Add(Tick); }
    void Stop() { if (!running) return; running = false; Anim.Remove(Tick); }
    void Tick(object? s, EventArgs e) => InvalidateVisual();

    public void SetImage(string? source)
    {
        if (string.IsNullOrEmpty(source) || source == loading) return;
        loading = source;
        _ = Load(source);
    }

    async Task Load(string source)
    {
        try
        {
            byte[] bytes;
            if (File.Exists(source)) bytes = await File.ReadAllBytesAsync(source);
            else if (CurseForge.SafeImage(source) is { } uri) bytes = await Modrinth.Client.GetByteArrayAsync(uri);
            else return;
            if (bytes.Length > 25 * 1024 * 1024) return;
            var (px, w, h, l, hgh) = await Task.Run(() =>
            {
                var bi = new BitmapImage();
                bi.BeginInit();
                bi.CacheOption = BitmapCacheOption.OnLoad;
                bi.StreamSource = new MemoryStream(bytes);
                bi.DecodePixelWidth = 560;
                bi.EndInit();
                bi.Freeze();
                var g = new FormatConvertedBitmap(bi, PixelFormats.Gray8, null, 0);
                var data = new byte[g.PixelWidth * g.PixelHeight];
                g.CopyPixels(data, g.PixelWidth, 0);
                var hist = new int[256];
                foreach (var b in data) hist[b]++;
                int Pct(double p) { var want = data.Length * p; var sum = 0; for (var i = 0; i < 256; i++) { sum += hist[i]; if (sum >= want) return i; } return 255; }
                return (data, g.PixelWidth, g.PixelHeight, Pct(0.04), Pct(0.97));
            });
            var (ed, cut) = await Task.Run(() => Trace(px, w, h));
            if (source != loading) return;
            lum = px; iw = w; ih = h; lo = l; hi = Math.Max(l + 20, hgh);
            edges = ed; edgeCut = cut;
            shownAt = (DateTime.UtcNow - start).TotalSeconds;
            InvalidateVisual();
        }
        catch (Exception ex) { AppLog.Write("banner: " + ex.Message); }
    }

    static (byte[] Edges, int Cut) Trace(byte[] src, int w, int h)
    {
        var soft = new float[w * h];
        for (var y = 1; y < h - 1; y++)
            for (var x = 1; x < w - 1; x++)
            {
                var s = 0f;
                for (var dy = -1; dy <= 1; dy++) for (var dx = -1; dx <= 1; dx++) s += src[(y + dy) * w + x + dx];
                soft[y * w + x] = s / 9f;
            }
        var mag = new byte[w * h];
        for (var y = 2; y < h - 2; y++)
            for (var x = 2; x < w - 2; x++)
            {
                float At(int xx, int yy) => soft[yy * w + xx];
                var gx = -At(x - 1, y - 1) - 2 * At(x - 1, y) - At(x - 1, y + 1) + At(x + 1, y - 1) + 2 * At(x + 1, y) + At(x + 1, y + 1);
                var gy = -At(x - 1, y - 1) - 2 * At(x, y - 1) - At(x + 1, y - 1) + At(x - 1, y + 1) + 2 * At(x, y + 1) + At(x + 1, y + 1);
                mag[y * w + x] = (byte)Math.Min(255, Math.Sqrt(gx * gx + gy * gy) / 3);
            }
        var hist = new int[256];
        foreach (var m in mag) hist[m]++;
        var want = mag.Length * 0.935;
        var sum = 0;
        var cut = 255;
        for (var i = 0; i < 256; i++) { sum += hist[i]; if (sum >= want) { cut = Math.Max(18, i); break; } }
        return (mag, cut);
    }

    static double Frac(string s) { uint h = 2166136261; foreach (var c in s) { h ^= c; h *= 16777619; } return (h & 0xFFFF) / 65536.0; }

    protected override void OnRender(DrawingContext dc)
    {
        if (Anim.Sizing && bitmap != null) { dc.DrawImage(bitmap, new Rect(0, 0, ActualWidth, ActualHeight)); return; }
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 40 || h < 40) return;
        var t = (DateTime.UtcNow - start).TotalSeconds;
        var c = (int)Math.Ceiling(w / P);
        var r = (int)Math.Ceiling(h / P);
        if (bitmap == null || c != cols || r != rows)
        {
            cols = c; rows = r;
            bitmap = new WriteableBitmap(cols, rows, 96, 96, PixelFormats.Pbgra32, null);
            buf = new int[cols * rows];
        }
        Array.Clear(buf);

        var appear = lum == null ? 1 : Math.Clamp((t - shownAt) / 0.9, 0, 1);
        var band = ((t * 0.09) % 1.6 - 0.3) * cols;
        var scale = lum == null ? 1 : Math.Max(cols * 1.06 / iw, rows * 1.06 / ih);
        var spareX = lum == null ? 0 : iw * scale - cols;
        var spareY = lum == null ? 0 : ih * scale - rows;
        var ox = spareX * (0.5 + 0.5 * Math.Sin(t * 0.035));
        var oy = spareY * 0.35;
        var s1 = Frac(seedName) * 100;
        var s2 = Frac(seedName + "~") * 100;

        for (var y = 0; y < rows; y++)
        {
            var fy = y / (double)rows;
            var mask = (fy < 0.30 ? 1 : Math.Max(0.10, 1 - (fy - 0.30) / 0.70 * 0.90)) * (fy < 0.22 ? 0.55 + 0.45 * fy / 0.22 : 1) * 0.85;
            for (var x = 0; x < cols; x++)
            {
                double v;
                if (lum != null && edges != null)
                {
                    var sx0 = (int)((x + ox) / scale);
                    var sy0 = (int)((y + oy) / scale);
                    var sx1 = (int)((x + 1 + ox) / scale);
                    var sy1 = (int)((y + 1 + oy) / scale);
                    var e = 0;
                    for (var sy = Math.Max(0, sy0); sy <= Math.Min(ih - 1, Math.Max(sy0, sy1 - 1)); sy++)
                        for (var sx = Math.Max(0, sx0); sx <= Math.Min(iw - 1, Math.Max(sx0, sx1 - 1)); sx++)
                            e = Math.Max(e, edges[sy * iw + sx]);
                    var cx = Math.Clamp(sx0, 0, iw - 1);
                    var cy = Math.Clamp(sy0, 0, ih - 1);
                    var tone = Math.Clamp((lum[cy * iw + cx] - lo) / (hi - lo), 0, 1);
                    var sweep = 0.10 * Math.Exp(-Math.Pow((x - band) / 22.0, 2));
                    if (e >= edgeCut)
                    {
                        var strength = Math.Min(1, (e - edgeCut) / (double)Math.Max(1, 255 - edgeCut) * 3 + 0.35);
                        var la = (int)((90 + 150 * strength * tone + 40 * strength) * mask * appear + sweep * 200);
                        la = Math.Clamp(la, 0, 255);
                        if (la > 12) { buf[y * cols + x] = (la << 24) | (la << 16) | (la << 8) | la; continue; }
                    }
                    var fill = (tone * tone * 0.42 + sweep) * mask * appear;
                    if (fill * 16 > Bayer[y & 3, x & 3])
                    {
                        var fa = 26 + (int)(tone * 34);
                        buf[y * cols + x] = (fa << 24) | (fa << 16) | (fa << 8) | fa;
                    }
                    continue;
                }
                else
                {
                    var n = Math.Sin(x * 0.045 + s1 + t * 0.03) * Math.Sin(y * 0.09 + s2) + Math.Sin(x * 0.11 - y * 0.07 + s2 * 0.7) * 0.5;
                    v = Math.Clamp(0.30 + n * 0.28, 0, 1) * 0.7;
                }
                v += 0.10 * Math.Exp(-Math.Pow((x - band) / 22.0, 2)) * (lum != null ? 1 : 0.5);
                v *= mask;
                if (v * 16 <= Bayer[y & 3, x & 3]) continue;
                var a = v > 0.72 ? 190 : v > 0.42 ? 115 : 55;
                buf[y * cols + x] = (a << 24) | (a << 16) | (a << 8) | a;
            }
        }

        bitmap.WritePixels(new Int32Rect(0, 0, cols, rows), buf, cols * 4, 0);
        dc.DrawImage(bitmap, new Rect(0, 0, cols * P, rows * P));
    }
}
