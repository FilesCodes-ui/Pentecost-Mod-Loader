using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pentecost.Manager;

public sealed record PackFile(string Name, string Kind, string Sha1, long Size, string? ProjectId);

public sealed record PackSummary(string VersionId, string Number, string Loader, int Mods, int Shaders, int Resources, long Bytes, List<PackFile> Files);

public static partial class PackInfo
{
    const long MaxPack = 40L * 1024 * 1024;
    const long MaxIndex = 8L * 1024 * 1024;
    static readonly Dictionary<string, Task<PackSummary?>> byProject = new();
    static readonly SemaphoreSlim gate = new(3);
    static string CacheDir => Path.Combine(Paths.Data, "packinfo");

    [GeneratedRegex(@"/data/([A-Za-z0-9]{8})/versions/")]
    private static partial Regex ProjectInUrl();

    public static Task<PackSummary?> For(string projectId)
    {
        lock (byProject)
        {
            if (!byProject.TryGetValue(projectId, out var t)) byProject[projectId] = t = Load(projectId);
            return t;
        }
    }

    static async Task<PackSummary?> Load(string projectId)
    {
        await gate.WaitAsync();
        try
        {
            var versions = await Modrinth.VersionsOnly(projectId, null, CancellationToken.None);
            var pick = versions.FirstOrDefault(v => v.Channel == "release") ?? versions.FirstOrDefault();
            return pick == null ? null : await ForVersion(pick);
        }
        catch (Exception ex)
        {
            AppLog.Write("pack info " + projectId + ": " + ex.Message);
            lock (byProject) byProject.Remove(projectId);
            return null;
        }
        finally { gate.Release(); }
    }

    public static async Task<PackSummary?> ForVersion(VersionInfo v)
    {
        var cache = Path.Combine(CacheDir, string.Concat(v.Id.Where(char.IsLetterOrDigit)) + ".json");
        try { if (File.Exists(cache)) return JsonSerializer.Deserialize<PackSummary>(await File.ReadAllTextAsync(cache)); }
        catch { }
        if (!v.FileName.EndsWith(".mrpack", StringComparison.OrdinalIgnoreCase) || v.Size > MaxPack) return null;
        if (Modrinth.SafeImage(v.Url) == null) return null;

        var bytes = await Modrinth.Client.GetByteArrayAsync(v.Url);
        if (!Convert.ToHexString(SHA1.HashData(bytes)).Equals(v.Sha1, StringComparison.OrdinalIgnoreCase))
            throw new IOException("the pack file did not match its checksum");

        var summary = await Task.Run(() => Read(v, bytes));
        try
        {
            Directory.CreateDirectory(CacheDir);
            await File.WriteAllTextAsync(cache, JsonSerializer.Serialize(summary));
        }
        catch { }
        return summary;
    }

    static PackSummary Read(VersionInfo v, byte[] bytes)
    {
        using var zip = new ZipArchive(new MemoryStream(bytes), ZipArchiveMode.Read);
        var entry = zip.GetEntry("modrinth.index.json") ?? throw new IOException("not a Modrinth pack");
        if (entry.Length > MaxIndex) throw new IOException("the pack's list is too large");
        JsonObject index;
        using (var r = new StreamReader(entry.Open()))
            index = JsonNode.Parse(r.ReadToEnd()) as JsonObject ?? throw new IOException("the pack's list cannot be read");

        var files = new List<PackFile>();
        long total = v.Size;
        foreach (var f in (index["files"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
        {
            if (f["env"]?["client"]?.ToString() == "unsupported") continue;
            var path = (f["path"]?.ToString() ?? "").Replace('\\', '/');
            var kind = Kind(path);
            if (kind == null) continue;
            long size = 0;
            try { size = f["fileSize"]?.GetValue<long>() ?? 0; } catch { }
            total += size;
            var url = (f["downloads"] as JsonArray)?.Select(x => x?.ToString() ?? "").FirstOrDefault() ?? "";
            var m = ProjectInUrl().Match(url);
            files.Add(new PackFile(Path.GetFileName(path), kind, (f["hashes"]?["sha1"]?.ToString() ?? "").ToLowerInvariant(), size, m.Success ? m.Groups[1].Value : null));
        }
        foreach (var e in zip.Entries)
        {
            var full = e.FullName.Replace('\\', '/');
            var rel = full.StartsWith("overrides/") ? full[10..] : full.StartsWith("client-overrides/") ? full[17..] : null;
            if (string.IsNullOrEmpty(rel) || full.EndsWith('/') || rel.Count(c => c == '/') != 1) continue;
            var kind = Kind(rel);
            if (kind == null) continue;
            var sha1 = "";
            if (kind == "mod" && e.Length < 64L * 1024 * 1024)
                using (var s = e.Open()) sha1 = Convert.ToHexString(SHA1.HashData(s)).ToLowerInvariant();
            files.Add(new PackFile(Path.GetFileName(rel), kind, sha1, e.Length, null));
        }

        var deps = index["dependencies"] as JsonObject;
        var loader = deps == null ? null
            : deps.ContainsKey("forge") ? "Forge"
            : deps.ContainsKey("neoforge") ? "NeoForge"
            : deps.ContainsKey("fabric-loader") ? "Fabric"
            : deps.ContainsKey("quilt-loader") ? "Quilt" : null;
        loader ??= v.Loaders.Length > 0 ? Ui.Nice(v.Loaders[0]) : "Unknown";

        return new PackSummary(v.Id, v.Number, loader,
            files.Count(f => f.Kind == "mod"), files.Count(f => f.Kind == "shader"), files.Count(f => f.Kind == "resource"), total, files);
    }

    static string? Kind(string path)
    {
        var parts = path.Split('/');
        if (parts.Length != 2 || parts[1].Length == 0) return null;
        return parts[0] switch
        {
            "mods" when parts[1].EndsWith(".jar", StringComparison.OrdinalIgnoreCase) => "mod",
            "shaderpacks" => "shader",
            "resourcepacks" => "resource",
            _ => null,
        };
    }

    static readonly Dictionary<string, string> hashed = new();
    static Task<Dictionary<string, string>>? local;
    static DateTime localAt;

    public static Task<Dictionary<string, string>> Local()
    {
        lock (hashed)
        {
            if (local == null || (DateTime.UtcNow - localAt).TotalMinutes > 5)
            {
                localAt = DateTime.UtcNow;
                local = Task.Run(Scan);
            }
            return local;
        }
    }

    public static void Forget() { lock (hashed) local = null; }

    static Dictionary<string, string> Scan()
    {
        var set = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (!Directory.Exists(Instances.Root)) return set;
        foreach (var file in Directory.EnumerateFiles(Instances.Root, "*", SearchOption.AllDirectories))
        {
            if (!file.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) && !file.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                var info = new FileInfo(file);
                var key = file + "|" + info.Length + "|" + info.LastWriteTimeUtc.Ticks;
                string? sha;
                lock (hashed) hashed.TryGetValue(key, out sha);
                if (sha == null)
                {
                    using var s = File.OpenRead(file);
                    sha = Convert.ToHexString(SHA1.HashData(s)).ToLowerInvariant();
                    lock (hashed) hashed[key] = sha;
                }
                set.TryAdd(sha, file);
            }
            catch { }
        }
        return set;
    }

    public static int Have(PackSummary s, Dictionary<string, string> here) => s.Files.Count(f => f.Kind == "mod" && f.Sha1.Length > 0 && here.ContainsKey(f.Sha1));

    public static string Line(PackSummary s, int have)
    {
        var parts = new List<string> { s.Mods == 1 ? "1 mod" : s.Mods + " mods", s.Loader, Ui.Bytes(s.Bytes) };
        if (have > 0) parts.Add(have + " already here");
        return string.Join(",  ", parts);
    }
}
