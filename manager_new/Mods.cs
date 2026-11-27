using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pentecost.Manager;

public sealed record ModUpdate(string FileName, string Url, string Sha1, string VersionNumber, string Sha512 = "");

public sealed class ModEntry
{
    public string File = "", Name = "", Id = "", Version = "", Loader = "Unknown", Authors = "", Summary = "";
    public bool Enabled;
    public bool GameOff;
    public long Size;
    public DateTime Stamp;
    public string? Sha1;
    public byte[]? IconData;
    public object? IconCache;
    public string? ProjectId, IconUrl, Desc, FileUrl, Sha512;
    public ModUpdate? Update;
    public bool Looked;
    public List<string> Requires = new();
    public List<string> Needs = new();
    public List<string> Bundled = new();
    public List<string> Provides = new();

    public string BaseName
    {
        get
        {
            var n = Path.GetFileName(File);
            return n.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? n[..^9] : n;
        }
    }
}

public static class ModScan
{
    static readonly object Gate = new();
    static readonly Dictionary<string, ModEntry> Cache = new(StringComparer.OrdinalIgnoreCase);

    public static List<ModEntry> Scan()
    {
        var list = new List<ModEntry>();
        if (!Directory.Exists(Paths.Mods)) return list;
        lock (Gate)
        {
            string[] files;
            try { files = Directory.GetFiles(Paths.Mods); }
            catch { return list; }
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var gameOff = GameChoices.ModsOff();
            foreach (var f in files)
            {
                var on = f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase);
                var off = f.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase);
                if (!on && !off) continue;
                var info = new FileInfo(f);
                var key = (off ? f[..^9] : f).ToLowerInvariant();
                seen.Add(key);
                if (!Cache.TryGetValue(key, out var e) || e.Size != info.Length || e.Stamp != info.LastWriteTimeUtc)
                {
                    e = Read(f);
                    e.Size = info.Length;
                    e.Stamp = info.LastWriteTimeUtc;
                    Cache[key] = e;
                }
                e.File = f;
                e.GameOff = e.Id.Length > 0 && gameOff.Contains(GameChoices.Norm(e.Id));
                e.Enabled = on && !e.GameOff;
                list.Add(e);
            }
            foreach (var gone in Cache.Keys.Where(k => !seen.Contains(k)).ToList()) Cache.Remove(gone);
        }
        return list.OrderBy(m => m.Name, StringComparer.OrdinalIgnoreCase).ToList();
    }

    static ModEntry Read(string f)
    {
        var plain = Path.GetFileName(f);
        if (plain.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase)) plain = plain[..^9];
        var e = new ModEntry { Name = Path.GetFileNameWithoutExtension(plain) };
        try
        {
            using var z = ZipFile.OpenRead(f);
            ReadBundled(z, e);
            var fab = z.GetEntry("fabric.mod.json");
            var quilt = z.GetEntry("quilt.mod.json");
            var forge = z.GetEntry("META-INF/mods.toml") ?? z.GetEntry("META-INF/neoforge.mods.toml");
            string? icon = null;
            if (fab != null)
            {
                using var s = new StreamReader(fab.Open());
                if (JsonNode.Parse(s.ReadToEnd()) is JsonObject o)
                {
                    e.Id = o["id"]?.ToString() ?? "";
                    if (o["name"]?.ToString() is { Length: > 0 } n && !n.Contains('$')) e.Name = n;
                    if (o["version"]?.ToString() is { Length: > 0 } v && !v.Contains('$')) e.Version = v;
                    if (o["description"]?.ToString() is { Length: > 0 } d) e.Summary = Tidy(d);
                    e.Authors = Names(o["authors"]);
                    if (o["depends"] is JsonObject dep)
                        foreach (var kv in dep) if (Wanted(kv.Key)) e.Needs.Add(kv.Key);
                    if (o["provides"] is JsonArray fprov)
                        foreach (var pv in fprov) if (pv?.ToString() is { Length: > 0 } pid) e.Provides.Add(pid);
                    icon = o["icon"] switch
                    {
                        JsonValue v2 => v2.ToString(),
                        JsonObject map => map.Select(kv => kv.Value?.ToString()).LastOrDefault(x => !string.IsNullOrEmpty(x)),
                        _ => null,
                    };
                }
                e.Loader = forge != null ? "Both" : "Fabric";
                if (forge != null)
                {
                    using var fs = new StreamReader(forge.Open());
                    var ft = fs.ReadToEnd();
                    foreach (System.Text.RegularExpressions.Match own2 in Regex.Matches(ft, @"\[\[mods\]\](.*?)(?=\r?\n\s*\[\[|\z)", RegexOptions.Singleline))
                    {
                        var oid = Match(own2.Groups[1].Value, "modId\\s*=\\s*\"([^\"]+)\"");
                        if (oid.Length > 0 && oid != e.Id && !e.Provides.Contains(oid)) e.Provides.Add(oid);
                    }
                }
                e.Needs.RemoveAll(n => e.Provides.Contains(n));
            }
            else if (forge != null)
            {
                using var s = new StreamReader(forge.Open());
                var t = s.ReadToEnd();
                e.Loader = "Forge";
                e.Id = Match(t, "modId\\s*=\\s*\"([^\"]+)\"");
                foreach (System.Text.RegularExpressions.Match own in Regex.Matches(t, @"\[\[mods\]\](.*?)(?=\r?\n\s*\[\[|\z)", RegexOptions.Singleline))
                {
                    var ownId = Match(own.Groups[1].Value, "modId\\s*=\\s*\"([^\"]+)\"");
                    if (ownId.Length > 0 && ownId != e.Id && !e.Provides.Contains(ownId)) e.Provides.Add(ownId);
                }
                var dn = Match(t, "displayName\\s*=\\s*\"([^\"]+)\"");
                if (dn.Length > 0 && !dn.Contains('$')) e.Name = dn;
                var ver = Match(t, "(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
                if (ver.Length > 0 && !ver.Contains('$')) e.Version = ver;
                e.Authors = Match(t, "authors\\s*=\\s*\"([^\"]+)\"");
                var desc = Match(t, "description\\s*=\\s*'''(.*?)'''");
                if (desc.Length == 0) desc = Match(t, "description\\s*=\\s*\"([^\"]*)\"");
                e.Summary = Tidy(desc);
                icon = Match(t, "logoFile\\s*=\\s*\"([^\"]+)\"");
                foreach (var fdep in ForgeRequired(t))
                    if (Wanted(fdep) && fdep != e.Id && !e.Provides.Contains(fdep) && !e.Needs.Contains(fdep)) e.Needs.Add(fdep);
            }
            else if (quilt != null)
            {
                using var s = new StreamReader(quilt.Open());
                if (JsonNode.Parse(s.ReadToEnd()) is JsonObject o && o["quilt_loader"] is JsonObject q)
                {
                    e.Id = q["id"]?.ToString() ?? "";
                    if (q["version"]?.ToString() is { Length: > 0 } v) e.Version = v;
                    if (q["depends"] is JsonArray qdeps)
                        foreach (var qd in qdeps)
                        {
                            var qid = qd is JsonObject qobj ? qobj["id"]?.ToString() : qd?.ToString();
                            var qopt = qd is JsonObject qobj2 && qobj2["optional"]?.ToString() == "true";
                            if (!qopt && !string.IsNullOrEmpty(qid) && Wanted(qid) && !e.Needs.Contains(qid)) e.Needs.Add(qid);
                        }
                    if (q["provides"] is JsonArray qprov)
                        foreach (var qp in qprov)
                            if ((qp is JsonObject qpo ? qpo["id"]?.ToString() : qp?.ToString()) is { Length: > 0 } qpid) e.Provides.Add(qpid);
                    if (q["metadata"] is JsonObject meta)
                    {
                        if (meta["name"]?.ToString() is { Length: > 0 } n) e.Name = n;
                        if (meta["description"]?.ToString() is { Length: > 0 } d) e.Summary = Tidy(d);
                        e.Authors = Names(meta["contributors"]);
                        icon = meta["icon"]?.ToString();
                    }
                }
                e.Loader = "Quilt";
            }
            e.Needs.RemoveAll(n => e.Bundled.Any(b => string.Equals(b.Replace('-', '_'), n.Replace('-', '_'), StringComparison.OrdinalIgnoreCase)));
            e.IconData = Picture(z, icon, e.Id);
        }
        catch (Exception ex) { AppLog.Write("Cannot read " + plain + ": " + ex.Message); }
        return e;
    }

    static void ReadBundled(ZipArchive z, ModEntry e)
    {
        foreach (var inner in z.Entries)
        {
            var n = inner.FullName;
            if (!(n.StartsWith("META-INF/jarjar/", StringComparison.OrdinalIgnoreCase) || n.StartsWith("META-INF/jars/", StringComparison.OrdinalIgnoreCase))) continue;
            if (!n.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || inner.Length > 60_000_000) continue;
            try
            {
                using var s = inner.Open();
                using var ms = new MemoryStream();
                s.CopyTo(ms);
                ms.Position = 0;
                using var nz = new ZipArchive(ms);
                if (nz.GetEntry("fabric.mod.json") is { } fe)
                {
                    using var r = new StreamReader(fe.Open());
                    if (JsonNode.Parse(r.ReadToEnd()) is JsonObject o && o["id"]?.ToString() is { Length: > 0 } id) e.Bundled.Add(id);
                }
                if ((nz.GetEntry("META-INF/mods.toml") ?? nz.GetEntry("META-INF/neoforge.mods.toml")) is { } te)
                {
                    using var r = new StreamReader(te.Open());
                    foreach (System.Text.RegularExpressions.Match m in Regex.Matches(r.ReadToEnd(), "\\[\\[mods\\]\\][^\\[]*?modId\\s*=\\s*\"([^\"]+)\""))
                        e.Bundled.Add(m.Groups[1].Value);
                }
            }
            catch { }
        }
    }

    static string Match(string text, string pattern)
    {
        try { return Regex.Match(text, pattern, RegexOptions.Singleline).Groups[1].Value.Trim(); }
        catch { return ""; }
    }

    static bool Wanted(string id) => !DepMatch.Platform(id);

    static IEnumerable<string> ForgeRequired(string toml)
    {
        foreach (System.Text.RegularExpressions.Match block in Regex.Matches(toml, @"\[\[dependencies\.[^\]]+\]\](.*?)(?=\r?\n\s*\[\[|\z)", RegexOptions.Singleline))
        {
            var body = block.Groups[1].Value;
            var id = Match(body, "modId\\s*=\\s*\"([^\"]+)\"");
            if (id.Length == 0) continue;
            var optional = Regex.IsMatch(body, @"mandatory\s*=\s*false", RegexOptions.IgnoreCase)
                || Regex.IsMatch(body, "type\\s*=\\s*\"(optional|discouraged|incompatible)\"", RegexOptions.IgnoreCase);
            var serverOnly = Regex.IsMatch(body, "side\\s*=\\s*\"SERVER\"", RegexOptions.IgnoreCase);
            if (!optional && !serverOnly) yield return id;
        }
    }

    static string Tidy(string s) => Regex.Replace(s, "\\s+", " ").Trim();

    static string Names(JsonNode? node)
    {
        if (node is not JsonArray arr) return "";
        var names = arr.Select(x => x switch
        {
            JsonObject o => o["name"]?.ToString(),
            null => null,
            _ => x.ToString(),
        }).Where(x => !string.IsNullOrWhiteSpace(x)).Select(x => x!.Trim()).Take(3).ToList();
        return string.Join(", ", names);
    }

    static byte[]? Picture(ZipArchive z, string? declared, string id)
    {
        var tries = new List<string>();
        if (!string.IsNullOrWhiteSpace(declared)) tries.Add(declared!.Replace('\\', '/').TrimStart('/'));
        if (id.Length > 0)
        {
            tries.Add($"assets/{id}/icon.png");
            tries.Add($"assets/{id}/logo.png");
            tries.Add($"assets/{id}/textures/icon.png");
        }
        tries.Add("icon.png");
        tries.Add("logo.png");
        tries.Add("pack.png");

        foreach (var want in tries)
        {
            var entry = z.GetEntry(want)
                ?? z.Entries.FirstOrDefault(x => string.Equals(x.FullName, want, StringComparison.OrdinalIgnoreCase));
            if (entry == null && !want.Contains('/'))
                entry = z.Entries.FirstOrDefault(x => string.Equals(Path.GetFileName(x.FullName), want, StringComparison.OrdinalIgnoreCase)
                    && x.FullName.Count(c => c == '/') <= 1);
            if (entry == null || entry.Length == 0 || entry.Length > 3_000_000) continue;
            try
            {
                using var s = entry.Open();
                using var ms = new MemoryStream();
                s.CopyTo(ms);
                var bytes = ms.ToArray();
                if (bytes.Length > 8 && bytes[0] == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return bytes;
            }
            catch { }
        }
        return null;
    }

    public static Func<bool>? GameRunning;

    public static void SetEnabled(ModEntry m, bool on)
    {
        if (on == m.Enabled) return;
        if (on && m.GameOff)
        {
            if (GameRunning?.Invoke() == true) throw new IOException("The game keeps its own copy of this switch while it runs.");
            GameChoices.TurnOn(m.Id);
            m.GameOff = false;
        }
        var fileOn = !m.File.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase);
        if (on != fileOn)
        {
            var target = on ? m.File[..^9] : m.File + ".disabled";
            System.IO.File.Move(m.File, target);
            m.File = target;
        }
        m.Enabled = on;
    }

    public static void Delete(ModEntry m)
    {
        Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile(m.File,
            Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs, Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
        lock (Gate) Cache.Remove((m.File.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? m.File[..^9] : m.File).ToLowerInvariant());
    }

    public static string Sha1Of(ModEntry m)
    {
        if (m.Sha1 != null) return m.Sha1;
        using var s = System.IO.File.OpenRead(m.File);
        m.Sha1 = Convert.ToHexString(SHA1.HashData(s)).ToLowerInvariant();
        return m.Sha1;
    }

    public static List<string> AddFiles(IEnumerable<string> paths)
    {
        var added = new List<string>();
        Directory.CreateDirectory(Paths.Mods);
        foreach (var p in paths)
        {
            if (!p.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || !System.IO.File.Exists(p)) continue;
            var name = Path.GetFileName(p);
            var dest = Path.Combine(Paths.Mods, name);
            if (System.IO.File.Exists(dest) || System.IO.File.Exists(dest + ".disabled")) continue;
            System.IO.File.Copy(p, dest);
            added.Add(name);
        }
        return added;
    }
}

public static class GameChoices
{
    static DateTime stamp;
    static long size = -1;
    static HashSet<string> off = new(StringComparer.Ordinal);

    static readonly System.Text.Json.JsonSerializerOptions Pretty = new()
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static string Norm(string id) => id.ToLowerInvariant().Replace('-', '_');

    public static HashSet<string> ModsOff()
    {
        try
        {
            var info = new FileInfo(Paths.Choices);
            if (!info.Exists)
            {
                stamp = DateTime.MinValue;
                size = 0;
                off = new(StringComparer.Ordinal);
                return off;
            }
            if (info.LastWriteTimeUtc == stamp && info.Length == size) return off;
            var set = new HashSet<string>(StringComparer.Ordinal);
            if (JsonNode.Parse(System.IO.File.ReadAllText(Paths.Choices)) is JsonObject root && root["modsOff"] is JsonArray list)
                foreach (var item in list)
                    if (item?.ToString() is { Length: > 0 } s) set.Add(Norm(s));
            off = set;
            stamp = info.LastWriteTimeUtc;
            size = info.Length;
        }
        catch (Exception ex) { AppLog.Write("Cannot read the game's choices: " + ex.Message); }
        return off;
    }

    public static bool Changed()
    {
        try
        {
            var info = new FileInfo(Paths.Choices);
            return info.Exists ? info.LastWriteTimeUtc != stamp || info.Length != size : size != 0 && size != -1;
        }
        catch { return false; }
    }

    public static void TurnOn(string id)
    {
        var path = Paths.Choices;
        if (!System.IO.File.Exists(path)) return;
        var root = JsonNode.Parse(System.IO.File.ReadAllText(path)) as JsonObject
            ?? throw new IOException("The game's choices file is not in the format I expected. It was not changed.");
        if (root["modsOff"] is not JsonArray list) return;
        var want = Norm(id);
        for (var i = list.Count - 1; i >= 0; i--)
            if (Norm(list[i]?.ToString() ?? "") == want) list.RemoveAt(i);
        var tmp = path + ".pentecost-tmp";
        System.IO.File.WriteAllText(tmp, root.ToJsonString(Pretty), new System.Text.UTF8Encoding(false));
        System.IO.File.Move(tmp, path, true);
    }
}

public static class DepMatch
{
    static string Flat(string id)
    {
        var i = id.IndexOf(':');
        if (i > 0 && i < id.Length - 1) id = id[(i + 1)..];
        return Regex.Replace(id.ToLowerInvariant(), "[^a-z0-9]", "");
    }

    static string Canon(string flat) => flat == "clothconfig2" ? "clothconfig" : flat;

    static string Base(string flat)
    {
        var b = Regex.Replace(flat, "\\d+$", "");
        return b.Length >= 5 ? b : flat;
    }

    /// <summary>Loader and Fabric API plumbing that Pentecost itself supplies. Never shown as a missing mod.</summary>
    public static bool Platform(string id)
    {
        var d = id.Trim().ToLowerInvariant();
        var i = d.IndexOf(':');
        if (i > 0 && i < d.Length - 1) d = d[(i + 1)..];
        return d is "minecraft" or "java" or "forge" or "neoforge" or "fabricloader" or "fabric" or "fabric_api"
                or "quilt_loader" or "quilt_base" or "quilted_fabric_api" or "qsl" or "mixinextras"
            || Regex.IsMatch(d, "^fabric[-_].+[-_]v\\d+$") || Regex.IsMatch(d, "^fabric[-_]api.*")
            || d.StartsWith("quilt_") || d.StartsWith("org.quiltmc");
    }

    static IEnumerable<string> Names(ModEntry m)
    {
        if (m.Id.Length > 0) yield return m.Id;
        foreach (var p in m.Provides) yield return p;
        foreach (var b in m.Bundled) yield return b;
    }

    /// <summary>True when this mod is what the dependency id points at: same id, provided id, bundled id, or the same library under a different loader's id.</summary>
    public static bool Provides(ModEntry m, string need)
    {
        var want = Canon(Flat(need));
        var wantBase = Base(want);
        foreach (var n in Names(m))
        {
            var have = Canon(Flat(n));
            if (have == want || Base(have) == wantBase) return true;
        }
        return false;
    }

    public static ModEntry? Find(IEnumerable<ModEntry> mods, string need)
    {
        var list = mods as IList<ModEntry> ?? mods.ToList();
        var want = Canon(Flat(need));
        foreach (var m in list)
            foreach (var n in Names(m))
                if (Canon(Flat(n)) == want) return m;
        return list.FirstOrDefault(m => Provides(m, need));
    }
}
