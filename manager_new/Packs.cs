using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public sealed record PackResult(string Name, int Mods, int Downloaded, int Copied, List<string> Problems)
{
    public string Summary => $"Pack {Name} is ready: {Mods} mods, {Downloaded} downloaded"
        + (Problems.Count > 0 ? $", {Problems.Count} skipped. The log lists them." : ".");
}

/// <summary>
/// Modrinth packs (.mrpack). Export writes the mods of a profile. Import downloads a pack's mods and makes a profile
/// for it. Import only writes inside the game's mods, config, shaderpacks and resourcepacks folders, and it never
/// replaces a file that is already there.
/// </summary>
public static class Packs
{
    static string RunDir => Path.Combine(Paths.Home, "run");
    const long MaxIndex = 8_000_000;
    const long MaxFile = 512L * 1024 * 1024;
    const long MaxTotal = 4L * 1024 * 1024 * 1024;
    const int MaxEntries = 20_000;
    static readonly string[] Folders = { "mods", "shaderpacks", "resourcepacks" };

    public static async Task<(int Linked, int Bundled)> Export(ModProfile profile, string path, CancellationToken ct)
    {
        var mods = await Task.Run(ModScan.Scan, ct);
        try { await Modrinth.Lookup(mods, ct); }
        catch (Exception ex) { AppLog.Write("pack export lookup: " + ex.Message); }
        var on = new HashSet<string>(profile.On, StringComparer.OrdinalIgnoreCase);
        var chosen = mods.Where(m => on.Count > 0 ? on.Contains(m.BaseName) : m.Enabled).ToList();
        if (chosen.Count == 0) throw new IOException("This profile has no mods to export.");

        var linked = 0;
        var bundled = 0;
        await Task.Run(() =>
        {
            if (File.Exists(path)) File.Delete(path);
            using var zip = ZipFile.Open(path, ZipArchiveMode.Create);
            var files = new JsonArray();
            foreach (var m in chosen)
            {
                if (m.FileUrl != null && m.Sha512 != null && m.Sha1 != null && Modrinth.SafeImage(m.FileUrl) != null)
                {
                    files.Add(new JsonObject
                    {
                        ["path"] = "mods/" + m.BaseName,
                        ["hashes"] = new JsonObject { ["sha1"] = m.Sha1, ["sha512"] = m.Sha512 },
                        ["env"] = new JsonObject { ["client"] = "required", ["server"] = "required" },
                        ["downloads"] = new JsonArray(JsonValue.Create(m.FileUrl)),
                        ["fileSize"] = m.Size,
                    });
                    linked++;
                }
                else
                {
                    zip.CreateEntryFromFile(m.File, "overrides/mods/" + m.BaseName);
                    bundled++;
                }
            }
            var index = new JsonObject
            {
                ["formatVersion"] = 1,
                ["game"] = "minecraft",
                ["versionId"] = DateTime.Now.ToString("yyyy.MM.dd"),
                ["name"] = profile.Name,
                ["summary"] = "A Pentecost profile. Forge, Fabric and Quilt mods run together.",
                ["files"] = files,
                ["dependencies"] = new JsonObject { ["minecraft"] = "1.20.1" },
            };
            var entry = zip.CreateEntry("modrinth.index.json");
            using var w = new StreamWriter(entry.Open());
            w.Write(index.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        }, ct);
        return (linked, bundled);
    }

    /// <summary>Why this pack cannot run here, or null when it can.</summary>
    public static string? Problem(JsonObject index)
    {
        var deps = index["dependencies"] as JsonObject;
        var mc = deps?["minecraft"]?.ToString();
        if (mc != null && mc != "1.20.1") return $"This pack is for Minecraft {mc}. Pentecost runs 1.20.1.";
        if (deps != null && deps.ContainsKey("neoforge") && !deps.ContainsKey("forge") && !deps.ContainsKey("fabric-loader") && !deps.ContainsKey("quilt-loader"))
            return "This pack needs NeoForge. Pentecost runs Forge, Fabric and Quilt mods.";
        return null;
    }

    /// <summary>Where a pack's file goes inside the game folder, or null when its name would reach outside it.</summary>
    public static string? SafeTarget(string root, string rel)
    {
        if (string.IsNullOrEmpty(rel) || rel.Contains(':')) return null;
        var rootFull = Path.GetFullPath(root) + Path.DirectorySeparatorChar;
        var dest = Path.GetFullPath(Path.Combine(rootFull, rel.Replace('/', Path.DirectorySeparatorChar)));
        return dest.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase) ? dest : null;
    }

    /// <summary>Downloads the newest pack file of a Modrinth modpack for Minecraft 1.20.1 and imports it.</summary>
    public static async Task<PackResult> InstallFromModrinth(string projectId, string? loader, CancellationToken ct)
    {
        var versions = await Modrinth.Versions(projectId, null, ct);
        var wanted = loader == null ? versions : versions.Where(v => v.Loaders.Contains(loader)).ToList();
        if (wanted.Count == 0) wanted = versions;
        var pick = wanted.FirstOrDefault(v => v.Channel == "release") ?? wanted.FirstOrDefault()
            ?? throw new IOException("There is no version of this pack for Minecraft 1.20.1");
        return await InstallVersion(pick, ct);
    }

    /// <summary>Downloads one version of a modpack, checks it, imports it, and removes the pack file.</summary>
    public static async Task<PackResult> InstallVersion(VersionInfo v, CancellationToken ct)
    {
        if (!v.FileName.EndsWith(".mrpack", StringComparison.OrdinalIgnoreCase)) throw new IOException("That version is not a Modrinth pack file.");
        var dir = Path.Combine(Paths.Data, "packs");
        var file = Path.Combine(dir, v.FileName);
        await Downloads.Fetch(v.Url, dir, v.FileName, v.Sha1, ct, Modrinth.Strong(v.Sha512));
        try { return await Import(file, ct); }
        finally { try { File.Delete(file); } catch { } }
    }

    public static async Task<PackResult> Import(string path, CancellationToken ct)
    {
        using var zip = ZipFile.OpenRead(path);
        var indexEntry = zip.GetEntry("modrinth.index.json") ?? throw new IOException("This file is not a Modrinth pack.");
        if (indexEntry.Length > MaxIndex || zip.Entries.Count > MaxEntries) throw new IOException("This pack is larger than a pack should be, so it was not opened.");
        JsonObject index;
        using (var r = new StreamReader(indexEntry.Open()))
            index = JsonNode.Parse(await r.ReadToEndAsync(ct)) as JsonObject ?? throw new IOException("The pack's index cannot be read.");
        if (Problem(index) is { } bad) throw new IOException(bad);
        var name = index["name"]?.ToString() is { Length: > 0 } n ? n : Path.GetFileNameWithoutExtension(path);

        var modNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var problems = new List<string>();
        var downloaded = 0;
        var jobs = new List<Task>();

        async Task Get(string url, string dir, string file, string sha1, string? sha512)
        {
            try { await Downloads.Fetch(url, dir, file, sha1, ct, sha512); Interlocked.Increment(ref downloaded); }
            catch (Exception ex) { lock (problems) problems.Add(file + ": " + ex.Message); }
        }

        foreach (var f in (index["files"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
        {
            if (f["env"]?["client"]?.ToString() == "unsupported") continue;
            var parts = (f["path"]?.ToString() ?? "").Replace('\\', '/').Split('/');
            if (parts.Length != 2 || !Folders.Contains(parts[0]) || parts[1] != Path.GetFileName(parts[1]) || parts[1].Length == 0)
            {
                problems.Add((f["path"]?.ToString() ?? "?") + ": not a mod, shader or resource pack file, skipped");
                continue;
            }
            var file = parts[1];
            if (parts[0] == "mods" && !file.EndsWith(".jar", StringComparison.OrdinalIgnoreCase))
            {
                problems.Add(file + ": a mod file that is not a .jar, skipped");
                continue;
            }
            var dir = Path.Combine(RunDir, parts[0]);
            if (parts[0] == "mods") modNames.Add(file);
            if (File.Exists(Path.Combine(dir, file)) || File.Exists(Path.Combine(dir, file) + ".disabled")) continue;
            var url = (f["downloads"] as JsonArray)?.Select(x => x?.ToString()).FirstOrDefault(u => Modrinth.SafeImage(u) != null);
            var sha1 = f["hashes"]?["sha1"]?.ToString() ?? "";
            if (url == null) { problems.Add(file + ": not hosted on Modrinth, skipped"); continue; }
            jobs.Add(Get(url, dir, file, sha1, Modrinth.Strong(f["hashes"]?["sha512"]?.ToString())));
        }
        await Task.WhenAll(jobs);

        // Files the pack carries inside itself.
        var copied = await Task.Run(() =>
        {
            var count = 0;
            long total = 0;
            foreach (var e in zip.Entries)
            {
                var full = e.FullName.Replace('\\', '/');
                var rel = full.StartsWith("overrides/") ? full[10..] : full.StartsWith("client-overrides/") ? full[17..] : null;
                if (string.IsNullOrEmpty(rel) || full.EndsWith('/')) continue;
                var top = rel.Split('/')[0];
                if (top != "config" && !Folders.Contains(top)) continue;
                var dest = SafeTarget(RunDir, rel);
                if (dest == null) continue;
                if (e.Length > MaxFile || (total += e.Length) > MaxTotal)
                {
                    lock (problems) problems.Add(rel + ": too large, skipped");
                    continue;
                }
                if (top == "mods")
                {
                    if (!rel.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || rel.Count(c => c == '/') != 1) continue;
                    lock (modNames) modNames.Add(Path.GetFileName(rel));
                }
                if (File.Exists(dest) || File.Exists(dest + ".disabled")) continue;
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                    e.ExtractToFile(dest);
                    count++;
                }
                catch (Exception ex) { lock (problems) problems.Add(rel + ": " + ex.Message); }
            }
            return count;
        }, ct);

        // The profile: the pack's mods on, every other mod off.
        var profile = new ModProfile { Name = ProfileStore.UniqueName(name) };
        foreach (var m in ModScan.Scan())
            (modNames.Contains(m.BaseName) ? profile.On : profile.Off).Add(m.BaseName);
        ProfileStore.Load().Profiles.Add(profile);
        ProfileStore.Save();
        ProfileStore.SetActive(profile.Name);
        return new PackResult(profile.Name, profile.On.Count, downloaded, copied, problems);
    }
}
