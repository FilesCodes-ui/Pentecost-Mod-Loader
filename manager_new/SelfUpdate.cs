using System.Diagnostics;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace Pentecost.Manager;

public static class SelfUpdate
{
    static string Dir => Path.Combine(Path.GetTempPath(), "pentecost-manager");

    static string FileFor(ReleaseInfo r) => Path.Combine(Dir, "Pentecost-Manager-Setup-" + r.ManagerVersion + ".exe");

    public static async Task<(string? Path, string? Error)> Download(ReleaseInfo r, Action<double>? progress, CancellationToken ct)
    {
        if (r.ManagerVersion == null || r.ManagerUrl == null) return (null, "That release has no Manager setup.");
        if (!Updates.IsOfficialAsset(r.ManagerUrl)) return (null, "The Manager link is not from the Pentecost release page, so it was not used.");
        if (r.ManagerSha256 == null) return (null, "The release has no checksum for the Manager, so it was not downloaded.");
        var exe = FileFor(r);
        try
        {
            Directory.CreateDirectory(Dir);
            RemoveOld(exe);
            if (File.Exists(exe) && await Matches(exe, r, ct)) { progress?.Invoke(1); return (exe, null); }
            using (var resp = await Modrinth.Client.GetAsync(r.ManagerUrl, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                resp.EnsureSuccessStatusCode();
                var total = resp.Content.Headers.ContentLength ?? r.ManagerSize;
                await using var src = await resp.Content.ReadAsStreamAsync(ct);
                await using var dst = File.Create(exe + ".part");
                var buf = new byte[81920];
                long done = 0;
                int n;
                while ((n = await src.ReadAsync(buf, ct)) > 0)
                {
                    await dst.WriteAsync(buf.AsMemory(0, n), ct);
                    done += n;
                    if (total > 0) progress?.Invoke((double)done / total);
                }
            }
            if (!await Matches(exe + ".part", r, ct))
            {
                File.Delete(exe + ".part");
                return (null, "The Manager download did not match its checksum, so it was not used.");
            }
            File.Move(exe + ".part", exe, true);
            return (exe, null);
        }
        catch (OperationCanceledException) { return (null, "Cancelled."); }
        catch (Exception ex)
        {
            AppLog.Write("manager download: " + ex.Message);
            return (null, "Could not download the Manager: " + ex.Message);
        }
    }

    static async Task<bool> Matches(string file, ReleaseInfo r, CancellationToken ct)
    {
        if (r.ManagerSize > 0 && new FileInfo(file).Length != r.ManagerSize) return false;
        await using var s = File.OpenRead(file);
        return Convert.ToHexString(await SHA256.HashDataAsync(s, ct)).Equals(r.ManagerSha256, StringComparison.OrdinalIgnoreCase);
    }

    static void RemoveOld(string keep)
    {
        foreach (var f in Directory.EnumerateFiles(Dir, "Pentecost-Manager-Setup-*"))
        {
            if (string.Equals(f, keep, StringComparison.OrdinalIgnoreCase) || string.Equals(f, keep + ".part", StringComparison.OrdinalIgnoreCase)) continue;
            if (!Regex.IsMatch(Path.GetFileName(f), "^Pentecost-Manager-Setup-[0-9.]+\\.exe(\\.part)?$")) continue;
            try { File.Delete(f); } catch { }
        }
    }

    public static string? Run(string setup, bool restart)
    {
        try
        {
            var psi = new ProcessStartInfo(setup) { UseShellExecute = false, CreateNoWindow = true };
            psi.ArgumentList.Add("--silent");
            if (restart) psi.ArgumentList.Add("--restart");
            Process.Start(psi)?.Dispose();
            return null;
        }
        catch (Exception ex)
        {
            AppLog.Write("manager update run: " + ex.Message);
            return "Could not start the Manager setup: " + ex.Message;
        }
    }
}
