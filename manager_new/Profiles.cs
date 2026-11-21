using System.Text.Json;

namespace Pentecost.Manager;

public sealed class ModProfile
{
    public string Name { get; set; } = "";
    public string Folder { get; set; } = "";
    public int MemoryGb { get; set; }
    public int ReserveGb { get; set; } = -1;
    public string JvmArgs { get; set; } = "";
    public string FromPack { get; set; } = "";
    public List<string> On { get; set; } = new();
    public List<string> Off { get; set; } = new();
}

public sealed class ProfileFile
{
    public string Active { get; set; } = "Default";
    public List<ModProfile> Profiles { get; set; } = new();
}

public static class ProfileStore
{
    static readonly JsonSerializerOptions Opt = new() { WriteIndented = true };
    static string FilePath => Path.Combine(Paths.Data, "profiles.json");
    static ProfileFile? cache;

    public static ProfileFile Load()
    {
        if (cache != null) return cache;
        try
        {
            if (File.Exists(FilePath))
                cache = JsonSerializer.Deserialize<ProfileFile>(File.ReadAllText(FilePath));
        }
        catch (Exception ex) { AppLog.Write("Profiles load: " + ex.Message); }
        cache ??= new ProfileFile();
        if (cache.Profiles.Count == 0) cache.Profiles.Add(new ModProfile { Name = "Default" });
        if (!cache.Profiles.Any(p => p.Name == cache.Active)) cache.Active = cache.Profiles[0].Name;
        return cache;
    }

    public static void Save()
    {
        try { File.WriteAllText(FilePath, JsonSerializer.Serialize(Load(), Opt)); }
        catch (Exception ex) { AppLog.Write("Profiles save: " + ex.Message); }
    }

    public static ModProfile Active
    {
        get { var f = Load(); return f.Profiles.First(p => p.Name == f.Active); }
    }

    public static string? SetActive(string name)
    {
        var p = Load().Profiles.FirstOrDefault(q => q.Name == name);
        return p == null ? "There is no profile called " + name + "." : Instances.Switch(p);
    }

    public static string UniqueName(string wanted)
    {
        var f = Load();
        var name = wanted.Trim().Length == 0 ? "Profile" : wanted.Trim();
        var n = name;
        for (var i = 2; f.Profiles.Any(p => p.Name.Equals(n, StringComparison.OrdinalIgnoreCase)); i++) n = name + " " + i;
        return n;
    }

    public static ModProfile Add(string name, bool copyActive)
    {
        var p = new ModProfile { Name = UniqueName(name) };
        Instances.Create(p, copyActive);
        Load().Profiles.Add(p);
        Save();
        return p;
    }

    public static string? Remove(ModProfile p)
    {
        var f = Load();
        if (f.Profiles.Count <= 1) return "The last profile cannot be deleted.";
        var err = Instances.Delete(p);
        if (err != null) return err;
        f.Profiles.Remove(p);
        Save();
        return null;
    }

    public static void Capture(ModProfile p)
    {
        p.On.Clear();
        p.Off.Clear();
        foreach (var m in ModScan.Scan())
            (m.Enabled ? p.On : p.Off).Add(m.BaseName);
    }

    public static void CaptureNone(ModProfile p)
    {
        p.On.Clear();
        p.Off.Clear();
        foreach (var m in ModScan.Scan()) p.Off.Add(m.BaseName);
    }

    public static (int Changed, List<string> Failed) Apply(ModProfile p)
    {
        var on = new HashSet<string>(p.On, StringComparer.OrdinalIgnoreCase);
        var off = new HashSet<string>(p.Off, StringComparer.OrdinalIgnoreCase);
        var changed = 0;
        var failed = new List<string>();
        foreach (var m in ModScan.Scan())
        {
            var want = on.Contains(m.BaseName) ? true : off.Contains(m.BaseName) ? false : (bool?)null;
            if (want == null || want == m.Enabled) continue;
            try { ModScan.SetEnabled(m, want.Value); changed++; }
            catch (Exception ex) { failed.Add(m.Name + ": " + ex.Message); }
        }
        return (changed, failed);
    }
}
