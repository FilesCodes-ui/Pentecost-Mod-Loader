using System.Diagnostics;
using Microsoft.Win32;

namespace Pentecost.Manager;

public static class SelfInstall
{
    const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\PentecostManager";
    const string ExeName = "Pentecost-Manager.exe";
    const string Description = "Pentecost: Forge, Fabric and Quilt mods in one game";

    static readonly string? TestTarget = Environment.GetEnvironmentVariable("PENTECOST_SETUP_TEST");

    public static string Target => TestTarget ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pentecost Manager");
    public static string TargetExe => Path.Combine(Target, ExeName);
    static string? Self => Environment.ProcessPath;

    public static bool IsBundle => string.IsNullOrEmpty(typeof(SelfInstall).Assembly.Location);

    public static bool ShouldOffer => IsBundle && Self != null && !SamePath(Self, TargetExe);

    public static bool CanUpdate => IsBundle && Self != null && SamePath(Self, TargetExe);

    public static string Version => typeof(SelfInstall).Assembly.GetName().Version?.ToString(3) ?? "";

    static bool SamePath(string a, string b)
    {
        try { return string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.OrdinalIgnoreCase); }
        catch { return false; }
    }

    static string DesktopLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Pentecost Manager.lnk");
    static string StartLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Pentecost Manager.lnk");

    public static string? Install()
    {
        try
        {
            var self = Self ?? throw new IOException("This program does not know where it is.");
            Directory.CreateDirectory(Target);
            StopRunning();
            Copy(self, TargetExe);
            if (TestTarget == null)
            {
                Shortcut(DesktopLink);
                Shortcut(StartLink);
                Register();
            }
            return null;
        }
        catch (Exception ex)
        {
            AppLog.Write("self install: " + ex);
            return "Could not install the Manager: " + ex.Message;
        }
    }

    public static void Launch(string args)
    {
        try { Process.Start(new ProcessStartInfo(TargetExe, args) { UseShellExecute = true, WorkingDirectory = Target }); }
        catch (Exception ex) { AppLog.Write("self install launch: " + ex.Message); }
    }

    public static string? Uninstall()
    {
        try
        {
            StopRunning();
            if (TestTarget == null)
            {
                foreach (var f in new[] { DesktopLink, StartLink }) if (File.Exists(f)) File.Delete(f);
                Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false);
            }
            return null;
        }
        catch (Exception ex)
        {
            AppLog.Write("uninstall: " + ex);
            return "Could not remove the Manager: " + ex.Message;
        }
    }

    public static void RemoveFolderLater()
    {
        try
        {
            if (!Directory.Exists(Target) || (TestTarget == null && Path.GetFileName(Target.TrimEnd('\\')) != "Pentecost Manager")) return;
            var psi = new ProcessStartInfo("cmd.exe") { CreateNoWindow = true, UseShellExecute = false };
            psi.Arguments = $"/c ping 127.0.0.1 -n 3 >nul & rmdir /s /q \"{Target}\"";
            Process.Start(psi);
        }
        catch (Exception ex) { AppLog.Write("remove folder: " + ex.Message); }
    }

    static void StopRunning()
    {
        foreach (var p in Process.GetProcessesByName("Pentecost-Manager"))
        {
            try
            {
                if (p.Id == Environment.ProcessId) continue;
                if (!SamePath(p.MainModule?.FileName ?? "", TargetExe)) continue;
                p.Kill();
                p.WaitForExit(4000);
            }
            catch { }
            finally { p.Dispose(); }
        }
    }

    static void Copy(string from, string to)
    {
        for (var i = 0; ; i++)
        {
            try { File.Copy(from, to, true); return; }
            catch (IOException) when (i < 8) { Thread.Sleep(400); }
        }
    }

    static void Shortcut(string path)
    {
        var type = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows has no shortcut maker here.");
        dynamic shell = Activator.CreateInstance(type)!;
        dynamic link = shell.CreateShortcut(path);
        link.TargetPath = TargetExe;
        link.WorkingDirectory = Target;
        link.IconLocation = TargetExe + ",0";
        link.Description = Description;
        link.Save();
    }

    static void Register()
    {
        using var k = Registry.CurrentUser.CreateSubKey(UninstallKey);
        k.SetValue("DisplayName", "Pentecost Manager");
        k.SetValue("DisplayVersion", Version);
        k.SetValue("Publisher", "Kilo-Heat Industrialists");
        k.SetValue("InstallLocation", Target);
        k.SetValue("DisplayIcon", TargetExe + ",0");
        k.SetValue("UninstallString", $"\"{TargetExe}\" --uninstall");
        k.SetValue("NoModify", 1, RegistryValueKind.DWord);
        k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
        try { k.SetValue("EstimatedSize", (int)(new FileInfo(TargetExe).Length / 1024), RegistryValueKind.DWord); } catch { }
    }
}
