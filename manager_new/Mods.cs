using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pentecost.Manager;

public sealed record ModUpdate(string FileName, string Url, string Sha1, string VersionNumber);

public sealed class ModEntry
{
    public string File = "", Name = "", Id = "", Version = "", Loader = "Unknown", Authors = "", Summary = "";
    public bool Enabled;
    public long Size;
    public DateTime Stamp;
    public string? Sha1;
    /// <summary>The mod's own picture, taken out of its jar. Every mod ships one, so this works with no internet.</summary>
    public byte[]? IconData;
    /// <summary>Kept once it has been decoded, so scrolling does not decode it again.</summary>
    public object? IconCache;
    /// <summary>Filled by the Modrinth lookup.</summary>
    public string? ProjectId, IconUrl, Desc, FileUrl, Sha512;
    public ModUpdate? Update;
    public bool Looked;
    public List<string> Requires = new();
    /// <summary>Mod ids this jar says it needs, read out of the jar itself.</summary>
    public List<string> Needs = new();

    public string BaseName
    {
        get
        {
            var n = Path.GetFileName(File);
            return n.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? n[..^9] : n;
        }
    }
}

/// <summary>Reads the jars in run\mods. A jar renamed to .jar.disabled is a mod that is switched off.</summary>
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
                e.Enabled = on;
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
                    // "icon" is either a path or a map from size to path; the biggest one wins.
                    icon = o["icon"] switch
                    {
                        JsonValue v2 => v2.ToString(),
                        JsonObject map => map.Select(kv => kv.Value?.ToString()).LastOrDefault(x => !string.IsNullOrEmpty(x)),
                        _ => null,
                    };
                }
                e.Loader = forge != null ? "Both" : "Fabric";
            }
            else if (forge != null)
            {
                using var s = new StreamReader(forge.Open());
                var t = s.ReadToEnd();
                e.Loader = "Forge";
                e.Id = Match(t, "modId\\s*=\\s*\"([^\"]+)\"");
                var dn = Match(t, "displayName\\s*=\\s*\"([^\"]+)\"");
                if (dn.Length > 0 && !dn.Contains('$')) e.Name = dn;
                var ver = Match(t, "(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
                if (ver.Length > 0 && !ver.Contains('$')) e.Version = ver;
                e.Authors = Match(t, "authors\\s*=\\s*\"([^\"]+)\"");
                var desc = Match(t, "description\\s*=\\s*'''(.*?)'''");
                if (desc.Length == 0) desc = Match(t, "description\\s*=\\s*\"([^\"]*)\"");
                e.Summary = Tidy(desc);
                icon = Match(t, "logoFile\\s*=\\s*\"([^\"]+)\"");
                foreach (System.Text.RegularExpressions.Match d in Regex.Matches(t, "modId\\s*=\\s*\"([^\"]+)\""))
                    if (Wanted(d.Groups[1].Value) && d.Groups[1].Value != e.Id && !e.Needs.Contains(d.Groups[1].Value)) e.Needs.Add(d.Groups[1].Value);
            }
            else if (quilt != null)
            {
                using var s = new StreamReader(quilt.Open());
                if (JsonNode.Parse(s.ReadToEnd()) is JsonObject o && o["quilt_loader"] is JsonObject q)
                {
                    e.Id = q["id"]?.ToString() ?? "";
                    if (q["version"]?.ToString() is { Length: > 0 } v) e.Version = v;
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
            e.IconData = Picture(z, icon, e.Id);
        }
        catch (Exception ex) { AppLog.Write("Cannot read " + plain + ": " + ex.Message); }
        return e;
    }

    static string Match(string text, string pattern)
    {
        try { return Regex.Match(text, pattern, RegexOptions.Singleline).Groups[1].Value.Trim(); }
        catch { return ""; }
    }

    /// <summary>The loader and the game itself are always there, so they are not worth listing.</summary>
    static bool Wanted(string id) => id is not ("minecraft" or "java" or "forge" or "neoforge" or "fabricloader" or "fabric" or "fabric-api" or "quilt_loader" or "quilt_base");

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

    /// <summary>Pulls the mod's own picture out of its jar. Falls back to the places mods usually keep one.</summary>
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
                // Only a real PNG; anything else is not worth showing.
                if (bytes.Length > 8 && bytes[0] == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return bytes;
            }
            catch { }
        }
        return null;
    }

    public static void SetEnabled(ModEntry m, bool on)
    {
        if (on == m.Enabled) return;
        var target = on ? m.File[..^9] : m.File + ".disabled";
        System.IO.File.Move(m.File, target);
        m.File = target;
        m.Enabled = on;
    }

    /// <summary>Moves the jar to the Recycle Bin, so a wrong click can be undone.</summary>
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

    /// <summary>Copies jars into the mods folder. Returns the names added.</summary>
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
