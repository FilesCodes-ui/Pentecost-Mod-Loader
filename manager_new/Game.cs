using System.Diagnostics;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Pentecost.Manager;

public static class JavaFinder
{
    public static string? Find()
    {
        var direct = new List<string>();
        var env = Environment.GetEnvironmentVariable("PENTECOST_JAVA");
        if (!string.IsNullOrWhiteSpace(env)) direct.Add(env);
        var jt = Path.Combine(Paths.Home, "java.txt");
        try
        {
            if (File.Exists(jt) && File.ReadLines(jt).FirstOrDefault()?.Trim() is { Length: > 0 } first) direct.Add(first);
        }
        catch { }
        var jh = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(jh)) direct.Add(Path.Combine(jh, "bin", "java.exe"));
        foreach (var c in direct) if (File.Exists(c) && Major(c) >= 21) return c;

        var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var roots = new[]
        {
            Path.Combine(Paths.McDir, "runtime", "java-runtime-delta"),
            Path.Combine(appData, "ModrinthApp", "meta", "java_versions"),
            Path.Combine(profile, ".gradle", "jdks"), Path.Combine(profile, ".jdks"),
            @"C:\Program Files\Eclipse Adoptium", @"C:\Program Files\Java", @"C:\Program Files\Microsoft",
        };
        foreach (var r in roots)
        {
            if (!Directory.Exists(r)) continue;
            try
            {
                foreach (var f in Directory.EnumerateFiles(r, "java.exe", SearchOption.AllDirectories))
                    if (Major(f) >= 21) return f;
            }
            catch { }
        }
        return Major("java") >= 21 ? "java" : null;
    }

    public static string? Choose(string exe)
    {
        if (!File.Exists(exe)) return "That file does not exist.";
        var v = Major(exe);
        if (v < 21) return v == 0 ? "That file did not answer as Java." : $"That is Java {v}. Pentecost needs Java 21 or newer.";
        try { Directory.CreateDirectory(Paths.Home); File.WriteAllText(Path.Combine(Paths.Home, "java.txt"), exe); }
        catch (Exception ex) { return ex.Message; }
        return null;
    }

    static int Major(string exe)
    {
        try
        {
            var psi = new ProcessStartInfo(exe, "-version") { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
            using var p = Process.Start(psi);
            if (p == null) return 0;
            var err = p.StandardError.ReadToEndAsync();
            var outp = p.StandardOutput.ReadToEndAsync();
            if (!p.WaitForExit(6000)) { try { p.Kill(true); } catch { } return 0; }
            var m = Regex.Match(err.Result + outp.Result, "version \"(\\d+)");
            return m.Success ? int.Parse(m.Groups[1].Value) : 0;
        }
        catch { return 0; }
    }
}

public sealed class GameRun
{
    Process? p;
    public event Action<string>? Line;
    public event Action<int>? Exited;
    public bool Running => p is { HasExited: false };
    public Process? Proc => p;

    public static (int Heap, int Young) AutoMemory()
    {
        var gb = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes / (1024.0 * 1024 * 1024);
        return gb >= 24 ? (8, 4) : gb >= 16 ? (6, 3) : gb >= 12 ? (5, 2) : gb >= 8 ? (4, 2) : (3, 1);
    }

    public static double TotalGb => GC.GetGCMemoryInfo().TotalAvailableMemoryBytes / (1024.0 * 1024 * 1024);

    public static int AutoReserve(int heap)
    {
        var gb = (int)TotalGb;
        return FitReserve(heap, gb >= 12 ? 2 : gb >= 8 ? 1 : 0);
    }

    public static int FitReserve(int heap, int reserve) => Math.Max(0, Math.Min(Math.Min(16, reserve), (int)TotalGb - 2 - heap));

    public static (int Heap, int Reserve, int Young) PlanFor(ModProfile p)
    {
        var heap = p.MemoryGb > 0 ? p.MemoryGb : AutoMemory().Heap;
        var reserve = p.ReserveGb >= 0 ? FitReserve(heap, p.ReserveGb) : AutoReserve(heap);
        return (heap, reserve, Math.Max(1, heap / 2));
    }

    public static string MemoryText(ModProfile p, string auto)
    {
        var (heap, reserve, _) = PlanFor(p);
        return (p.MemoryGb > 0 ? "" : auto + ", ") + heap + " GB" + (reserve > 0 ? " + " + reserve + " GB backup" : "");
    }

    public static void SyncGameSettings(ModProfile p)
    {
        if (p.MemoryGb <= 0 && p.ReserveGb < 0) return;
        try
        {
            var file = Paths.GameSettings;
            Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            var root = File.Exists(file) ? JsonNode.Parse(File.ReadAllText(file)) as JsonObject ?? new JsonObject() : new JsonObject();
            var changed = false;
            void Put(string key, int value)
            {
                if (root[key]?.ToString() == value.ToString()) return;
                root[key] = value.ToString();
                changed = true;
            }
            if (p.MemoryGb > 0) Put("java.memory", p.MemoryGb);
            if (p.ReserveGb >= 0) Put("java.reserve", FitReserve(p.MemoryGb > 0 ? p.MemoryGb : AutoMemory().Heap, p.ReserveGb));
            if (!changed) return;
            var tmp = file + ".pentecost-tmp";
            File.WriteAllText(tmp, root.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, file, true);
        }
        catch (Exception ex) { AppLog.Write("game settings: " + ex.Message); }
    }

    public string? Start(LaunchMode mode, string? java, ModProfile profile, string? world = null)
    {
        if (Running) return "The game is already running.";
        ProcessStartInfo psi;
        switch (mode)
        {
            case LaunchMode.Launcher: return OpenLauncher(profile);
            case LaunchMode.Source:
                if (Paths.DevSource.Length == 0) return "No source folder is set on this account.";
                psi = new ProcessStartInfo("cmd.exe", "/c .tools\\gradle-8.14.5\\bin\\gradle.bat runPlay --console=plain -q")
                { WorkingDirectory = Paths.DevSource };
                break;
            default:
                if (!Paths.Installed) return "Pentecost is not installed at " + Paths.Home + ".";
                if (java == null) return "Java 21 was not found. Choose java.exe in Settings.";
                var err = Standalone(java, profile, out psi!);
                if (err != null) return err;
                if (world != null) psi.Environment["PENTECOST_WORLD"] = world;
                break;
        }
        psi.UseShellExecute = false;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.CreateNoWindow = true;
        var proc = new Process { StartInfo = psi, EnableRaisingEvents = true };
        proc.OutputDataReceived += (_, e) => { if (e.Data != null) Line?.Invoke(e.Data); };
        proc.ErrorDataReceived += (_, e) => { if (e.Data != null) Line?.Invoke(e.Data); };
        proc.Exited += (_, _) => { try { proc.WaitForExit(); Exited?.Invoke(proc.ExitCode); } catch { } };
        try
        {
            proc.Start();
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
        }
        catch (Exception ex) { return "Could not start: " + ex.Message; }
        p = proc;
        return null;
    }

    public void Stop() { try { p?.Kill(true); } catch { } }

    const string LauncherAppId = "Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft";

    public static bool LauncherRunning() =>
        Process.GetProcessesByName("MinecraftLauncher").Length > 0 || Process.GetProcessesByName("Minecraft.Launcher").Length > 0;

    static string? PrepareLauncherProfile(ModProfile profile)
    {
        try
        {
            var file = Path.Combine(Paths.McDir, "launcher_profiles.json");
            if (!File.Exists(file)) return "launcher_profiles.json was not found.";
            if (JsonNode.Parse(File.ReadAllText(file)) is not JsonObject root || root["profiles"] is not JsonObject profiles
                || profiles["pentecost"] is not JsonObject prof)
                return "The Pentecost profile is not in the launcher yet. Install Pentecost first.";
            var bak = file + ".pentecost-manager.bak";
            if (!File.Exists(bak)) File.Copy(file, bak);
            prof["lastUsed"] = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
            var (heap, reserve, young) = PlanFor(profile);
            prof["javaArgs"] = $"-Xms{heap}G -Xmx{heap + reserve}G -Xmn{young}G -XX:-DontCompileHugeMethods {profile.JvmArgs}".Trim();
            SyncGameSettings(profile);
            var tmp = file + ".pentecost-tmp";
            File.WriteAllText(tmp, root.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, file, true);
            return null;
        }
        catch (Exception ex) { return ex.Message; }
    }

    string? OpenLauncher(ModProfile profile)
    {
        if (LauncherRunning())
        {
            Line?.Invoke("The Minecraft Launcher is already open. Pick the Pentecost profile there and press Play.");
            return null;
        }
        var note = PrepareLauncherProfile(profile);
        var started = false;
        try
        {
            Process.Start(new ProcessStartInfo("shell:AppsFolder\\" + LauncherAppId) { UseShellExecute = true });
            started = true;
        }
        catch { }
        if (!started)
        {
            var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var exe = new[]
            {
                @"C:\Program Files (x86)\Minecraft Launcher\MinecraftLauncher.exe",
                @"C:\Program Files\Minecraft Launcher\MinecraftLauncher.exe",
                Path.Combine(local, "Programs", "Minecraft Launcher", "MinecraftLauncher.exe"),
            }.FirstOrDefault(File.Exists);
            if (exe == null) return "The Minecraft Launcher was not found. Install it, or use Standalone.";
            try { Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true }); }
            catch (Exception ex) { return "Could not open the launcher: " + ex.Message; }
        }
        Line?.Invoke(note == null
            ? "Minecraft Launcher opened with Pentecost first in its list. Press Play there."
            : "Minecraft Launcher opened. Pick the Pentecost profile and press Play. (Could not prepare it: " + note + ")");
        return null;
    }

    static string? Standalone(string java, ModProfile profile, out ProcessStartInfo? psi)
    {
        psi = null;
        var home = Paths.Home;
        var lib = Path.Combine(home, "lib");
        var jar = Directory.GetFiles(lib, "pentecost-*.jar").Select(f => Path.GetFileName(f))
            .FirstOrDefault(n => !n.Contains("gameglue") && !n.Contains("forgeglue"));
        if (jar == null) return "lib is missing from " + home + ". Reinstall Pentecost.";
        var version = Path.GetFileNameWithoutExtension(jar)["pentecost-".Length..];

        var cp = Path.Combine(lib, "*");
        var sec = Path.Combine(home, "cache", "forge-install", "libraries", "cpw", "mods", "securejarhandler");
        if (Directory.Exists(sec) && Directory.GetDirectories(sec).OrderBy(x => x).LastOrDefault() is { } d)
            cp += ";" + Path.Combine(d, "*");

        var args = new List<string>();
        var flags = Path.Combine(home, "jvm-args.txt");
        SyncGameSettings(profile);
        var (heap, reserve, young) = PlanFor(profile);
        if (profile.MemoryGb <= 0 && File.Exists(flags))
            args.AddRange(File.ReadAllLines(flags).Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('#')));
        else
            args.AddRange(new[] { $"-Xms{heap}G", $"-Xmx{heap + reserve}G", $"-Xmn{young}G" });
        if (profile.JvmArgs.Trim().Length > 0)
            args.AddRange(profile.JvmArgs.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        args.AddRange(new[]
        {
            "-Dpentecost.home=" + home,
            "-Dpentecost.gameglue=" + Path.Combine(lib, "glue", $"pentecost-gameglue-{version}.jar"),
            "-Dpentecost.forgeglue=" + Path.Combine(lib, "glue", $"pentecost-forgeglue-{version}.jar"),
            "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.util=ALL-UNNAMED",
            "-XX:-DontCompileHugeMethods",
            "-cp", cp,
            "khi.pentecost.boot.PentecostMain", "launch", "--forge",
        });
        var run = Path.Combine(home, "run");
        Directory.CreateDirectory(run);
        psi = new ProcessStartInfo(java) { WorkingDirectory = run };
        foreach (var a in args) psi.ArgumentList.Add(a);
        return null;
    }
}
