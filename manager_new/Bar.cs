using System.Windows.Controls.Primitives;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace Pentecost.Manager;

/// <summary>The launch panel on the left, and the motion of the window: tab line, page changes, boxes.</summary>
public partial class MainWindow
{

    void Max_Click(object s, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    /// <summary>Draws the launch panel from the current state.</summary>
    void RefreshBar()
    {
        Cockpit.Children.Clear();
        var installed = Paths.Installed;
        var running = game.Running;
        var needsJava = mode != LaunchMode.Launcher && java == null;
        var (text, color) = running ? ("The game is running", "Ok")
            : installing ? ("Installing", "Warn")
            : !installed ? ("Not installed yet", "Warn")
            : !javaDone ? ("Looking for Java", "Muted")
            : needsJava ? ("Java 21 was not found", "Warn")
            : ("Ready to play", "Ok");

        // The big button. Its word is drawn in Pentecost's letters.
        var install = !installed && !installing;
        var word = new Wordmark { Text = install ? "INSTALL" : running ? "STOP" : "PLAY", Pixel = 2, VerticalAlignment = VerticalAlignment.Center, Fill = Ui.Br("Text") };
        // A pixel arrow (or a square while the game runs), drawn in 2 pixel blocks.
        var sign = new Canvas { Width = 12, Height = 18, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 16, 0), SnapsToDevicePixels = true };
        if (!install)
            for (var i = running ? 2 : 0; i < (running ? 7 : 9); i++)
            {
                var wide = running ? 10 : (i <= 4 ? i + 1 : 9 - i) * 2;
                var cell = new System.Windows.Shapes.Rectangle { Width = wide, Height = 2, Fill = Ui.Br("Text") };
                Canvas.SetLeft(cell, 0);
                Canvas.SetTop(cell, i * 2);
                sign.Children.Add(cell);
            }
        var face = new Grid { ClipToBounds = true };
        face.Children.Add(new DitherBackdrop());
        var line = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        if (!install) line.Children.Add(sign);
        line.Children.Add(word);
        face.Children.Add(line);
        var play = new Button { Content = face, Style = Ui.St("Secondary"), Height = 64, Padding = new Thickness(0), BorderBrush = Ui.Br("Accent"), Foreground = Ui.Br("Text") };
        if (install) play.Click += (_, _) => AskInstall(true);
        else
        {
            play.Click += (_, _) => StartGame();
            play.IsEnabled = !installing && (running || !needsJava);
        }
        play.ToolTip = install ? "Install Pentecost" : running ? "Stop the game (Ctrl+P)" : needsJava && javaDone ? "Choose Java in Settings first" : "Play (Ctrl+P)";
        ToolTipService.SetShowOnDisabled(play, true);
        if (play.IsEnabled && !running) Ui.Pulse(play);
        Cockpit.Children.Add(play);

        // What the game is doing, then three short groups with a square before each name.
        var ver = Ui.T(installed ? "v" + (Paths.Version ?? "?") : "", 11.5, "Faint");
        var status = Ui.Row(Ui.H(Ui.Dot(color, 7), Ui.T(text, 12.5, "Soft", trim: true).With(new Thickness(9, 0, 0, 0))), ver);
        status.Margin = new Thickness(1, 14, 0, 0);
        Cockpit.Children.Add(status);

        FrameworkElement Head(string name, double top)
        {
            var h = Ui.H(Ui.Dot("Accent", 5).With(new Thickness(0, 0, 9, 0)), Ui.Cap(name));
            h.Margin = new Thickness(1, top, 0, 9);
            return h;
        }

        var loadout = new StackPanel { IsEnabled = !running && !installing, Opacity = running || installing ? 0.45 : 1 };
        loadout.Children.Add(Head("Profile", 28));
        loadout.Children.Add(ProfilePicker());
        loadout.Children.Add(Head("Start with", 22));
        var modes = new UniformGrid { Rows = 1, Margin = new Thickness(0, 0, -4, 0) };
        foreach (var (label, m, tip) in new[]
        {
            ("Pentecost", LaunchMode.Standalone, "Starts Pentecost directly. No launcher."),
            ("Launcher", LaunchMode.Launcher, "Opens the Minecraft Launcher with the Pentecost profile first."),
            ("Source", LaunchMode.Source, "Builds and runs your source folder."),
        })
        {
            if (m == LaunchMode.Source && Paths.DevSource.Length == 0) continue;
            var target = m;
            var rb = new RadioButton { Content = label, Style = Ui.St("Seg"), GroupName = "mode", IsChecked = mode == m, ToolTip = tip };
            rb.Click += (_, _) => SetMode(target);
            modes.Children.Add(rb);
        }
        loadout.Children.Add(modes);
        Cockpit.Children.Add(loadout);

        var readout = new StackPanel();
        readout.Children.Add(Head("System", 26));
        void Fact(string label, UIElement value, string? tip = null)
        {
            var g = new Grid { Margin = new Thickness(14, 0, 0, 6) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(70) });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.Children.Add(Ui.T(label, 11.5, "Faint"));
            Grid.SetColumn(value, 1);
            g.Children.Add(value);
            if (tip != null) g.ToolTip = tip;
            readout.Children.Add(g);
        }
        Fact("Java", Ui.T(!javaDone ? "looking" : java == null ? "not found" : "21 or newer", 11.5, "Soft", trim: true), java);
        var prof = ProfileStore.Active;
        Fact("Memory", Ui.T(prof.MemoryGb > 0 ? prof.MemoryGb + " GB" : "auto, " + GameRun.AutoMemory().Heap + " GB", 11.5, "Soft"));
        var modCount = Ui.T("counting", 11.5, "Soft", trim: true);
        Fact("Mods", modCount);
        Cockpit.Children.Add(readout);
        _ = Task.Run(ModScan.Scan).ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            if (t.IsFaulted) { modCount.Text = "cannot read"; return; }
            var on = t.Result.Count(m => m.Enabled);
            modCount.Text = t.Result.Count == 0 ? "none yet" : on + " on, " + (t.Result.Count - on) + " off";
        }));
    }

    /// <summary>The profile button, with the list of profiles under it.</summary>
    Button ProfilePicker()
    {
        var active = ProfileStore.Active;
        var face = Ui.Row(Ui.T(active.Name, 13, "Text", trim: true), Ui.Glyph("", 10, "Muted"));
        var btn = new Button { Content = face, Style = Ui.St("Secondary"), HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(13, 9, 12, 9) };
        btn.ToolTip = "A profile keeps the memory, the Java arguments and which mods are on";
        Ui.Menu(btn, () =>
        {
            var now = ProfileStore.Active.Name;
            var items = ProfileStore.Load().Profiles
                .Select(p => (p.Name, (Action)(() => { ProfileStore.SetActive(p.Name); Toast("Profile " + p.Name + " is active."); Refresh(); }), p.Name == now))
                .ToList();
            items.Add(("Manage profiles", () => Go("Profiles"), false));
            return items;
        });        return btn;
    }

    // ---------------------------------------------------------------- motion

    /// <summary>Slides the white line under the open tab.</summary>
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

    /// <summary>A page comes in from the side its tab is on, and its parts follow one after another.</summary>
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
