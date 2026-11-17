using System.Text.Json;

namespace Pentecost.Manager;

/// <summary>A saved setup: memory, extra Java arguments and which mods are switched on.</summary>
public sealed class ModProfile
{
    public string Name { get; set; } = "";
    /// <summary>Heap in GB. 0 means automatic, from this computer's memory.</summary>
    public int MemoryGb { get; set; }
    public string JvmArgs { get; set; } = "";
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

    public static void SetActive(string name)
    {
        var f = Load();
        if (f.Profiles.Any(p => p.Name == name)) { f.Active = name; Save(); }
    }

    public static string UniqueName(string wanted)
    {
        var f = Load();
        var name = wanted.Trim().Length == 0 ? "Profile" : wanted.Trim();
        var n = name;
        for (var i = 2; f.Profiles.Any(p => p.Name.Equals(n, StringComparison.OrdinalIgnoreCase)); i++) n = name + " " + i;
        return n;
    }

    public static ModProfile Add(string name, bool copyMods)
    {
        var p = new ModProfile { Name = UniqueName(name) };
        if (copyMods) Capture(p);
        Load().Profiles.Add(p);
        Save();
        return p;
    }

    public static void Remove(ModProfile p)
    {
        var f = Load();
        if (f.Profiles.Count <= 1) return;
        f.Profiles.Remove(p);
        if (f.Active == p.Name) f.Active = f.Profiles[0].Name;
        Save();
    }

    /// <summary>Remembers which mods are on and off right now.</summary>
    public static void Capture(ModProfile p)
    {
        p.On.Clear();
        p.Off.Clear();
        foreach (var m in ModScan.Scan())
            (m.Enabled ? p.On : p.Off).Add(m.BaseName);
    }

    /// <summary>Every mod switched off, so the profile is built up from nothing.</summary>
    public static void CaptureNone(ModProfile p)
    {
        p.On.Clear();
        p.Off.Clear();
        foreach (var m in ModScan.Scan()) p.Off.Add(m.BaseName);
    }

    /// <summary>Switches mods to match the profile. Mods the profile has never seen stay as they are.</summary>
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
