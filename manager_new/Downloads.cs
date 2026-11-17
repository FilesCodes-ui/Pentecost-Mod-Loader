using System.Net.Http;
using System.Security.Cryptography;

namespace Pentecost.Manager;

public sealed class DlJob
{
    public string Name = "";
    public double Progress;
    /// <summary>Queued, Downloading, Done or Failed.</summary>
    public string State = "Queued";
}

/// <summary>
/// Every download goes through here. Three run at once and the rest wait their turn.
/// Rules: https from modrinth.com only, .jar or .zip only, SHA-1 checked, written as .part first.
/// </summary>
public static class Downloads
{
    static readonly object Gate = new();
    static readonly List<DlJob> Jobs = new();
    static readonly SemaphoreSlim Slots = new(3);

    /// <summary>Raised on any thread when the list or a job's progress changes.</summary>
    public static event Action? Changed;

    public static List<DlJob> Snapshot() { lock (Gate) return Jobs.ToList(); }

    public static async Task Fetch(string link, string dir, string name, string sha1, CancellationToken ct)
    {
        var okType = name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase);
        if (!okType || name != Path.GetFileName(name) || sha1.Length != 40
            || !Uri.TryCreate(link, UriKind.Absolute, out var uri) || uri.Scheme != "https"
            || !uri.Host.EndsWith("modrinth.com", StringComparison.OrdinalIgnoreCase))
            throw new IOException($"{name}: the file entry is not one I will download");

        var job = new DlJob { Name = name };
        lock (Gate) Jobs.Add(job);
        Changed?.Invoke();
        var tmp = Path.Combine(dir, name + ".part");
        try
        {
            await Slots.WaitAsync(ct);
            try
            {
                job.State = "Downloading";
                Changed?.Invoke();
                Directory.CreateDirectory(dir);
                string got;
                using (var resp = await Modrinth.Client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct))
                {
                    resp.EnsureSuccessStatusCode();
                    var total = resp.Content.Headers.ContentLength ?? 0;
                    using var src = await resp.Content.ReadAsStreamAsync(ct);
                    using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA1);
                    await using (var dst = File.Create(tmp))
                    {
                        var buf = new byte[81920];
                        long done = 0;
                        var last = Environment.TickCount64;
                        int n;
                        while ((n = await src.ReadAsync(buf, ct)) > 0)
                        {
                            await dst.WriteAsync(buf.AsMemory(0, n), ct);
                            sha.AppendData(buf, 0, n);
                            done += n;
                            if (total > 0) job.Progress = done / (double)total;
                            if (Environment.TickCount64 - last > 120) { last = Environment.TickCount64; Changed?.Invoke(); }
                        }
                    }
                    got = Convert.ToHexString(sha.GetHashAndReset());
                }
                if (!string.Equals(got, sha1, StringComparison.OrdinalIgnoreCase))
                    throw new IOException($"{name} did not match its checksum");
                File.Move(tmp, Path.Combine(dir, name), true);
                job.Progress = 1;
                job.State = "Done";
            }
            finally { Slots.Release(); }
        }
        catch
        {
            job.State = "Failed";
            try { if (File.Exists(tmp)) File.Delete(tmp); } catch { }
            throw;
        }
        finally
        {
            Changed?.Invoke();
            _ = Task.Delay(job.State == "Failed" ? 6000 : 2500).ContinueWith(_ =>
            {
                lock (Gate) Jobs.Remove(job);
                Changed?.Invoke();
            });
        }
    }
}
