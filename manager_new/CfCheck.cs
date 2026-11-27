using System.Linq;

namespace Pentecost.Manager;

// --cf-check: runs the merged browser against the live sites and writes cf-check.txt in the Manager's data folder.
// It uses the signed-in Pentecost account the same way Browse does. No token or key is ever written.
public static class CfCheck
{
    public static async Task<List<string>> Run()
    {
        var log = new List<string>();
        var ct = CancellationToken.None;
        void L(string s = "") => log.Add(s);
        async Task Step(string name, Func<Task> body)
        {
            L(); L("== " + name + " ==");
            try { await body(); }
            catch (Exception ex) { L("STEP FAILED: " + ex.GetType().Name + ": " + ex.Message); }
        }

        if (Online.LoadSession() == null) { L("session: MISSING. Sign in to Pentecost online first."); return log; }
        L("session: found");
        L("server: " + await CurseForge.Test(ct));

        await Step("PROBE: which search URLs does CurseForge refuse?", async () =>
        {
            var b = "/mods/search?gameId=432&classId=6&gameVersion=1.20.1";
            var probes = new[]
            {
                b + "&pageSize=50&index=0&sortField=6&sortOrder=desc",
                b + "&pageSize=50&index=0&sortField=6&sortOrder=desc&nonce=" + Guid.NewGuid().ToString("N")[..8],
                b + "&pageSize=50&index=0&sortField=2&sortOrder=desc",
                b + "&pageSize=20&sortField=6&sortOrder=desc",
                b + "&pageSize=50&index=0&sortField=6&sortOrder=desc&searchFilter=magic",
                b + "&pageSize=50&index=0&sortField=1&sortOrder=desc&searchFilter=magic",
                "/mods/search?gameId=432&classId=6&pageSize=50&sortField=6&sortOrder=desc",
                "/mods/search?gameId=432&classId=6&gameVersion=1.20.1&pageSize=10&sortField=6&sortOrder=desc",
                "/mods/search?gameId=432&classId=6552&gameVersion=1.20.1&pageSize=50&index=0&sortField=6&sortOrder=desc",
            };
            foreach (var u in probes)
            {
                L(u.Replace("/mods/search?gameId=432&", "") + "\n    -> " + await CurseForge.Probe(u, ct));
                L("    again -> " + await CurseForge.Probe(u, ct));
            }
        });

        await Step("CATEGORIES (Modrinth name -> CurseForge)", async () =>
        {
            var cf = await CurseForge.Categories(ct);
            var mr = await Modrinth.Categories(ct);
            foreach (var type in new[] { "mod", "resourcepack", "shader" })
            {
                L("-- " + type);
                L("CurseForge has: " + string.Join(", ", cf.Where(c => c.Type == type).Select(c => c.Name)));
                foreach (var m in mr.Where(c => c.Type == type))
                {
                    var ids = await CurseForge.MapCategories(type, new[] { m.Name }, ct);
                    L($"  {m.Name,-22} {(ids == null ? "NO CURSEFORGE TWIN" : "ok " + string.Join(",", ids))}");
                }
            }
        });

        await Step("SORT ORDER (CurseForge side)", async () =>
        {
            foreach (var sort in new[] { "downloads", "updated", "newest", "follows" })
            {
                var (mods, total) = await CurseForge.Search("", "mod", "All", new List<int>(), sort, 0, ct);
                var down = true;
                var up = true;
                for (var i = 1; i < mods.Count; i++)
                {
                    if (mods[i].Downloads > mods[i - 1].Downloads) down = false;
                    if ((mods[i].Updated ?? DateTime.MinValue) > (mods[i - 1].Updated ?? DateTime.MinValue)) up = false;
                }
                L($"{sort}: {mods.Count} mods, total {total}; downloads descending={down}, updated descending={up}");
                L("   top: " + string.Join(" | ", mods.Take(5).Select(m => $"{m.Title} ({m.Downloads:N0}, {m.Updated:yyyy-MM-dd})")));
            }
            foreach (var q in new[] { "create", "jei", "just enough items", "farmers delight", "waystones" })
            {
                var (mods, _) = await CurseForge.Search(q, "mod", "All", new List<int>(), "relevance", 0, ct);
                L($"relevance '{q}': " + string.Join(" | ", mods.Take(5).Select(m => m.Title)));
            }
            foreach (var l in new[] { "Forge", "Fabric" })
            {
                var (mods, total) = await CurseForge.Search("", "mod", l, new List<int>(), "downloads", 0, ct);
                var wrong = mods.Count(m => m.Loaders.Length > 0 && !m.Loaders.Contains(l.ToLowerInvariant()));
                L($"loader {l}: {mods.Count} mods, total {total}, {wrong} rows that do not list {l}");
            }
        });

        var cfOnly = new List<Hit>();
        foreach (var (type, query) in new[] { ("mod", ""), ("mod", "create"), ("mod", "magic"), ("shader", ""), ("resourcepack", ""), ("modpack", "") })
        {
            await Step($"MERGED SEARCH {type} '{query}' by downloads", async () =>
            {
                var page = await Catalog.Search(query, type, "All", new List<string>(), "downloads", 0, ct);
                var hits = page.Hits;
                L($"{hits.Count} rows, total {page.Total}, note: {page.Note ?? "-"}");
                L($"sites: Modrinth {hits.Count(h => h.Sites == "M")}, CurseForge {hits.Count(h => h.Sites == "C")}, both {hits.Count(h => h.Sites == "MC")}");
                var sorted = true;
                for (var i = 1; i < hits.Count; i++) if (hits[i].Downloads > hits[i - 1].Downloads) sorted = false;
                L("sorted by downloads: " + sorted);
                foreach (var g in hits.GroupBy(h => Catalog.Squash(h.Title)).Where(g => g.Key.Length > 0 && g.Count() > 1))
                    L("DUPLICATE TITLE: " + string.Join("  /  ", g.Select(h => $"{h.Title} [{h.ProjectId}] {h.Sites}")));
                foreach (var g in hits.GroupBy(h => h.ProjectId).Where(g => g.Count() > 1))
                    L("DUPLICATE ID: " + g.Key);
                foreach (var h in hits.Where(h => h.Sites == "C").Take(12))
                {
                    var mr = await Modrinth.Search(h.Title, type, "All", Array.Empty<string>(), "relevance", 0, ct);
                    var same = mr.Hits.Take(15).FirstOrDefault(x => Catalog.Squash(x.Title) == Catalog.Squash(h.Title));
                    if (same != null) L($"POSSIBLE MISSED TWIN: {h.Title} (cf:{h.CurseId}, by {h.Author}) ~ Modrinth '{same.Slug}' by {same.Author}");
                    else
                    {
                        L($"CF-only: {h.Title} by {h.Author}, {h.Downloads:N0} downloads, cf:{h.CurseId}");
                        cfOnly.Add(h);
                    }
                }
            });
        }

        await Step("CATEGORY FILTERS (merged)", async () =>
        {
            foreach (var cats in new[] { new[] { "adventure" }, new[] { "magic" }, new[] { "adventure", "magic" }, new[] { "library" }, new[] { "technology", "utility" } })
            {
                var page = await Catalog.Search("", "mod", "All", cats.ToList(), "downloads", 0, ct);
                L($"[{string.Join("+", cats)}]: {page.Hits.Count} rows, total {page.Total}, CF-only {page.Hits.Count(h => h.Sites == "C")}, both {page.Hits.Count(h => h.Sites == "MC")}, note: {page.Note ?? "-"}");
            }
        });

        await Step("CF-ONLY CANDIDATES (their files)", async () =>
        {
            foreach (var h in cfOnly.Where(h => h.Type == "mod").Take(10))
            {
                var vs = await Catalog.Versions(h.ProjectId, null, ct, full: true);
                var open = vs.Count(v => v.Url.Length > 0);
                var top = vs.FirstOrDefault();
                L($"{h.Title} ({h.ProjectId}): {vs.Count} files, {open} downloadable, {vs.Count - open} only from CurseForge's site; newest {top?.FileName} ({top?.Date:yyyy-MM-dd}); needs: {(top == null || top.Requires.Length == 0 ? "-" : string.Join(",", top.Requires))}");
            }
        });

        await Step("VERSIONS OF A MOD ON BOTH SITES", async () =>
        {
            var page = await Catalog.Search("create", "mod", "All", new List<string>(), "downloads", 0, ct);
            var both = page.Hits.FirstOrDefault(h => h.Sites == "MC");
            if (both == null) { L("no mod on both sites in the first results"); return; }
            var vs = await Catalog.Versions(both.ProjectId, null, ct, full: true);
            L($"{both.Title}: {vs.Count} versions; Modrinth only {vs.Count(v => v.Sites == "M")}, both {vs.Count(v => v.Sites == "MC")}, CurseForge only {vs.Count(v => v.Sites == "C")}");
            foreach (var g in vs.GroupBy(v => v.FileName.ToLowerInvariant()).Where(g => g.Count() > 1))
                L("DUPLICATE VERSION FILE: " + g.Key);
        });

        await Step("RESTRICTED DOWNLOADS (authors who keep files on CurseForge)", async () =>
        {
            var list = await CurseForge.RestrictedSample(ct);
            L(list.Count == 0 ? "none among the top 400 mods" : string.Join(Environment.NewLine, list));
        });

        L();
        L("done");
        return log;
    }
}
