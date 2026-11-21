using System.Windows.Controls.Primitives;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow
{

    void Max_Click(object s, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    System.Windows.Threading.DispatcherTimer? seaTimer;

    static (string Folder, DateTime When)? LastWorld()
    {
        try
        {
            var dir = Path.Combine(Paths.Home, "run", "saves");
            if (!Directory.Exists(dir)) return null;
            var best = Directory.GetDirectories(dir)
                .Select(d => (Folder: Path.GetFileName(d), File: Path.Combine(d, "level.dat")))
                .Where(x => File.Exists(x.File))
                .Select(x => (x.Folder, When: File.GetLastWriteTime(x.File)))
                .OrderByDescending(x => x.When).ToList();
            return best.Count == 0 ? null : best[0];
        }
        catch { return null; }
    }

    void RefreshBar()
    {
        Cockpit.Children.Clear();
        BarFoot.Children.Clear();
        seaTimer?.Stop();
        var installed = Paths.Installed;
        var running = game.Running;
        SideMast.Running = running;
        var needsJava = mode != LaunchMode.Launcher && java == null;
        var (text, color) = running ? ("At sea", "Ok")
            : installing ? ("Installing", "Warn")
            : !installed ? ("Not installed yet", "Warn")
            : !javaDone ? ("Looking for Java", "Muted")
            : needsJava ? ("Java 21 was not found", "Warn")
            : ("Ready to play", "Ok");

        var install = !installed && !installing;
        var enabled = install || (!installing && (running || (!needsJava && Feed.Gate() == null)));
        var art = new Beacon { Lit = running, Enabled = enabled };
        var word = new Wordmark { Text = install ? "INSTALL" : running ? "STOP" : "PLAY", Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 0, 6), IsHitTestVisible = false };
        var play = new Grid { Height = 74, Opacity = enabled ? 1 : 0.45 };
        play.Children.Add(art);
        play.Children.Add(word);
        if (install) art.Clicked += () => AskInstall(true);
        else art.Clicked += StartGame;
        play.ToolTip = install ? "Install Pentecost" : running ? "Stop the game (Ctrl+P)" : Feed.Gate() is { } gate ? gate : needsJava && javaDone ? "Choose Java in Settings first" : "Play (Ctrl+P)";
        ToolTipService.SetShowOnDisabled(play, true);
        Cockpit.Children.Add(play);

        var ver = Ui.T(installed ? "v" + (Paths.Version ?? "?") : "", 11.5, "Faint");
        var status = Ui.Row(Ui.H(Ui.Dot(color, 7), Ui.T(text, 12.5, color == "Warn" ? "Warn" : "Soft", trim: true).With(new Thickness(9, 0, 0, 0))), ver);
        status.Margin = new Thickness(1, 12, 0, 0);
        Cockpit.Children.Add(status);

        var mono = (FontFamily)FindResource("Mono");
        if (running)
        {
            var clock = Ui.T("", 12, "Text");
            clock.FontFamily = mono;
            var memText = Ui.T("", 10.5, "Faint");
            memText.FontFamily = mono;
            var gauge = new TideGauge { Height = 12, Margin = new Thickness(0, 6, 0, 0) };
            var (heap, reserve, _) = GameRun.PlanFor(ProfileStore.Active);
            gauge.Mark = heap / (double)Math.Max(1, heap + reserve);
            var panel = Ui.V(Ui.Row(Ui.Cap("Under way"), clock), gauge, memText.With(new Thickness(0, 4, 0, 0)));
            panel.Margin = new Thickness(0, 16, 0, 0);
            Cockpit.Children.Add(panel);
            void Tick()
            {
                var span = DateTime.Now - gameStart;
                clock.Text = span.TotalHours >= 1 ? span.ToString(@"h\:mm\:ss") : span.ToString(@"mm\:ss");
                try
                {
                    var p = game.Proc;
                    if (p is { HasExited: false })
                    {
                        p.Refresh();
                        var gb = p.WorkingSet64 / 1073741824.0;
                        gauge.Value = gb / Math.Max(1, heap + reserve);
                        memText.Text = $"{gb:0.0} GB OF {heap}{(reserve > 0 ? "+" + reserve : "")} GB";
                    }
                }
                catch { }
            }
            Tick();
            seaTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            seaTimer.Tick += (_, _) => Tick();
            seaTimer.Start();
        }
        else if (installed && !installing && mode == LaunchMode.Standalone && LastWorld() is { } last)
        {
            var name = Ui.T(last.Folder, 12.5, "Text", trim: true);
            var when = Ui.T(Ui.Ago(last.When), 11, "Faint");
            var row = Ui.Row(Ui.H(Ui.Glyph("\uE768", 10, "Muted").With(new Thickness(0, 1, 8, 0)), Ui.V(Ui.Cap("Resume"), name.With(new Thickness(0, 3, 0, 0)))), when);
            var resume = Ui.HoverRow(new Border { Child = row, Padding = new Thickness(8, 8, 8, 8), Margin = new Thickness(-8, 12, -8, 0), Cursor = Cursors.Hand, ToolTip = "Start straight into " + last.Folder });
            resume.IsEnabled = enabled;
            resume.MouseLeftButtonUp += (_, _) => StartGame(last.Folder);
            Cockpit.Children.Add(resume);
        }

        FrameworkElement Head(string name, double top)
        {
            var h = Ui.H(Ui.Cap(name));
            h.Margin = new Thickness(1, top, 0, 8);
            return h;
        }

        var loadout = new StackPanel { IsEnabled = !running && !installing, Opacity = running || installing ? 0.45 : 1 };
        var manage = Ui.Btn("Manage", "Ghost", (_, _) => Go("Profiles"));
        manage.FontSize = 11.5;
        manage.Padding = new Thickness(6, 2, 6, 2);
        var profHead = Ui.Row(Ui.Cap("Profile"), manage);
        profHead.Margin = new Thickness(1, 22, -6, 6);
        loadout.Children.Add(profHead);
        var chart = new SeaChart { Compact = true, Height = 140, Margin = new Thickness(-4, 0, -4, 0) };
        var file = ProfileStore.Load();
        chart.SetIslands(file.Profiles.Select(p => (p.Name, ModsIn(p), 0, 0, p.Name == file.Active)));
        chart.Picked += name =>
        {
            var err = ProfileStore.SetActive(name);
            if (err != null) { Toast(err, "Warn"); return; }
            chart.SetIslands(ProfileStore.Load().Profiles.Select(p => (p.Name, ModsIn(p), 0, 0, p.Name == ProfileStore.Load().Active)));
            Toast("Profile " + name + " is active.");
            Dispatcher.BeginInvoke(() => Refresh(), System.Windows.Threading.DispatcherPriority.Background);
        };
        loadout.Children.Add(chart);
        var activeName = Ui.T(ProfileStore.Active.Name, 13, "Text", bold: true, trim: true);
        loadout.Children.Add(activeName.With(new Thickness(1, 6, 0, 0)));

        loadout.Children.Add(Head("Start with", 20));
        var modes = new List<(string Label, LaunchMode Mode)> { ("Pentecost", LaunchMode.Standalone), ("Launcher", LaunchMode.Launcher) };
        if (Paths.DevSource.Length > 0) modes.Add(("Source", LaunchMode.Source));
        var tabs = new Grid { Margin = new Thickness(0, 8, 0, 0) };
        var pick = Math.Max(0, modes.FindIndex(m => m.Mode == mode));
        for (var k = 0; k < modes.Count; k++)
        {
            tabs.ColumnDefinitions.Add(new ColumnDefinition());
            var on = k == pick;
            var kk = k;
            var label = new TextBlock { Text = modes[k].Label.ToUpperInvariant(), FontFamily = (FontFamily)FindResource("Mono"), FontSize = 11, Foreground = Ui.Br(on ? "Text" : "Faint"), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 6, 0, 7) };
            var cell = new Border { Background = Brushes.Transparent, BorderBrush = Ui.Br(on ? "Text" : "Line"), BorderThickness = new Thickness(0, 0, 0, on ? 2 : 1), Child = label, Cursor = on ? Cursors.Arrow : Cursors.Hand };
            if (!on)
            {
                cell.MouseEnter += (_, _) => label.Foreground = Ui.Br("Soft");
                cell.MouseLeave += (_, _) => label.Foreground = Ui.Br("Faint");
                cell.MouseLeftButtonUp += (_, _) => SetMode(modes[kk].Mode);
            }
            Grid.SetColumn(cell, k);
            tabs.Children.Add(cell);
        }
        loadout.Children.Add(tabs);
        Cockpit.Children.Add(loadout);

        var prof = ProfileStore.Active;
        var (h2, r2, _) = GameRun.PlanFor(prof);
        var log = Ui.T("", 11, needsJava && javaDone ? "Warn" : "Soft", trim: true);
        log.FontFamily = mono;
        var javaPart = !javaDone ? "JAVA ..." : java == null ? "NO JAVA" : "JAVA 21";
        void Write(string mods) => log.Text = javaPart + "  /  " + h2 + (r2 > 0 ? "+" + r2 : "") + " GB  /  " + mods;
        Write("... MODS");
        log.ToolTip = java;
        var logBlock = Ui.V(Ui.Cap("Log"), log.With(new Thickness(0, 7, 0, 0)));
        logBlock.Margin = new Thickness(1, 0, 0, 0);
        BarFoot.Children.Add(logBlock);
        _ = Task.Run(ModScan.Scan).ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            if (t.IsFaulted) { Write("MODS ?"); log.Foreground = Ui.Br("Warn"); return; }
            var on = t.Result.Count(m => m.Enabled);
            Write(on + " MODS");
            log.ToolTip = (java ?? "") + (t.Result.Count - on > 0 ? "\n" + (t.Result.Count - on) + " mods switched off" : "");
        }));
    }

    int ModsIn(ModProfile p)
    {
        try
        {
            var dir = Path.Combine(Instances.Folder(p), "mods");
            return Directory.Exists(dir) ? Directory.GetFiles(dir, "*.jar").Length : 0;
        }
        catch { return 0; }
    }

    Button ProfilePicker()
    {
        var active = ProfileStore.Active;
        var face = Ui.Row(Ui.T(active.Name, 13, "Text", trim: true), Ui.Glyph("", 10, "Muted"));
        var btn = new Button { Content = face, Style = Ui.St("Secondary"), HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(13, 9, 12, 9) };
        btn.ToolTip = "Each profile has its own mods, settings, worlds and packs";
        Ui.Menu(btn, () =>
        {
            var now = ProfileStore.Active.Name;
            var items = ProfileStore.Load().Profiles
                .Select(p => (p.Name, (Action)(() =>
                {
                    var err = ProfileStore.SetActive(p.Name);
                    if (err != null) Toast(err, "Warn"); else Toast("Profile " + p.Name + " is active.");
                    Refresh();
                }), p.Name == now))
                .ToList();
            items.Add(("Manage profiles", () => Go("Profiles"), false));
            return items;
        });        return btn;
    }

    void MoveTabLine()
    {
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () =>
        {
            var tab = NavPanel.Children.OfType<RadioButton>().FirstOrDefault(r => r.IsChecked == true);
            if (tab == null || tab.ActualWidth < 1) return;
            var x = tab.TranslatePoint(new Point(0, 0), TabHost).X + 10;
            var w = tab.ActualWidth - 20;
            if (TabLine.Width < 1)
            {
                TabLine.Width = w;
                TabLineMove.X = x;
                return;
            }
            var time = TimeSpan.FromMilliseconds(280);
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            TabLineMove.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(x, time) { EasingFunction = ease });
            TabLine.BeginAnimation(WidthProperty, new DoubleAnimation(w, time) { EasingFunction = ease });
        });
    }

    void Enter(FrameworkElement page, double from)
    {
        var slide = new TranslateTransform(from, 0);
        page.RenderTransform = slide;
        slide.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(from, 0, TimeSpan.FromMilliseconds(300)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
        var parts = page is ScrollViewer { Content: Panel inner } ? inner.Children : page is Panel panel ? panel.Children : null;
        if (parts != null) Ui.Stagger(parts.OfType<UIElement>());
        else Ui.FadeIn(page, 220);
    }
}
