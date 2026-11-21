using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public static class Heads
{
    static readonly ConcurrentDictionary<string, Task<int[]?>> faces = new(StringComparer.OrdinalIgnoreCase);
    static readonly Regex Valid = new("^[A-Za-z0-9_]{1,16}$");
    static readonly SemaphoreSlim Gate = new(2);

    static async Task<string> Get(string url)
    {
        await Gate.WaitAsync();
        try
        {
            for (var i = 0; ; i++)
            {
                using var r = await Online.Http.GetAsync(url);
                if ((int)r.StatusCode == 429 && i < 4) { await Task.Delay(1500 * (i + 1)); continue; }
                r.EnsureSuccessStatusCode();
                return await r.Content.ReadAsStringAsync();
            }
        }
        finally { Gate.Release(); }
    }
    static string Dir => Directory.CreateDirectory(Path.Combine(Paths.Data, "heads")).FullName;

    public static Task<int[]?> Face(string name) => Valid.IsMatch(name) ? faces.GetOrAdd(name, n => Task.Run(() => Load(n))) : Task.FromResult<int[]?>(null);

    static async Task<int[]?> Load(string name)
    {
        var file = Path.Combine(Dir, name.ToLowerInvariant() + ".png");
        var fresh = File.Exists(file) && DateTime.UtcNow - File.GetLastWriteTimeUtc(file) < TimeSpan.FromDays(7);
        if (!fresh)
        {
            try
            {
                var http = Online.Http;
                var prof = JsonNode.Parse(await Get("https://api.mojang.com/users/profiles/minecraft/" + name));
                var id = prof?["id"]?.ToString();
                if (id == null || !Regex.IsMatch(id, "^[0-9a-fA-F]{32}$")) return Read(file);
                var sess = JsonNode.Parse(await Get("https://sessionserver.mojang.com/session/minecraft/profile/" + id));
                var value = sess?["properties"]?[0]?["value"]?.ToString();
                if (value == null) return Read(file);
                var tex = JsonNode.Parse(Convert.FromBase64String(value));
                var url = tex?["textures"]?["SKIN"]?["url"]?.ToString();
                if (url == null || !Uri.TryCreate(url, UriKind.Absolute, out var u) || !u.Host.Equals("textures.minecraft.net", StringComparison.OrdinalIgnoreCase)) return Read(file);
                var skin = await http.GetByteArrayAsync(new UriBuilder(u) { Scheme = "https", Port = -1 }.Uri);
                var face = Crop(skin);
                if (face != null) { Save(face, file); return face; }
            }
            catch { }
        }
        return Read(file);
    }

    static int[]? Crop(byte[] png)
    {
        try
        {
            var dec = BitmapDecoder.Create(new MemoryStream(png), BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            var src = new FormatConvertedBitmap(dec.Frames[0], PixelFormats.Bgra32, null, 0);
            if (src.PixelWidth < 64 || src.PixelHeight < 16) return null;
            var all = new int[src.PixelWidth * 16];
            src.CopyPixels(new Int32Rect(0, 0, src.PixelWidth, 16), all, src.PixelWidth * 4, 0);
            var face = new int[64];
            for (var y = 0; y < 8; y++)
                for (var x = 0; x < 8; x++)
                {
                    var b = all[(8 + y) * src.PixelWidth + 8 + x] | unchecked((int)0xFF000000);
                    var h = all[(8 + y) * src.PixelWidth + 40 + x];
                    face[y * 8 + x] = ((h >> 24) & 0xFF) > 127 ? h | unchecked((int)0xFF000000) : b;
                }
            return face;
        }
        catch { return null; }
    }

    static void Save(int[] face, string file)
    {
        try
        {
            var bmp = BitmapSource.Create(8, 8, 96, 96, PixelFormats.Bgra32, null, face, 32);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            using var fs = File.Create(file);
            enc.Save(fs);
        }
        catch { }
    }

    static int[]? Read(string file)
    {
        if (!File.Exists(file)) return null;
        try { return Crop8(File.ReadAllBytes(file)); } catch { return null; }
    }

    static int[]? Crop8(byte[] png)
    {
        var dec = BitmapDecoder.Create(new MemoryStream(png), BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        var src = new FormatConvertedBitmap(dec.Frames[0], PixelFormats.Bgra32, null, 0);
        if (src.PixelWidth != 8 || src.PixelHeight != 8) return null;
        var face = new int[64];
        src.CopyPixels(face, 32, 0);
        return face;
    }

    public static BitmapSource Bitmap(int[] face)
    {
        var bmp = BitmapSource.Create(8, 8, 96, 96, PixelFormats.Bgra32, null, face, 32);
        bmp.Freeze();
        return bmp;
    }

    public static FrameworkElement Tile(string name, double size, bool dim = false)
    {
        var img = new Image { Width = size, Height = size, Stretch = Stretch.Fill, Opacity = dim ? 0.45 : 1 };
        RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.NearestNeighbor);
        var box = new Border { Width = size, Height = size, Background = Ui.Br("Panel2"), Child = img, VerticalAlignment = VerticalAlignment.Center };
        _ = Face(name).ContinueWith(t => { if (t.Result is { } f) box.Dispatcher.BeginInvoke(() => img.Source = Bitmap(f)); });
        return box;
    }

    public static FrameworkElement Mosaic(IReadOnlyList<string> names, int across, double size)
    {
        var grid = new System.Windows.Controls.Primitives.UniformGrid { Columns = across, Rows = (names.Count + across - 1) / across, Width = size, Height = size };
        foreach (var n in names)
        {
            var img = new Image { Stretch = Stretch.Fill };
            RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.NearestNeighbor);
            grid.Children.Add(img);
            _ = Face(n).ContinueWith(t => { if (t.Result is { } f) img.Dispatcher.BeginInvoke(() => img.Source = Bitmap(f)); });
        }
        return new Border { Width = size, Height = size, Background = Ui.Br("Panel2"), Child = grid, VerticalAlignment = VerticalAlignment.Center };
    }

    public static async Task<(int[] Gray, int W, int H)> Flag(IReadOnlyList<string> names, int across, int down, int cell = 4)
    {
        var got = await Task.WhenAll(names.Take(across * down).Select(Face));
        var step = 8 / cell;
        int w = across * cell, h = down * cell;
        var gray = new int[w * h];
        for (var i = 0; i < got.Length; i++)
        {
            var f = got[i];
            int hx = i % across * cell, hy = i / across * cell;
            for (var qy = 0; qy < cell; qy++)
                for (var qx = 0; qx < cell; qx++)
                {
                    double sum = 0;
                    if (f != null)
                        for (var y = 0; y < step; y++)
                            for (var x = 0; x < step; x++)
                            {
                                var p = f[(qy * step + y) * 8 + qx * step + x];
                                sum += 0.3 * ((p >> 16) & 0xFF) + 0.59 * ((p >> 8) & 0xFF) + 0.11 * (p & 0xFF);
                            }
                    var v = f == null ? 40 : (int)Math.Clamp(sum / (step * step) * 0.9 + 25, 30, 245);
                    gray[(hy + qy) * w + hx + qx] = v;
                }
        }
        return (gray, w, h);
    }
}

public static class DemoFriend
{
    public const string Id = "dev:fans";
    public const string Name = "All My Fans";
    public static readonly string[] Crowd =
    {
        "UnspeakablePlays", "FuriousDestroyer", "SuperGirlyGamer", "AtlanticCraft", "KrakenKid", "stampylongnose", "iBallistiicSquid", "DanTDM",
        "BajanCanadian", "JeromeASF", "SSundee", "PrestonPlayz", "Grian", "MumboJumbo", "Notch", "jeb_",
    };
    public static string Status => Crowd.Length + " of them, cheering you on";
    public static bool Shown => DevAccess.Unlocked;

    static readonly string[] Cheers =
    {
        "first", "gg", "pentecost on top", "the boat goes hard", "when 1.0", "o7", "w update", "can you add my mod",
        "this loader is insane", "my fps doubled", "who else is here from the release", "lets gooo", "best launcher fr",
    };
    public static readonly List<(bool Mine, string Who, string Text, DateTime Time)> Chat = new()
    {
        (false, "AtlanticCraft", "o7", DateTime.Now.AddMinutes(-42)),
        (false, "KrakenKid", "the boat goes hard", DateTime.Now.AddMinutes(-41)),
        (false, "UnspeakablePlays", "when 1.0", DateTime.Now.AddMinutes(-7)),
    };
    static readonly Random rng = new();
    public static string Cheer() => Cheers[rng.Next(Cheers.Length)];
    public static string Who() => Crowd[rng.Next(Crowd.Length)];
}
