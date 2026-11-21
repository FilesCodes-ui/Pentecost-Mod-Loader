using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public enum LaunchMode { Standalone, Launcher, Source }

public static class AppLog
{
    static readonly object Gate = new();
    public static string File_ => Path.Combine(Paths.Data, "manager.log");

    public static void Write(string line)
    {
        try
        {
            lock (Gate)
            {
                var f = File_;
                if (File.Exists(f) && new FileInfo(f).Length > 400_000)
                    File.WriteAllText(f, File.ReadAllText(f)[^100_000..]);
                File.AppendAllText(f, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {line}{Environment.NewLine}");
            }
        }
        catch { }
    }
}

public static class Paths
{
    static readonly string AppData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
    public static string Data => Directory.CreateDirectory(Environment.GetEnvironmentVariable("PENTECOST_MANAGER_DATA") is { Length: > 0 } d ? d : Path.Combine(AppData, "PentecostManager")).FullName;
    public static string DefaultHome => Path.Combine(AppData, ".minecraft", "pentecost");
    public static string Home { get; set; } = DefaultHome;
    public static string McDir => Path.GetDirectoryName(Home.TrimEnd('\\', '/')) ?? Home;
    public static string DevSource => devSource.Length > 0 && DevAccess.Unlocked ? devSource : "";
    static string devSource = "";

    public static string Mods => Path.Combine(Home, "run", "mods");
    public static string Crashes => Path.Combine(Home, "run", "crash-reports");
    public static string Choices => Path.Combine(Home, "run", "config", "pentecost", "choices.json");
    public static string GameSettings => Path.Combine(Home, "run", "config", "pentecost", "settings.json");
    public static string Logs => Path.Combine(Home, "run", "logs");
    public static bool Installed => Directory.Exists(Path.Combine(Home, "lib"));

    public static void Load()
    {
        try
        {
            var cfg = Path.Combine(Data, "config.json");
            if (File.Exists(cfg) && JsonNode.Parse(File.ReadAllText(cfg)) is JsonObject o && o["home"]?.ToString() is { Length: > 0 } h)
                Home = h;
            var dev = Path.Combine(Data, "dev.json");
            if (File.Exists(dev) && JsonNode.Parse(File.ReadAllText(dev)) is JsonObject d
                && d["source"]?.ToString() is { Length: > 0 } s && Directory.Exists(s))
                devSource = s;
        }
        catch (Exception ex) { AppLog.Write("Paths.Load: " + ex.Message); }
    }

    public static void Save()
    {
        try { File.WriteAllText(Path.Combine(Data, "config.json"), new JsonObject { ["home"] = Home }.ToJsonString()); }
        catch (Exception ex) { AppLog.Write("Paths.Save: " + ex.Message); }
    }

    public static string Mode
    {
        get { try { var f = Path.Combine(Data, "mode.txt"); return File.Exists(f) ? File.ReadAllText(f).Trim() : "Standalone"; } catch { return "Standalone"; } }
        set { try { File.WriteAllText(Path.Combine(Data, "mode.txt"), value); } catch { } }
    }

    public static string Window
    {
        get => Read("window.txt", "");
        set => Store("window.txt", value);
    }

    public static string LastTab
    {
        get => Read("tab.txt", "Play");
        set => Store("tab.txt", value);
    }

    static string Read(string file, string fallback)
    {
        try { var p = Path.Combine(Data, file); return File.Exists(p) ? File.ReadAllText(p).Trim() : fallback; }
        catch { return fallback; }
    }

    static void Store(string file, string value)
    {
        try { File.WriteAllText(Path.Combine(Data, file), value); } catch { }
    }

    public static string? Version
    {
        get
        {
            try
            {
                var vt = Path.Combine(Home, "version.txt");
                if (File.Exists(vt) && File.ReadAllText(vt).Trim() is { Length: > 0 } v) return v;
                var lib = Path.Combine(Home, "lib");
                if (!Directory.Exists(lib)) return null;
                foreach (var f in Directory.GetFiles(lib, "pentecost-*.jar"))
                {
                    var n = Path.GetFileNameWithoutExtension(f);
                    if (n.Contains("gameglue") || n.Contains("forgeglue")) continue;
                    return n["pentecost-".Length..];
                }
            }
            catch { }
            return null;
        }
    }

    public static IEnumerable<string> AccountFiles()
    {
        yield return Path.Combine(Home, "pentecost-account.json");
        yield return Path.Combine(Home, "run", "pentecost-account.json");
    }

    public static string? AccountUuid()
    {
        foreach (var f in AccountFiles())
        {
            try
            {
                if (File.Exists(f) && JsonNode.Parse(File.ReadAllText(f)) is JsonObject o && o["uuid"]?.ToString() is { Length: > 0 } id)
                    return id;
            }
            catch { }
        }
        return null;
    }

    public static string? AccountName()
    {
        foreach (var f in AccountFiles())
        {
            try
            {
                if (File.Exists(f) && JsonNode.Parse(File.ReadAllText(f)) is JsonObject o && o["name"]?.ToString() is { Length: > 0 } n)
                    return n;
            }
            catch { }
        }
        return null;
    }

    public static void SignOut()
    {
        foreach (var f in AccountFiles())
        {
            try
            {
                if (!File.Exists(f)) continue;
                if (JsonNode.Parse(File.ReadAllText(f)) is JsonObject o && o["uuid"]?.ToString() is { Length: > 0 } id
                    && !id.Contains('/') && !id.Contains('\\') && !id.Contains(".."))
                {
                    var copy = Path.Combine(Path.GetDirectoryName(f)!, "pentecost-accounts", id + ".json");
                    if (File.Exists(copy)) File.Delete(copy);
                }
                File.Delete(f);
            }
            catch (Exception ex) { AppLog.Write("SignOut: " + ex.Message); }
        }
    }

    public static List<string> SavedAccounts()
    {
        var names = new List<string>();
        foreach (var dir in new[] { Path.Combine(Home, "run", "pentecost-accounts"), Path.Combine(Home, "pentecost-accounts") })
        {
            try
            {
                if (!Directory.Exists(dir)) continue;
                foreach (var f in Directory.GetFiles(dir, "*.json"))
                    if (JsonNode.Parse(File.ReadAllText(f)) is JsonObject o && o["name"]?.ToString() is { Length: > 0 } n && !names.Contains(n))
                        names.Add(n);
            }
            catch { }
        }
        names.Sort(StringComparer.OrdinalIgnoreCase);
        return names;
    }

    public static string? FriendCode()
    {
        try
        {
            var f = Path.Combine(Home, "run", "config", "pentecost", "friends.json");
            if (File.Exists(f) && JsonNode.Parse(File.ReadAllText(f)) is JsonObject o && o["myCode"]?.ToString() is { Length: 10 } c)
                return c;
        }
        catch { }
        return null;
    }
}
