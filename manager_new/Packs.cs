using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public sealed record PackResult(string Name, int Mods, int Downloaded, int Copied, List<string> Problems);

/// <summary>
/// Modrinth packs (.mrpack). Export writes the mods of a profile. Import downloads a pack's mods and makes a profile
/// for it. Import only writes inside the game's mods, config, shaderpacks and resourcepacks folders, and it never
/// replaces a file that is already there.
/// </summary>
public static class Packs
{
    static string RunDir => Path.Combine(Paths.Home, "run");
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

    public static async Task<PackResult> Import(string path, CancellationToken ct)
    {
        using var zip = ZipFile.OpenRead(path);
        var indexEntry = zip.GetEntry("modrinth.index.json") ?? throw new IOException("This file is not a Modrinth pack.");
        JsonObject index;
        using (var r = new StreamReader(indexEntry.Open()))
            index = JsonNode.Parse(await r.ReadToEndAsync(ct)) as JsonObject ?? throw new IOException("The pack's index cannot be read.");
        var mc = index["dependencies"]?["minecraft"]?.ToString();
        if (mc != null && mc != "1.20.1") throw new IOException($"This pack is for Minecraft {mc}. Pentecost runs 1.20.1.");
        var name = index["name"]?.ToString() is { Length: > 0 } n ? n : Path.GetFileNameWithoutExtension(path);

        var modNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var problems = new List<string>();
        var downloaded = 0;
        var jobs = new List<Task>();

        async Task Get(string url, string dir, string file, string sha1)
        {
            try { await Downloads.Fetch(url, dir, file, sha1, ct); Interlocked.Increment(ref downloaded); }
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
            var dir = Path.Combine(RunDir, parts[0]);
            if (parts[0] == "mods") modNames.Add(file);
            if (File.Exists(Path.Combine(dir, file)) || File.Exists(Path.Combine(dir, file) + ".disabled")) continue;
            var url = (f["downloads"] as JsonArray)?.Select(x => x?.ToString()).FirstOrDefault(u => Modrinth.SafeImage(u) != null);
            var sha1 = f["hashes"]?["sha1"]?.ToString() ?? "";
            if (url == null) { problems.Add(file + ": not hosted on Modrinth, skipped"); continue; }
            jobs.Add(Get(url, dir, file, sha1));
        }
        await Task.WhenAll(jobs);

        // Files the pack carries inside itself.
        var copied = await Task.Run(() =>
        {
            var count = 0;
            var rootFull = Path.GetFullPath(RunDir) + Path.DirectorySeparatorChar;
            foreach (var e in zip.Entries)
            {
                var full = e.FullName.Replace('\\', '/');
                var rel = full.StartsWith("overrides/") ? full[10..] : full.StartsWith("client-overrides/") ? full[17..] : null;
                if (string.IsNullOrEmpty(rel) || full.EndsWith('/')) continue;
                var top = rel.Split('/')[0];
                if (top != "config" && !Folders.Contains(top)) continue;
                var dest = Path.GetFullPath(Path.Combine(RunDir, rel.Replace('/', Path.DirectorySeparatorChar)));
                if (!dest.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase)) continue;
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
