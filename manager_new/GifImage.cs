using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace Pentecost.Manager;

public sealed class GifImage : Image
{
    sealed record Raw(BitmapSource Pixels, int Left, int Top, int Delay, int Disposal);

    const long ByteCap = 25L * 1024 * 1024;
    const long PixelCap = 80L * 1024 * 1024;

    readonly string url;
    readonly bool animate;
    List<Raw>? raws;
    BitmapSource?[] shown = Array.Empty<BitmapSource?>();
    BitmapSource? backdrop;
    int width, height, at;
    bool started;
    DispatcherTimer? timer;

    public GifImage(string address, bool play = true)
    {
        url = address;
        animate = play;
        Stretch = Stretch.Uniform;
        StretchDirection = StretchDirection.DownOnly;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.HighQuality);
        Loaded += (_, _) => Resume();
        Unloaded += (_, _) => Pause();
        IsVisibleChanged += (_, _) => { if (IsVisible) Resume(); else Pause(); };
        _ = Load();
    }

    static readonly string[] Hosts = { "modrinth.com", "github.io", "githubusercontent.com", "github.com", "imgur.com", "discordapp.com", "discordapp.net", "gitlab.io" };

    public static Uri? Allowed(string? address)
    {
        if (!Uri.TryCreate(address, UriKind.Absolute, out var u) || u.Scheme != "https") return null;
        foreach (var h in Hosts) if (u.Host.Equals(h, StringComparison.OrdinalIgnoreCase) || u.Host.EndsWith("." + h, StringComparison.OrdinalIgnoreCase)) return u;
        return null;
    }

    async Task Load()
    {
        try
        {
            if (Allowed(url) is not { } uri) return;
            var bytes = await Modrinth.Client.GetByteArrayAsync(uri);
            if (bytes.Length > ByteCap) return;
            var decoder = new GifBitmapDecoder(new MemoryStream(bytes), BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            if (decoder.Frames.Count == 0) return;
            var list = new List<Raw>();
            foreach (var f in decoder.Frames)
            {
                var delay = Meta<ushort>(f, "/grctlext/Delay", 10);
                list.Add(new Raw(f, Meta<ushort>(f, "/imgdesc/Left", 0), Meta<ushort>(f, "/imgdesc/Top", 0), delay <= 1 ? 100 : delay * 10, Meta<byte>(f, "/grctlext/Disposal", 0)));
            }
            var first = decoder.Frames[0];
            width = Meta<ushort>(decoder.Metadata, "/logscrdesc/Width", 0);
            height = Meta<ushort>(decoder.Metadata, "/logscrdesc/Height", 0);
            if (width == 0) width = list.Max(r => r.Left + r.Pixels.PixelWidth);
            if (height == 0) height = list.Max(r => r.Top + r.Pixels.PixelHeight);
            if (width == 0 || height == 0) width = first.PixelWidth;
            if (height == 0) height = first.PixelHeight;
            raws = list;
            shown = new BitmapSource?[list.Count];
            Source = Frame(0);
            if (animate && list.Count > 1 && Settings.Motion && (long)width * height <= 1_200_000 && (long)width * height * 4 * list.Count <= PixelCap) { started = true; Resume(); }
        }
        catch (Exception ex) { AppLog.Write("gif: " + ex.Message); }
    }

    static T Meta<T>(object? source, string query, T fallback)
    {
        try
        {
            var m = source is BitmapFrame f ? f.Metadata as BitmapMetadata : source as BitmapMetadata;
            if (m != null && m.ContainsQuery(query) && m.GetQuery(query) is T value) return value;
        }
        catch { }
        return fallback;
    }

    BitmapSource Frame(int i)
    {
        if (shown[i] is { } done) return done;
        var f = raws![i];
        var full = new Rect(0, 0, width, height);
        var part = new Rect(f.Left, f.Top, f.Pixels.PixelWidth, f.Pixels.PixelHeight);
        var dv = new DrawingVisual();
        using (var dc = dv.RenderOpen())
        {
            if (backdrop != null) dc.DrawImage(backdrop, full);
            dc.DrawImage(f.Pixels, part);
        }
        var rt = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        rt.Render(dv);
        rt.Freeze();
        shown[i] = rt;
        if (f.Disposal == 2)
        {
            var cut = new DrawingVisual();
            using (var dc = cut.RenderOpen())
            {
                dc.PushClip(new CombinedGeometry(GeometryCombineMode.Exclude, new RectangleGeometry(full), new RectangleGeometry(part)));
                dc.DrawImage(rt, full);
                dc.Pop();
            }
            var next = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
            next.Render(cut);
            next.Freeze();
            backdrop = next;
        }
        else if (f.Disposal != 3) backdrop = rt;
        return rt;
    }

    void Resume()
    {
        if (!started || raws == null || !IsVisible || !IsLoaded) return;
        timer ??= new DispatcherTimer(DispatcherPriority.Background);
        timer.Tick -= Step;
        timer.Tick += Step;
        timer.Interval = TimeSpan.FromMilliseconds(raws[at].Delay);
        timer.Start();
    }

    void Pause() => timer?.Stop();

    void Step(object? s, EventArgs e)
    {
        if (raws == null) return;
        var next = (at + 1) % raws.Count;
        if (shown[next] == null && next != FirstMissing()) { Pause(); started = false; return; }
        at = next;
        Source = Frame(at);
        timer!.Interval = TimeSpan.FromMilliseconds(raws[at].Delay);
    }

    int FirstMissing()
    {
        for (var i = 0; i < shown.Length; i++) if (shown[i] == null) return i;
        return -1;
    }
}
