using System.Net.Http;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Media.Imaging;

#pragma warning disable CS0649

namespace Pentecost.Setup;

public sealed class StarterMod
{
    public string Slug = "", Name = "", Blurb = "";
    public bool Included;
    public bool Picked;
    public string? IconUrl;
}

public static class StarterMods
{
    public static readonly StarterMod[] All =
    {
        new() { Slug = "embeddium", Name = "Embeddium", Blurb = "Much faster rendering", Picked = true },
        new() { Slug = "oculus", Name = "Oculus", Blurb = "Shader packs (needs Embeddium)", Picked = true },
        new() { Slug = "create", Name = "Create", Blurb = "Gears, machines and moving contraptions" },
        new() { Slug = "jei", Name = "Just Enough Items", Blurb = "Every item and recipe, searchable", Picked = true },
        new() { Slug = "jade", Name = "Jade", Blurb = "Shows what you are looking at", Picked = true },
        new() { Slug = "appleskin", Name = "AppleSkin", Blurb = "Food and saturation on your hunger bar", Picked = true },
    };

    public static string HandOffFile => Path.Combine(Fetch.InstallDir, "first-mods.txt");

    [DataContract]
    sealed class MrProject
    {
        [DataMember(Name = "slug")] public string? Slug;
        [DataMember(Name = "icon_url")] public string? Icon;
    }

    public static async Task LoadIcons(CancellationToken ct)
    {
        var ids = "[" + string.Join(",", All.Select(m => "\"" + m.Slug + "\"")) + "]";
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Pentecost-Setup/1.0");
        var bytes = await http.GetByteArrayAsync("https://api.modrinth.com/v2/projects?ids=" + Uri.EscapeDataString(ids)).ConfigureAwait(false);
        MrProject[]? list;
        using (var ms = new MemoryStream(bytes))
            list = new DataContractJsonSerializer(typeof(MrProject[])).ReadObject(ms) as MrProject[];
        foreach (var p in list ?? new MrProject[0])
        {
            var m = All.FirstOrDefault(x => x.Slug == p.Slug);
            if (m != null && SafeIcon(p.Icon)) m.IconUrl = p.Icon;
        }
    }

    static bool SafeIcon(string? url) =>
        url != null && Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == Uri.UriSchemeHttps
        && u.Host.EndsWith("modrinth.com", StringComparison.OrdinalIgnoreCase);

    public static BitmapImage? Icon(StarterMod m)
    {
        if (m.IconUrl == null) return null;
        try
        {
            var img = new BitmapImage();
            img.BeginInit();
            img.UriSource = new Uri(m.IconUrl);
            img.DecodePixelWidth = 72;
            img.CacheOption = BitmapCacheOption.OnLoad;
            img.EndInit();
            return img;
        }
        catch { return null; }
    }

    public static void HandOff()
    {
        var picks = All.Where(m => m.Picked).Select(m => m.Slug).ToList();
        try
        {
            Directory.CreateDirectory(Fetch.InstallDir);
            File.WriteAllLines(HandOffFile, new[] { "# Starter mods picked in Pentecost Setup. The Manager installs them after Pentecost." }.Concat(picks));
        }
        catch { }
    }
}
