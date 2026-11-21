using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class App : Application
{
    static Mutex? single;
    public static bool Welcome;

    [System.Runtime.InteropServices.DllImport("shell32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    static extern int SetCurrentProcessExplicitAppUserModelID(string id);
    public static bool Demo;

    public static void ReleaseSingle()
    {
        try { single?.ReleaseMutex(); single?.Dispose(); single = null; } catch { }
    }
    static readonly string[] AllPages = { "Play", "Mods", "Browse", "Profiles", "Friends", "Crashes", "Log", "Settings", "Dev" };

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args.Length >= 2 && e.Args[0].Equals("--icon", StringComparison.OrdinalIgnoreCase))
        {
            try { IconMaker.Write(e.Args[1]); }
            catch (Exception ex) { AppLog.Write("icon: " + ex); }
            Shutdown();
            return;
        }
        if (e.Args.Length >= 1 && e.Args[0].Equals("--uninstall", StringComparison.OrdinalIgnoreCase))
        {
            var err = SelfInstall.Uninstall();
            if (!e.Args.Any(a => a.Equals("--silent", StringComparison.OrdinalIgnoreCase)))
                MessageBox.Show(err ?? "Pentecost Manager was removed. Pentecost, your worlds, mods and settings were not touched.", "Pentecost Manager");
            if (err == null) SelfInstall.RemoveFolderLater();
            Shutdown();
            return;
        }
        if (e.Args.Length >= 1 && e.Args[0].Equals("--silent", StringComparison.OrdinalIgnoreCase))
        {
            var err = SelfInstall.Install();
            if (err != null) AppLog.Write("silent install: " + err);
            else if (e.Args.Any(a => a.Equals("--restart", StringComparison.OrdinalIgnoreCase))) SelfInstall.Launch("");
            Shutdown(err == null ? 0 : 1);
            return;
        }
        try { SetCurrentProcessExplicitAppUserModelID("KHI.Pentecost.Manager"); } catch { }
        GpuPref.Apply(Settings.HighGpu);
        System.Runtime.GCSettings.LatencyMode = System.Runtime.GCLatencyMode.SustainedLowLatency;
        if (e.Args.Length >= 1 && e.Args[0].Equals("--profiles-check", StringComparison.OrdinalIgnoreCase))
        {
            var log = new List<string>();
            try
            {
                Paths.Load();
                log.Add("home " + Paths.Home);
                log.Add("migrate: " + (Instances.Migrate() ?? "ok"));
                foreach (var k in Instances.Kinds) log.Add(k + " link=" + Instances.IsLink(Path.Combine(Paths.Home, "run", k)));
                log.Add("mods in run: " + string.Join(",", Directory.GetFiles(Paths.Mods).Select(Path.GetFileName)));
                var fresh = ProfileStore.Add("Fresh", copyActive: false);
                log.Add("switch fresh: " + (ProfileStore.SetActive(fresh.Name) ?? "ok"));
                log.Add("mods in run now: [" + string.Join(",", Directory.GetFiles(Paths.Mods).Select(Path.GetFileName)) + "]");
                File.WriteAllText(Path.Combine(Paths.Mods, "new-mod.jar"), "x");
                log.Add("switch back: " + (ProfileStore.SetActive("Default") ?? "ok"));
                log.Add("mods in run back: " + string.Join(",", Directory.GetFiles(Paths.Mods).Select(Path.GetFileName)));
                log.Add("fresh folder mods: " + string.Join(",", Directory.GetFiles(Path.Combine(Instances.Dir(fresh), "mods")).Select(Path.GetFileName)));
                var copy = ProfileStore.Add("Copy", copyActive: true);
                log.Add("copy mods: " + string.Join(",", Directory.GetFiles(Path.Combine(Instances.Dir(copy), "mods")).Select(Path.GetFileName)));
                log.Add("delete fresh: " + (ProfileStore.Remove(fresh) ?? "ok") + " exists=" + Directory.Exists(Instances.Dir(fresh)));
                log.Add("migrate again: " + (Instances.Migrate() ?? "ok"));
            }
            catch (Exception ex) { log.Add("FAILED " + ex); }
            File.WriteAllLines(Path.Combine(Paths.Data, "profiles-check.txt"), log);
            Shutdown();
            return;
        }
        if (e.Args.Length >= 1 && e.Args[0].Equals("--dev-inbox", StringComparison.OrdinalIgnoreCase))
        {
            Paths.Load();
            string result;
            try { result = Task.Run(DevInbox.Run).GetAwaiter().GetResult(); }
            catch (Exception ex) { result = "error: " + ex.Message; }
            try { File.WriteAllText(Path.Combine(DevInbox.Dir, "result.txt"), result); } catch { }
            Shutdown();
            return;
        }
        if (e.Args.Length >= 1 && e.Args[0].Equals("--feed-check", StringComparison.OrdinalIgnoreCase))
        {
            Paths.Load();
            List<string> log;
            try { log = Task.Run(Feed.SelfCheck).GetAwaiter().GetResult(); }
            catch (Exception ex) { log = new List<string> { "FAILED " + ex }; }
            File.WriteAllLines(Path.Combine(Paths.Data, "feed-check.txt"), log);
            Shutdown();
            return;
        }
        Welcome = e.Args.Any(a => a.Equals("--welcome", StringComparison.OrdinalIgnoreCase));
        Demo = e.Args.Any(a => a.Equals("--demo", StringComparison.OrdinalIgnoreCase));
        var shot = e.Args.Length >= 2 && e.Args[0].Equals("--shot", StringComparison.OrdinalIgnoreCase);
        var check = e.Args.Length >= 2 && e.Args[0].Equals("--check", StringComparison.OrdinalIgnoreCase);
        if (!shot && !check && !Demo)
        {
            single = new Mutex(true, "Local\\PentecostManager.Single", out var first);
            if (!first)
            {
                try { if (EventWaitHandle.TryOpenExisting("Local\\PentecostManager.Wake", out var wake)) { wake.Set(); wake.Dispose(); } } catch { }
                Shutdown();
                return;
            }
        }
        DispatcherUnhandledException += OnUnhandled;
        AppDomain.CurrentDomain.UnhandledException += (_, a) => AppLog.Write("fatal: " + a.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, a) => { AppLog.Write("task: " + a.Exception); a.SetObserved(); };
        Paths.Load();
        DevInbox.NoteExe();
        if (!shot && !check && !Demo) DiscordPresence.Start();

        var window = new MainWindow();
        MainWindow = window;
        if (!shot && !check && !Demo)
        {
            var signal = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\PentecostManager.Wake");
            new Thread(() => { while (signal.WaitOne()) window.Dispatcher.BeginInvoke(window.ShowFromTray); }) { IsBackground = true }.Start();
        }
        if (shot || check)
        {
            window.WindowStartupLocation = WindowStartupLocation.Manual;
            window.Left = -20000;
            window.Top = 0;
            window.ShowActivated = false;
            window.ShowInTaskbar = false;
            window.Loaded += async (_, _) =>
            {
                try
                {
                    if (check) await window.Check(e.Args[1]);
                    else await window.Shoot(e.Args[1], e.Args.Length > 2 ? e.Args[2..] : AllPages);
                }
                catch (Exception ex) { AppLog.Write((check ? "check: " : "shot: ") + ex); }
                Shutdown();
            };
            window.Show();
            return;
        }
        if (Settings.StartWithWindows) Autostart.Set(true);
        if (e.Args.Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase)))
        {
            if (Settings.CloseAction == "tray") { window.Loaded += (_, _) => window.HideToTray(); window.ShowActivated = false; }
            else window.WindowState = WindowState.Minimized;
        }
        window.Show();
    }

    void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        AppLog.Write("unhandled: " + e.Exception);
        e.Handled = true;
        if (MainWindow is MainWindow w)
            try { w.Toast("Something went wrong: " + e.Exception.Message, "Bad"); } catch { }
    }
}
