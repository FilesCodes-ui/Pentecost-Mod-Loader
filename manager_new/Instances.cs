using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Pentecost.Manager;

public static class Instances
{
    public static readonly string[] Kinds = { "mods", "config", "saves", "resourcepacks", "shaderpacks" };

    public static string Root => Path.Combine(Paths.Home, "profiles");
    static string Run => Path.Combine(Paths.Home, "run");
    static string Marker => Path.Combine(Root, ".pentecost-profiles-v1");

    public static Func<bool> GameRunning = () => false;

    public static string Dir(ModProfile p) => Path.Combine(Root, p.Folder);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CreateHardLink(string newFile, string existingFile, IntPtr security);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool RemoveDirectory(string path);

    public static bool IsLink(string path) =>
        Directory.Exists(path) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;

    static string Slug(string name)
    {
        var s = new string(name.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' or ' ' ? c : '_').ToArray()).Trim();
        return s.Length == 0 ? "profile" : s.Length > 40 ? s[..40] : s;
    }

    static void GiveFolder(ModProfile p, ProfileFile f)
    {
        if (p.Folder.Length > 0) return;
        var baseName = Slug(p.Name);
        var name = baseName;
        for (var i = 2; f.Profiles.Any(q => q != p && q.Folder.Equals(name, StringComparison.OrdinalIgnoreCase)) || Directory.Exists(Path.Combine(Root, name)); i++)
            name = baseName + " " + i;
        p.Folder = name;
    }

    public static string? Migrate()
    {
        if (!Paths.Installed) return null;
        var f = ProfileStore.Load();
        try
        {
            Directory.CreateDirectory(Root);
            var def = f.Profiles.FirstOrDefault(p => p.Name == "Default") ?? f.Profiles[0];
            foreach (var p in f.Profiles) GiveFolder(p, f);
            ProfileStore.Save();
            if (File.Exists(Marker))
            {
                foreach (var p in f.Profiles) EnsureFolders(p);
                if (!File.Exists(Marker2) && !GameRunning()) Tidy(f, def);
                return Link(ProfileStore.Active);
            }
            if (GameRunning()) return "Close the game first.";

            EnsureFolders(def);
            foreach (var kind in Kinds)
            {
                var live = Path.Combine(Run, kind);
                if (!Directory.Exists(live) || IsLink(live)) continue;
                MoveInto(live, Path.Combine(Dir(def), kind));
            }

            foreach (var p in f.Profiles.Where(p => p != def))
            {
                EnsureFolders(p);
                var on = new HashSet<string>(p.On, StringComparer.OrdinalIgnoreCase);
                var off = new HashSet<string>(p.Off, StringComparer.OrdinalIgnoreCase);
                foreach (var file in Directory.GetFiles(Path.Combine(Dir(def), "mods")))
                {
                    var name = Path.GetFileName(file);
                    var bare = name.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? name[..^9] : name;
                    var enabled = on.Contains(bare) || (!off.Contains(bare) && !name.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase));
                    if (enabled) Share(file, Path.Combine(Dir(p), "mods", bare));
                }
                CopyTree(Path.Combine(Dir(def), "config"), Path.Combine(Dir(p), "config"), link: false);
                CopyTree(Path.Combine(Dir(def), "resourcepacks"), Path.Combine(Dir(p), "resourcepacks"), link: true);
                CopyTree(Path.Combine(Dir(def), "shaderpacks"), Path.Combine(Dir(p), "shaderpacks"), link: true);
            }
            f.Active = def.Name;
            ProfileStore.Save();
            var err = Link(def);
            if (err != null) return err;
            File.WriteAllText(Marker, "Profiles have their own folders since " + DateTime.Now.ToString("u") + ".\n");
            File.WriteAllText(Marker2, "Profiles hold only their own mods.\n");
            AppLog.Write("profiles: moved to separate folders");
            return null;
        }
        catch (Exception ex)
        {
            AppLog.Write("profiles migrate: " + ex);
            return "Could not set up profile folders: " + ex.Message;
        }
    }

    static string Marker2 => Path.Combine(Root, ".pentecost-profiles-v2");

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
    struct FileInfoByHandle
    {
        public uint Attributes;
        public long Created, Accessed, Written;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetFileInformationByHandle(Microsoft.Win32.SafeHandles.SafeFileHandle file, out FileInfoByHandle info);

    static uint Links(string file)
    {
        try
        {
            using var fs = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return GetFileInformationByHandle(fs.SafeFileHandle, out var info) ? info.Links : 1;
        }
        catch { return 1; }
    }

    static void Tidy(ProfileFile f, ModProfile def)
    {
        var removed = 0;
        foreach (var p in f.Profiles.Where(p => p != def))
        {
            var dir = Path.Combine(Dir(p), "mods");
            if (!Directory.Exists(dir)) continue;
            foreach (var file in Directory.GetFiles(dir, "*.disabled"))
                if (Links(file) > 1)
                    try { File.Delete(file); removed++; }
                    catch (Exception ex) { AppLog.Write("profiles tidy " + file + ": " + ex.Message); }
        }
        try { File.WriteAllText(Marker2, "Profiles hold only their own mods.\n"); } catch { }
        AppLog.Write($"profiles: took {removed} switched-off copies out of profiles");
    }

    static void EnsureFolders(ModProfile p)
    {
        foreach (var kind in Kinds) Directory.CreateDirectory(Path.Combine(Dir(p), kind));
    }

    public static string? Link(ModProfile p)
    {
        EnsureFolders(p);
        foreach (var kind in Kinds)
        {
            var live = Path.Combine(Run, kind);
            var target = Path.Combine(Dir(p), kind);
            if (IsLink(live))
            {
                if (SamePlace(LinkTarget(live), target)) continue;
                if (!RemoveDirectory(live)) return $"Could not switch the {kind} folder (Windows error {Marshal.GetLastWin32Error()}).";
            }
            else if (Directory.Exists(live))
            {
                MoveInto(live, target);
            }
            var err = MakeLink(live, target);
            if (err != null) return err;
        }
        return null;
    }

    static string? LinkTarget(string link)
    {
        try { return new DirectoryInfo(link).LinkTarget; } catch { return null; }
    }

    static bool SamePlace(string? a, string b) =>
        a != null && string.Equals(Path.GetFullPath(a).TrimEnd('\\'), Path.GetFullPath(b).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);

    static string? MakeLink(string link, string target)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(link)!);
        var psi = new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{link}\" \"{target}\"") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        using var proc = Process.Start(psi);
        proc?.WaitForExit(10_000);
        return IsLink(link) ? null : "Could not link the " + Path.GetFileName(link) + " folder: " + proc?.StandardError.ReadToEnd().Trim();
    }

    static void MoveInto(string from, string to)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(to)!);
        if (!Directory.Exists(to) || !Directory.EnumerateFileSystemEntries(to).Any())
        {
            if (Directory.Exists(to)) Directory.Delete(to);
            Directory.Move(from, to);
            return;
        }
        foreach (var entry in Directory.GetFileSystemEntries(from))
        {
            var dest = Path.Combine(to, Path.GetFileName(entry));
            if (File.Exists(dest) || Directory.Exists(dest)) dest = Path.Combine(to, Path.GetFileNameWithoutExtension(entry) + " (moved)" + Path.GetExtension(entry));
            if (Directory.Exists(entry)) Directory.Move(entry, dest); else File.Move(entry, dest);
        }
        Directory.Delete(from);
    }

    static void Share(string existing, string dest)
    {
        if (File.Exists(dest)) return;
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        if (!CreateHardLink(dest, existing, IntPtr.Zero)) File.Copy(existing, dest);
    }

    static void CopyTree(string from, string to, bool link)
    {
        if (!Directory.Exists(from)) return;
        foreach (var file in Directory.EnumerateFiles(from, "*", SearchOption.AllDirectories))
        {
            var dest = Path.Combine(to, Path.GetRelativePath(from, file));
            if (link) Share(file, dest);
            else if (!File.Exists(dest))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                File.Copy(file, dest);
            }
        }
    }

    public static void Create(ModProfile p, bool copyActive)
    {
        var f = ProfileStore.Load();
        GiveFolder(p, f);
        EnsureFolders(p);
        var from = ProfileStore.Active;
        if (copyActive)
        {
            CopyTree(Path.Combine(Dir(from), "mods"), Path.Combine(Dir(p), "mods"), link: true);
            foreach (var off in Directory.GetFiles(Path.Combine(Dir(p), "mods"), "*.disabled"))
                try { File.Delete(off); } catch { }
            CopyTree(Path.Combine(Dir(from), "config"), Path.Combine(Dir(p), "config"), link: false);
            CopyTree(Path.Combine(Dir(from), "resourcepacks"), Path.Combine(Dir(p), "resourcepacks"), link: true);
            CopyTree(Path.Combine(Dir(from), "shaderpacks"), Path.Combine(Dir(p), "shaderpacks"), link: true);
        }
        else
        {
            var settings = Path.Combine(Dir(from), "config", "pentecost", "settings.json");
            if (File.Exists(settings))
            {
                Directory.CreateDirectory(Path.Combine(Dir(p), "config", "pentecost"));
                File.Copy(settings, Path.Combine(Dir(p), "config", "pentecost", "settings.json"), true);
            }
        }
    }

    public static string? Switch(ModProfile p)
    {
        if (GameRunning()) return "Close the game before switching profiles.";
        try
        {
            if (!File.Exists(Marker)) { var m = Migrate(); if (m != null) return m; }
            var err = Link(p);
            if (err != null) return err;
            ProfileStore.Load().Active = p.Name;
            ProfileStore.Save();
            return null;
        }
        catch (Exception ex)
        {
            AppLog.Write("profile switch: " + ex);
            return "Could not switch profiles: " + ex.Message;
        }
    }

    public static string? Delete(ModProfile p)
    {
        if (ProfileStore.Active == p) return "Use another profile first.";
        try
        {
            var dir = Dir(p);
            if (p.Folder.Length > 0 && Directory.Exists(dir) && !IsLink(dir))
                Microsoft.VisualBasic.FileIO.FileSystem.DeleteDirectory(dir, Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs,
                    Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
            return null;
        }
        catch (Exception ex)
        {
            AppLog.Write("profile delete: " + ex);
            return "Could not remove the profile's folder: " + ex.Message;
        }
    }

    public static string Folder(ModProfile p) => Dir(p);
}
