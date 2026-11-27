using System.Net.Http;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public sealed record Friend(string Uuid, string Name, bool Online, JsonObject? Status);
public sealed record Pending(string Uuid, string Name, long Ts);
public sealed record ChatLine(bool Mine, string Text, DateTime Time);
public sealed record Shared(string From, string FromName, string Name, JsonObject Pack, DateTime Time);

public static class Online
{
    public const string Server = "https://api.pentecost-modloader.workers.dev";
    public static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    public static event Action? Changed;
    public static event Action<string, string>? Arrived;

    public static string State { get; private set; } = "off";
    public static string? MyUuid { get; private set; }
    public static string? MyName { get; private set; }
    public static string? MyCode { get; private set; }
    public static bool AllowJoin { get; private set; }

    static readonly object Gate = new();
    static List<Friend> friends = new();
    static List<Pending> incoming = new(), outgoing = new();
    static readonly Dictionary<string, List<ChatLine>> chats = new();
    static readonly List<Shared> shares = new();
    static readonly Dictionary<string, int> unread = new();

    public static List<Friend> Friends { get { lock (Gate) return friends.ToList(); } }
    public static List<Pending> Incoming { get { lock (Gate) return incoming.ToList(); } }
    public static List<Pending> Outgoing { get { lock (Gate) return outgoing.ToList(); } }
    public static List<Shared> Shares { get { lock (Gate) return shares.ToList(); } }
    public static List<ChatLine> Chat(string uuid) { lock (Gate) return chats.TryGetValue(uuid, out var c) ? c.ToList() : new(); }
    public static int Unread(string uuid) { lock (Gate) return unread.GetValueOrDefault(uuid); }
    public static int UnreadTotal { get { lock (Gate) return unread.Values.Sum() + shares.Count; } }
    public static void MarkRead(string uuid) { bool had; lock (Gate) had = unread.Remove(uuid); if (had) Raise(); }
    public static int OnlineCount { get { lock (Gate) return friends.Count(f => f.Online); } }

    static ClientWebSocket? socket;
    static Task? loop;
    static int nextId;
    static readonly Dictionary<int, TaskCompletionSource<JsonObject>> waiting = new();
    static readonly SemaphoreSlim sendLock = new(1, 1);

    static void Raise() { try { Changed?.Invoke(); } catch (Exception ex) { AppLog.Write("online changed: " + ex.Message); } }

    public sealed record Session(string Token, string Uuid, string Name, long Expires);

    static IEnumerable<string> SessionFiles()
    {
        yield return Path.Combine(Paths.Home, "run", "pentecost-online.json");
        yield return Path.Combine(Paths.Home, "pentecost-online.json");
    }

    public static Session? LoadSession()
    {
        var account = Paths.AccountUuid()?.Replace("-", "");
        foreach (var f in SessionFiles())
        {
            try
            {
                if (!File.Exists(f) || JsonNode.Parse(File.ReadAllText(f)) is not JsonObject o) continue;
                var uuid = o["uuid"]?.ToString() ?? "";
                var exp = o["expires"]?.GetValue<long>() ?? 0;
                if (exp < DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 60_000) continue;
                if (account != null && !uuid.Equals(account, StringComparison.OrdinalIgnoreCase)) continue;
                if (o["protection"]?.ToString() != "dpapi") continue;
                var plain = Unprotect(Convert.FromBase64String(o["token"]?.ToString() ?? ""));
                if (plain == null) continue;
                return new Session(Encoding.UTF8.GetString(plain), uuid, o["name"]?.ToString() ?? "", exp);
            }
            catch (Exception ex) { AppLog.Write("online session: " + ex.Message); }
        }
        return null;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct Blob { public int Size; public IntPtr Data; }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, ref Blob output);

    [DllImport("kernel32.dll")]
    static extern IntPtr LocalFree(IntPtr mem);

    static byte[]? Unprotect(byte[] sealedData)
    {
        var h = GCHandle.Alloc(sealedData, GCHandleType.Pinned);
        try
        {
            var input = new Blob { Size = sealedData.Length, Data = h.AddrOfPinnedObject() };
            var output = new Blob();
            if (!CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, ref output)) return null;
            try
            {
                var plain = new byte[output.Size];
                Marshal.Copy(output.Data, plain, 0, output.Size);
                return plain;
            }
            finally { LocalFree(output.Data); }
        }
        finally { h.Free(); }
    }

    public static void Drop() { try { socket?.Abort(); } catch { } }

    public static void Start()
    {
        if (loop != null) return;
        loop = Task.Run(Run);
    }

    static async Task Run()
    {
        var wait = TimeSpan.FromSeconds(3);
        while (true)
        {
            if (!Settings.FriendsOnline)
            {
                SetState("off");
                await Task.Delay(TimeSpan.FromSeconds(3));
                continue;
            }
            var s = LoadSession();
            if (s == null)
            {
                SetState("nosession");
                await Task.Delay(TimeSpan.FromSeconds(30));
                continue;
            }
            SetState("connecting");
            var ws = new ClientWebSocket();
            ws.Options.SetRequestHeader("Authorization", "Bearer " + s.Token);
            ws.Options.KeepAliveInterval = TimeSpan.Zero;
            using var stop = new CancellationTokenSource();
            try
            {
                await ws.ConnectAsync(new Uri(Server.Replace("https://", "wss://") + "/ws?client=manager"), CancellationToken.None);
                socket = ws;
                wait = TimeSpan.FromSeconds(3);
                SetState("online");
                _ = Pinger(ws, stop.Token);
                await Receive(ws);
            }
            catch (Exception ex) { AppLog.Write("online: " + ex.Message); }
            stop.Cancel();
            socket = null;
            ws.Dispose();
            lock (Gate) foreach (var w in waiting.Values) w.TrySetResult(new JsonObject { ["t"] = "error", ["error"] = "Disconnected." });
            lock (Gate) waiting.Clear();
            SetState("connecting");
            await Task.Delay(wait);
            wait = TimeSpan.FromSeconds(Math.Min(60, wait.TotalSeconds * 2));
        }
    }

    static void SetState(string s)
    {
        if (State == s) return;
        State = s;
        Raise();
    }

    static async Task Pinger(ClientWebSocket ws, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested && ws.State == WebSocketState.Open)
            {
                await Task.Delay(TimeSpan.FromSeconds(50), ct);
                await SendText(ws, "ping");
            }
        }
        catch { }
    }

    static async Task Receive(ClientWebSocket ws)
    {
        var buf = new byte[16384];
        var ms = new MemoryStream();
        while (ws.State == WebSocketState.Open)
        {
            ms.SetLength(0);
            WebSocketReceiveResult r;
            do
            {
                r = await ws.ReceiveAsync(buf, CancellationToken.None);
                if (r.MessageType == WebSocketMessageType.Close) return;
                ms.Write(buf, 0, r.Count);
                if (ms.Length > 1_000_000) return;
            } while (!r.EndOfMessage);
            var text = Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length);
            if (text == "pong") continue;
            try { if (JsonNode.Parse(text) is JsonObject m) Handle(m); }
            catch (Exception ex) { AppLog.Write("online message: " + ex.Message); }
        }
    }

    static async Task SendText(ClientWebSocket ws, string text)
    {
        await sendLock.WaitAsync();
        try { await ws.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, CancellationToken.None); }
        finally { sendLock.Release(); }
    }

    public static async Task<JsonObject> Ask(JsonObject msg)
    {
        var ws = socket;
        if (ws == null || ws.State != WebSocketState.Open) return new JsonObject { ["t"] = "error", ["error"] = "Not connected to Pentecost online." };
        var id = Interlocked.Increment(ref nextId);
        var tcs = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (Gate) waiting[id] = tcs;
        msg["id"] = id;
        try { await SendText(ws, msg.ToJsonString()); }
        catch (Exception ex) { lock (Gate) waiting.Remove(id); return new JsonObject { ["t"] = "error", ["error"] = ex.Message }; }
        var done = await Task.WhenAny(tcs.Task, Task.Delay(10_000));
        lock (Gate) waiting.Remove(id);
        return done == tcs.Task ? tcs.Task.Result : new JsonObject { ["t"] = "error", ["error"] = "The server did not answer." };
    }

    public static async Task Tell(JsonObject msg)
    {
        var ws = socket;
        if (ws == null || ws.State != WebSocketState.Open) return;
        try { await SendText(ws, msg.ToJsonString()); } catch { }
    }

    static Friend ToFriend(JsonObject f) => new(
        f["uuid"]?.ToString() ?? "", f["name"]?.ToString() ?? "?", f["online"]?.GetValue<bool>() == true, f["status"] as JsonObject);

    static Pending ToPending(JsonNode? n) => new(n?["uuid"]?.ToString() ?? "", n?["name"]?.ToString() ?? "?", n?["ts"]?.GetValue<long>() ?? 0);

    static void Handle(JsonObject m)
    {
        if (m["re"] is JsonValue re && re.TryGetValue<int>(out var id))
        {
            TaskCompletionSource<JsonObject>? tcs;
            lock (Gate) waiting.Remove(id, out tcs);
            tcs?.TrySetResult(m);
            if (m["t"]?.ToString() is "ok" or "error") return;
        }
        var acks = new JsonArray();
        lock (Gate)
        {
            switch (m["t"]?.ToString())
            {
                case "state":
                    MyUuid = m["me"]?["uuid"]?.ToString();
                    MyName = m["me"]?["name"]?.ToString();
                    MyCode = m["me"]?["code"]?.ToString();
                    AllowJoin = m["me"]?["allowJoin"]?.GetValue<bool>() == true;
                    friends = (m["friends"] as JsonArray)?.OfType<JsonObject>().Select(ToFriend).ToList() ?? new();
                    incoming = (m["incoming"] as JsonArray)?.Select(ToPending).ToList() ?? new();
                    outgoing = (m["outgoing"] as JsonArray)?.Select(ToPending).ToList() ?? new();
                    foreach (var x in (m["mail"] as JsonArray)?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
                    {
                        Take(x);
                        if (x["id"] is JsonValue v && v.TryGetValue<long>(out var mid)) acks.Add(mid);
                    }
                    break;
                case "presence":
                {
                    var uuid = m["uuid"]?.ToString();
                    var i = friends.FindIndex(f => f.Uuid == uuid);
                    if (i >= 0) friends[i] = ToFriend(m);
                    break;
                }
                case "friend":
                    if (m["friend"] is JsonObject nf)
                    {
                        var f = ToFriend(nf);
                        friends.RemoveAll(x => x.Uuid == f.Uuid);
                        friends.Add(f);
                    }
                    break;
                case "unfriend":
                    friends.RemoveAll(x => x.Uuid == m["uuid"]?.ToString());
                    break;
                case "request":
                {
                    var p = ToPending(m["from"]);
                    incoming.RemoveAll(x => x.Uuid == p.Uuid);
                    incoming.Add(p);
                    break;
                }
                case "requestSent":
                {
                    var p = ToPending(m["to"]);
                    outgoing.RemoveAll(x => x.Uuid == p.Uuid);
                    outgoing.Add(p);
                    break;
                }
                case "requestGone":
                    incoming.RemoveAll(x => x.Uuid == m["uuid"]?.ToString());
                    outgoing.RemoveAll(x => x.Uuid == m["uuid"]?.ToString());
                    break;
                case "code":
                    MyCode = m["code"]?.ToString();
                    break;
                case "msg":
                case "share":
                    Take(m);
                    break;
                case "feed":
                    _ = Feed.Refresh();
                    return;
            }
        }
        if (acks.Count > 0) _ = Tell(new JsonObject { ["t"] = "ack", ["ids"] = acks });
        Raise();
    }

    static void Take(JsonObject m)
    {
        var from = m["from"]?.ToString() ?? "";
        var ts = m["ts"]?.GetValue<long>() ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var time = DateTimeOffset.FromUnixTimeMilliseconds(ts).LocalDateTime;
        var kind = m["t"]?.ToString();
        if (kind == "msg" && m["text"]?.ToString() is { Length: > 0 } text)
        {
            if (!chats.TryGetValue(from, out var list)) chats[from] = list = new();
            list.Add(new ChatLine(false, text, time));
            if (list.Count > 300) list.RemoveRange(0, list.Count - 300);
            unread[from] = unread.GetValueOrDefault(from) + 1;
        }
        else if (kind == "share" && m["pack"] is JsonObject pack)
        {
            var name = friends.FirstOrDefault(f => f.Uuid == from)?.Name ?? "A friend";
            shares.Add(new Shared(from, name, m["name"]?.ToString() ?? "Profile", (JsonObject)pack.DeepClone(), time));
        }
        else return;
        var k = kind;
        Task.Run(() => Arrived?.Invoke(from, k!));
    }

    public static async Task<string?> Add(string code)
    {
        var r = await Ask(new JsonObject { ["t"] = "add", ["code"] = code });
        return r["t"]?.ToString() == "ok"
            ? null
            : r["error"]?.ToString() ?? "Could not add that code.";
    }

    public static async Task<string?> Simple(string kind, string uuid)
    {
        var r = await Ask(new JsonObject { ["t"] = kind, ["uuid"] = uuid });
        return r["t"]?.ToString() == "ok" ? null : r["error"]?.ToString() ?? "That did not work.";
    }

    public static async Task<string?> Send(string uuid, string text)
    {
        var r = await Ask(new JsonObject { ["t"] = "msg", ["to"] = uuid, ["text"] = text });
        if (r["t"]?.ToString() != "ok") return r["error"]?.ToString() ?? "Not sent.";
        lock (Gate)
        {
            if (!chats.TryGetValue(uuid, out var list)) chats[uuid] = list = new();
            list.Add(new ChatLine(true, text, DateTime.Now));
        }
        Raise();
        return null;
    }

    public static async Task<string?> ShareProfile(string uuid, ModProfile profile)
    {
        var mods = await Task.Run(ModScan.Scan);
        var list = new JsonArray();
        foreach (var m in mods.Where(m => m.Sha1 != null).Take(600))
            list.Add(new JsonObject { ["file"] = m.BaseName, ["sha1"] = m.Sha1, ["enabled"] = m.Enabled });
        if (list.Count == 0) return "This profile has no mods to share.";
        var pack = new JsonObject { ["minecraft"] = "1.20.1", ["mods"] = list };
        var r = await Ask(new JsonObject { ["t"] = "share", ["to"] = uuid, ["name"] = profile.Name, ["pack"] = pack });
        return r["t"]?.ToString() == "ok" ? null : r["error"]?.ToString() ?? "Not sent.";
    }

    public static async Task<PackResult> ImportShare(Shared s)
    {
        var dir = Path.Combine(Paths.Data, "packs");
        Directory.CreateDirectory(dir);
        var file = Path.Combine(dir, "shared-" + Guid.NewGuid().ToString("N") + ".zip");
        try
        {
            var index = (JsonObject)s.Pack.DeepClone();
            index["created"] = s.Name + " from " + s.FromName;
            using (var zip = System.IO.Compression.ZipFile.Open(file, System.IO.Compression.ZipArchiveMode.Create))
            {
                using var w = new StreamWriter(zip.CreateEntry("pentecost-pack.json").Open());
                w.Write(index.ToJsonString());
            }
            var result = await Packs.ImportPentecost(file, CancellationToken.None);
            lock (Gate) shares.Remove(s);
            Raise();
            return result;
        }
        finally { try { File.Delete(file); } catch { } }
    }

    public static void Dismiss(Shared s) { lock (Gate) shares.Remove(s); Raise(); }

    public static async Task<string?> SetAllowJoin(bool on)
    {
        var r = await Ask(new JsonObject { ["t"] = "allowJoin", ["v"] = on });
        if (r["t"]?.ToString() != "ok") return r["error"]?.ToString() ?? "Not saved.";
        AllowJoin = on;
        Raise();
        return null;
    }

    public static Task NewCode() => Tell(new JsonObject { ["t"] = "newCode" });

    public static Task SayStatus(string profile) =>
        Tell(new JsonObject { ["t"] = "status", ["s"] = new JsonObject { ["state"] = "menu", ["profile"] = profile } });

    public static string Pretty(string? code) => code is { Length: 10 } ? code[..5] + "-" + code[5..] : code ?? "";

    public static string Doing(Friend f)
    {
        if (!f.Online) return "Offline";
        var s = f.Status;
        var state = s?["state"]?.ToString();
        var world = s?["world"]?.ToString();
        var server = s?["server"]?.ToString();
        return state switch
        {
            "singleplayer" => world != null ? "Playing " + world : "In a world",
            "multiplayer" => server != null ? "On " + server : "On a server",
            "idle" => "Away",
            _ => s?["profile"]?.ToString() is { Length: > 0 } p ? "In the menu, profile " + p : "Online",
        };
    }

    static string SentFile => Path.Combine(Paths.Data, "crash-sent.txt");

    public static async Task ReportCrashes(List<CrashInfo> crashes)
    {
        if (!Settings.CrashCounts) return;
        var s = LoadSession();
        if (s == null) return;
        HashSet<string> sent;
        try { sent = File.Exists(SentFile) ? File.ReadAllLines(SentFile).ToHashSet(StringComparer.OrdinalIgnoreCase) : new(); }
        catch { sent = new(); }
        foreach (var c in crashes.Where(c => c.Mod != null && (DateTime.Now - c.Time).TotalDays < 7))
        {
            var key = Path.GetFileName(c.File);
            if (!sent.Add(key)) continue;
            string Clean(string v, int max) => new string(v.Where(ch => char.IsLetterOrDigit(ch) || "._+-: ".Contains(ch)).Take(max).ToArray()).Trim();
            var body = new JsonObject
            {
                ["mod"] = Clean(c.Mod!.ProjectId ?? c.Mod.Id, 64),
                ["version"] = Clean(c.Mod.Version.Length > 0 ? c.Mod.Version : "unknown", 64),
                ["kind"] = Clean(c.Title, 96),
            };
            if (((string?)body["mod"])!.Length == 0) continue;
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Post, Server + "/crash") { Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json") };
                req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", s.Token);
                using var _ = await Http.SendAsync(req);
            }
            catch (Exception ex) { AppLog.Write("crash count: " + ex.Message); }
        }
        try { File.WriteAllLines(SentFile, sent.TakeLast(500)); } catch { }
    }
}

public static class Settings
{
    static string FileName => Path.Combine(Paths.Data, "settings.json");

    static JsonObject? cache;

    public static event Action? Changed;

    static JsonObject Load()
    {
        if (cache != null) return cache;
        try { cache = File.Exists(FileName) && JsonNode.Parse(File.ReadAllText(FileName)) is JsonObject o ? o : new(); }
        catch { cache = new(); }
        return cache;
    }

    static void Put(string key, JsonNode? value)
    {
        var o = Load();
        o[key] = value;
        try { File.WriteAllText(FileName, o.ToJsonString()); } catch (Exception ex) { AppLog.Write("settings: " + ex.Message); }
        Changed?.Invoke();
    }

    static bool B(string key, bool fallback) { try { return Load()[key]?.GetValue<bool>() ?? fallback; } catch { return fallback; } }
    static int I(string key, int fallback) { try { return Load()[key]?.GetValue<int>() ?? fallback; } catch { return fallback; } }
    static string S(string key, string fallback) => Load()[key]?.ToString() is { Length: > 0 } s ? s : fallback;

    public static bool Motion { get => B("motion", true); set => Put("motion", value); }
    public static bool Storm { get => B("storm", true); set => Put("storm", value); }
    public static bool HighGpu { get => B("highGpu", true); set => Put("highGpu", value); }
    public static bool ShowBeta { get => B("showBeta", false); set => Put("showBeta", value); }
    public static bool DiscordStatus { get => B("discordStatus", true); set => Put("discordStatus", value); }
    public static int Fps { get => I("fps", 60); set => Put("fps", value); }
    public static bool PausePlaying { get => B("pausePlaying", true); set => Put("pausePlaying", value); }
    public static bool PauseBattery { get => B("pauseBattery", false); set => Put("pauseBattery", value); }
    public static bool StartWithWindows { get => B("startWithWindows", false); set => Put("startWithWindows", value); }
    public static string CloseAction { get => S("closeAction", "quit"); set => Put("closeAction", value); }
    public static bool CrashJump { get => B("crashJump", true); set => Put("crashJump", value); }
    public static bool AutoUpdate { get => B("autoUpdate", true); set => Put("autoUpdate", value); }
    public static bool UpdateOnClose { get => B("updateOnClose", true); set => Put("updateOnClose", value); }
    public static bool MessagePopups { get => B("messagePopups", true); set => Put("messagePopups", value); }
    public static bool LogAutoLoad { get => B("logAutoLoad", true); set => Put("logAutoLoad", value); }
    public static bool LogWrap { get => B("logWrap", true); set => Put("logWrap", value); }
    public static int LogLines { get => I("logLines", 5000); set => Put("logLines", value); }

    public static string OpenOn { get => S("openOn", "Last"); set => Put("openOn", value); }
    public static string OnLaunch { get => S("onLaunch", "stay"); set => Put("onLaunch", value); }

    public static bool StarChart { get => B("starChart", true); set => Put("starChart", value); }
    public static int Stars { get => I("stars", 40); set => Put("stars", value); }
    public static string ModSort { get => S("modSort", "Name"); set => Put("modSort", value); }
    public static bool UpdateCheck { get => B("updateCheck", true); set => Put("updateCheck", value); }

    public static string BrowseLoader { get => S("browseLoader", "All"); set => Put("browseLoader", value); }
    public static string BrowseSort { get => S("browseSort", "relevance"); set => Put("browseSort", value); }
    public static bool PackDetails { get => B("packDetails", true); set => Put("packDetails", value); }

    public static bool ReuseMods { get => B("reuseMods", true); set => Put("reuseMods", value); }

    public static bool FriendsOnline { get => B("friendsOnline", true); set => Put("friendsOnline", value); }

    public static bool CrashCounts
    {
        get => Load()["crashCounts"]?.GetValue<bool>() == true;
        set => Put("crashCounts", value);
    }

    public static string SeenWhatsNew
    {
        get => Load()["seenWhatsNew"]?.ToString() ?? "";
        set => Put("seenWhatsNew", value);
    }

    public static HashSet<string> Dismissed
    {
        get => (Load()["dismissed"] as JsonArray)?.Select(x => x?.ToString() ?? "").ToHashSet() ?? new();
        set => Put("dismissed", new JsonArray(value.TakeLast(200).Select(x => (JsonNode?)JsonValue.Create(x)).ToArray()));
    }
}
