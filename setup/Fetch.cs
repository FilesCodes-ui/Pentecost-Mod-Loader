using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

#pragma warning disable CS0649

namespace Pentecost.Setup;

public sealed class ManagerRelease
{
    public string Version = "";
    public string Url = "";
    public string Name = "";
    public long Size;
    public string? Sha256;
}

public static class Fetch
{
    const string Repo = "FilesCodes-ui/Pentecost-Mod-Loader";
    const string Prefix = "Pentecost-Manager-Setup-";

    public static string InstallDir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pentecost Manager");
    public static string InstalledExe => Path.Combine(InstallDir, "Pentecost-Manager.exe");

    static readonly HttpClient Http = MakeClient();

    static HttpClient MakeClient()
    {
        ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("Pentecost-Setup/1.0");
        return c;
    }

    [DataContract]
    sealed class GhRelease
    {
        [DataMember(Name = "tag_name")] public string? Tag;
        [DataMember(Name = "assets")] public GhAsset[]? Assets;
    }

    [DataContract]
    sealed class GhAsset
    {
        [DataMember(Name = "name")] public string? Name;
        [DataMember(Name = "browser_download_url")] public string? Url;
        [DataMember(Name = "size")] public long Size;
        [DataMember(Name = "digest")] public string? Digest;
    }

    public static async Task<ManagerRelease> Latest(CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{Repo}/releases/latest");
        req.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) throw new SetupException($"GitHub answered {(int)resp.StatusCode} when asked for the latest release.");
        var bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
        GhRelease? rel;
        using (var ms = new MemoryStream(bytes))
            rel = new DataContractJsonSerializer(typeof(GhRelease)).ReadObject(ms) as GhRelease;
        var asset = rel?.Assets?.FirstOrDefault(a => a.Name != null && a.Name.StartsWith(Prefix, StringComparison.OrdinalIgnoreCase)
                                                     && a.Name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase));
        if (asset == null || asset.Url == null) throw new SetupException("The latest release has no Pentecost Manager file yet.");
        if (!Official(asset.Url)) throw new SetupException("The Manager link is not on the Pentecost release page, so it was not used.");
        var sha = asset.Digest != null && asset.Digest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase) ? asset.Digest.Substring(7) : null;
        return new ManagerRelease
        {
            Version = (rel!.Tag ?? "").TrimStart('v', 'V'),
            Url = asset.Url,
            Name = Path.GetFileName(asset.Name),
            Size = asset.Size,
            Sha256 = sha,
        };
    }

    static bool Official(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == Uri.UriSchemeHttps
        && u.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase)
        && u.AbsolutePath.StartsWith("/" + Repo + "/releases/download/", StringComparison.OrdinalIgnoreCase);

    public static async Task<string> Download(ManagerRelease r, Action<long, long> progress, CancellationToken ct)
    {
        var dir = Path.Combine(Path.GetTempPath(), "pentecost-setup");
        Directory.CreateDirectory(dir);
        var file = Path.Combine(dir, r.Name);
        using var resp = await Http.GetAsync(r.Url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) throw new SetupException($"The download answered {(int)resp.StatusCode}.");
        var total = resp.Content.Headers.ContentLength ?? r.Size;
        using (var src = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false))
        using (var dst = File.Create(file + ".part"))
        {
            var buf = new byte[81920];
            long done = 0;
            int n;
            long lastReport = 0;
            while ((n = await src.ReadAsync(buf, 0, buf.Length, ct).ConfigureAwait(false)) > 0)
            {
                await dst.WriteAsync(buf, 0, n, ct).ConfigureAwait(false);
                done += n;
                if (done - lastReport > 262144 || done == total) { progress(done, total); lastReport = done; }
            }
        }
        if (File.Exists(file)) File.Delete(file);
        File.Move(file + ".part", file);
        return file;
    }

    public static async Task Verify(string file, ManagerRelease r, CancellationToken ct)
    {
        var len = new FileInfo(file).Length;
        if (r.Size > 0 && len != r.Size)
        {
            File.Delete(file);
            throw new SetupException("The download was cut off. Try again.");
        }
        if (r.Sha256 == null) return;
        string actual = await Task.Run(() =>
        {
            using var s = File.OpenRead(file);
            using var sha = SHA256.Create();
            return string.Concat(sha.ComputeHash(s).Select(b => b.ToString("X2")));
        }, ct).ConfigureAwait(false);
        if (!actual.Equals(r.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            File.Delete(file);
            throw new SetupException("The download did not match the checksum on the release page, so it was not run.");
        }
    }

    public static async Task RunManagerSetup(string file)
    {
        var psi = new ProcessStartInfo(file, "--silent") { UseShellExecute = false, CreateNoWindow = true };
        int code = await Task.Run(() =>
        {
            using var p = Process.Start(psi) ?? throw new SetupException("The Manager did not start.");
            if (!p.WaitForExit(180_000)) throw new SetupException("The Manager took too long to install itself.");
            return p.ExitCode;
        }).ConfigureAwait(false);
        if (code != 0 || !File.Exists(InstalledExe)) throw new SetupException("The Manager could not install itself. Close it if it is open, then try again.");
        try { File.Delete(file); } catch { }
    }

    public static string Explain(Exception ex) => ex switch
    {
        SetupException s => s.Message,
        HttpRequestException => "Could not reach GitHub. Check your internet connection.",
        TaskCanceledException => "GitHub took too long to answer.",
        UnauthorizedAccessException => "Windows did not allow writing a file: " + ex.Message,
        IOException => "A file could not be written: " + ex.Message,
        _ => ex.Message,
    };
}

public sealed class SetupException : Exception
{
    public SetupException(string message) : base(message) { }
}
