using System;
using System.Linq;
using System.Windows;

namespace Pentecost.Setup
{
    public partial class App : Application
    {
        public static bool Demo;

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            Demo = e.Args.Any(a => a.Equals("--demo", StringComparison.OrdinalIgnoreCase));
            var shot = e.Args.Length >= 2 && e.Args[0].Equals("--shot", StringComparison.OrdinalIgnoreCase);
            var window = new SetupWindow();
            MainWindow = window;
            if (shot)
            {
                Demo = true;
                window.WindowStartupLocation = WindowStartupLocation.Manual;
                window.Left = -20000;
                window.Top = 0;
                window.ShowActivated = false;
                window.ShowInTaskbar = false;
                window.Loaded += async (_, _) =>
                {
                    try { await window.Shoot(e.Args[1]); }
                    finally { Shutdown(); }
                };
            }
            window.Show();
        }
    }
}
