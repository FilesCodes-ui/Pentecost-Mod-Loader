using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public static class DiscordPresence
{
    const string App = "1573450531718955129";
    static readonly long Started = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    static NamedPipeClientStream? pipe;
    static string sent = "";
    static bool cleared = true;

    public static volatile string Page = "";

    public static void Start()
    {
        var t = new Thread(Loop) { IsBackground = true, Name = "discord-presence", Priority = ThreadPriority.Lowest };
        t.Start();
    }

    static string Doing(string page) => page switch
    {
        "Mods" => "Managing mods",
        "Browse" => "Browsing mods",
        "Updates" => "Updating mods",
        "Profiles" => "Sorting profiles",
        "Friends" => "Checking on friends",
        "Crashes" => "Reading crash reports",
        "Settings" => "In settings",
        _ => "In the Manager"
    };

    static string Mods()
    {
        try
        {
            var dir = Path.Combine(Instances.Dir(ProfileStore.Active), "mods");
            var n = Directory.EnumerateFiles(dir).Count(f => f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase));
            return n == 1 ? "1 mod" : n + " mods";
        }
        catch { return "Pentecost"; }
    }

    static void Loop()
    {
        Thread.Sleep(4000);
        while (true)
        {
            try { Tick(); }
            catch { Close(); }
            Thread.Sleep(2000);
        }
    }

    static void Tick()
    {
        var off = !Settings.DiscordStatus || Instances.GameRunning() || GameAlive();
        if (off)
        {
            if (pipe != null && !cleared) { Send(1, Activity(null)); cleared = true; sent = ""; }
            Close();
            return;
        }
        if (pipe == null && !Open()) return;
        var act = new JsonObject
        {
            ["details"] = Doing(Page),
            ["state"] = ProfileStore.Active.Name + " · " + Mods(),
            ["timestamps"] = new JsonObject { ["start"] = Started },
            ["assets"] = new JsonObject { ["large_image"] = "pentecost", ["large_text"] = "Pentecost mod loader" },
            ["buttons"] = new JsonArray(new JsonObject { ["label"] = "Get Pentecost", ["url"] = "https://github.com/FilesCodes-ui/Pentecost-Mod-Loader" })
        };
        var text = act.ToJsonString();
        if (text == sent) return;
        Send(1, Activity(act));
        sent = text;
        cleared = false;
    }

    static bool GameAlive()
    {
        try
        {
            var f = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PentecostManager", "game-alive");
            return File.Exists(f) && (DateTime.UtcNow - File.GetLastWriteTimeUtc(f)).TotalSeconds < 20;
        }
        catch { return false; }
    }

    static JsonObject Activity(JsonObject? act)
    {
        var args = new JsonObject { ["pid"] = Environment.ProcessId };
        if (act != null) args["activity"] = act;
        return new JsonObject { ["cmd"] = "SET_ACTIVITY", ["args"] = args, ["nonce"] = Guid.NewGuid().ToString() };
    }

    static bool Open()
    {
        for (var i = 0; i < 10; i++)
        {
            try
            {
                var p = new NamedPipeClientStream(".", "discord-ipc-" + i, PipeDirection.InOut);
                p.Connect(300);
                pipe = p;
                Send(0, new JsonObject { ["v"] = 1, ["client_id"] = App });
                sent = "";
                cleared = true;
                return true;
            }
            catch { Close(); }
        }
        return false;
    }

    static void Send(int op, JsonObject body)
    {
        var p = pipe ?? throw new IOException("closed");
        var data = Encoding.UTF8.GetBytes(body.ToJsonString());
        var head = new byte[8];
        BitConverter.TryWriteBytes(head.AsSpan(0, 4), op);
        BitConverter.TryWriteBytes(head.AsSpan(4, 4), data.Length);
        p.Write(head);
        p.Write(data);
        p.Flush();
        var h = new byte[8];
        p.ReadExactly(h);
        var len = BitConverter.ToInt32(h, 4);
        if (len < 0 || len > 1_000_000) throw new IOException("bad frame");
        var reply = new byte[len];
        p.ReadExactly(reply);
        if (BitConverter.ToInt32(h, 0) == 2) throw new IOException("closed");
    }

    static void Close()
    {
        try { pipe?.Dispose(); } catch { }
        pipe = null;
    }
}
