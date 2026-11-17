using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public sealed record Hit(string ProjectId, string Slug, string Title, string Author, string Desc, string? IconUrl,
    long Downloads, long Follows, string[] Loaders, string[] Categories, string Type);
public sealed record SearchPage(List<Hit> Hits, int Total);
public sealed record Missing(string ProjectId, string Title, string NeededBy, string? IconUrl);
public sealed record Category(string Name, string Type);
public sealed record VersionInfo(string Id, string Name, string Number, string Channel, string[] Loaders, DateTime Date,
    long Downloads, string FileName, string Url, string Sha1, long Size, string[] Requires);
public sealed record ProjectInfo(string Id, string Slug, string Title, string Desc, string Body, string? IconUrl, string Type,
    long Downloads, long Follows, string[] Categories, string[] Gallery);

/// <summary>Modrinth search, install and update. Files are fetched through <see cref="Downloads"/>, which holds the download rules.</summary>
public static class Modrinth
{
    const string Api = "https://api.modrinth.com/v2";
    const string Game = "%5B%221.20.1%22%5D";
    static readonly HttpClient Http = Make();

    static HttpClient Make()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("Pentecost-Manager (https://github.com/FilesCodes-ui/Pentecost-Mod-Loader)");
        return c;
    }

    public static HttpClient Client => Http;

    static readonly Dictionary<string, string> Thumbs = new();

    /// <summary>The small copy of a gallery picture, for the page.</summary>
    public static string Thumb(string raw) { lock (Thumbs) return Thumbs.TryGetValue(raw, out var t) ? t : raw; }

    /// <summary>An image link is used only when it points at Modrinth's own CDN.</summary>
    public static Uri? SafeImage(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == "https" && u.Host.EndsWith("modrinth.com", StringComparison.OrdinalIgnoreCase) ? u : null;

    /// <summary>Where a kind of project is kept inside the game folder.</summary>
    public static string DirFor(string type) => type switch
    {
        "shader" => Path.Combine(Paths.Home, "run", "shaderpacks"),
        "resourcepack" => Path.Combine(Paths.Home, "run", "resourcepacks"),
        _ => Paths.Mods,
    };

    static async Task<JsonNode?> Get(string url, CancellationToken ct) =>
        JsonNode.Parse(await Http.GetStringAsync(url, ct));

    static async Task<JsonNode?> Post(string url, JsonNode body, CancellationToken ct)
    {
        using var content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var r = await Http.PostAsync(url, content, ct);
        r.EnsureSuccessStatusCode();
        return JsonNode.Parse(await r.Content.ReadAsStringAsync(ct));
    }

    static string[] Strings(JsonNode? n) => (n as JsonArray)?.Select(x => x?.ToString() ?? "").Where(x => x.Length > 0).ToArray() ?? Array.Empty<string>();
    static long Long(JsonNode? n) { try { return n?.GetValue<long>() ?? 0; } catch { return 0; } }

    // ---------------------------------------------------------------- search

    static readonly string[] ModLoaders = { "forge", "fabric", "quilt", "neoforge" };

    public static async Task<SearchPage> Search(string query, string type, string loader, IEnumerable<string> categories, string sort, int offset, CancellationToken ct)
    {
        var facets = new JsonArray(
            new JsonArray(JsonValue.Create("versions:1.20.1")),
            new JsonArray(JsonValue.Create("project_type:" + type)));
        if (type == "mod")
            facets.Add(loader == "All"
                ? new JsonArray(JsonValue.Create("categories:forge"), JsonValue.Create("categories:fabric"), JsonValue.Create("categories:quilt"))
                : new JsonArray(JsonValue.Create("categories:" + loader.ToLowerInvariant())));
        foreach (var c in categories) facets.Add(new JsonArray(JsonValue.Create("categories:" + c)));
        var index = query.Length == 0 && sort == "relevance" ? "downloads" : sort;
        var url = $"{Api}/search?query={Uri.EscapeDataString(query)}&facets={Uri.EscapeDataString(facets.ToJsonString())}&limit=100&offset={offset}&index={index}";
        var root = await Get(url, ct) as JsonObject ?? throw new IOException("Modrinth gave an answer I do not understand");
        var hits = new List<Hit>();
        foreach (var h in (root["hits"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
        {
            var cats = Strings(h["categories"]);
            var shown = Strings(h["display_categories"]).Where(c => !ModLoaders.Contains(c)).ToArray();
            hits.Add(new Hit(h["project_id"]?.ToString() ?? "", h["slug"]?.ToString() ?? "", h["title"]?.ToString() ?? "",
                h["author"]?.ToString() ?? "", h["description"]?.ToString() ?? "", h["icon_url"]?.ToString(),
                Long(h["downloads"]), Long(h["follows"]), ModLoaders.Where(cats.Contains).ToArray(), shown, type));
        }
        return new SearchPage(hits, (int)Long(root["total_hits"]));
    }

    static List<Category>? categoryCache;

    /// <summary>Every category Modrinth knows, with the kind of project it belongs to.</summary>
    public static async Task<List<Category>> Categories(CancellationToken ct)
    {
        if (categoryCache != null) return categoryCache;
        var list = new List<Category>();
        if (await Get(Api + "/tag/category", ct) is JsonArray arr)
            foreach (var o in arr.OfType<JsonObject>())
                list.Add(new Category(o["name"]?.ToString() ?? "", o["project_type"]?.ToString() ?? ""));
        return categoryCache = list.Where(c => c.Name.Length > 0).ToList();
    }

    // ---------------------------------------------------------------- one project

    public static async Task<ProjectInfo> Project(string id, CancellationToken ct)
    {
        var o = await Get($"{Api}/project/{Uri.EscapeDataString(id)}", ct) as JsonObject ?? throw new IOException("Modrinth gave an answer I do not understand");
        // Modrinth keeps a 350 pixel copy and the original. The page uses the copy and the viewer uses the original.
        var gallery = new List<string>();
        foreach (var g in (o["gallery"] as JsonArray)?.OfType<JsonObject>().OrderByDescending(g => g["featured"]?.GetValue<bool>() == true) ?? Enumerable.Empty<JsonObject>())
        {
            var url = g["url"]?.ToString() ?? "";
            var raw = g["raw_url"]?.ToString();
            if (string.IsNullOrEmpty(raw)) raw = url;
            if (SafeImage(raw) == null) continue;
            lock (Thumbs) Thumbs[raw] = SafeImage(url) != null ? url : raw;
            gallery.Add(raw);
        }
        return new ProjectInfo(o["id"]?.ToString() ?? id, o["slug"]?.ToString() ?? id, o["title"]?.ToString() ?? "", o["description"]?.ToString() ?? "",
            o["body"]?.ToString() ?? "", o["icon_url"]?.ToString(), o["project_type"]?.ToString() ?? "mod",
            Long(o["downloads"]), Long(o["followers"]), Strings(o["categories"]), gallery.ToArray());
    }

    /// <summary>Versions of a project for Minecraft 1.20.1, newest first.</summary>
    public static async Task<List<VersionInfo>> Versions(string id, string? loader, CancellationToken ct)
    {
        var url = $"{Api}/project/{Uri.EscapeDataString(id)}/version?game_versions={Game}";
        if (loader != null) url += $"&loaders=%5B%22{loader}%22%5D";
        var list = new List<VersionInfo>();
        foreach (var v in (await Get(url, ct) as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
        {
            var files = (v["files"] as JsonArray)?.OfType<JsonObject>().ToList() ?? new();
            var file = files.FirstOrDefault(f => f["primary"]?.GetValue<bool>() == true) ?? files.FirstOrDefault();
            var name = Path.GetFileName(file?["filename"]?.ToString() ?? "");
            var link = file?["url"]?.ToString();
            var sha1 = file?["hashes"]?["sha1"]?.ToString();
            if (file == null || name.Length == 0 || link == null || sha1 == null) continue;
            var requires = (v["dependencies"] as JsonArray)?.OfType<JsonObject>()
                .Where(d => d["dependency_type"]?.ToString() == "required")
                .Select(d => d["project_id"]?.ToString() ?? "").Where(p => p.Length > 0).ToArray() ?? Array.Empty<string>();
            DateTime.TryParse(v["date_published"]?.ToString(), out var date);
            list.Add(new VersionInfo(v["id"]?.ToString() ?? "", v["name"]?.ToString() ?? "", v["version_number"]?.ToString() ?? "",
                v["version_type"]?.ToString() ?? "release", Strings(v["loaders"]), date, Long(v["downloads"]), name, link, sha1, Long(file["size"]), requires));
        }
        return list;
    }

    // ---------------------------------------------------------------- lookup of installed mods

    static string[] LoadersFor(ModEntry m) => m.Loader switch
    {
        "Fabric" => new[] { "fabric" },
        "Forge" => new[] { "forge" },
        "Quilt" => new[] { "quilt", "fabric" },
        _ => new[] { "forge", "fabric" },
    };

    /// <summary>
    /// Matches installed jars to Modrinth projects by hash. Sets each mod's project, icon, description and update, and
    /// returns required mods that are not installed. Jars Modrinth does not know are left alone.
    /// </summary>
    public static async Task<List<Missing>> Lookup(List<ModEntry> mods, CancellationToken ct)
    {
        var missing = new List<Missing>();
        var todo = mods.Where(m => !m.Looked).ToList();
        await Task.Run(() => { foreach (var m in todo) { try { ModScan.Sha1Of(m); } catch { } } }, ct);
        var byHash = todo.Where(m => m.Sha1 != null).GroupBy(m => m.Sha1!).ToDictionary(g => g.Key, g => g.First());

        if (byHash.Count > 0)
        {
            var hashes = new JsonArray(byHash.Keys.Select(h => (JsonNode)JsonValue.Create(h)!).ToArray());
            var found = await Post(Api + "/version_files", new JsonObject { ["hashes"] = hashes, ["algorithm"] = "sha1" }, ct) as JsonObject;
            if (found != null)
                foreach (var (hash, v) in found)
                {
                    if (!byHash.TryGetValue(hash, out var m) || v is not JsonObject vo) continue;
                    m.ProjectId = vo["project_id"]?.ToString();
                    if (m.ProjectId == null) continue;
                    m.Requires.Clear();
                    foreach (var d in (vo["dependencies"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
                        if (d["dependency_type"]?.ToString() == "required" && d["project_id"]?.ToString() is { Length: > 0 } pid && !m.Requires.Contains(pid))
                            m.Requires.Add(pid);
                    // The exact file, so a pack export can point at it.
                    var mine = (vo["files"] as JsonArray)?.OfType<JsonObject>()
                        .FirstOrDefault(f => string.Equals(f["hashes"]?["sha1"]?.ToString(), hash, StringComparison.OrdinalIgnoreCase));
                    m.FileUrl = mine?["url"]?.ToString();
                    m.Sha512 = mine?["hashes"]?["sha512"]?.ToString();
                }

            // Updates: one call for each loader group.
            foreach (var group in byHash.Values.Where(m => m.ProjectId != null).GroupBy(m => string.Join(",", LoadersFor(m))))
            {
                var gh = new JsonArray(group.Select(m => (JsonNode)JsonValue.Create(m.Sha1!)!).ToArray());
                var body = new JsonObject
                {
                    ["hashes"] = gh, ["algorithm"] = "sha1",
                    ["loaders"] = new JsonArray(group.Key.Split(',').Select(l => (JsonNode)JsonValue.Create(l)!).ToArray()),
                    ["game_versions"] = new JsonArray(JsonValue.Create("1.20.1")),
                };
                try
                {
                    var latest = await Post(Api + "/version_files/update", body, ct) as JsonObject;
                    if (latest == null) continue;
                    foreach (var m in group)
                    {
                        if (!latest.TryGetPropertyValue(m.Sha1!, out var lv) || lv is not JsonObject vo) continue;
                        var files = (vo["files"] as JsonArray)?.OfType<JsonObject>().ToList() ?? new();
                        var file = files.FirstOrDefault(f => f["primary"]?.GetValue<bool>() == true) ?? files.FirstOrDefault();
                        var sha1 = file?["hashes"]?["sha1"]?.ToString();
                        var name = Path.GetFileName(file?["filename"]?.ToString() ?? "");
                        var url = file?["url"]?.ToString();
                        if (file == null || sha1 == null || url == null || name.Length == 0) continue;
                        if (!string.Equals(sha1, m.Sha1, StringComparison.OrdinalIgnoreCase))
                            m.Update = new ModUpdate(name, url, sha1, vo["version_number"]?.ToString() ?? "");
                    }
                }
                catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException) { AppLog.Write("update lookup: " + ex.Message); }
            }
            foreach (var m in todo) m.Looked = true;
        }

        // Icons and names for every known project, plus the projects that are needed but missing.
        // Requirements are kept on each mod, so this answer is the same every time the page opens.
        var have = mods.Where(m => m.ProjectId != null).Select(m => m.ProjectId!).ToHashSet();
        var needed = mods.Where(m => m.ProjectId != null && m.Enabled)
            .SelectMany(m => m.Requires.Select(p => (Dep: p, By: m.ProjectId!))).Where(x => !have.Contains(x.Dep)).ToList();
        var ids = have.Concat(needed.Select(n => n.Dep)).Distinct().ToList();
        var info = await Projects(ids, ct);
        foreach (var m in mods.Where(m => m.ProjectId != null && info.ContainsKey(m.ProjectId)))
        {
            m.IconUrl = info[m.ProjectId!].Icon;
            m.Desc = info[m.ProjectId!].Desc;
        }
        foreach (var g in needed.GroupBy(n => n.Dep))
        {
            if (!info.TryGetValue(g.Key, out var p)) continue;
            // Pentecost already supplies the Fabric API and loader pieces.
            if (p.Title.Contains("Fabric API", StringComparison.OrdinalIgnoreCase) || p.Title.Equals("Fabric Loader", StringComparison.OrdinalIgnoreCase)) continue;
            var by = string.Join(", ", g.Select(x => info.TryGetValue(x.By, out var b) ? b.Title : "a mod").Distinct().Take(3));
            missing.Add(new Missing(g.Key, p.Title, by, p.Icon));
        }
        return missing;
    }

    static async Task<Dictionary<string, (string Title, string? Icon, string Desc)>> Projects(List<string> ids, CancellationToken ct)
    {
        var result = new Dictionary<string, (string, string?, string)>();
        for (var i = 0; i < ids.Count; i += 100)
        {
            var chunk = ids.Skip(i).Take(100).ToList();
            var arr = new JsonArray(chunk.Select(x => (JsonNode)JsonValue.Create(x)!).ToArray());
            try
            {
                if (await Get($"{Api}/projects?ids={Uri.EscapeDataString(arr.ToJsonString())}", ct) is JsonArray list)
                    foreach (var p in list.OfType<JsonObject>())
                        result[p["id"]?.ToString() ?? ""] = (p["title"]?.ToString() ?? "", p["icon_url"]?.ToString(), p["description"]?.ToString() ?? "");
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException) { AppLog.Write("projects: " + ex.Message); }
        }
        return result;
    }

    // ---------------------------------------------------------------- install and update

    /// <summary>Installs a mod and its required dependencies. Returns the file names that were newly added.</summary>
    public static async Task<List<string>> Install(string project, string loader, Action<string> report, CancellationToken ct)
    {
        var added = new List<string>();
        Directory.CreateDirectory(Paths.Mods);
        await One(project, loader.ToLowerInvariant(), new HashSet<string>(), 0, added, report, ct);
        return added;
    }

    /// <summary>Installs the newest shader pack or resource pack of a project.</summary>
    public static async Task<List<string>> InstallOther(string project, string type, CancellationToken ct)
    {
        var versions = await Versions(project, null, ct);
        var v = versions.FirstOrDefault(x => x.Channel == "release") ?? versions.FirstOrDefault()
            ?? throw new IOException("There is no version for Minecraft 1.20.1");
        return await InstallVersion(v, type, ct);
    }

    /// <summary>Installs one chosen version. For a mod, another version of the same mod goes to the Recycle Bin.</summary>
    public static async Task<List<string>> InstallVersion(VersionInfo v, string type, CancellationToken ct)
    {
        var added = new List<string>();
        var dir = DirFor(type);
        var dest = Path.Combine(dir, v.FileName);
        if (File.Exists(dest) || File.Exists(dest + ".disabled")) return added;
        var old = type == "mod" ? ModScan.Scan().Where(m => BaseName(m.File) == BaseName(v.FileName)).ToList() : new List<ModEntry>();
        await Downloads.Fetch(v.Url, dir, v.FileName, v.Sha1, ct);
        added.Add(v.FileName);
        foreach (var m in old)
            try { ModScan.Delete(m); } catch (Exception ex) { AppLog.Write("replace " + m.BaseName + ": " + ex.Message); }
        if (type == "mod")
        {
            var loader = v.Loaders.Contains("fabric") && !v.Loaders.Contains("forge") && !v.Loaders.Contains("neoforge") ? "fabric" : "forge";
            var seen = new HashSet<string>();
            foreach (var dep in v.Requires)
                try { await One(dep, loader, seen, 1, added, _ => { }, ct); }
                catch (Exception ex) when (ex is IOException or HttpRequestException) { AppLog.Write("needed mod " + dep + ": " + ex.Message); }
        }
        return added;
    }

    static async Task<VersionInfo?> Best(string project, string loader, CancellationToken ct)
    {
        var versions = await Versions(project, loader, ct);
        return versions.FirstOrDefault(v => v.Channel == "release") ?? versions.FirstOrDefault();
    }

    static async Task One(string project, string loader, HashSet<string> seen, int depth, List<string> added, Action<string> report, CancellationToken ct)
    {
        if (!seen.Add(project) || depth > 3) return;
        report("Looking up " + project);
        // Pentecost runs both loaders, so a mod that only exists for the other one is still fine.
        var version = await Best(project, loader, ct) ?? await Best(project, loader == "forge" ? "fabric" : "forge", ct)
            ?? throw new IOException("There is no version for Minecraft 1.20.1");
        if (!AlreadyThere(version.FileName))
        {
            report("Downloading " + version.FileName);
            await Downloads.Fetch(version.Url, Paths.Mods, version.FileName, version.Sha1, ct);
            added.Add(version.FileName);
        }
        foreach (var id in version.Requires)
            await One(id, loader, seen, depth + 1, added, report, ct);
    }

    /// <summary>Downloads the new version of a mod, then moves the old jar to the Recycle Bin.</summary>
    public static async Task ApplyUpdate(ModEntry m, Action<string> report, CancellationToken ct)
    {
        var u = m.Update ?? throw new IOException("There is no update for this mod.");
        var wasOff = !m.Enabled;
        report("Downloading " + u.FileName);
        await Downloads.Fetch(u.Url, Paths.Mods, u.FileName, u.Sha1, ct);
        var fresh = Path.Combine(Paths.Mods, u.FileName);
        if (!string.Equals(Path.GetFileName(m.File), u.FileName, StringComparison.OrdinalIgnoreCase)) ModScan.Delete(m);
        m.Update = null;
        if (wasOff && File.Exists(fresh)) File.Move(fresh, fresh + ".disabled", true);
    }

    public static string BaseName(string file)
    {
        var n = Path.GetFileNameWithoutExtension(Path.GetFileName(file).Replace(".disabled", ""));
        var m = System.Text.RegularExpressions.Regex.Match(n, "^(.*?)[-_+ ]v?\\d");
        return (m.Success ? m.Groups[1].Value : n).ToLowerInvariant();
    }

    public static bool AlreadyThere(string name)
    {
        if (!Directory.Exists(Paths.Mods)) return false;
        var b = BaseName(name);
        return Directory.GetFiles(Paths.Mods).Any(f => BaseName(f) == b);
    }
}
