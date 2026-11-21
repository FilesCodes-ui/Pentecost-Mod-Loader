using System.Diagnostics;
using System.Net.Http;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public static class Feed
{
    static readonly byte[] Tag = Encoding.ASCII.GetBytes("pentecost-feed-v1\0");
    static string CacheJson => Path.Combine(Paths.Data, "feed.json");
    static string CacheSig => Path.Combine(Paths.Data, "feed.sig");
    static string StateFile => Path.Combine(Paths.Data, "feed-state.json");

    public static event Action? Changed;

    static JsonObject current = new();
    static JsonObject? preview;
    public static JsonObject Current => current;

    public static JsonObject? Preview
    {
        get => preview;
        set { preview = value; Changed?.Invoke(); }
    }

    public static void PreviewQuiet(JsonObject? p) => preview = p;

    static JsonObject Data => preview ?? pending ?? current;

    static JsonObject? pending;
    static System.Threading.Timer? publishTimer;
    static int publishing;
    static volatile bool again;

    public static event Action<string>? PublishFailed;

    public static bool Uploading => pending != null;

    public static JsonObject Change(Action<JsonObject> edit)
    {
        var before = (JsonObject)(pending ?? current).DeepClone();
        var next = (JsonObject)before.DeepClone();
        next["mods"] ??= new JsonObject();
        next["posts"] ??= new JsonArray();
        next["kill"] ??= new JsonArray();
        edit(next);
        pending = next;
        Changed?.Invoke();
        publishTimer?.Dispose();
        publishTimer = new System.Threading.Timer(_ => _ = RunPublish(), null, 1500, System.Threading.Timeout.Infinite);
        return before;
    }

    public static void Restore(JsonObject snapshot) => Change(d =>
    {
        d.Clear();
        foreach (var kv in snapshot) d[kv.Key] = kv.Value?.DeepClone();
    });

    static async Task RunPublish()
    {
        if (Interlocked.Exchange(ref publishing, 1) == 1) { again = true; return; }
        try
        {
            var p = pending;
            if (p == null) return;
            var err = await Publish(p, _ => { });
            if (err != null) PublishFailed?.Invoke(err);
            else if (pending == p) { pending = null; Changed?.Invoke(); }
        }
        catch (Exception ex) { PublishFailed?.Invoke(ex.Message); }
        finally
        {
            Interlocked.Exchange(ref publishing, 0);
            if (again) { again = false; _ = RunPublish(); }
        }
    }
    public static long Seq => current["seq"]?.GetValue<long>() ?? 0;
    public static string? LastError { get; private set; }

    static X509Certificate2? Cert(string resource)
    {
        try
        {
            using var s = typeof(Feed).Assembly.GetManifestResourceStream(resource);
            if (s == null) return null;
            using var ms = new MemoryStream();
            s.CopyTo(ms);
            return X509CertificateLoader.LoadCertificate(ms.ToArray());
        }
        catch { return null; }
    }

    static readonly Lazy<X509Certificate2?> Primary = new(() => Cert("dev-key.cer"));
    static readonly Lazy<X509Certificate2?> Backup = new(() => Cert("dev-key-backup.cer"));
    public static bool HasBackupKey => Backup.Value != null;

    static JsonObject LoadState()
    {
        try { return File.Exists(StateFile) && JsonNode.Parse(File.ReadAllText(StateFile)) is JsonObject o ? o : new(); }
        catch { return new(); }
    }

    static void SaveState(JsonObject o)
    {
        try { File.WriteAllText(StateFile, o.ToJsonString()); } catch { }
    }

    public static bool PrimaryRetired => LoadState()["primaryRetired"]?.GetValue<bool>() == true;

    static JsonObject? Check(byte[] json, string sigText)
    {
        try
        {
            if (JsonNode.Parse(sigText) is not JsonObject sig) { LastError = "The feed signature cannot be read."; return null; }
            var which = sig["key"]?.ToString();
            var cert = which == "backup" ? Backup.Value : which == "primary" ? Primary.Value : null;
            if (cert == null) { LastError = "The feed names a key this Manager does not have."; return null; }
            if (which == "primary" && PrimaryRetired) { LastError = "The feed was signed with a retired key."; return null; }
            using var rsa = cert.GetRSAPublicKey();
            var bytes = Convert.FromBase64String(sig["sig"]?.ToString() ?? "");
            var msg = Tag.Concat(json).ToArray();
            if (rsa == null || !rsa.VerifyData(msg, bytes, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1))
            {
                LastError = "The feed signature does not match. It was ignored.";
                return null;
            }
            if (JsonNode.Parse(json) is not JsonObject feed) { LastError = "The feed cannot be read."; return null; }
            var state = LoadState();
            var seq = feed["seq"]?.GetValue<long>() ?? 0;
            if (seq < (state["seq"]?.GetValue<long>() ?? 0)) { LastError = "An older feed was refused."; return null; }
            state["seq"] = seq;
            if (which == "backup" && feed["retirePrimary"]?.GetValue<bool>() == true) state["primaryRetired"] = true;
            SaveState(state);
            LastError = null;
            return feed;
        }
        catch (Exception ex) { LastError = ex.Message; return null; }
    }

    public static void LoadCached()
    {
        try
        {
            if (!File.Exists(CacheJson) || !File.Exists(CacheSig)) return;
            if (Check(File.ReadAllBytes(CacheJson), File.ReadAllText(CacheSig)) is { } f) current = f;
        }
        catch { }
    }

    static int busy;

    public static async Task Refresh()
    {
        if (Interlocked.Exchange(ref busy, 1) == 1) return;
        try
        {
            var stamp = DateTime.UtcNow.Ticks;
            var json = await Online.Http.GetByteArrayAsync($"{Online.Server}/feed/feed.json?t={stamp}");
            var sig = await Online.Http.GetStringAsync($"{Online.Server}/feed/feed.sig?t={stamp}");
            if (json.Length > 4_000_000) return;
            var f = Check(json, sig);
            if (f == null) { AppLog.Write("feed: " + LastError); return; }
            if (f["seq"]?.GetValue<long>() == Seq && current.Count > 0) return;
            current = f;
            try { File.WriteAllBytes(CacheJson, json); File.WriteAllText(CacheSig, sig); } catch { }
            Changed?.Invoke();
        }
        catch (HttpRequestException ex) when (ex.StatusCode == System.Net.HttpStatusCode.NotFound) { }
        catch (Exception ex) { AppLog.Write("feed: " + ex.Message); }
        finally { Interlocked.Exchange(ref busy, 0); }
    }

    static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public static JsonObject? Mod(string? projectId) =>
        projectId != null && Data["mods"]?[projectId] is JsonObject o ? o : null;

    public static bool Flag(string? projectId, string key) => Mod(projectId)?[key]?.GetValue<bool>() == true;

    public static string? Locked(string? projectId, string? version)
    {
        if (Mod(projectId)?["warn"] is not JsonObject w || w["lock"]?.GetValue<bool>() != true) return null;
        var list = (w["versions"] as JsonArray)?.Select(x => x?.ToString() ?? "").Where(x => x.Length > 0).ToList() ?? new();
        if (list.Count > 0 && (version == null || !list.Contains(version))) return null;
        return w["text"]?.ToString() is { Length: > 0 } t ? t : "The Pentecost developer marked this version as broken.";
    }

    public static bool Live(JsonObject p, IReadOnlyCollection<string>? installedProjects = null)
    {
        var at = p["at"]?.GetValue<long>() ?? 0;
        var until = p["until"]?.GetValue<long>() ?? 0;
        if (at > 0 && Now < at) return false;
        if (until > 0 && Now > until) return false;
        if (p["target"] is JsonObject t)
        {
            var cur = Paths.Version ?? "0";
            if (t["minVersion"]?.ToString() is { Length: > 0 } min && Updates.Newer(min, cur)) return false;
            if (t["maxVersion"]?.ToString() is { Length: > 0 } max && Updates.Newer(cur, max)) return false;
            if (t["mod"]?.ToString() is { Length: > 0 } mod && (installedProjects == null || !installedProjects.Contains(mod))) return false;
        }
        return true;
    }

    public static IEnumerable<JsonObject> Posts(string kind, IReadOnlyCollection<string>? installed = null) =>
        (Data["posts"] as JsonArray)?.OfType<JsonObject>().Where(p => p["kind"]?.ToString() == kind && Live(p, installed)) ?? Enumerable.Empty<JsonObject>();

    public static string? Gate()
    {
        if (Data["gate"] is not JsonObject g || g["minVersion"]?.ToString() is not { Length: > 0 } min) return null;
        var cur = Paths.Version;
        if (cur == null || !Updates.Newer(min, cur)) return null;
        return g["text"]?.ToString() is { Length: > 0 } t ? t : $"Pentecost {cur} has a problem that makes it unsafe. Update to {min} or newer to play.";
    }

    static readonly byte[] ProofTag = Encoding.ASCII.GetBytes("pentecost-dev-proof-v1\0");
    const string Owner = "808323d1022140ccb69bf8bcd0d16112";

    static byte[] Int32BE(int n) => new[] { (byte)(n >> 24), (byte)(n >> 16), (byte)(n >> 8), (byte)n };

    static byte[] ProofMessage(byte[] challenge, string purpose)
    {
        var p = Encoding.UTF8.GetBytes(purpose);
        var id = Convert.FromHexString(Owner);
        return ProofTag.Concat(Int32BE(challenge.Length)).Concat(challenge).Concat(id).Concat(Int32BE(p.Length)).Concat(p).ToArray();
    }

    static async Task<JsonObject?> Prove(string purpose)
    {
        if (!DevAccess.Unlocked) return null;
        var c = JsonNode.Parse(await Online.Http.GetStringAsync(Online.Server + "/dev/challenge")) as JsonObject;
        var challenge = Convert.FromHexString(c?["challenge"]?.ToString() ?? "");
        var sig = DevAccess.Sign(ProofMessage(challenge, purpose));
        if (c == null || sig == null) return null;
        c["proof"] = Convert.ToBase64String(sig);
        return c;
    }

    static async Task<JsonObject> DevPost(string path, JsonObject body)
    {
        using var r = await Online.Http.PostAsync(Online.Server + path, new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
        var text = await r.Content.ReadAsStringAsync();
        var o = JsonNode.Parse(text) as JsonObject ?? new JsonObject();
        if (!r.IsSuccessStatusCode) throw new IOException(o["error"]?.ToString() ?? "The server said no (" + (int)r.StatusCode + ").");
        return o;
    }

    public static async Task<List<(string Mod, string Version, string Kind, int Count, DateTime Last)>> CrashCounts()
    {
        var proof = await Prove("crash-report") ?? throw new IOException("The developer key is not on this computer.");
        var r = await DevPost("/dev/crashes", proof);
        return (r["crashes"] as JsonArray)?.OfType<JsonObject>().Select(x => (
            x["mod"]?.ToString() ?? "", x["version"]?.ToString() ?? "", x["kind"]?.ToString() ?? "",
            x["n"]?.GetValue<int>() ?? 0, DateTimeOffset.FromUnixTimeMilliseconds(x["last"]?.GetValue<long>() ?? 0).LocalDateTime)).ToList() ?? new();
    }

    static string ServerFolderFile => Path.Combine(Paths.Data, "server-folder.txt");

    public static string ServerDir
    {
        get
        {
            var picks = new List<string>();
            if (Paths.DevSource.Length > 0) picks.Add(Path.Combine(Paths.DevSource, "server"));
            try { if (File.Exists(ServerFolderFile)) picks.Add(File.ReadAllText(ServerFolderFile).Trim()); } catch { }
            picks.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "source", "Pentecost", "server"));
            return picks.FirstOrDefault(p => p.Length > 0 && File.Exists(Path.Combine(p, "wrangler.jsonc"))) ?? "";
        }
    }

    public static string? ChooseServerDir(string dir)
    {
        if (!File.Exists(Path.Combine(dir, "wrangler.jsonc"))) return "That folder has no wrangler.jsonc.";
        try { File.WriteAllText(ServerFolderFile, dir); return null; } catch (Exception ex) { return ex.Message; }
    }
    public static string HistoryDir => Path.Combine(ServerDir, "feed-history");
    static string DraftFile => Path.Combine(Paths.Data, "feed-draft.json");

    public static JsonObject Draft()
    {
        try { if (File.Exists(DraftFile) && JsonNode.Parse(File.ReadAllText(DraftFile)) is JsonObject d) return d; }
        catch { }
        var copy = (JsonObject)current.DeepClone();
        copy["mods"] ??= new JsonObject();
        copy["posts"] ??= new JsonArray();
        copy["kill"] ??= new JsonArray();
        return copy;
    }

    public static JsonObject Snapshot()
    {
        var copy = (JsonObject)current.DeepClone();
        copy["mods"] ??= new JsonObject();
        copy["posts"] ??= new JsonArray();
        copy["kill"] ??= new JsonArray();
        return copy;
    }

    public static void SaveDraft(JsonObject d)
    {
        try { File.WriteAllText(DraftFile, d.ToJsonString(new JsonSerializerOptions { WriteIndented = true })); }
        catch (Exception ex) { AppLog.Write("feed draft: " + ex.Message); }
    }

    public static void DropDraft() { try { File.Delete(DraftFile); } catch { } }

    public static bool HasDraft => File.Exists(DraftFile);

    public static async Task<string?> Publish(JsonObject draft, Action<string> step)
    {
        if (!DevAccess.Unlocked) return "The developer key is not on this computer.";
        if (ServerDir.Length == 0) return "The server folder was not found. Choose it in the Dev tab.";
        var feed = (JsonObject)draft.DeepClone();
        feed["v"] = 1;
        feed["seq"] = Math.Max(Seq, LoadState()["seq"]?.GetValue<long>() ?? 0) + 1;
        feed["issued"] = Now;
        var json = Encoding.UTF8.GetBytes(feed.ToJsonString());
        step("Signing");
        var sig = DevAccess.Sign(Tag.Concat(json).ToArray());
        if (sig == null) return "The developer key did not sign.";
        var sigText = new JsonObject { ["key"] = "primary", ["sig"] = Convert.ToBase64String(sig) }.ToJsonString();
        if (Check(json, sigText) == null) return "The signed feed did not pass its own check: " + LastError;

        var pub = Path.Combine(ServerDir, "public", "feed");
        Directory.CreateDirectory(pub);
        Directory.CreateDirectory(HistoryDir);
        File.WriteAllBytes(Path.Combine(pub, "feed.json"), json);
        File.WriteAllText(Path.Combine(pub, "feed.sig"), sigText);
        File.WriteAllBytes(Path.Combine(HistoryDir, $"{feed["seq"]}.json"), json);

        step("Uploading");
        var err = await Task.Run(Deploy);
        if (err != null) return err;
        current = feed;
        try { File.WriteAllBytes(CacheJson, json); File.WriteAllText(CacheSig, sigText); } catch { }
        DropDraft();
        Changed?.Invoke();

        step("Telling everyone");
        try
        {
            var proof = await Prove("feed-ping");
            if (proof != null) { proof["seq"] = feed["seq"]!.GetValue<long>(); await DevPost("/dev/ping", proof); }
        }
        catch (Exception ex) { AppLog.Write("feed ping: " + ex.Message); }
        return null;
    }

    static string? Deploy()
    {
        try
        {
            var psi = new ProcessStartInfo("node") { WorkingDirectory = ServerDir, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            psi.ArgumentList.Add(Path.Combine("node_modules", "wrangler", "bin", "wrangler.js"));
            psi.ArgumentList.Add("deploy");
            using var p = Process.Start(psi)!;
            var o = p.StandardOutput.ReadToEndAsync();
            var e = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(180_000)) { try { p.Kill(true); } catch { } return "Uploading took too long."; }
            AppLog.Write("feed deploy: " + o.Result.Trim() + " " + e.Result.Trim());
            return p.ExitCode == 0 ? null : "Uploading failed. The Manager log has the details.";
        }
        catch (Exception ex) { return "Could not run the upload: " + ex.Message; }
    }

    public static async Task<List<string>> SelfCheck()
    {
        var log = new List<string>();
        LoadCached();
        log.Add("live before: " + Seq);
        var copy = (JsonObject)current.DeepClone();
        copy["mods"] ??= new JsonObject();
        copy["posts"] ??= new JsonArray();
        copy["kill"] ??= new JsonArray();
        log.Add("unlocked: " + DevAccess.Unlocked + ", server folder: " + ServerDir);
        var err = await Publish(copy, s => log.Add("step: " + s));
        log.Add("publish: " + (err ?? "ok") + ", seq " + Seq);
        if (err != null) return log;
        var stamp = DateTime.UtcNow.Ticks;
        var json = await Online.Http.GetByteArrayAsync($"{Online.Server}/feed/feed.json?t={stamp}");
        var sig = await Online.Http.GetStringAsync($"{Online.Server}/feed/feed.sig?t={stamp}");
        var good = Check(json, sig);
        log.Add("server copy: " + (good != null ? "signature ok, seq " + good["seq"] : "REFUSED " + LastError));
        var bad = (byte[])json.Clone();
        bad[bad.Length / 2] ^= 1;
        log.Add("changed byte: " + (Check(bad, sig) == null ? "refused (good)" : "ACCEPTED (bad)"));
        var hist = History();
        if (hist.Count > 1)
        {
            var oldJson = File.ReadAllBytes(hist[1].File);
            var oldSig = new JsonObject { ["key"] = "primary", ["sig"] = Convert.ToBase64String(DevAccess.Sign(Tag.Concat(oldJson).ToArray()) ?? Array.Empty<byte>()) }.ToJsonString();
            log.Add("older feed: " + (Check(oldJson, oldSig) == null ? "refused (good): " + LastError : "ACCEPTED (bad)"));
        }
        return log;
    }

    public static List<(long Seq, DateTime When, string File)> History()
    {
        var list = new List<(long, DateTime, string)>();
        try
        {
            if (!Directory.Exists(HistoryDir)) return list;
            foreach (var f in Directory.GetFiles(HistoryDir, "*.json"))
                if (long.TryParse(Path.GetFileNameWithoutExtension(f), out var n)) list.Add((n, File.GetLastWriteTime(f), f));
        }
        catch { }
        return list.OrderByDescending(x => x.Item1).ToList();
    }
}
