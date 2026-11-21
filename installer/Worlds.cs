using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace Pentecost.Installer;

public sealed record WorldEntry(string Seed, string Asset, long Size, string Sha256);

public sealed record WorldManifest(string BaseUrl, List<WorldEntry> Worlds);

public static class Worlds
{
    static readonly JsonSerializerOptions Options = new() { PropertyNameCaseInsensitive = true };

    public static WorldManifest Load()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("worlds.json");
        if (s == null) return new WorldManifest("", new());
        return JsonSerializer.Deserialize<WorldManifest>(s, Options) ?? new WorldManifest("", new());
    }

    public static long TotalBytes() => Load().Worlds.Sum(w => w.Size);

    public static List<WorldEntry> Missing(string home)
    {
        var dir = Path.Combine(home, "menu_world");
        return Load().Worlds.Where(w =>
        {
            var f = Path.Combine(dir, w.Seed + ".zip");
            return !(File.Exists(f) && new FileInfo(f).Length == w.Size);
        }).ToList();
    }

    public static async Task<int> DownloadAsync(string home, Action<double, string> report, CancellationToken ct)
    {
        var m = Load();
        if (m.Worlds.Count == 0) return 0;
        var dir = Path.Combine(home, "menu_world");
        Directory.CreateDirectory(dir);

        var total = Math.Max(1, m.Worlds.Sum(w => w.Size));
        long done = 0;
        var failed = 0;
        var index = 0;
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Pentecost-Installer/0.3");

        foreach (var w in m.Worlds)
        {
            ct.ThrowIfCancellationRequested();
            index++;
            var label = $"World {index} of {m.Worlds.Count}";
            var dest = Path.Combine(dir, w.Seed + ".zip");
            if (File.Exists(dest) && new FileInfo(dest).Length == w.Size)
            {
                done += w.Size;
                report(done / (double)total, label + " is already installed");
                continue;
            }

            var part = dest + ".part";
            try
            {
                using var resp = await http.GetAsync(m.BaseUrl + Uri.EscapeDataString(w.Asset), HttpCompletionOption.ResponseHeadersRead, ct);
                resp.EnsureSuccessStatusCode();
                using var input = await resp.Content.ReadAsStreamAsync(ct);
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                long got = 0;
                var lastReport = Environment.TickCount64;
                using (var output = File.Create(part))
                {
                    var buffer = new byte[128 * 1024];
                    int n;
                    while ((n = await input.ReadAsync(buffer, ct)) > 0)
                    {
                        await output.WriteAsync(buffer.AsMemory(0, n), ct);
                        hash.AppendData(buffer, 0, n);
                        got += n;
                        if (Environment.TickCount64 - lastReport > 120)
                        {
                            lastReport = Environment.TickCount64;
                            report((done + got) / (double)total, $"{label}: {got / 1048576} of {w.Size / 1048576} MB");
                        }
                    }
                }
                var hex = Convert.ToHexString(hash.GetHashAndReset());
                if (got != w.Size || !hex.Equals(w.Sha256, StringComparison.OrdinalIgnoreCase))
                    throw new IOException("the download was damaged");
                File.Move(part, dest, true);
                done += w.Size;
                Log.Write($"world {w.Seed} downloaded");
            }
            catch (Exception ex) when (ex is HttpRequestException or IOException or TaskCanceledException && !ct.IsCancellationRequested)
            {
                failed++;
                done += w.Size;
                Log.Write($"world {w.Seed} failed: {ex.Message}");
                try { File.Delete(part); } catch { }
            }
        }
        return failed;
    }
}
