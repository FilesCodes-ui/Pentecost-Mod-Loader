using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public enum LaunchMode { Standalone, Launcher, Source }

/// <summary>The manager's own log, in its data folder. Never throws.</summary>
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

/// <summary>Where things are. The Pentecost home is the folder that holds lib, cache and run.</summary>
public static class Paths
{
    static readonly string AppData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
    public static string Data => Directory.CreateDirectory(Path.Combine(AppData, "PentecostManager")).FullName;
    public static string DefaultHome => Path.Combine(AppData, ".minecraft", "pentecost");
    public static string Home { get; set; } = DefaultHome;
    /// <summary>The .minecraft folder (the parent of the Pentecost home).</summary>
    public static string McDir => Path.GetDirectoryName(Home.TrimEnd('\\', '/')) ?? Home;
    /// <summary>Set only when dev.json names an existing source folder. Unlocks "Run from source".</summary>
    public static string DevSource { get; private set; } = "";

    public static string Mods => Path.Combine(Home, "run", "mods");
    public static string Crashes => Path.Combine(Home, "run", "crash-reports");
    /// <summary>Where the game's own Mods screen keeps its switches, among them the list of mods turned off.</summary>
    public static string Choices => Path.Combine(Home, "run", "config", "pentecost", "choices.json");
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
                DevSource = s;
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

    /// <summary>Where the window was last time, as x,y,width,height,maximised.</summary>
    public static string Window
    {
        get => Read("window.txt", "");
        set => Store("window.txt", value);
    }

    /// <summary>The tab that was open last time.</summary>
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

    /// <summary>Installed Pentecost version: version.txt (written by the installer), else lib\pentecost-X.jar.</summary>
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

    /// <summary>The saved sign-in file the game writes. Only the name is read here; the token stays encrypted and untouched.</summary>
    public static IEnumerable<string> AccountFiles()
    {
        yield return Path.Combine(Home, "pentecost-account.json");
        yield return Path.Combine(Home, "run", "pentecost-account.json");
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
            try { if (File.Exists(f)) File.Delete(f); } catch (Exception ex) { AppLog.Write("SignOut: " + ex.Message); }
    }
}
