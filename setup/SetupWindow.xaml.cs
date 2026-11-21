using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Pentecost.Setup;

public partial class SetupWindow : Window
{
    ManagerRelease? release;
    Task<ManagerRelease>? lookup;
    CancellationTokenSource? cancel;
    bool busy;
    bool openPentecostAfter = true;
    bool installing;
    int page = -1;

    public SetupWindow()
    {
        InitializeComponent();
        VersionText.Text = "SETUP " + typeof(SetupWindow).Assembly.GetName().Version.ToString(3);
        if (App.Demo) DemoTag.Visibility = Visibility.Visible;
        Loaded += (_, _) =>
        {
            lookup = Fetch.Latest(CancellationToken.None);
            _ = lookup.ContinueWith(t =>
            {
                if (t.Status == TaskStatus.RanToCompletion) Dispatcher.BeginInvoke(new Action(() => { release = t.Result; if (page == 0) ShowWelcome(); }));
            });
            ShowWelcome();
        };
    }

    void ShowWelcome()
    {
        var again = page == 0;
        page = 0;
        Scene.Mode = SceneMode.Idle;
        Scene.Progress = 0;
        SetLine(0, false);
        var update = File.Exists(Fetch.InstalledExe);
        var size = release != null ? release.Size / 1048576 : 56;
        var version = release != null ? release.Version : "";
        var body = Stack(
            Title(update ? "Update Pentecost" : "Pentecost"),
            Line(update ? "Your game, worlds and mods stay as they are." : "Forge, Fabric and Quilt mods, together in one game."),
            Meta((version.Length > 0 ? "MANAGER " + version + "   " : "") + size + " MB   " + Shorten(Fetch.InstallDir), 22));
        var then = new CheckBox { Style = (Style)FindResource("Switch"), IsChecked = openPentecostAfter, Content = Line("Install the game right after", 13), Margin = new Thickness(0, 26, 0, 0) };
        then.Checked += (_, _) => openPentecostAfter = true;
        then.Unchecked += (_, _) => openPentecostAfter = false;
        body.Children.Add(then);
        body.Children.Add(Buttons(Btn(update ? "Continue" : "Continue", "Primary", ShowMods), Btn("Cancel", "Ghost", Close)));
        Show(body, !again);
    }

    TextBlock? hint;

    void ShowMods()
    {
        page = 1;
        Scene.Mode = SceneMode.Idle;
        hint = Meta("", 14);
        var tiles = new WrapPanel { Margin = new Thickness(-4, 22, 0, 0) };
        foreach (var m in StarterMods.All) tiles.Children.Add(Tile(m));
        UpdateHint(null);
        var body = Stack(
            Title("Starter mods"),
            Line("Pick what goes in your first game. Change it any time later."),
            tiles,
            hint,
            Buttons(Btn("Install", "Primary", () => _ = Install()), Btn("Back", "Ghost", ShowWelcome)));
        Show(body, true);
        if (!iconsAsked)
        {
            iconsAsked = true;
            _ = StarterMods.LoadIcons(CancellationToken.None).ContinueWith(t =>
            {
                if (t.Status == TaskStatus.RanToCompletion) Dispatcher.BeginInvoke(new Action(() => { if (page == 1) ShowMods(); }));
            });
        }
    }

    bool iconsAsked;

    void UpdateHint(StarterMod? over)
    {
        if (hint == null) return;
        if (over != null) { hint.Text = over.Name.ToUpperInvariant() + "   " + over.Blurb; return; }
        var n = Picks();
        hint.Text = n == 0 ? "NOTHING PICKED" : n == 1 ? "1 MOD PICKED" : n + " MODS PICKED";
    }

    FrameworkElement Tile(StarterMod m)
    {
        var img = StarterMods.Icon(m);
        FrameworkElement icon = img != null
            ? new Image { Source = img, Width = 40, Height = 40, Stretch = Stretch.UniformToFill }
            : new TextBlock { Text = m.Name.Substring(0, 1), FontSize = 20, Foreground = (Brush)FindResource("Muted"), HorizontalAlignment = HorizontalAlignment.Center };
        RenderOptions.SetBitmapScalingMode(icon, BitmapScalingMode.HighQuality);
        icon.HorizontalAlignment = HorizontalAlignment.Center;
        var name = new TextBlock
        {
            Text = m.Name, FontSize = 11, Foreground = (Brush)FindResource("Soft"), TextTrimming = TextTrimming.CharacterEllipsis,
            HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 0), MaxWidth = 88,
        };
        var mark = new Border { Height = 1, Width = 18, Margin = new Thickness(0, 7, 0, 0), HorizontalAlignment = HorizontalAlignment.Center, Background = (Brush)FindResource("Text") };
        var inner = new StackPanel();
        inner.Children.Add(icon);
        inner.Children.Add(name);
        inner.Children.Add(mark);
        var tile = new Border { Width = 98, Padding = new Thickness(4, 8, 4, 6), Margin = new Thickness(0, 0, 8, 4), Background = Brushes.Transparent, Child = inner };
        void Paint()
        {
            var on = m.Picked || m.Included;
            tile.Opacity = on ? 1 : 0.32;
            mark.Visibility = on ? Visibility.Visible : Visibility.Hidden;
        }
        Paint();
        tile.MouseEnter += (_, _) => { tile.Background = (Brush)FindResource("Hover"); UpdateHint(m); };
        tile.MouseLeave += (_, _) => { tile.Background = Brushes.Transparent; UpdateHint(null); };
        if (!m.Included)
        {
            tile.Cursor = System.Windows.Input.Cursors.Hand;
            tile.MouseLeftButtonUp += (_, _) => { m.Picked = !m.Picked; Paint(); };
        }
        return tile;
    }

    static int Picks() => StarterMods.All.Count(m => m.Picked && !m.Included);

    readonly Row[] rows = new Row[4];
    TextBlock? status, detail;

    void ShowInstalling()
    {
        page = 2;
        Scene.Progress = 0;
        Scene.Mode = SceneMode.Working;
        status = Line("Starting");
        detail = Meta("", 10);
        string[] names = { "Finding the latest release", "Downloading Pentecost Manager", "Checking the download", "Installing" };
        for (var i = 0; i < names.Length; i++) rows[i] = new Row(names[i], this);
        var body = Stack(Title("Installing"), status, detail, Buttons(Btn("Cancel", "Ghost", () => cancel?.Cancel())));
        Show(body, true);
        SetLine(0, true);
    }

    void ShowDone()
    {
        page = 3;
        Scene.Progress = 1;
        Scene.Mode = SceneMode.Done;
        SetLine(1, true);
        var n = Picks();
        var more = n > 0 ? $" Your {n} starter mods come with the game." : "";
        var body = Stack(
            Title("Ready"),
            Line("The Manager is on your desktop." + more),
            Buttons(Btn("Open Pentecost Manager", "Primary", Open), Btn("Close", "Ghost", Close)));
        Show(body, true);
    }

    void ShowError(string why)
    {
        page = 4;
        Scene.Mode = SceneMode.Failed;
        SetLine(0, false);
        var after = installing ? "The Manager may be partly installed. Trying again replaces it cleanly." : "Nothing was changed on your computer.";
        var body = Stack(Title("Setup stopped"), Line(why), Meta(after.ToUpperInvariant(), 12),
            Buttons(Btn("Try again", "Primary", () => _ = Install()), Btn("Close", "Ghost", Close)));
        Show(body, true);
    }

    async Task Install()
    {
        if (busy) return;
        busy = true;
        installing = false;
        cancel = new CancellationTokenSource();
        ShowInstalling();
        var ct = cancel.Token;
        try
        {
            if (App.Demo) await Demo(ct);
            else
            {
                rows[0].Start();
                var r = release ?? await (lookup != null && !lookup.IsFaulted ? lookup : Fetch.Latest(ct));
                release = r;
                rows[0].Finish("VERSION " + r.Version);

                rows[1].Start();
                string file = await Fetch.Download(r, (done, total) => Dispatcher.BeginInvoke(new Action(() =>
                {
                    Progress(0.85 * done / Math.Max(1, total));
                    rows[1].Detail($"{done / 1048576} / {total / 1048576} MB");
                })), ct);
                rows[1].Finish($"{r.Size / 1048576} MB");

                rows[2].Start();
                await Fetch.Verify(file, r, ct);
                rows[2].Finish(r.Sha256 != null ? "CHECKSUM MATCHES" : "SIZE MATCHES");
                Progress(0.9);

                rows[3].Start();
                cancel = null;
                installing = true;
                await Fetch.RunManagerSetup(file);
                StarterMods.HandOff();
                rows[3].Finish("");
                Progress(1);
            }
            await Task.Delay(450);
            ShowDone();
        }
        catch (OperationCanceledException)
        {
            ShowWelcome();
        }
        catch (Exception ex)
        {
            ShowError(Fetch.Explain(ex));
        }
        finally
        {
            busy = false;
            cancel = null;
        }
    }

    async Task Demo(CancellationToken ct)
    {
        rows[0].Start();
        await Task.Delay(900, ct);
        rows[0].Finish("VERSION 0.7.0");
        rows[1].Start();
        const int total = 57;
        for (var mb = 0; mb <= total; mb++)
        {
            Progress(0.85 * mb / total);
            rows[1].Detail($"{mb} / {total} MB");
            await Task.Delay(55, ct);
        }
        rows[1].Finish($"{total} MB");
        rows[2].Start();
        await Task.Delay(700, ct);
        rows[2].Finish("CHECKSUM MATCHES");
        Progress(0.9);
        rows[3].Start();
        await Task.Delay(1200, ct);
        rows[3].Finish("");
        Progress(1);
    }

    void Open()
    {
        if (App.Demo) { Close(); return; }
        try
        {
            Process.Start(new ProcessStartInfo(Fetch.InstalledExe, openPentecostAfter ? "--welcome" : "")
            { UseShellExecute = true, WorkingDirectory = Fetch.InstallDir });
        }
        catch (Exception ex) { ShowError("The Manager is installed but did not open: " + ex.Message); return; }
        Close();
    }

    double lineAt;

    void Progress(double f)
    {
        f = Math.Max(0, Math.Min(1, f));
        Scene.Progress = f;
        SetLine(f, true);
    }

    void SetLine(double f, bool shown)
    {
        lineAt = f;
        var target = shown ? Root.ActualWidth * f : 0;
        ProgressLine.BeginAnimation(WidthProperty, new DoubleAnimation(target, new Duration(TimeSpan.FromMilliseconds(shown ? 260 : 0))));
    }

    void Min_Click(object s, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    void Close_Click(object s, RoutedEventArgs e)
    {
        if (busy && cancel == null) return;
        cancel?.Cancel();
        Close();
    }

    void Show(UIElement content, bool move)
    {
        PageHost.Content = content;
        if (!move) return;
        var d = new Duration(TimeSpan.FromMilliseconds(420));
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        PageHost.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, d) { EasingFunction = ease });
        PageMove.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(10, 0, d) { EasingFunction = ease });
    }

    static StackPanel Stack(params UIElement[] items)
    {
        var s = new StackPanel();
        foreach (var i in items) s.Children.Add(i);
        return s;
    }

    TextBlock Title(string text) => new TextBlock { Text = text, FontSize = 40, FontWeight = FontWeights.Light, Foreground = (Brush)FindResource("Text"), Margin = new Thickness(-2, 0, 0, 0) };

    TextBlock Line(string text, double size = 15) => new TextBlock
    {
        Text = text, FontSize = size, Foreground = (Brush)FindResource("Soft"), TextWrapping = TextWrapping.Wrap,
        Margin = new Thickness(0, size >= 15 ? 10 : 0, 0, 0), LineHeight = size * 1.45,
    };

    TextBlock Meta(string text, double top) => new TextBlock
    {
        Text = text, FontFamily = (FontFamily)FindResource("Mono"), FontSize = 10.5, Foreground = (Brush)FindResource("Faint"),
        TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, top, 0, 0),
    };

    StackPanel Buttons(params Button[] buttons)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 30, 0, 0) };
        for (var i = 0; i < buttons.Length; i++)
        {
            if (i > 0) buttons[i].Margin = new Thickness(8, 0, 0, 0);
            row.Children.Add(buttons[i]);
        }
        return row;
    }

    static string Shorten(string path)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return (path.StartsWith(home, StringComparison.OrdinalIgnoreCase) ? "~" + path.Substring(home.Length) : path).ToUpperInvariant();
    }

    Button Btn(string text, string style, Action click)
    {
        var b = new Button { Content = text, Style = (Style)FindResource(style) };
        b.Click += (_, _) => click();
        return b;
    }

    sealed class Row
    {
        readonly string name;
        readonly SetupWindow w;

        public Row(string name, SetupWindow w)
        {
            this.name = name;
            this.w = w;
        }

        public void Start()
        {
            if (w.status != null) w.status.Text = name;
            if (w.detail != null) w.detail.Text = "";
        }

        public void Detail(string text)
        {
            if (w.detail != null) w.detail.Text = text;
        }

        public void Finish(string text)
        {
            if (w.detail != null && text.Length > 0) w.detail.Text = text;
        }
    }
}
