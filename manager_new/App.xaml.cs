using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class App : Application
{
    static Mutex? single;
    /// <summary>Set when the setup just installed the Manager. The first window then offers to install Pentecost.</summary>
    public static bool Welcome;

    /// <summary>The setup hands over to the installed copy, which must be allowed to start.</summary>
    public static void ReleaseSingle()
    {
        try { single?.ReleaseMutex(); single?.Dispose(); single = null; } catch { }
    }
    static readonly string[] AllPages = { "Play", "Mods", "Browse", "Profiles", "Crashes", "Log", "Settings" };

    /// <summary>
    /// Command line (all optional):
    ///   --shot DIR [pages]   draw the named pages (default all) into PNG files in DIR, then exit
    ///   --check DIR          walk the whole window by itself, write report.txt and PNG files in DIR, then exit
    ///   --icon FILE          write the program icon from the mark, then exit
    ///   --silent             install the Manager without asking and without starting it, then exit
    ///   --uninstall          remove the Manager (shortcuts, app list entry, folder), then exit
    ///   --welcome            sent by the setup to the new copy: offer to install Pentecost
    /// Both run off screen and never take focus.
    /// </summary>
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
            Shutdown(err == null ? 0 : 1);
            return;
        }
        Welcome = e.Args.Any(a => a.Equals("--welcome", StringComparison.OrdinalIgnoreCase));
        var shot = e.Args.Length >= 2 && e.Args[0].Equals("--shot", StringComparison.OrdinalIgnoreCase);
        var check = e.Args.Length >= 2 && e.Args[0].Equals("--check", StringComparison.OrdinalIgnoreCase);
        if (!shot && !check)
        {
            single = new Mutex(true, "Local\\PentecostManager.Single", out var first);
            if (!first) { Shutdown(); return; }
        }
        DispatcherUnhandledException += OnUnhandled;
        AppDomain.CurrentDomain.UnhandledException += (_, a) => AppLog.Write("fatal: " + a.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, a) => { AppLog.Write("task: " + a.Exception); a.SetObserved(); };
        Paths.Load();

        var window = new MainWindow();
        MainWindow = window;
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
