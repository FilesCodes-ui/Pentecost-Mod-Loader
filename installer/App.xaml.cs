using System.Windows;
using System.Windows.Threading;

namespace Pentecost.Installer;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var args = e.Args;
        bool Has(string name) => args.Contains(name, StringComparer.OrdinalIgnoreCase);
        var mc = Setup.DefaultMinecraftDir;
        for (var i = 0; i < args.Length - 1; i++)
            if (args[i].Equals("--minecraft-dir", StringComparison.OrdinalIgnoreCase)) mc = args[i + 1];

        var waitPid = -1;
        for (var i = 0; i < args.Length - 1; i++)
            if (args[i].Equals("--wait", StringComparison.OrdinalIgnoreCase) && int.TryParse(args[i + 1], out var pid)) waitPid = pid;

        DispatcherUnhandledException += OnUnhandled;

        if (Has("--install") || Has("--uninstall"))
        {
            Shutdown(RunSilent(mc, uninstall: Has("--uninstall"), worlds: !Has("--no-worlds"), removeData: Has("--remove-data"), videoMods: Has("--video-mods")));
            return;
        }

        var window = new MainWindow(mc, Has("--update"), waitPid);
        MainWindow = window;
        window.Show();

        if (Has("--ui-selftest"))
        {
            var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            timer.Tick += (_, _) => { timer.Stop(); Shutdown(0); };
            timer.Start();
        }
    }

    static int RunSilent(string mc, bool uninstall, bool worlds, bool removeData, bool videoMods)
    {
        var progress = new Progress<Progress>(p => Console.WriteLine($"{(int)(p.Fraction * 100),3} %  {p.Step}  {p.Detail}"));
        try
        {
            if (uninstall) Task.Run(() => Setup.Uninstall(mc, removeData, progress)).GetAwaiter().GetResult();
            else
            {
                var r = Task.Run(() => Setup.InstallAsync(mc, worlds, videoMods, progress, CancellationToken.None)).GetAwaiter().GetResult();
                Console.WriteLine($"installed {r.Version}, worlds failed: {r.WorldsFailed}");
            }
            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine("FAILED: " + ex.Message);
            Log.Write("silent failure: " + ex);
            return 1;
        }
    }

    void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Log.Write("unhandled: " + e.Exception);
        MessageBox.Show("Something unexpected went wrong:\n\n" + e.Exception.Message + "\n\nDetails: " + Log.Path_,
            "Pentecost", MessageBoxButton.OK, MessageBoxImage.Error);
        e.Handled = true;
    }
}
