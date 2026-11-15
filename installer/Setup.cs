using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pentecost.Installer;

public sealed record Progress(string Step, string Detail, double Fraction);

public sealed record InstallResult(string Version, int WorldsFailed);

/// <summary>An error whose message is meant for the person running the installer.</summary>
public sealed class SetupException : Exception
{
    public SetupException(string message) : base(message) { }
}

public static class Log
{
    static readonly string File_ = Path.Combine(Path.GetTempPath(), "pentecost-installer.log");
    public static string Path_ => File_;

    public static void Write(string line)
    {
        try { File.AppendAllText(File_, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {line}{Environment.NewLine}"); }
        catch { /* logging must never break the install */ }
    }
}

public static class Setup
{
    const string ProfileKey = "pentecost";
    static readonly JsonSerializerOptions Pretty = new() { WriteIndented = true };

    public static readonly string DefaultMinecraftDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft");

    public static string HomeOf(string mc) => Path.Combine(mc, "pentecost");
    static string ProfilesFile(string mc) => Path.Combine(mc, "launcher_profiles.json");

    public static string? InstalledVersion(string mc)
    {
        var f = Path.Combine(HomeOf(mc), "version.txt");
        return File.Exists(f) ? File.ReadAllText(f).Trim() : null;
    }

    static Stream OpenPayload() =>
        Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip")
        ?? throw new SetupException("This installer is damaged. Download it again.");

    static string Norm(string zipName) => zipName.Replace('\\', '/');

    public static string PayloadVersion()
    {
        using var zip = new ZipArchive(OpenPayload(), ZipArchiveMode.Read);
        foreach (var e in zip.Entries)
        {
            var n = Norm(e.FullName);
            if (n.StartsWith("payload/lib/pentecost-") && n.EndsWith(".jar") && !n.Contains("gameglue") && !n.Contains("forgeglue"))
                return Path.GetFileNameWithoutExtension(n)["pentecost-".Length..];
        }
        throw new SetupException("This installer is damaged (no Pentecost program inside). Download it again.");
    }

    public static bool LauncherRunning(string mc)
    {
        // Only the real launcher folder is protected. A test folder somewhere else is not.
        var a = Path.GetFullPath(mc).TrimEnd('\\');
        var b = Path.GetFullPath(DefaultMinecraftDir).TrimEnd('\\');
        if (!string.Equals(a, b, StringComparison.OrdinalIgnoreCase)) return false;
        foreach (var name in new[] { "MinecraftLauncher", "Minecraft.Launcher" })
            if (Process.GetProcessesByName(name).Length > 0) return true;
        return false;
    }

    public static void Preflight(string mc)
    {
        if (!Directory.Exists(mc))
            throw new SetupException($"I cannot find {mc}.\n\nInstall the Minecraft Launcher, open it once and sign in, then run this again.");
        if (!File.Exists(ProfilesFile(mc)))
            throw new SetupException("I cannot find launcher_profiles.json in the Minecraft folder.\n\nOpen the Minecraft Launcher once, sign in, close it, then run this again.");
        if (LauncherRunning(mc))
            throw new SetupException("The Minecraft Launcher is open.\n\nClose it completely, also from the system tray, then press Retry. The launcher rewrites its profile file when it closes.");
    }

    // ---------------------------------------------------------------- install

    public static async Task<InstallResult> InstallAsync(string mc, bool downloadWorlds, IProgress<Progress> progress, CancellationToken ct)
    {
        Preflight(mc);
        var version = PayloadVersion();
        var versionId = "Pentecost-" + version;
        var home = HomeOf(mc);
        Log.Write($"install {version} into {mc} worlds={downloadWorlds}");

        progress.Report(new("Copying program files", "", 0.03));
        await Task.Run(() => CopyPayload(home, version), ct);

        progress.Report(new("Adding Pentecost to the launcher", "", 0.30));
        await Task.Run(() =>
        {
            WriteLauncherVersion(mc, home, version, versionId);
            WriteProfile(mc, home, versionId);
        }, ct);

        var failed = 0;
        if (downloadWorlds)
        {
            failed = await Worlds.DownloadAsync(home, (f, detail) =>
                progress.Report(new("Downloading the main menu worlds", detail, 0.35 + 0.63 * f)), ct);
        }

        progress.Report(new("Done", "", 1.0));
        Log.Write($"install finished, worldsFailed={failed}");
        return new InstallResult(version, failed);
    }

    static void ExtractTo(ZipArchiveEntry e, string root, string relative)
    {
        var rootFull = Path.GetFullPath(root);
        var dest = Path.GetFullPath(Path.Combine(rootFull, relative.Replace('/', Path.DirectorySeparatorChar)));
        if (!dest.StartsWith(rootFull + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new SetupException("The installer holds an unsafe file path. Download it again.");
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        e.ExtractToFile(dest, true);
    }

    static void CopyPayload(string home, string version)
    {
        Directory.CreateDirectory(home);
        // The old jars go first so versions do not pile up. Worlds, mods and settings (run\) and downloads (cache\) stay.
        foreach (var d in new[] { "lib", "shaderpack" })
        {
            var p = Path.Combine(home, d);
            if (Directory.Exists(p)) Directory.Delete(p, true);
        }
        using var zip = new ZipArchive(OpenPayload(), ZipArchiveMode.Read);
        foreach (var e in zip.Entries)
        {
            var n = Norm(e.FullName);
            if (n.EndsWith('/')) continue;
            string? rel = null;
            if (n.StartsWith("payload/")) rel = n["payload/".Length..];
            else if (n is "LICENSE.txt" or "THIRD-PARTY-NOTICES.txt") rel = n;
            if (rel != null) ExtractTo(e, home, rel);
        }
        File.WriteAllText(Path.Combine(home, "version.txt"), version, Encoding.ASCII);
        Directory.CreateDirectory(Path.Combine(home, "run", "mods"));
    }

    static string Now() => DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'");

    static void WriteLauncherVersion(string mc, string home, string version, string versionId)
    {
        // The launcher version builds on vanilla 1.20.1 for the launcher's own bookkeeping, then replaces the main class
        // and the class path. The launcher puts its own -cp first and Java uses the LAST -cp, so this one wins.
        var lib = Path.Combine(home, "lib");
        var now = Now();
        var jvm = new JsonArray
        {
            $"-Dpentecost.home={home}",
            $"-Dpentecost.gameglue={Path.Combine(lib, $"pentecost-gameglue-{version}.jar")}",
            $"-Dpentecost.forgeglue={Path.Combine(lib, $"pentecost-forgeglue-{version}.jar")}",
            "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.util=ALL-UNNAMED",
            "-XX:-DontCompileHugeMethods",
            "-cp", Path.Combine(lib, "*"),
        };
        var v = new JsonObject
        {
            ["id"] = versionId,
            ["inheritsFrom"] = "1.20.1",
            ["type"] = "release",
            ["time"] = now,
            ["releaseTime"] = now,
            ["mainClass"] = "khi.pentecost.boot.PentecostMain",
            ["javaVersion"] = new JsonObject { ["component"] = "java-runtime-delta", ["majorVersion"] = 21 },
            ["arguments"] = new JsonObject { ["game"] = new JsonArray { "launch", "--forge" }, ["jvm"] = jvm },
        };
        var dir = Path.Combine(mc, "versions", versionId);
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, versionId + ".json"), v.ToJsonString(Pretty), new UTF8Encoding(false));
    }

    static JsonObject ReadProfiles(string mc)
    {
        var file = ProfilesFile(mc);
        var backup = file + ".pentecost-backup";
        if (!File.Exists(backup)) File.Copy(file, backup);
        try
        {
            var root = JsonNode.Parse(File.ReadAllText(file)) as JsonObject;
            if (root != null) return root;
        }
        catch (JsonException) { }
        throw new SetupException("launcher_profiles.json is not in the format I expected. It was not changed.");
    }

    static void SaveProfiles(string mc, JsonObject root)
    {
        var file = ProfilesFile(mc);
        var tmp = file + ".pentecost-tmp";
        File.WriteAllText(tmp, root.ToJsonString(Pretty), new UTF8Encoding(false));
        File.Move(tmp, file, true);
    }

    static (int heap, int young) MemoryTier()
    {
        var gb = (int)(GC.GetGCMemoryInfo().TotalAvailableMemoryBytes / (1L << 30));
        if (gb >= 24) return (8, 4);
        if (gb >= 16) return (6, 3);
        if (gb >= 12) return (5, 2);
        if (gb >= 8) return (4, 2);
        return (3, 1);
    }

    static void WriteProfile(string mc, string home, string versionId)
    {
        var root = ReadProfiles(mc);
        if (root["profiles"] is not JsonObject profiles)
        {
            profiles = new JsonObject();
            root["profiles"] = profiles;
        }
        var now = Now();
        if (profiles[ProfileKey] is JsonObject entry)
        {
            Log.Write("existing profile updated");
        }
        else
        {
            var (heap, young) = MemoryTier();
            entry = new JsonObject
            {
                ["name"] = "Pentecost",
                ["type"] = "custom",
                ["created"] = now,
                ["javaArgs"] = $"-Xms{heap}G -Xmx{heap}G -Xmn{young}G -XX:-DontCompileHugeMethods",
            };
            var png = Path.Combine(home, "pentecost_still_256.png");
            entry["icon"] = File.Exists(png) && new FileInfo(png).Length < 80000
                ? "data:image/png;base64," + Convert.ToBase64String(File.ReadAllBytes(png))
                : "Furnace";
            profiles[ProfileKey] = entry;
            Log.Write($"profile added, heap {heap} G");
        }
        entry["lastVersionId"] = versionId;
        entry["gameDir"] = home;
        entry["lastUsed"] = now;
        SaveProfiles(mc, root);
    }

    // -------------------------------------------------------------- uninstall

    public static void Uninstall(string mc, bool removeData, IProgress<Progress> progress)
    {
        Preflight(mc);
        var home = HomeOf(mc);
        Log.Write($"uninstall from {mc} removeData={removeData}");

        progress.Report(new("Removing Pentecost from the launcher", "", 0.2));
        var root = ReadProfiles(mc);
        if (root["profiles"] is JsonObject profiles && profiles.ContainsKey(ProfileKey))
        {
            profiles.Remove(ProfileKey);
            SaveProfiles(mc, root);
        }
        var versions = Path.Combine(mc, "versions");
        if (Directory.Exists(versions))
            foreach (var d in Directory.GetDirectories(versions, "Pentecost-*")) Directory.Delete(d, true);

        progress.Report(new("Removing program files", "", 0.6));
        foreach (var item in new[] { "lib", "shaderpack", "menu_world" })
        {
            var p = Path.Combine(home, item);
            if (Directory.Exists(p)) Directory.Delete(p, true);
        }
        foreach (var item in new[] { "pentecost-wrapper.ps1", "pentecost-wrapper.cmd", "pentecost.ico", "pentecost_still_256.png",
                     "version.txt", "LICENSE.txt", "THIRD-PARTY-NOTICES.txt" })
        {
            var p = Path.Combine(home, item);
            if (File.Exists(p)) File.Delete(p);
        }
        if (removeData && Directory.Exists(home))
        {
            progress.Report(new("Deleting your Pentecost data", "", 0.85));
            Directory.Delete(home, true);
        }
        progress.Report(new("Done", "", 1.0));
    }
}
