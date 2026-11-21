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
    readonly List<string> logLines = new();
    readonly DispatcherTimer toastTimer = new() { Interval = TimeSpan.FromSeconds(4) };
    readonly DispatcherTimer updateTimer = new() { Interval = TimeSpan.FromMinutes(30) };
    readonly Dictionary<string, FrameworkElement> kept = new();

    public MainWindow()
    {
        InitializeComponent();
        SourceInitialized += (_, _) => Anim.Hook(this);
        Anim.Fps = Settings.Fps;
        Anim.Paused = () => (Settings.PausePlaying && game.Running) || (Settings.PauseBattery && Power.OnBattery);
        InitExtras();
        mode = Enum.TryParse<LaunchMode>(Paths.Mode, out var m) ? m : LaunchMode.Standalone;
        if (mode == LaunchMode.Source && Paths.DevSource.Length == 0) mode = LaunchMode.Standalone;

        game.Line += s => Dispatcher.BeginInvoke(() => AppendLog(s));
        game.Exited += code => Dispatcher.BeginInvoke(() => OnExited(code));
        toastTimer.Tick += (_, _) => { toastTimer.Stop(); Toast_Hide(); };
        updateTimer.Tick += (_, _) => { if (!testing && !installing) _ = CheckUpdates(false); };
        StateChanged += (_, _) => Frame.Margin = WindowState == WindowState.Maximized ? new Thickness(7) : new Thickness(0);
        SizeChanged += (_, _) => MoveTabLine();

        Closing += (_, _) => iconTimer.Stop();
        Closing += (_, _) => updateTimer.Stop();
        Closed += (_, _) => InstallOnClose();
        ModScan.GameRunning = () => game.Running;
        Activated += (_, _) =>
        {
            if (testing || inFull || Modal.Visibility == Visibility.Visible || current is not ("Mods" or "Play")) return;
            if (GameChoices.Changed()) ShowPage(current, false);
        };
        Closing += (_, _) => Paths.Window = $"{(int)Left},{(int)Top},{(int)Width},{(int)Height},{(WindowState == WindowState.Maximized ? 1 : 0)}";
        Loaded += async (_, _) =>
        {
            Instances.GameRunning = () => game.Running;
            if (!testing)
            {
                var err = Instances.Migrate();
                if (err != null) Toast(err, "Warn");
            }
            RestoreWindow();
            StartIconSpin();
            DevTab.Visibility = DevAccess.Unlocked ? Visibility.Visible : Visibility.Collapsed;
            RefreshInstalledProjects();
            InitOnline();
            RefreshBar();
            Go(Settings.OpenOn != "Last" && Pages.Contains(Settings.OpenOn) ? Settings.OpenOn : Pages.Contains(Paths.LastTab) ? Paths.LastTab : "Play");
            RefreshAccount();
            ShowWhatsNew();
            if (!testing)
                _ = Task.Run(() => CrashScan.Recent(ModScan.Scan(), 10)).ContinueWith(t => { if (!t.IsFaulted) _ = Online.ReportCrashes(t.Result); });
            if (App.Demo)
            {
                Title = "Pentecost Manager (installer demo)";
                OfferSelfInstall();
                Toast("Installer demo: every step is faked, nothing is installed.");
                return;
            }
            if (!testing && SelfInstall.ShouldOffer) OfferSelfInstall();
            java = await Task.Run(JavaFinder.Find);
            javaDone = true;
            Refresh();
            if (Settings.AutoUpdate)
            {
                await CheckUpdates(false);
                if (!testing) updateTimer.Start();
            }
            if (App.Welcome && !testing && Paths.Version == null) AskInstall(true);
        };
    }

    readonly DateTime iconStart = DateTime.UtcNow;
    readonly DispatcherTimer iconTimer = new() { Interval = TimeSpan.FromMilliseconds(50) };

    void StartIconSpin()
    {
        try
        {
            var direct = true;
            iconTimer.Tick += (_, _) =>
            {
                var s = (DateTime.UtcNow - iconStart).TotalSeconds;
                if (direct)
                {
                    try { IconSpin.Show(this, s); return; }
                    catch (Exception ex) { direct = false; AppLog.Write("taskbar icon: " + ex.Message); }
                }
                Icon = IconMaker.FrameAt(32, s);
            };
            iconTimer.Start();
        }
        catch (Exception ex) { AppLog.Write("icon spin: " + ex.Message); }
    }

    void Min_Click(object s, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    void Close_Click(object s, RoutedEventArgs e) => Close();

    void Nav_Checked(object s, RoutedEventArgs e)
    {
        if (PageHost == null) return;
        ShowPage(((RadioButton)s).Uid, true);
    }

    static readonly string[] Pages = { "Play", "Mods", "Browse", "Profiles", "Friends", "Crashes", "Log", "Settings", "Dev" };

    public void Go(string page)
    {
        DiscordPresence.Page = page;
        foreach (var rb in NavPanel.Children.OfType<RadioButton>())
            if (rb.Uid == page) { if (rb.IsChecked == true) ShowPage(page, true); else rb.IsChecked = true; return; }
    }

    void Refresh()
    {
        RefreshBar();
        if (current.Length > 0) ShowPage(current, false);
    }

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
                "Friends" => BuildFriends(),
                "Dev" => BuildDev(),
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

    static ScrollViewer Scroll(UIElement content) => new()
    {
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        Content = content, Padding = new Thickness(0, 0, 10, 28),
    };

    Action? toastAction;

    public void Toast(string text, string kind, string action, Action onAction)
    {
        Toast(text, kind);
        toastAction = onAction;
        ToastAction.Content = action;
        ToastAction.Visibility = Visibility.Visible;
        toastTimer.Stop();
        toastTimer.Interval = TimeSpan.FromSeconds(8);
        toastTimer.Start();
    }

    void ToastAction_Click(object s, RoutedEventArgs e)
    {
        e.Handled = true;
        var a = toastAction;
        toastAction = null;
        ToastAction.Visibility = Visibility.Collapsed;
        toastTimer.Stop();
        Toast_Hide();
        a?.Invoke();
    }

    public void Toast(string text, string kind = "Ok")
    {
        toastAction = null;
        ToastAction.Visibility = Visibility.Collapsed;
        toastTimer.Interval = TimeSpan.FromSeconds(4);
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

    void RefreshAccount()
    {
        var name = Paths.AccountName();
        AccountName.Text = name ?? "Offline";
        var online = Online.State == "online" ? Online.OnlineCount : -1;
        var unread = Online.UnreadTotal;
        AccountSub.Text = name == null ? "Sign in inside the game"
            : unread > 0 ? unread + " new from friends"
            : online > 0 ? online + (online == 1 ? " friend online" : " friends online")
            : "Signed in with Microsoft";
        AccountLetter.Text = name != null ? char.ToUpperInvariant(name[0]).ToString() : "?";
        AccountLetter.Foreground = Ui.Br(name != null ? "Text" : "Muted");
        var hosting = Online.Friends.Count(f => f.Online && f.Status?["server"] != null);
        if (name != null && hosting > 0) AccountSub.Text += ", " + hosting + " hosting";
        if (name != null && AccountTile.Tag as string != name)
        {
            AccountTile.Tag = name;
            var grid = new Grid();
            grid.Children.Add(DetachLetter());
            grid.Children.Add(Heads.Tile(name, 34));
            AccountTile.Child = grid;
        }
    }

    void Account_Click(object s, MouseButtonEventArgs e) => Go("Friends");

    UIElement DetachLetter()
    {
        if (AccountLetter.Parent is Panel p) p.Children.Remove(AccountLetter);
        else if (AccountLetter.Parent == AccountTile) AccountTile.Child = null;
        return AccountLetter;
    }

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

    DateTime gameStart;

    void StartGame() => StartGame(null);

    void StartGame(string? world)
    {
        if (testing) { AppLog.Write("self-test: did not start the game"); return; }
        if (game.Running) { game.Stop(); return; }
        if (installing) return;
        var profile = ProfileStore.Active;
        var linkErr = Instances.Switch(profile);
        if (linkErr != null) { Toast(linkErr, "Bad"); return; }
        ClearLog();
        var err = game.Start(mode, java, profile, world);
        if (err != null) { Toast(err, "Bad"); Refresh(); return; }
        AppendLog($"Starting ({mode}, profile {profile.Name}{(world != null ? ", world " + world : "")}).");
        gameStart = DateTime.Now;
        SideMast.StartedAt = SideMast.Clock;
        Toast(mode == LaunchMode.Launcher ? "Minecraft Launcher opened. Press Play there." : "Starting Pentecost");
        if (Settings.OnLaunch == "minimize") { minimizedForGame = true; WindowState = WindowState.Minimized; }
        Refresh();
    }

    void OnExited(int code)
    {
        AppendLog($"The game ended with code {code}.");
        if (code != 0) Toast($"The game stopped with code {code}. See Crashes.", "Warn");
        if (minimizedForGame)
        {
            minimizedForGame = false;
            WindowState = WindowState.Normal;
            Activate();
        }
        if (code != 0 && Settings.CrashJump && !testing)
        {
            if (!IsVisible) ShowFromTray();
            Go("Crashes");
        }
        Refresh();
    }

    void SetMode(LaunchMode m)
    {
        mode = m;
        Paths.Mode = m.ToString();
        Refresh();
    }

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
        if (announce || !updatesChecked) Refresh();
        else if (before != (latest?.Tag, latest?.ManagerVersion)) RefreshUpdateViews();
        updatesChecked = true;
    }

    bool ManagerUpdate => latest != null && Updates.ManagerNewer(latest) && (SelfInstall.CanUpdate || testing);

    void RefreshUpdateViews()
    {
        if (current is "Play" or "Settings" && !inFull && Modal.Visibility != Visibility.Visible) ShowPage(current, false);
    }

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

    void InstallOnClose()
    {
        if (testing || managerHandedOver || managerSetup == null || managerSetupVersion == null || !Settings.UpdateOnClose) return;
        if (!SelfInstall.CanUpdate) return;
        AppLog.Write("installing Manager " + managerSetupVersion + " on close");
        SelfUpdate.Run(managerSetup, false);
    }

    async Task RunInstall(bool worlds, ReleaseInfo? pick = null)
    {
        if (testing) { AppLog.Write("self-test: did not install"); return; }
        if (App.Demo)
        {
            installing = true;
            Refresh();
            await DemoInstall.Pentecost(worlds, (text, f) => Dispatcher.BeginInvoke(() =>
            {
                if (installLabel != null) installLabel.Text = text;
                if (installBar != null) { installBar.IsIndeterminate = false; installBar.Value = f; }
            }));
            installing = false;
            installBar = null;
            installLabel = null;
            Refresh();
            Toast("Pentecost is installed. (Demo: nothing was installed.)");
            return;
        }
        if (pick == null && latest == null) { await CheckUpdates(false); if (latest == null) { Toast("Could not reach GitHub.", "Bad"); return; } }
        if (game.Running) { Toast("Close the game first.", "Warn"); return; }
        var rel = pick ?? latest!;
        installing = true;
        Refresh();
        var starter = StarterPicks();
        var bothVideo = starter == null || (starter.Contains("embeddium") && starter.Contains("oculus"));
        var err = await InstallService.Run(rel, worlds, (text, f) => Dispatcher.BeginInvoke(() =>
        {
            if (installLabel != null) installLabel.Text = text;
            if (installBar != null) { installBar.IsIndeterminate = false; installBar.Value = f; }
        }), CancellationToken.None, bothVideo);
        string? javaErr = null;
        if (err == null && await Task.Run(JavaFinder.Find) == null)
            javaErr = await JavaGet.Ensure((text, f) => Dispatcher.BeginInvoke(() =>
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
            AppendLog($"Pentecost {rel.Version} installed.");
            Toast($"Pentecost {rel.Version} is installed.");
            if (javaErr != null) Toast(javaErr, "Warn");
            _ = InstallStarterMods(bothVideo);
            _ = Task.Run(JavaFinder.Find).ContinueWith(t => Dispatcher.BeginInvoke(() => { java = t.Result; Refresh(); }));
        }
        else Toast(err, "Bad");
    }

    static string StarterFile => Path.Combine(SelfInstall.Target, "first-mods.txt");

    static List<string>? StarterPicks()
    {
        try
        {
            if (!File.Exists(StarterFile)) return null;
            return File.ReadAllLines(StarterFile).Select(l => l.Trim())
                .Where(l => l.Length > 0 && !l.StartsWith('#') && l.All(c => char.IsLetterOrDigit(c) || c is '-' or '_')).ToList();
        }
        catch (Exception ex) { AppLog.Write("starter mods: " + ex.Message); return null; }
    }

    async Task InstallStarterMods(bool videoDone)
    {
        var file = StarterFile;
        var picks = StarterPicks();
        if (picks == null) return;
        var slugs = picks.Where(s => !(videoDone && s is "embeddium" or "oculus")).ToList();
        if (slugs.Count == 0) { try { File.Delete(file); } catch { } return; }
        Toast($"Installing your {slugs.Count} starter mods.");
        int done = 0;
        var failed = new List<string>();
        foreach (var slug in slugs)
        {
            try { await Modrinth.Install(slug, "forge", _ => { }, CancellationToken.None); done++; }
            catch (Exception ex) { failed.Add(slug); AppLog.Write("starter mod " + slug + ": " + ex.Message); }
        }
        try { File.Delete(file); } catch { }
        AppendLog($"Starter mods: {done} installed" + (failed.Count > 0 ? ", could not get " + string.Join(", ", failed) : "") + ".");
        Toast(failed.Count == 0 ? $"Your {done} starter mods are installed." : $"{done} starter mods installed. Could not get: {string.Join(", ", failed)}.", failed.Count == 0 ? "Ok" : "Warn");
        Refresh();
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
