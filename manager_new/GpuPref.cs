namespace Pentecost.Manager;

public static class GpuPref
{
    const string Key = @"Software\Microsoft\DirectX\UserGpuPreferences";
    const string High = "GpuPreference=2;";

    // Laptops with two graphics chips give a new program the weak one. This asks Windows for the fast
    // chip for the Java that runs the game, once, and leaves a choice the player made alone.
    public static void ForGame(string exe)
    {
        try
        {
            if (!Settings.HighGpu || !Path.IsPathRooted(exe) || !File.Exists(exe)) return;
            using var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(Key);
            if (key.GetValue(exe) is string cur && cur.Contains("GpuPreference=")) return;
            key.SetValue(exe, High);
            AppLog.Write("graphics preference set to high performance for " + exe);
        }
        catch (Exception ex) { AppLog.Write("gpu: " + ex.Message); }
    }

    public static void Apply(bool on)
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return;
            using var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(Key);
            var now = key.GetValue(exe) as string;
            if (on)
            {
                if (now != High) key.SetValue(exe, High);
            }
            else if (now == High) key.DeleteValue(exe, false);
        }
        catch (Exception ex) { AppLog.Write("gpu: " + ex.Message); }
    }
}
