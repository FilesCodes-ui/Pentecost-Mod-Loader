using System.Collections.Concurrent;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

// One catalog over Modrinth and CurseForge.
// A mod that lives on both sites is one entry. Its id is the Modrinth id, so everything keyed on that id
// (installed marks, the news feed, hidden and pinned mods) keeps working. A mod only on CurseForge uses "cf:<id>".
public static class Catalog
{
    static readonly object Gate = new();
    static Dictionary<string, string>? links;       // CurseForge id -> Modrinth id
    static Dictionary<string, long>? misses;        // "cf:<id>" or a Modrinth id -> when we last failed to find a twin
    static Dictionary<string, string>? installs;    // file name -> project id, for mods that only CurseForge knows
    static readonly ConcurrentDictionary<string, ProjectInfo> Projects = new();
    static readonly TimeSpan MissFor = TimeSpan.FromDays(3);

    static string LinkFile => Path.Combine(Paths.Data, "site-links.json");
    static string InstallFile => Path.Combine(Paths.Data, "site-installs.json");

    public static bool CurseOn => CurseForge.Ready;

    public static string Squash(string? s) => s == null ? "" : new string(s.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());

    public static string SiteName(string sites) => sites switch { "MC" => "Modrinth and CurseForge", "C" => "CurseForge", _ => "Modrinth" };
    public static string Short(string sites) => sites switch { "MC" => "MR+CF", "C" => "CF", _ => "MR" };

    static bool Soft(Exception ex, CancellationToken ct) =>
        ex is HttpRequestException or IOException or JsonException || (ex is TaskCanceledException && !ct.IsCancellationRequested);

    // ---------- remembered links ----------

    static void Load()
    {
        if (links != null) return;
        links = new();
        misses = new();
        try
        {
            if (File.Exists(LinkFile) && JsonNode.Parse(File.ReadAllText(LinkFile)) is JsonObject o)
            {
                foreach (var (k, v) in o["links"] as JsonObject ?? new JsonObject())
                    if (v?.ToString() is { Length: > 0 } s) links[k] = s;
                foreach (var (k, v) in o["misses"] as JsonObject ?? new JsonObject())
                    try { misses[k] = v!.GetValue<long>(); } catch { }
            }
        }
        catch (Exception ex) { AppLog.Write("site links: " + ex.Message); }
    }

    static void Save()
    {
        lock (Gate)
        {
            Load();
            try
            {
                var l = new JsonObject();
                foreach (var (k, v) in links!) l[k] = v;
                var m = new JsonObject();
                var cutoff = DateTime.UtcNow.Subtract(MissFor).Ticks;
                foreach (var (k, v) in misses!) if (v > cutoff) m[k] = v;
                File.WriteAllText(LinkFile, new JsonObject { ["links"] = l, ["misses"] = m }.ToJsonString());
            }
            catch (Exception ex) { AppLog.Write("site links: " + ex.Message); }
        }
    }

    public static string? Linked(string curseId) { lock (Gate) { Load(); return links!.TryGetValue(curseId, out var m) ? m : null; } }

    static string? CurseFor(string modrinthId)
    {
        lock (Gate)
        {
            Load();
            foreach (var (k, v) in links!) if (v == modrinthId) return k;
            return null;
        }
    }

    public static string? CurseIdOf(string id) => id.StartsWith("cf:", StringComparison.Ordinal) ? id[3..] : CurseFor(id);

    static void Link(string curseId, string modrinthId) { lock (Gate) { Load(); links![curseId] = modrinthId; misses!.Remove("cf:" + curseId); misses.Remove(modrinthId); } }
    static void Miss(string key) { lock (Gate) { Load(); misses![key] = DateTime.UtcNow.Ticks; } }
    static bool Missed(string key) { lock (Gate) { Load(); return misses!.TryGetValue(key, out var t) && t > DateTime.UtcNow.Subtract(MissFor).Ticks; } }

    public static IEnumerable<(string Label, string Url)> WebLinks(string id, string type)
    {
        if (!id.StartsWith("cf:", StringComparison.Ordinal)) yield return ("Modrinth", "https://modrinth.com/" + type + "/" + id);
        if (CurseIdOf(id) is { } cf) yield return ("CurseForge", "https://www.curseforge.com/projects/" + cf);
    }

    // ---------- installed marks for mods that Modrinth cannot identify ----------

    static void LoadInstalls()
    {
        if (installs != null) return;
        installs = new(StringComparer.OrdinalIgnoreCase);
        try
        {
            if (File.Exists(InstallFile) && JsonNode.Parse(File.ReadAllText(InstallFile)) is JsonObject o)
                foreach (var (k, v) in o)
                    if (v?.ToString() is { Length: > 0 } s) installs[k] = s;
        }
        catch (Exception ex) { AppLog.Write("site installs: " + ex.Message); }
    }

    // added[0] is the mod itself. The rest are the mods it needed.
    public static void Remember(string projectId, IEnumerable<string> added)
    {
        var file = added.FirstOrDefault();
        if (file == null) return;
        if (file.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase)) file = file[..^9];
        lock (Gate)
        {
            LoadInstalls();
            installs![file] = projectId;
            try
            {
                var o = new JsonObject();
                foreach (var (k, v) in installs.TakeLast(2000)) o[k] = v;
                File.WriteAllText(InstallFile, o.ToJsonString());
            }
            catch (Exception ex) { AppLog.Write("site installs: " + ex.Message); }
        }
    }

    public static List<(string File, string Id)> Installed()
    {
        lock (Gate) { LoadInstalls(); return installs!.Select(kv => (kv.Key, kv.Value)).ToList(); }
    }

    // ---------- search ----------

    sealed class Row { public Hit H = null!; public double Rank; }

    static DateTime? Later(DateTime? a, DateTime? b) => a == null ? b : b == null ? a : a > b ? a : b;

    // The same author usually writes the same one-line summary on both sites.
    public static bool Similar(string? a, string? b)
    {
        var x = Squash(a);
        var y = Squash(b);
        if (x.Length < 20 || y.Length < 20) return false;
        if (x == y || x.Contains(y) || y.Contains(x)) return true;
        static HashSet<string> Words(string? s) =>
            (s ?? "").ToLowerInvariant().Split(' ', ',', '.', '-', '_', '(', ')', '[', ']', '!', '?', ':', ';', '\'', '"', '/', '&', '\n').Where(w => w.Length > 2).ToHashSet();
        var wa = Words(a);
        var wb = Words(b);
        return wa.Count >= 4 && wb.Count >= 4 && wa.Intersect(wb).Count() / (double)wa.Union(wb).Count() >= 0.6;
    }

    static bool SameMod(CfMod c, Hit h)
    {
        var slug = Squash(c.Slug).Length > 0 && Squash(c.Slug) == Squash(h.Slug);
        var title = Squash(c.Title).Length > 0 && Squash(c.Title) == Squash(h.Title);
        var author = Squash(h.Author).Length > 0 && c.Authors.Any(a => Squash(a) == Squash(h.Author));
        return (slug && (title || author)) || (title && (author || Similar(c.Summary, h.Desc)));
    }

    static Hit Combine(Hit m, CfMod c) => m with
    {
        Downloads = m.Downloads + c.Downloads,
        Follows = m.Follows + c.Thumbs,
        Loaders = m.Loaders.Union(c.Loaders).ToArray(),
        Gallery = m.Gallery.Concat(c.Gallery).Distinct().ToArray(),
        Updated = Later(m.Updated, c.Updated),
        Sites = "MC",
        CurseId = c.Id,
    };

    static Hit FromCurse(CfMod c, string id, string sites) => new(id, c.Slug, c.Title, c.Author, c.Summary, c.Icon, c.Downloads, c.Thumbs,
        c.Loaders, c.Categories.Take(4).ToArray(), c.Type, c.Gallery, c.Updated, sites, c.Id);

    public static async Task<SearchPage> Search(string query, string type, string loader, List<string> categories, string sort, int offset, CancellationToken ct)
    {
        var mrTask = Modrinth.Search(query, type, loader, categories, sort, offset, ct);
        string? note = null;
        Task<(List<CfMod> Mods, int Total)>? cfTask = null;

        if (Settings.UseCurseForge)
        {
            if (!CurseForge.SignedIn) note = "sign in to Pentecost online to search CurseForge too";
            else if (type == "modpack") note = "modpacks come from Modrinth only";
            else
            {
                List<int>? ids = null;
                try { ids = await CurseForge.MapCategories(type, categories, ct); }
                catch (Exception ex) when (Soft(ex, ct)) { note = "CurseForge: " + ex.Message; }
                if (ids != null) cfTask = CurseForge.Search(query, type, loader, ids, sort, offset, ct);
                else if (note == null) note = "a chosen category is not on CurseForge, so only Modrinth is shown";
            }
        }

        SearchPage mr;
        var mrDown = false;
        try { mr = await mrTask; }
        catch (Exception ex) when (cfTask != null && Soft(ex, ct))
        {
            AppLog.Write("modrinth search: " + ex.Message);
            mr = new SearchPage(new List<Hit>(), 0);
            mrDown = true;
            note = "Modrinth did not answer, so only CurseForge is shown";
        }
        var cf = new List<CfMod>();
        var cfTotal = 0;
        if (cfTask != null)
        {
            try { (cf, cfTotal) = await cfTask; }
            catch (Exception ex) when (Soft(ex, ct))
            {
                AppLog.Write("curseforge search: " + ex.Message);
                if (mrDown) throw;
                note = "CurseForge: " + ex.Message;
            }
            // Modrinth needs every chosen category. CurseForge's list filter may accept any of them, so keep only mods in all.
            if (categories.Count > 1) cf = cf.Where(c => CurseForge.InAll(c, categories)).ToList();
        }

        // Which Modrinth mod is each CurseForge result? Remembered links first, then the exact same jar, then names.
        var target = new Dictionary<string, string>();
        var unresolved = new List<CfMod>();
        foreach (var c in cf)
        {
            if (Linked(c.Id) is { } known) target[c.Id] = known;
            else unresolved.Add(c);
        }

        if (unresolved.Count > 0)
        {
            try
            {
                var byHash = await Modrinth.ProjectsByHashes(unresolved.SelectMany(c => c.Sha1s), ct);
                foreach (var c in unresolved.ToList())
                    foreach (var s in c.Sha1s)
                        if (byHash.TryGetValue(s, out var pid) && !target.ContainsValue(pid))
                        {
                            target[c.Id] = pid;
                            Link(c.Id, pid);
                            unresolved.Remove(c);
                            break;
                        }
            }
            catch (Exception ex) when (Soft(ex, ct)) { AppLog.Write("hash match: " + ex.Message); }
        }

        foreach (var c in unresolved.ToList())
        {
            var twin = mr.Hits.FirstOrDefault(h => !target.ContainsValue(h.ProjectId) && SameMod(c, h));
            if (twin == null) continue;
            target[c.Id] = twin.ProjectId;
            Link(c.Id, twin.ProjectId);
            unresolved.Remove(c);
        }

        async Task<string?> Twin(CfMod c)
        {
            var cands = new List<(string Id, string Desc)>();
            var bySlug = await Modrinth.ProjectOrNull(c.Slug, ct);
            if (bySlug != null && bySlug.Type == type && Squash(bySlug.Title) == Squash(c.Title)) cands.Add((bySlug.Id, bySlug.Desc));
            if (cands.Count == 0)
            {
                var found = await Modrinth.Search(c.Title, type, "All", Array.Empty<string>(), "relevance", 0, ct);
                cands.AddRange(found.Hits.Where(h => Squash(h.Title) == Squash(c.Title)).Take(3).Select(h => (h.ProjectId, h.Desc)));
            }
            foreach (var (id, desc) in cands)
            {
                if (Similar(c.Summary, desc)) return id;
                var team = await Modrinth.TeamNames(id, ct);
                if (team.Any(m => c.Authors.Any(a => Squash(a) == Squash(m)))) return id;
            }
            return null;
        }

        // What is left may still be on Modrinth, just not on this page of Modrinth results.
        var pending = unresolved.Where(c => c.Slug.Length > 0 && !Missed("cf:" + c.Id)).Take(30).ToList();
        if (pending.Count > 0)
        {
            using var gate = new SemaphoreSlim(6);
            await Task.WhenAll(pending.Select(async c =>
            {
                await gate.WaitAsync(ct);
                try
                {
                    if (await Twin(c) is { } twinId)
                    {
                        lock (target) { if (!target.ContainsValue(twinId)) { target[c.Id] = twinId; Link(c.Id, twinId); } }
                        return;
                    }
                    Miss("cf:" + c.Id);
                }
                catch (Exception ex) when (Soft(ex, ct)) { AppLog.Write("twin lookup " + c.Slug + ": " + ex.Message); }
                finally { gate.Release(); }
            }));
        }
        if (cf.Count > 0) Save();

        // One row per mod.
        var rows = new List<Row>();
        var byId = new Dictionary<string, Row>();
        for (var i = 0; i < mr.Hits.Count; i++)
        {
            var r = new Row { H = mr.Hits[i], Rank = i };
            rows.Add(r);
            byId.TryAdd(r.H.ProjectId, r);
        }
        var combined = new HashSet<string>();
        for (var i = 0; i < cf.Count; i++)
        {
            var c = cf[i];
            if (target.TryGetValue(c.Id, out var mid))
            {
                if (!combined.Add(mid))
                {
                    rows.Add(new Row { H = FromCurse(c, "cf:" + c.Id, "C"), Rank = i });
                    continue;
                }
                if (byId.TryGetValue(mid, out var row)) { row.H = Combine(row.H, c); row.Rank = Math.Min(row.Rank, i); }
                else
                {
                    var r = new Row { H = FromCurse(c, mid, "MC"), Rank = i };
                    rows.Add(r);
                    byId[mid] = r;
                }
            }
            else rows.Add(new Row { H = FromCurse(c, "cf:" + c.Id, "C"), Rank = i });
        }

        IEnumerable<Row> ordered = sort switch
        {
            "downloads" => rows.OrderByDescending(r => r.H.Downloads),
            "follows" => rows.OrderByDescending(r => r.H.Follows),
            "updated" => rows.OrderByDescending(r => r.H.Updated ?? DateTime.MinValue),
            _ => rows.OrderBy(r => r.Rank),
        };
        return new SearchPage(ordered.Select(r => r.H).ToList(), Math.Max(mr.Total, cfTotal), note);
    }

    // ---------- one mod ----------

    static async Task<ProjectInfo> ModrinthProject(string id, CancellationToken ct)
    {
        if (Projects.TryGetValue(id, out var cached)) return cached;
        var p = await Modrinth.ProjectOnly(id, ct);
        Projects[id] = p;
        return p;
    }

    public static async Task<ProjectInfo> Project(string id, CancellationToken ct)
    {
        var onCf = id.StartsWith("cf:", StringComparison.Ordinal);
        var cf = CurseIdOf(id);
        var mr = onCf ? Linked(id[3..]) : id;

        Task<(CfMod Mod, string Body)>? cfTask = cf != null && CurseForge.Ready ? CurseForge.Project(cf, ct) : null;
        ProjectInfo? m = mr != null ? await ModrinthProject(mr, ct) : null;
        (CfMod Mod, string Body)? c = null;
        if (cfTask != null)
        {
            try { c = await cfTask; }
            catch (Exception ex) when (Soft(ex, ct)) { AppLog.Write("curseforge project: " + ex.Message); if (m == null) throw; }
        }

        if (m == null && c == null) throw new IOException("This mod is on CurseForge. Sign in to Pentecost online to open it.");
        if (c == null) return m!;
        var x = c.Value;
        if (m == null)
            return new ProjectInfo(id, x.Mod.Slug, x.Mod.Title, x.Mod.Summary, x.Body, x.Mod.Icon, x.Mod.Type, x.Mod.Downloads, x.Mod.Thumbs, x.Mod.Categories, x.Mod.Gallery);
        return m with
        {
            Downloads = m.Downloads + x.Mod.Downloads,
            Follows = m.Follows + x.Mod.Thumbs,
            IconUrl = m.IconUrl ?? x.Mod.Icon,
            Gallery = m.Gallery.Concat(x.Mod.Gallery).Distinct().ToArray(),
            Body = m.Body.Trim().Length > 0 ? m.Body : x.Body,
        };
    }

    // ---------- versions ----------

    // full: the detail page, which wants every version from both sites. Installs only reach for CurseForge
    // when Modrinth has nothing, which keeps dependency installs quick.
    public static async Task<List<VersionInfo>> Versions(string id, string? loader, CancellationToken ct, bool full = false)
    {
        var onCf = id.StartsWith("cf:", StringComparison.Ordinal);
        var cf = CurseIdOf(id);
        var mr = onCf ? Linked(id[3..]) : id;

        var a = new List<VersionInfo>();
        Exception? modrinthFailed = null;
        if (mr != null)
        {
            try { a = await Modrinth.VersionsOnly(mr, loader, ct); }
            catch (Exception ex) when (Soft(ex, ct)) { modrinthFailed = ex; }
        }

        var b = new List<VersionInfo>();
        Exception? curseFailed = null;
        if (CurseForge.Ready && (full || a.Count == 0))
        {
            try
            {
                if (cf == null && mr != null) cf = await Discover(mr, a, ct);
                if (cf != null) b = await CurseForge.Files(cf, loader, ct);
            }
            catch (Exception ex) when (Soft(ex, ct)) { AppLog.Write("curseforge versions: " + ex.Message); curseFailed = ex; }
        }
        else if (mr == null) throw new IOException("This mod is on CurseForge. Sign in to Pentecost online to see its versions.");

        if (a.Count == 0 && b.Count == 0)
        {
            if (modrinthFailed != null) throw modrinthFailed;
            if (mr == null && curseFailed != null) throw curseFailed;
        }
        return Merge(a, b);
    }

    // The same jar on both sites is one version. Modrinth stays the download source because it needs no key and has no download limits.
    static List<VersionInfo> Merge(List<VersionInfo> modrinth, List<VersionInfo> curse)
    {
        if (curse.Count == 0) return modrinth;
        var all = new List<VersionInfo>(modrinth);
        foreach (var c in curse)
        {
            var i = all.FindIndex(x => x.Sha1.Equals(c.Sha1, StringComparison.OrdinalIgnoreCase)
                || (x.FileName.Equals(c.FileName, StringComparison.OrdinalIgnoreCase) && x.Size == c.Size));
            if (i >= 0) all[i] = all[i] with { Sites = "MC", Web = c.Web };
            else all.Add(c);
        }
        return all.OrderByDescending(v => v.Date).ToList();
    }

    // Finds the CurseForge twin of a Modrinth mod. A matching jar proves it. Otherwise the names must match and so must an author.
    static async Task<string?> Discover(string modrinthId, List<VersionInfo> known, CancellationToken ct)
    {
        if (Missed(modrinthId)) return null;
        var p = await ModrinthProject(modrinthId, ct);
        if (p.Type == "modpack") return null;

        var shas = known.Select(v => v.Sha1).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var candidates = await CurseForge.Find(p.Slug, p.Type, true, ct);
        if (candidates.Count == 0) candidates = await CurseForge.Find(p.Title, p.Type, false, ct);

        string[]? team = null;
        foreach (var c in candidates)
        {
            if (Linked(c.Id) is { } other && other != modrinthId) continue;
            var sameTitle = Squash(c.Title) == Squash(p.Title);
            var sameSlug = Squash(c.Slug) == Squash(p.Slug);
            if (!sameTitle && !sameSlug) continue;
            var sameJar = c.Sha1s.Any(s => shas.Contains(s));
            var sameAuthor = false;
            if (!sameJar)
            {
                team ??= await Modrinth.TeamNames(modrinthId, ct);
                sameAuthor = c.Authors.Any(a => team.Any(t => Squash(t) == Squash(a))) || Similar(c.Summary, p.Desc);
            }
            if (!sameJar && !sameAuthor) continue;
            Link(c.Id, modrinthId);
            Save();
            return c.Id;
        }
        Miss(modrinthId);
        Save();
        return null;
    }
}
