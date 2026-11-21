using System.Diagnostics;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pentecost.Manager;

public sealed record ReleaseInfo(string Tag, string Url, string? InstallerUrl, string? InstallerName, long InstallerSize, string? InstallerSha256, string Notes,
    string? ManagerVersion = null, string? ManagerUrl = null, long ManagerSize = 0, string? ManagerSha256 = null, bool Pre = false, DateTime? Date = null)
{
    public string Version => Tag.TrimStart('v', 'V');
}

public static class Updates
{
    const string Repo = "FilesCodes-ui/Pentecost-Mod-Loader";
    const string DownloadPrefix = "https://github.com/" + Repo + "/releases/download/";

    public static async Task<ReleaseInfo?> Latest(CancellationToken ct = default)
    {
        var text = await Modrinth.Client.GetStringAsync($"https://api.github.com/repos/{Repo}/releases/latest", ct);
        return Parse(text);
    }

    public static async Task<List<ReleaseInfo>> All(CancellationToken ct = default)
    {
        var text = await Modrinth.Client.GetStringAsync($"https://api.github.com/repos/{Repo}/releases?per_page=30", ct);
        var list = new List<ReleaseInfo>();
        if (JsonNode.Parse(text) is JsonArray a)
            foreach (var o in a.OfType<JsonObject>())
            {
                if (o["draft"]?.GetValue<bool>() == true) continue;
                if (Parse(o) is { } r) list.Add(r);
            }
        return list;
    }

    public static ReleaseInfo? Parse(string text) => JsonNode.Parse(text) is JsonObject o ? Parse(o) : null;

    public static ReleaseInfo? Parse(JsonObject o)
    {
        var tag = o["tag_name"]?.ToString();
        var url = o["html_url"]?.ToString();
        if (tag == null || url == null) return null;
        string? aUrl = null, aName = null, aSha = null;
        long aSize = 0;
        string? mVer = null, mUrl = null, mSha = null;
        long mSize = 0;
        foreach (var a in (o["assets"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
        {
            var name = a["name"]?.ToString() ?? "";
            var dl = a["browser_download_url"]?.ToString();
            var size = a["size"]?.GetValue<long>() ?? 0;
            var digest = a["digest"]?.ToString();
            var sha = digest != null && digest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase) ? digest[7..] : null;
            if (aUrl == null && Regex.IsMatch(name, "^Pentecost-Installer-.*\\.exe$", RegexOptions.IgnoreCase))
            {
                aUrl = dl;
                aName = name;
                aSize = size;
                aSha = sha;
                continue;
            }
            var m = Regex.Match(name, "^Pentecost-Manager-Setup-(\\d+(?:\\.\\d+){1,3})\\.exe$", RegexOptions.IgnoreCase);
            if (m.Success && (mVer == null || Newer(m.Groups[1].Value, mVer)))
            {
                mVer = m.Groups[1].Value;
                mUrl = dl;
                mSize = size;
                mSha = sha;
            }
        }
        var notes = (o["body"]?.ToString() ?? "").Trim();
        if (notes.Length > 600) notes = notes[..600] + "...";
        return new ReleaseInfo(tag, url, aUrl, aName, aSize, aSha, notes, mVer, mUrl, mSize, mSha, o["prerelease"]?.GetValue<bool>() == true, DateTime.TryParse(o["published_at"]?.ToString(), out var when) ? when : null);
    }

    public static bool Newer(string a, string b)
    {
        static int[] P(string s) => Regex.Matches(s, "\\d+").Select(m => int.Parse(m.Value)).ToArray();
        int[] x = P(a), y = P(b);
        for (var i = 0; i < Math.Max(x.Length, y.Length); i++)
        {
            int xi = i < x.Length ? x[i] : 0, yi = i < y.Length ? y[i] : 0;
            if (xi != yi) return xi > yi;
        }
        return false;
    }

    public static bool ManagerNewer(ReleaseInfo? r) => r?.ManagerVersion != null && Newer(r.ManagerVersion, SelfInstall.Version);

    public static bool IsOfficialAsset(string? url) => url != null && url.StartsWith(DownloadPrefix, StringComparison.Ordinal);
}

public static class InstallService
{
    static string InstallerLog => Path.Combine(Path.GetTempPath(), "pentecost-installer.log");

    public static async Task<string?> Run(ReleaseInfo r, bool worlds, Action<string, double> progress, CancellationToken ct, bool videoMods = true)
    {
        if (r.InstallerUrl == null || r.InstallerName == null) return "That release has no installer file.";
        if (!Updates.IsOfficialAsset(r.InstallerUrl)) return "The installer link is not from the Pentecost release page, so it was not used.";
        if (GameRun.LauncherRunning()) return "The Minecraft Launcher is open. Close it completely, also from the system tray, then try again.";

        var dir = Path.Combine(Path.GetTempPath(), "pentecost-manager");
        Directory.CreateDirectory(dir);
        var exe = Path.Combine(dir, Path.GetFileName(r.InstallerName));
        try
        {
            progress("Downloading the installer", 0);
            using (var resp = await Modrinth.Client.GetAsync(r.InstallerUrl, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                resp.EnsureSuccessStatusCode();
                var total = resp.Content.Headers.ContentLength ?? r.InstallerSize;
                await using var src = await resp.Content.ReadAsStreamAsync(ct);
                await using var dst = File.Create(exe + ".part");
                var buf = new byte[81920];
                long done = 0;
                int n;
                while ((n = await src.ReadAsync(buf, ct)) > 0)
                {
                    await dst.WriteAsync(buf.AsMemory(0, n), ct);
                    done += n;
                    if (total > 0) progress($"Downloading the installer, {done / 1048576} of {total / 1048576} MB", 0.6 * done / total);
                }
            }
            var info = new FileInfo(exe + ".part");
            if (r.InstallerSize > 0 && info.Length != r.InstallerSize)
            {
                File.Delete(exe + ".part");
                return "The download was cut off. Try again.";
            }
            if (r.InstallerSha256 != null)
            {
                string actual;
                await using (var s = File.OpenRead(exe + ".part")) actual = Convert.ToHexString(await SHA256.HashDataAsync(s, ct));
                if (!actual.Equals(r.InstallerSha256, StringComparison.OrdinalIgnoreCase))
                {
                    File.Delete(exe + ".part");
                    return "The installer did not match its checksum, so it was not run.";
                }
            }
            File.Move(exe + ".part", exe, true);
        }
        catch (OperationCanceledException) { return "Cancelled."; }
        catch (Exception ex) { AppLog.Write("installer download: " + ex); return "Could not download the installer: " + ex.Message; }

        progress("Installing Pentecost", 0.65);
        var startOfLog = File.Exists(InstallerLog) ? new FileInfo(InstallerLog).Length : 0;
        var psi = new ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true };
        psi.ArgumentList.Add("--install");
        psi.ArgumentList.Add("--minecraft-dir");
        psi.ArgumentList.Add(Paths.McDir);
        if (!worlds) psi.ArgumentList.Add("--no-worlds");
        if (videoMods) psi.ArgumentList.Add("--video-mods");
        try
        {
            using var proc = Process.Start(psi) ?? throw new IOException("The installer did not start.");
            var wait = proc.WaitForExitAsync(ct);
            while (!wait.IsCompleted)
            {
                await Task.WhenAny(wait, Task.Delay(700, ct));
                progress("Installing Pentecost. " + LastLogLine(startOfLog), 0.7);
            }
            await wait;
            if (proc.ExitCode == 0) { progress("Done", 1); return null; }
            return "The installer stopped. " + FailureReason(startOfLog);
        }
        catch (OperationCanceledException) { return "Cancelled."; }
        catch (Exception ex) { AppLog.Write("installer run: " + ex); return "Could not run the installer: " + ex.Message; }
    }

    static IEnumerable<string> NewLogLines(long from)
    {
        try
        {
            using var fs = new FileStream(InstallerLog, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            if (fs.Length <= from) return Array.Empty<string>();
            fs.Seek(from, SeekOrigin.Begin);
            using var sr = new StreamReader(fs);
            return sr.ReadToEnd().Split('\n', StringSplitOptions.RemoveEmptyEntries).Select(l => l.Trim()).ToList();
        }
        catch { return Array.Empty<string>(); }
    }

    static string LastLogLine(long from) => NewLogLines(from).LastOrDefault() is { } l && l.Length > 20 ? l[20..] : "";

    static string FailureReason(long from)
    {
        var lines = NewLogLines(from).ToList();
        var fail = lines.LastOrDefault(l => l.Contains("failure", StringComparison.OrdinalIgnoreCase));
        if (fail != null) return Regex.Replace(fail.Length > 20 ? fail[20..] : fail, "^silent failure:\\s*", "");
        return lines.Count > 0 ? lines[^1] : "No details were written. Open the installer log in your temp folder.";
    }
}

public sealed record CrashInfo(string File, DateTime Time, string Title, string Cause, string Summary, ModEntry? Mod, string Broke = "", string Blamed = "", string BlamedFile = "");

public static class CrashScan
{
    static readonly HashSet<string> Ignore = new(StringComparer.OrdinalIgnoreCase)
        { "minecraft", "forge", "fabric", "fabricloader", "fabric-api", "pentecost", "java", "mixin", "mixinextras" };

    public static List<CrashInfo> Recent(List<ModEntry> mods, int take = 15)
    {
        var list = new List<CrashInfo>();
        if (!Directory.Exists(Paths.Crashes)) return list;
        foreach (var f in new DirectoryInfo(Paths.Crashes).GetFiles("*.txt").OrderByDescending(x => x.LastWriteTime).Take(take))
        {
            try
            {
                var text = System.IO.File.ReadAllText(f.FullName);
                if (text.Length > 300_000) text = text[..300_000];
                var title = Regex.Match(text, "(?m)^Description: (.+)$").Groups[1].Value.Trim();
                var cause = Regex.Match(text, "(?m)^(?:Caused by: )?([\\w.$]+(?:Exception|Error)\\b.*)$").Groups[1].Value.Trim();
                ModEntry? best = null;
                var bestCount = 0;
                foreach (var m in mods.Where(m => m.Enabled && m.Id.Length >= 3 && !Ignore.Contains(m.Id)))
                {
                    var c = Regex.Matches(text, "\\b" + Regex.Escape(m.Id) + "\\b", RegexOptions.IgnoreCase).Count;
                    if (c > bestCount) { best = m; bestCount = c; }
                }
                var broke = Regex.Match(text, "(?m)^WHAT BROKE\\s*\\r?\\n\\s+(.+?)\\r?$").Groups[1].Value.Trim();
                var blamedLine = Regex.Match(text, "(?m)^MOST LIKELY CAUSE\\s*\\r?\\n\\s+(.+?)\\r?$").Groups[1].Value.Trim();
                var blamedFile = Regex.Match(text, "(?m)^MOST LIKELY CAUSE\\s*\\r?\\n.*\\r?\\n\\s+File: (.+?)\\r?$").Groups[1].Value.Trim();
                var blamedId = Regex.Match(blamedLine, "id ([\\w.-]+)\\)").Groups[1].Value;
                var blamedName = Regex.Replace(blamedLine, "\\s*\\(.*$", "").Trim();
                if (blamedId.Length > 0) best = mods.FirstOrDefault(m => m.Id.Equals(blamedId, StringComparison.OrdinalIgnoreCase));
                list.Add(new CrashInfo(f.FullName, f.LastWriteTime, title.Length > 0 ? title : "Crash report", cause, Summary(text), best, broke, blamedName, blamedFile));
            }
            catch (Exception ex) { AppLog.Write("crash scan: " + ex.Message); }
        }
        return list;
    }

    static string Summary(string text)
    {
        var lines = text.Split('\n');
        var t = Array.FindIndex(lines, l => l.StartsWith("Time:", StringComparison.Ordinal));
        if (t <= 3) return "";
        var body = lines.Skip(1).Take(t - 1).Select(l => l.TrimEnd('\r')).Where(l => l.Trim().Length > 0 && !l.StartsWith("//")).Take(8);
        return string.Join("\n", body);
    }
}
