using System.Globalization;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pentecost.Manager;

public sealed record CfMod(string Id, string Slug, string Title, string Author, string[] Authors, string Summary, string? Icon,
    long Downloads, long Thumbs, string[] Loaders, string[] Categories, string[] Gallery, DateTime? Updated, string Type, string[] Sha1s);

// The CurseForge side of Browse. Nothing in the UI calls this directly: Catalog merges it with Modrinth.
public static class CurseForge
{
    const int GameId = 432;
    const string Minecraft = "1.20.1";
    static readonly HttpClient Http = Make();
    static List<(int Id, string Name, string Type)>? categoryCache;

    static HttpClient Make()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromSeconds(40) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("Pentecost-Manager (https://github.com/FilesCodes-ui/Pentecost-Mod-Loader)");
        c.DefaultRequestHeaders.Accept.ParseAdd("application/json");
        return c;
    }

    static DateTime signedCheck = DateTime.MinValue;
    static bool signedIn;

    // CurseForge is asked through the Pentecost server, which holds the key. So all it takes here is a signed-in Pentecost account.
    public static bool SignedIn
    {
        get
        {
            if (DateTime.UtcNow - signedCheck > TimeSpan.FromSeconds(30)) { signedIn = Online.LoadSession() != null; signedCheck = DateTime.UtcNow; }
            return signedIn;
        }
    }

    public static bool Ready => Settings.UseCurseForge && SignedIn;

    public static bool IsCdnHost(string host) =>
        host.Equals("forgecdn.net", StringComparison.OrdinalIgnoreCase) || host.EndsWith(".forgecdn.net", StringComparison.OrdinalIgnoreCase);

    static bool IsImageHost(string host) =>
        IsCdnHost(host) || host.Equals("curseforge.com", StringComparison.OrdinalIgnoreCase) || host.EndsWith(".curseforge.com", StringComparison.OrdinalIgnoreCase);

    // Pictures may come from either site. Downloads are checked separately in Downloads.Fetch.
    public static Uri? SafeImage(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == "https" && (Modrinth.IsModrinthHost(u.Host) || IsImageHost(u.Host)) ? u : null;

    public static int ClassOf(string type) => type switch { "shader" => 6552, "resourcepack" => 12, "modpack" => 4471, _ => 6 };
    public static string TypeOf(int cls) => cls switch { 6552 => "shader", 12 => "resourcepack", 4471 => "modpack", _ => "mod" };
    static int LoaderCode(string? l) => l?.ToLowerInvariant() switch { "forge" => 1, "fabric" => 4, "quilt" => 5, "neoforge" => 6, _ => 0 };
    static string? LoaderName(int code) => code switch { 1 => "forge", 4 => "fabric", 5 => "quilt", 6 => "neoforge", _ => null };

    static async Task<JsonNode?> Get(string path, CancellationToken ct)
    {
        var session = Online.LoadSession() ?? throw new HttpRequestException("Sign in to Pentecost online to use CurseForge");
        for (var attempt = 0; ; attempt++)
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, Online.Server + "/cf/v1" + path);
            req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", session.Token);
            using var r = await Http.SendAsync(req, ct);
            var body = await r.Content.ReadAsStringAsync(ct);
            if (r.IsSuccessStatusCode) return JsonNode.Parse(body);

            var code = (int)r.StatusCode;
            if (code is 502 or 503 or 504 && attempt < 2) { await Task.Delay(500 * (attempt + 1), ct); continue; }
            var detail = "";
            try { detail = JsonNode.Parse(body)?["error"]?.ToString() ?? ""; } catch (Exception) { }
            AppLog.Write($"curseforge {code} for {path.Split('?')[0]}: {detail}");
            var why = code == 401 ? "Sign in to Pentecost online again to use CurseForge" : code == 429 ? "CurseForge says slow down, try again in a minute" : code == 503 && detail.Contains("not set up") ? "CurseForge is not set up on the Pentecost server yet" : $"CurseForge answered {code}";
            throw new HttpRequestException(why, null, r.StatusCode);
        }
    }

    static long Long(JsonNode? n) { try { return n?.GetValue<long>() ?? 0; } catch { return 0; } }
    static bool Bool(JsonNode? n, bool fallback) { try { return n?.GetValue<bool>() ?? fallback; } catch { return fallback; } }
    static string Str(JsonNode? n) => n?.ToString() ?? "";

    static DateTime? When(JsonNode? n) =>
        DateTime.TryParse(n?.ToString(), CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out var d) ? d.ToLocalTime() : null;

    static bool For120(JsonObject f) => (f["gameVersions"] as JsonArray)?.Any(g => g?.ToString() == Minecraft) == true;

    static string? Sha1Of(JsonObject f)
    {
        var hash = (f["hashes"] as JsonArray)?.OfType<JsonObject>().FirstOrDefault(h => Long(h["algo"]) == 1);
        var value = hash?["value"]?.ToString();
        return value is { Length: 40 } ? value : null;
    }

    static string[] LoadersIn(JsonObject f)
    {
        var set = new List<string>();
        foreach (var g in (f["gameVersions"] as JsonArray) ?? new JsonArray())
        {
            var name = g?.ToString().ToLowerInvariant();
            if (name is "forge" or "fabric" or "quilt" or "neoforge" && !set.Contains(name)) set.Add(name);
        }
        return set.ToArray();
    }

    static CfMod Parse(JsonObject o)
    {
        var authors = (o["authors"] as JsonArray)?.OfType<JsonObject>().Select(a => Str(a["name"])).Where(x => x.Length > 0).ToArray() ?? Array.Empty<string>();
        var logo = o["logo"] as JsonObject;
        var icon = Str(logo?["thumbnailUrl"]);
        if (icon.Length == 0) icon = Str(logo?["url"]);
        var shots = (o["screenshots"] as JsonArray)?.OfType<JsonObject>().Select(s => Str(s["url"])).Where(u => SafeImage(u) != null).ToArray() ?? Array.Empty<string>();
        var cats = (o["categories"] as JsonArray)?.OfType<JsonObject>().Select(c => Str(c["name"])).Where(x => x.Length > 0).ToArray() ?? Array.Empty<string>();
        var latest = (o["latestFiles"] as JsonArray)?.OfType<JsonObject>().Where(For120).ToList() ?? new List<JsonObject>();

        var loaders = new List<string>();
        foreach (var ix in (o["latestFilesIndexes"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
            if (Str(ix["gameVersion"]) == Minecraft && LoaderName((int)Long(ix["modLoader"])) is { } n && !loaders.Contains(n)) loaders.Add(n);
        foreach (var f in latest)
            foreach (var n in LoadersIn(f))
                if (!loaders.Contains(n)) loaders.Add(n);

        var sha1s = new List<string>();
        foreach (var f in latest)
            if (Sha1Of(f) is { } s && !sha1s.Contains(s)) sha1s.Add(s);

        var id = Long(o["id"]).ToString(CultureInfo.InvariantCulture);
        return new CfMod(id, Str(o["slug"]), Str(o["name"]), authors.FirstOrDefault() ?? "", authors, Str(o["summary"]),
            icon.Length > 0 ? icon : null, Long(o["downloadCount"]), Long(o["thumbsUpCount"]), loaders.ToArray(), cats, shots,
            When(o["dateModified"]), TypeOf((int)Long(o["classId"])), sha1s.ToArray());
    }

    static int Collect(JsonNode? root, List<CfMod> into)
    {
        if (root is not JsonObject o) return 0;
        foreach (var m in (o["data"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
            if (Long(m["id"]) > 0) into.Add(Parse(m));
        return (int)Long((o["pagination"] as JsonObject)?["totalCount"]);
    }

    // CurseForge pages hold 50 at most and Browse pages hold 100, so this reads two pages at once.
    public static async Task<(List<CfMod> Mods, int Total)> Search(string query, string type, string loader, IReadOnlyList<int> categories,
        string sort, int offset, CancellationToken ct)
    {
        if (offset >= 10000) return (new List<CfMod>(), 0);
        var field = sort switch { "downloads" => 6, "updated" => 3, "newest" => 11, _ => query.Length > 0 ? 1 : 2 };
        var code = LoaderCode(loader);

        string Url(int index)
        {
            var u = $"/mods/search?gameId={GameId}&classId={ClassOf(type)}&gameVersion={Minecraft}&pageSize=50&index={index}&sortField={field}&sortOrder=desc";
            if (query.Length > 0) u += "&searchFilter=" + Uri.EscapeDataString(query);
            if (type == "mod" && loader != "All" && code > 0) u += "&modLoaderType=" + code;
            if (categories.Count == 1) u += "&categoryId=" + categories[0];
            else if (categories.Count > 1) u += "&categoryIds=" + Uri.EscapeDataString("[" + string.Join(",", categories) + "]");
            return u;
        }

        var mods = new List<CfMod>();
        var firstTask = Get(Url(offset), ct);
        Task<JsonNode?>? secondTask = offset + 50 < 10000 ? Get(Url(offset + 50), ct) : null;
        var total = Collect(await firstTask, mods);
        if (secondTask != null)
        {
            try { Collect(await secondTask, mods); }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException) { AppLog.Write("curseforge page 2: " + ex.Message); }
        }
        if (type == "resourcepack") mods.RemoveAll(IsDatapack);
        return (mods, Math.Min(total, 10000));
    }

    static bool IsDatapack(CfMod c)
    {
        var cats = c.Categories.Select(Catalog.Squash).ToList();
        return cats.Contains("datapacks") && !cats.Any(x => Regex.IsMatch(x, @"^\d+x"));
    }

    // For --cf-check: mods whose authors keep downloads on CurseForge's own site.
    public static async Task<List<string>> RestrictedSample(CancellationToken ct)
    {
        var found = new List<string>();
        for (var index = 0; index < 400 && found.Count < 8; index += 50)
        {
            var root = await Get($"/mods/search?gameId={GameId}&classId=6&gameVersion={Minecraft}&pageSize=50&index={index}&sortField=6&sortOrder=desc", ct) as JsonObject;
            foreach (var m in (root?["data"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
            {
                var files = (m["latestFiles"] as JsonArray)?.OfType<JsonObject>().Where(For120).ToList() ?? new List<JsonObject>();
                var blocked = !Bool(m["allowModDistribution"], true) || (files.Count > 0 && files.All(f => Str(f["downloadUrl"]).Length == 0));
                if (blocked)
                    found.Add($"{Str(m["name"])} (cf:{Long(m["id"])}), {Long(m["downloadCount"]):N0} downloads, allowModDistribution={Str(m["allowModDistribution"])}");
            }
        }
        return found;
    }

    // A handful of candidates for one mod, to find its twin when Modrinth showed it first.
    public static async Task<List<CfMod>> Find(string text, string type, bool bySlug, CancellationToken ct)
    {
        var u = $"/mods/search?gameId={GameId}&classId={ClassOf(type)}&gameVersion={Minecraft}&pageSize=20&sortField=2&sortOrder=desc";
        u += bySlug ? "&slug=" + Uri.EscapeDataString(text) : "&searchFilter=" + Uri.EscapeDataString(text);
        var mods = new List<CfMod>();
        Collect(await Get(u, ct), mods);
        return mods;
    }

    public static async Task<(CfMod Mod, string Body)> Project(string modId, CancellationToken ct)
    {
        var id = Uri.EscapeDataString(modId);
        var modTask = Get($"/mods/{id}", ct);
        var descTask = Get($"/mods/{id}/description", ct);
        var o = (await modTask)?["data"] as JsonObject ?? throw new IOException("CurseForge gave an answer I do not understand");
        var body = "";
        try { body = HtmlToText(Str((await descTask)?["data"])); }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException) { AppLog.Write("curseforge description: " + ex.Message); }
        return (Parse(o), body);
    }

    public static async Task<List<VersionInfo>> Files(string modId, string? loader, CancellationToken ct)
    {
        var path = $"/mods/{Uri.EscapeDataString(modId)}/files?gameVersion={Minecraft}&pageSize=50";
        var code = LoaderCode(loader);
        if (code > 0) path += "&modLoaderType=" + code;
        var list = new List<VersionInfo>();
        var files = new List<JsonObject>();
        for (var index = 0; index < 150; index += 50)
        {
            if (await Get(path + "&index=" + index, ct) is not JsonObject root || root["data"] is not JsonArray arr) break;
            files.AddRange(arr.OfType<JsonObject>());
            var count = (int)Long((root["pagination"] as JsonObject)?["totalCount"]);
            if (arr.Count < 50 || index + 50 >= count) break;
        }
        foreach (var f in files)
        {
            var sha1 = Sha1Of(f);
            var name = Path.GetFileName(Str(f["fileName"]));
            if (sha1 == null || name.Length == 0 || !Bool(f["isAvailable"], true)) continue;

            // A blank link means the author does not allow downloads outside CurseForge.
            var url = Str(f["downloadUrl"]);
            if (!(Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Scheme == "https" && IsCdnHost(uri.Host))) url = "";

            var display = Str(f["displayName"]);
            var number = Regex.Replace(display, @"\.(jar|zip)$", "", RegexOptions.IgnoreCase);
            if (number.Length == 0) number = Path.GetFileNameWithoutExtension(name);
            var channel = Long(f["releaseType"]) switch { 2 => "beta", 3 => "alpha", _ => "release" };
            var requires = (f["dependencies"] as JsonArray)?.OfType<JsonObject>().Where(d => Long(d["relationType"]) == 3)
                .Select(d => "cf:" + Long(d["modId"]).ToString(CultureInfo.InvariantCulture)).Where(x => x.Length > 3).Distinct().ToArray() ?? Array.Empty<string>();

            list.Add(new VersionInfo(Long(f["id"]).ToString(CultureInfo.InvariantCulture), display, number, channel, LoadersIn(f),
                When(f["fileDate"]) ?? DateTime.MinValue, Long(f["downloadCount"]), name, url, sha1, Long(f["fileLength"]), requires,
                "", "C", "https://www.curseforge.com/projects/" + modId));
        }
        return list;
    }

    public static async Task<List<(int Id, string Name, string Type)>> Categories(CancellationToken ct)
    {
        if (categoryCache != null) return categoryCache;
        var list = new List<(int Id, string Name, string Type)>();
        if (await Get($"/categories?gameId={GameId}", ct) is JsonObject root && root["data"] is JsonArray arr)
            foreach (var o in arr.OfType<JsonObject>())
            {
                if (Bool(o["isClass"], false)) continue;
                var cls = (int)Long(o["classId"]);
                if (cls is not (6 or 12 or 6552 or 4471)) continue;
                list.Add(((int)Long(o["id"]), Str(o["name"]), TypeOf(cls)));
            }
        return categoryCache = list;
    }

    static readonly Dictionary<string, string> CategoryAlias = new()
    {
        ["adventure"] = "adventureandrpg", ["equipment"] = "armortoolsandweapons", ["decoration"] = "cosmetic",
        ["library"] = "apiandlibrary", ["utility"] = "utilityqol", ["optimization"] = "performance", ["transportation"] = "playertransport",
        ["512x"] = "512xandhigher", ["modded"] = "modsupport", ["fonts"] = "fontpacks", ["realistic"] = "photorealistic", ["vanillalike"] = "vanilla",
    };

    // Modrinth category names to CurseForge ids. Null when a chosen category has no twin, so Browse can say why CurseForge is left out.
    public static async Task<List<int>?> MapCategories(string type, IEnumerable<string> names, CancellationToken ct)
    {
        var chosen = names.ToList();
        var ids = new List<int>();
        if (chosen.Count == 0) return ids;
        var all = await Categories(ct);
        foreach (var n in chosen)
        {
            var key = Catalog.Squash(n);
            var m = all.FirstOrDefault(c => c.Type == type && Catalog.Squash(c.Name) == key);
            if (m.Name == null && CategoryAlias.TryGetValue(key, out var alias))
                m = all.FirstOrDefault(c => c.Type == type && Catalog.Squash(c.Name) == alias);
            if (m.Name == null) return null;
            ids.Add(m.Id);
        }
        return ids;
    }

    // True when the mod is in every chosen category (Modrinth names, same aliases as MapCategories).
    public static bool InAll(CfMod c, IEnumerable<string> names)
    {
        var have = c.Categories.Select(Catalog.Squash).ToHashSet();
        foreach (var n in names)
        {
            var key = Catalog.Squash(n);
            if (!have.Contains(key) && !(CategoryAlias.TryGetValue(key, out var alias) && have.Contains(alias))) return false;
        }
        return true;
    }

    // For --cf-check: one request, no retries, returns what the server answered.
    public static async Task<string> Probe(string path, CancellationToken ct)
    {
        var session = Online.LoadSession();
        if (session == null) return "no session";
        using var req = new HttpRequestMessage(HttpMethod.Get, Online.Server + "/cf/v1" + path);
        req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", session.Token);
        using var r = await Http.SendAsync(req, ct);
        var body = await r.Content.ReadAsStringAsync(ct);
        return $"{(int)r.StatusCode} " + (r.IsSuccessStatusCode ? body.Length + " bytes" : body.Replace("\n", " ")[..Math.Min(260, body.Length)]);
    }

    public static async Task<string> Test(CancellationToken ct)
    {
        if (!SignedIn) return "Sign in to Pentecost online first.";
        try { await Get($"/games/{GameId}", ct); return "CurseForge is working."; }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException) { return ex.Message; }
    }

    static string HtmlToText(string html)
    {
        var s = Regex.Replace(html, @"<(script|style)[^>]*>.*?</\1>", "", RegexOptions.Singleline | RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"<\s*li[^>]*>", "- ", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"<\s*br\s*/?>|</\s*(p|div|h[1-6]|li|tr|ul|ol)\s*>", "\n", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, "<[^>]+>", "");
        s = System.Net.WebUtility.HtmlDecode(s);
        s = Regex.Replace(s, @"[ \t]+\n", "\n");
        s = Regex.Replace(s, @"\n{3,}", "\n\n");
        return s.Trim();
    }
}
