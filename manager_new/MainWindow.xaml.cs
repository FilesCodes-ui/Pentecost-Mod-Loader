using System.Diagnostics;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow : Window
{
    readonly GameRun game = new();
    LaunchMode mode;
    string? java;
    bool javaDone;
    ReleaseInfo? latest;
    bool updatesChecked;
    string? managerSetup;
    string? managerSetupVersion;
    Task? managerFetch;
    bool managerBusy;
    bool managerHandedOver;
    bool installing;
    ProgressBar? installBar;
    TextBlock? installLabel;
    string current = "";
    readonly TextBox logBox;
    readonly DispatcherTimer toastTimer = new() { Interval = TimeSpan.FromSeconds(4) };
    readonly DispatcherTimer updateTimer = new() { Interval = TimeSpan.FromMinutes(30) };
    readonly Dictionary<string, FrameworkElement> kept = new();

    public MainWindow()
    {
        InitializeComponent();
        InitExtras();
        mode = Enum.TryParse<LaunchMode>(Paths.Mode, out var m) ? m : LaunchMode.Standalone;
        if (mode == LaunchMode.Source && Paths.DevSource.Length == 0) mode = LaunchMode.Standalone;

        logBox = new TextBox
        {
            IsReadOnly = true, BorderThickness = new Thickness(0), Background = Brushes.Transparent, Padding = new Thickness(16, 12, 16, 12),
            FontFamily = (FontFamily)FindResource("Mono"), FontSize = 12, Foreground = Ui.Br("Soft"),
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            TextWrapping = TextWrapping.NoWrap,
        };

        game.Line += s => Dispatcher.BeginInvoke(() => AppendLog(s));
        game.Exited += code => Dispatcher.BeginInvoke(() => OnExited(code));
        toastTimer.Tick += (_, _) => { toastTimer.Stop(); Toast_Hide(); };
        updateTimer.Tick += (_, _) => { if (!testing && !installing) _ = CheckUpdates(false); };
        StateChanged += (_, _) => Frame.Margin = WindowState == WindowState.Maximized ? new Thickness(7) : new Thickness(0);
        SizeChanged += (_, _) => MoveTabLine();

        Closing += (_, _) => iconTimer.Stop();
        Closing += (_, _) => updateTimer.Stop();
        Closed += (_, _) => InstallOnClose();
        Closing += (_, _) => Paths.Window = $"{(int)Left},{(int)Top},{(int)Width},{(int)Height},{(WindowState == WindowState.Maximized ? 1 : 0)}";
        Loaded += async (_, _) =>
        {
            RestoreWindow();
            StartIconSpin();
            RefreshBar();
            Go(Pages.Contains(Paths.LastTab) ? Paths.LastTab : "Play");
            RefreshAccount();
            if (!testing && SelfInstall.ShouldOffer) OfferSelfInstall();
            java = await Task.Run(JavaFinder.Find);
            javaDone = true;
            Refresh();
            await CheckUpdates(false);
            if (!testing) updateTimer.Start();
            if (App.Welcome && !testing && Paths.Version == null) AskInstall(true);
        };
    }

    // ---------------------------------------------------------------- the taskbar icon turns too

    System.Windows.Media.Imaging.BitmapSource[]? iconFrames;
    int iconAt;
    readonly DispatcherTimer iconTimer = new() { Interval = TimeSpan.FromMilliseconds(100) };

    /// <summary>Draws the frames once, then shows the next one about ten times a second.</summary>
    void StartIconSpin()
    {
        try
        {
            iconFrames = IconMaker.Frames(32, 24);
            iconTimer.Tick += (_, _) =>
            {
                if (iconFrames == null) return;
                Icon = iconFrames[iconAt];
                iconAt = (iconAt + 1) % iconFrames.Length;
            };
            iconTimer.Start();
        }
        catch (Exception ex) { AppLog.Write("icon spin: " + ex.Message); }
    }

    // ---------------------------------------------------------------- window chrome
    void Min_Click(object s, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    void Close_Click(object s, RoutedEventArgs e) => Close();

    // ---------------------------------------------------------------- navigation
    void Nav_Checked(object s, RoutedEventArgs e)
    {
        if (PageHost == null) return;
        ShowPage(((RadioButton)s).Uid, true);
    }

    static readonly string[] Pages = { "Play", "Mods", "Browse", "Profiles", "Crashes", "Log", "Settings" };

    /// <summary>Selects a tab, which opens its page.</summary>
    public void Go(string page)
    {
        foreach (var rb in NavPanel.Children.OfType<RadioButton>())
            if (rb.Uid == page) { if (rb.IsChecked == true) ShowPage(page, true); else rb.IsChecked = true; return; }
    }

    /// <summary>Builds the current page again, without the slide-in.</summary>
    void Refresh()
    {
        RefreshBar();
        if (current.Length > 0) ShowPage(current, false);
    }

    /// <summary>Puts the window back where it was last time, as long as that spot is still on a screen.</summary>
    void RestoreWindow()
    {
        var parts = Paths.Window.Split(',');
        if (parts.Length != 5 || !parts.Take(4).All(p => int.TryParse(p, out _))) return;
        var (x, y, w, h) = (int.Parse(parts[0]), int.Parse(parts[1]), int.Parse(parts[2]), int.Parse(parts[3]));
        if (w < MinWidth || h < MinHeight) return;
        var screen = SystemParameters.VirtualScreenWidth;
        if (x < -w + 80 || y < -40 || x > screen - 80 || y > SystemParameters.VirtualScreenHeight - 60) return;
        WindowStartupLocation = WindowStartupLocation.Manual;
        Left = x;
        Top = y;
        Width = w;
        Height = h;
        if (parts[4] == "1") WindowState = WindowState.Maximized;
    }

    void ShowPage(string name, bool animate)
    {
        var before = Array.IndexOf(Pages, current);
        var after = Array.IndexOf(Pages, name);
        current = name;
        inFull = false;
        if (!testing) Paths.LastTab = name;
        HideModal();
        FrameworkElement page;
        try
        {
            page = name switch
            {
                "Play" => BuildPlay(),
                "Mods" => BuildMods(),
                "Browse" => Kept("Browse", BuildBrowse),
                "Profiles" => BuildProfiles(),
                "Crashes" => BuildCrashes(),
                "Log" => BuildLog(),
                "Settings" => BuildSettings(),
                _ => new Grid(),
            };
        }
        catch (Exception ex)
        {
            AppLog.Write($"page {name}: {ex}");
            page = new TextBlock { Text = "This page could not be built: " + ex.Message, Foreground = Ui.Br("Bad"), TextWrapping = TextWrapping.Wrap };
        }
        PageHost.Content = page;
        MoveTabLine();
        if (animate) Enter(page, before >= 0 && after >= 0 && after < before ? -26 : 26);
    }

    FrameworkElement Kept(string key, Func<FrameworkElement> build)
    {
        if (!kept.TryGetValue(key, out var el)) kept[key] = el = build();
        return el;
    }

    // ---------------------------------------------------------------- shared page pieces
    static ScrollViewer Scroll(UIElement content) => new()
    {
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        Content = content, Padding = new Thickness(0, 0, 10, 28),
    };

    // ---------------------------------------------------------------- toast and dialogs
    public void Toast(string text, string kind = "Ok")
    {
        ToastText.Text = text;
        ToastDot.Background = TryFindResource(kind) as Brush ?? Ui.Br("Ok");
        ToastBox.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(140)));
        var move = new TranslateTransform();
        ToastBox.RenderTransform = move;
        move.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(14, 0, TimeSpan.FromMilliseconds(220)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
        toastTimer.Stop();
        toastTimer.Start();
    }

    void Toast_Click(object s, MouseButtonEventArgs e) { toastTimer.Stop(); Toast_Hide(); }

    void Toast_Hide() => ToastBox.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(260)));

    Action? modalEnter;

    public void ShowModal(FrameworkElement content)
    {
        modalEnter = null;
        ModalHost.Content = content;
        Modal.Visibility = Visibility.Visible;
        Modal.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(130)));
        var grow = new ScaleTransform(0.97, 0.97);
        ModalHost.RenderTransform = grow;
        var pop = new DoubleAnimation(0.97, 1, TimeSpan.FromMilliseconds(190)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
        grow.BeginAnimation(ScaleTransform.ScaleXProperty, pop);
        grow.BeginAnimation(ScaleTransform.ScaleYProperty, pop);
    }

    public void HideModal()
    {
        modalEnter = null;
        pictureStep = null;
        Modal.Visibility = Visibility.Collapsed;
        ModalHost.Content = null;
    }

    /// <summary>A small yes or no box.</summary>
    void Confirm(string title, string body, string yes, Action onYes, bool danger = false)
    {
        var no = Ui.Btn("Cancel", "Secondary", (_, _) => HideModal());
        var ok = Ui.Btn(yes, danger ? "Secondary" : "Primary", (_, _) => { HideModal(); onYes(); });
        if (danger) ok.Foreground = Ui.Br("Bad");
        var buttons = Ui.H(no, ok.With(new Thickness(10, 0, 0, 0)));
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        var box = Ui.V(Ui.T(title, 17, bold: true), Ui.T(body, 13.5, "Soft", wrap: true).With(new Thickness(0, 10, 0, 22)), buttons);
        box.Width = 440;
        ShowModal(Ui.Card(box, 26));
        modalEnter = () => { HideModal(); onYes(); };
    }

    // ---------------------------------------------------------------- account
    void RefreshAccount()
    {
        var name = Paths.AccountName();
        AccountName.Text = name ?? "Offline";
        AccountSub.Text = name != null ? "Signed in with Microsoft" : "Sign in inside the game";
        AccountLetter.Text = name != null ? char.ToUpperInvariant(name[0]).ToString() : "?";
        AccountLetter.Foreground = Ui.Br(name != null ? "Text" : "Muted");
    }

    void Account_Click(object s, MouseButtonEventArgs e) => Go("Settings");

    // ---------------------------------------------------------------- drag and drop of mod jars
    void Window_DragOver(object s, DragEventArgs e)
    {
        var jars = e.Data.GetDataPresent(DataFormats.FileDrop)
            && e.Data.GetData(DataFormats.FileDrop) is string[] f && f.Any(p => p.EndsWith(".jar", StringComparison.OrdinalIgnoreCase));
        e.Effects = jars ? DragDropEffects.Copy : DragDropEffects.None;
        if (jars && DropHint.Visibility != Visibility.Visible)
        {
            DropHint.Visibility = Visibility.Visible;
            Ui.FadeIn(DropHint, 120);
        }
        e.Handled = true;
    }

    void Window_DragLeave(object s, DragEventArgs e) => DropHint.Visibility = Visibility.Collapsed;

    void Window_Drop(object s, DragEventArgs e)
    {
        DropHint.Visibility = Visibility.Collapsed;
        if (e.Data.GetData(DataFormats.FileDrop) is not string[] files) return;
        AddJars(files);
    }

    void AddJars(IEnumerable<string> files)
    {
        if (testing) return;
        try
        {
            var list = files.ToList();
            var added = ModScan.AddFiles(list);
            var skipped = list.Count(f => f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) - added.Count;
            if (added.Count > 0) Toast(added.Count == 1 ? "Added " + added[0] : $"Added {added.Count} mods");
            else Toast(skipped > 0 ? "Those mods are already in the folder." : "Only .jar files can be added.", "Warn");
            if (current == "Mods") Refresh();
        }
        catch (Exception ex) { AppLog.Write("add jars: " + ex); Toast("Could not add the mod: " + ex.Message, "Bad"); }
    }

    // ---------------------------------------------------------------- launching
    void StartGame()
    {
        if (testing) { AppLog.Write("self-test: did not start the game"); return; }
        if (game.Running) { game.Stop(); return; }
        if (installing) return;
        var profile = ProfileStore.Active;
        if (profile.On.Count + profile.Off.Count > 0)
        {
            var (changed, failed) = ProfileStore.Apply(profile);
            if (failed.Count > 0) { Toast("Could not switch some mods: " + failed[0], "Bad"); return; }
            if (changed > 0) AppendLog($"Profile {profile.Name}: switched {changed} mods.");
        }
        logBox.Clear();
        var err = game.Start(mode, java, profile);
        if (err != null) { Toast(err, "Bad"); Refresh(); return; }
        AppendLog($"Starting ({mode}, profile {profile.Name}).");
        Toast(mode == LaunchMode.Launcher ? "Minecraft Launcher opened. Press Play there." : "Starting Pentecost");
        Refresh();
    }

    void OnExited(int code)
    {
        AppendLog($"The game ended with code {code}.");
        if (code != 0) Toast($"The game stopped with code {code}. See Crashes.", "Warn");
        Refresh();
    }

    void AppendLog(string line)
    {
        logBox.AppendText(line + "\n");
        if (logBox.Text.Length > 300_000) logBox.Text = logBox.Text[^150_000..];
        logBox.ScrollToEnd();
    }

    void SetMode(LaunchMode m)
    {
        mode = m;
        Paths.Mode = m.ToString();
        Refresh();
    }

    // ---------------------------------------------------------------- install and update
    async Task CheckUpdates(bool announce)
    {
        var before = (latest?.Tag, latest?.ManagerVersion);
        try
        {
            latest = await Updates.Latest();
            FetchManager();
            if (announce)
            {
                var cur = Paths.Version;
                var pent = latest != null && (cur == null || Updates.Newer(latest.Version, cur));
                var mgr = Updates.ManagerNewer(latest);
                if (latest == null) Toast("Could not read the release list.", "Warn");
                else if (pent && mgr) Toast($"Pentecost {latest.Version} and Manager {latest.ManagerVersion} are available.");
                else if (pent) Toast($"Pentecost {latest.Version} is available.");
                else if (mgr) Toast($"Manager {latest.ManagerVersion} is available." + (SelfInstall.CanUpdate ? " It installs when you close the Manager." : ""));
                else Toast("You are up to date.");
            }
        }
        catch (Exception ex)
        {
            AppLog.Write("update check: " + ex.Message);
            if (announce) Toast("Could not reach GitHub.", "Warn");
        }
        // The half hourly check must not rebuild a page the player is working in, unless something new showed up.
        if (announce || !updatesChecked) Refresh();
        else if (before != (latest?.Tag, latest?.ManagerVersion)) RefreshUpdateViews();
        updatesChecked = true;
    }

    /// <summary>True when a newer Manager is on the release and this copy can show or apply it.</summary>
    bool ManagerUpdate => latest != null && Updates.ManagerNewer(latest) && (SelfInstall.CanUpdate || testing);

    /// <summary>Only Home and Settings show update state, so only they are rebuilt.</summary>
    void RefreshUpdateViews()
    {
        if (current is "Play" or "Settings" && !inFull && Modal.Visibility != Visibility.Visible) ShowPage(current, false);
    }

    /// <summary>
    /// Fetches the newest Manager setup in the background, so the update is ready when the player presses Update now
    /// or closes the Manager. Only the installed copy does this.
    /// </summary>
    void FetchManager()
    {
        var r = latest;
        if (testing || r == null || !SelfInstall.CanUpdate || !Updates.ManagerNewer(r)) return;
        if (managerSetupVersion == r.ManagerVersion || managerFetch is { IsCompleted: false }) return;
        managerFetch = Task.Run(async () =>
        {
            var (path, err) = await SelfUpdate.Download(r, null, CancellationToken.None);
            if (path != null)
            {
                managerSetup = path;
                managerSetupVersion = r.ManagerVersion;
                AppLog.Write("manager " + r.ManagerVersion + " is ready to install");
            }
            else AppLog.Write("manager update: " + err);
            await Dispatcher.BeginInvoke(() => RefreshUpdateViews());
        });
    }

    /// <summary>Update now: the checked Setup replaces the installed Manager and starts it again.</summary>
    async Task UpdateManagerNow()
    {
        if (testing) { AppLog.Write("self-test: did not update the Manager"); return; }
        var r = latest;
        if (r == null || !Updates.ManagerNewer(r)) { Toast("The Manager is up to date."); return; }
        if (!SelfInstall.CanUpdate) { Toast("This copy is not the installed Manager, so it does not update itself.", "Warn"); return; }
        if (managerBusy) return;
        managerBusy = true;
        try
        {
            Toast("Getting the new Manager.");
            if (managerFetch is { IsCompleted: false }) await managerFetch;
            if (managerSetupVersion != r.ManagerVersion)
            {
                var (path, err) = await SelfUpdate.Download(r, null, CancellationToken.None);
                if (path == null) { Toast(err ?? "Could not get the Manager.", "Bad"); return; }
                managerSetup = path;
                managerSetupVersion = r.ManagerVersion;
            }
            var run = SelfUpdate.Run(managerSetup!, true);
            if (run != null) { Toast(run, "Bad"); return; }
            managerHandedOver = true;
            Close();
        }
        finally { managerBusy = false; }
    }

    /// <summary>A checked update that was not applied with the button installs now, without a window and without starting again.</summary>
    void InstallOnClose()
    {
        if (testing || managerHandedOver || managerSetup == null || managerSetupVersion == null) return;
        if (!SelfInstall.CanUpdate) return;
        AppLog.Write("installing Manager " + managerSetupVersion + " on close");
        SelfUpdate.Run(managerSetup, false);
    }

    async Task RunInstall(bool worlds)
    {
        if (testing) { AppLog.Write("self-test: did not install"); return; }
        if (latest == null) { await CheckUpdates(false); if (latest == null) { Toast("Could not reach GitHub.", "Bad"); return; } }
        if (game.Running) { Toast("Close the game first.", "Warn"); return; }
        installing = true;
        Refresh();
        var err = await InstallService.Run(latest!, worlds, (text, f) => Dispatcher.BeginInvoke(() =>
        {
            if (installLabel != null) installLabel.Text = text;
            if (installBar != null) { installBar.IsIndeterminate = false; installBar.Value = f; }
        }), CancellationToken.None);
        installing = false;
        installBar = null;
        installLabel = null;
        Refresh();
        if (err == null)
        {
            AppendLog($"Pentecost {latest!.Version} installed.");
            Toast($"Pentecost {latest.Version} is installed.");
            _ = Task.Run(JavaFinder.Find).ContinueWith(t => Dispatcher.BeginInvoke(() => { java = t.Result; Refresh(); }));
        }
        else Toast(err, "Bad");
    }

    void OpenPath(string path)
    {
        if (testing) { AppLog.Write("self-test: did not open " + path); return; }
        try { Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); }
        catch (Exception ex) { AppLog.Write("open " + path + ": " + ex.Message); }
    }

    void OpenFolder(string dir)
    {
        if (testing) { AppLog.Write("self-test: did not open " + dir); return; }
        try { Directory.CreateDirectory(dir); OpenPath(dir); } catch { }
    }
}
