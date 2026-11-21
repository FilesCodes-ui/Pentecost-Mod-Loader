namespace Pentecost.Manager;

public static class GpuPref
{
    const string Key = @"Software\Microsoft\DirectX\UserGpuPreferences";
    const string High = "GpuPreference=2;";

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
