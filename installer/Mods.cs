using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pentecost.Installer;

public static class VideoMods
{
    static readonly string[] Projects = { "embeddium", "oculus" };
    static readonly HttpClient Http = Make();

    static HttpClient Make()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("Pentecost-Installer (https://github.com/FilesCodes-ui/Pentecost-Mod-Loader)");
        return c;
    }

    public static async Task<int> InstallAsync(string home, Action<double, string> report, CancellationToken ct)
    {
        var mods = Path.Combine(home, "run", "mods");
        Directory.CreateDirectory(mods);
        var failed = 0;
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < Projects.Length; i++)
        {
            report(i / (double)Projects.Length, Projects[i]);
            if (Has(mods, Projects[i]))
            {
                Log.Write($"{Projects[i]}: already installed, nothing to download");
                continue;
            }
            try { await InstallOne(Projects[i], mods, seen, 0, ct); }
            catch (OperationCanceledException) { throw; }
            catch (Exception ex)
            {
                failed++;
                Log.Write($"mod {Projects[i]} failed: {ex.Message}");
            }
        }
        report(1, "");
        return failed;
    }

    static async Task InstallOne(string project, string mods, HashSet<string> seen, int depth, CancellationToken ct)
    {
        if (!seen.Add(project) || depth > 3) return;
        var url = $"https://api.modrinth.com/v2/project/{project}/version?game_versions=%5B%221.20.1%22%5D&loaders=%5B%22forge%22%5D";
        var versions = (JsonNode.Parse(await Http.GetStringAsync(url, ct)) as JsonArray)?.OfType<JsonObject>().ToList()
            ?? throw new IOException("Modrinth gave an answer I do not understand");
        var version = versions.FirstOrDefault(v => v["version_type"]?.ToString() == "release") ?? versions.FirstOrDefault()
            ?? throw new IOException($"{project} has no version for Minecraft 1.20.1 on Forge");
        var files = (version["files"] as JsonArray)?.OfType<JsonObject>().ToList() ?? new();
        var file = files.FirstOrDefault(f => f["primary"]?.GetValue<bool>() == true) ?? files.FirstOrDefault()
            ?? throw new IOException($"{project} has no file");
        var name = Path.GetFileName(file["filename"]?.ToString() ?? "");
        var link = file["url"]?.ToString() ?? "";
        var sha1 = file["hashes"]?["sha1"]?.ToString() ?? "";
        if (!name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || sha1.Length != 40
            || !Uri.TryCreate(link, UriKind.Absolute, out var uri) || uri.Scheme != "https"
            || !(uri.Host.Equals("modrinth.com", StringComparison.OrdinalIgnoreCase) || uri.Host.EndsWith(".modrinth.com", StringComparison.OrdinalIgnoreCase)))
            throw new IOException($"{project}: the file entry is not one I will download");

        if (!AlreadyThere(mods, name))
        {
            var bytes = await Http.GetByteArrayAsync(uri, ct);
            if (!string.Equals(Convert.ToHexString(SHA1.HashData(bytes)), sha1, StringComparison.OrdinalIgnoreCase))
                throw new IOException($"{name} did not match its checksum");
            var sha512 = file["hashes"]?["sha512"]?.ToString();
            if (sha512 is { Length: 128 } && !string.Equals(Convert.ToHexString(SHA512.HashData(bytes)), sha512, StringComparison.OrdinalIgnoreCase))
                throw new IOException($"{name} did not match its checksum");
            var tmp = Path.Combine(mods, name + ".part");
            await File.WriteAllBytesAsync(tmp, bytes, ct);
            File.Move(tmp, Path.Combine(mods, name), true);
            Log.Write($"installed {name}");
        }
        else Log.Write($"{name}: a jar of this mod is already there");

        if (version["dependencies"] is JsonArray deps)
            foreach (var d in deps.OfType<JsonObject>())
                if (d["dependency_type"]?.ToString() == "required" && d["project_id"]?.ToString() is { Length: > 0 } id)
                    await InstallOne(id, mods, seen, depth + 1, ct);
    }

    public static (bool Embeddium, bool Oculus) Installed(string home)
    {
        var mods = Path.Combine(home, "run", "mods");
        return (Has(mods, "embeddium"), Has(mods, "oculus"));
    }

    static bool Has(string mods, string project)
    {
        if (!Directory.Exists(mods)) return false;
        var rx = new Regex("^" + Regex.Escape(project) + @"(?:[-_+ ](?:forge|mc))*(?:[-_+ ]?v?\d|\.jar$)", RegexOptions.IgnoreCase);
        return Directory.GetFiles(mods, "*.jar").Any(f => rx.IsMatch(Path.GetFileName(f)));
    }

    static string BaseName(string file)
    {
        var n = Path.GetFileNameWithoutExtension(file);
        var m = Regex.Match(n, @"^(.*?)[-_+ ]v?\d");
        return (m.Success ? m.Groups[1].Value : n).ToLowerInvariant();
    }

    static bool AlreadyThere(string mods, string name)
    {
        var b = BaseName(name);
        return Directory.GetFiles(mods, "*.jar").Any(f => BaseName(f) == b);
    }
}