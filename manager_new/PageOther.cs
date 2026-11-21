using System.Text.RegularExpressions;
using System.Diagnostics;
using System.Reflection;

namespace Pentecost.Manager;

public partial class MainWindow
{
    FrameworkElement BuildCrashes()
    {
        var page = new StackPanel();

        const double headH = 400;
        var head = new Grid { Height = headH, ClipToBounds = true };
        var fade = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 0));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.04));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.96));
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 1));
        var scene = new Grid { OpacityMask = fade };
        var wrecks = new Wrecks { SeaLine = 0.68 };
        scene.Children.Add(wrecks);
        var seaH = headH * (1 - 0.68) + 24;
        scene.Children.Add(new CloudSea { Big = true, BareBg = "Bg", BareTop = 40 / seaH, Height = seaH, VerticalAlignment = VerticalAlignment.Bottom, IsHitTestVisible = false });
        head.Children.Add(scene);

        var title = new Wordmark { Text = "CRASHES", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var tally = Ui.T("", 12, "Soft");
        tally.FontFamily = (FontFamily)FindResource("Mono");
        var topLeft = Ui.V(title, tally.With(new Thickness(2, 8, 0, 0)));
        topLeft.HorizontalAlignment = HorizontalAlignment.Left;
        topLeft.VerticalAlignment = VerticalAlignment.Top;
        topLeft.Margin = new Thickness(0, 6, 0, 0);
        topLeft.IsHitTestVisible = false;
        head.Children.Add(topLeft);

        var heroCap = Ui.T("LAST WRECK", 11, "Faint");
        heroCap.FontFamily = (FontFamily)FindResource("Mono");
        var heroName = new Wordmark { Text = "", Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var heroWhen = Ui.T("", 12, "Soft");
        heroWhen.FontFamily = (FontFamily)FindResource("Mono");
        var hero = Ui.V(heroCap, heroName.With(new Thickness(0, 8, 0, 0)), heroWhen.With(new Thickness(0, 8, 0, 0)));
        hero.HorizontalAlignment = HorizontalAlignment.Left;
        hero.VerticalAlignment = VerticalAlignment.Top;
        hero.Margin = new Thickness(2, 168, 0, 0);
        hero.IsHitTestVisible = false;
        hero.Visibility = Visibility.Collapsed;
        head.Children.Add(hero);

        var folder = Ui.Btn("Open folder", "Secondary", (_, _) => OpenFolder(Paths.Crashes), "");
        folder.HorizontalAlignment = HorizontalAlignment.Right;
        folder.VerticalAlignment = VerticalAlignment.Top;
        folder.Margin = new Thickness(0, 10, 0, 0);
        head.Children.Add(folder);
        page.Children.Add(head);

        var body = new Grid { Margin = new Thickness(0, 8, 0, 0) };
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.42, GridUnitType.Star), MinWidth = 320 });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.58, GridUnitType.Star) });
        var list = new StackPanel();
        var detail = new StackPanel();
        body.Children.Add(list);
        Grid.SetColumn(detail, 2);
        body.Children.Add(detail);
        page.Children.Add(body);
        list.Children.Add(Ui.T("Reading reports", 13, "Muted"));

        List<CrashInfo> all = new();
        List<(CrashInfo C, int N)> groups = new();
        var rows = new List<Border>();
        var selected = -1;

        void Select(int i)
        {
            selected = i;
            for (var k = 0; k < rows.Count; k++) StyleRow(rows[k], k == i);
            detail.Children.Clear();
            if (i < 0 || i >= groups.Count) return;
            detail.Children.Add(CrashDetail(groups[i].C, groups[i].N));
        }

        void Render(string? mod)
        {
            list.Children.Clear();
            rows.Clear();
            var shown = mod == null ? all : all.Where(c => Culprit(c) == mod).ToList();
            groups = new();
            foreach (var c in shown)
            {
                var hit = groups.FindIndex(g => Headline(g.C) == Headline(c) && Culprit(g.C) == Culprit(c));
                if (hit >= 0) groups[hit] = (groups[hit].C, groups[hit].N + 1);
                else groups.Add((c, 1));
            }
            var cap = Ui.T(mod == null ? "ALL WRECKS" : mod.ToUpperInvariant(), 11, "Faint");
            cap.FontFamily = (FontFamily)FindResource("Mono");
            var capRow = Ui.Row(cap, mod == null ? null : Ui.Btn("Show all", "Ghost", (_, _) => { wrecks.Clear(); Render(null); }));
            capRow.Margin = new Thickness(0, 0, 0, 8);
            list.Children.Add(capRow);
            for (var i = 0; i < groups.Count; i++)
            {
                var k = i;
                var row = CrashListRow(groups[i].C, groups[i].N);
                row.MouseLeftButtonUp += (_, _) => Select(k);
                rows.Add(row);
                list.Children.Add(row);
            }
            Select(groups.Count > 0 ? 0 : -1);
        }
        wrecks.Picked += Render;

        _ = Task.Run(() => CrashScan.Recent(ModScan.Scan(), 20)).ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            list.Children.Clear();
            if (t.IsFaulted) { list.Children.Add(Ui.T("The reports could not be read.", 13, "Bad")); return; }
            all = t.Result;
            wrecks.SetWrecks(all.Select(c => (Culprit(c), c.Time)));
            if (all.Count > 0) wrecks.HeroCount = all.Count(c => Culprit(c) == Culprit(all[0]));
            var week = all.Count(c => c.Time > DateTime.Now.AddDays(-7));
            tally.Text = all.Count == 0 ? "CALM WATERS" : (week == 1 ? "1 WRECK" : week + " WRECKS") + " THIS WEEK";
            if (all.Count == 0)
            {
                list.Children.Add(Ui.T("No crashes on record.", 14, "Soft"));
                return;
            }
            heroName.Text = Culprit(all[0]).ToUpperInvariant();
            heroWhen.Text = Ui.Ago(all[0].Time).ToUpperInvariant();
            hero.Visibility = Visibility.Visible;
            Render(null);
            if (!testing) _ = Online.ReportCrashes(all);
        }));
        return Scroll(page);
    }

    static string Culprit(CrashInfo c)
    {
        if (c.Blamed.Length > 0) return Regex.Replace(c.Blamed, "\\s+v?\\d[\\w.+-]*$", "").Trim();
        return c.Mod?.Name ?? "Unknown";
    }

    static string Headline(CrashInfo c)
    {
        var missing = Regex.Match(c.Broke + " " + c.Cause, "class ([\\w.$]+)");
        if (c.Broke.StartsWith("The game asked for the class") && missing.Success) return "Missing code: " + missing.Groups[1].Value.Split('.').Last();
        if (c.Broke.Length > 0)
        {
            var b = Regex.Replace(c.Broke, "\\s*\\(.*$", "").TrimEnd('.');
            return b.Length > 90 ? b[..90] + "..." : b;
        }
        var generic = c.Title is "Crash report" or "Unexpected error" or "Initializing game" || c.Title.Length == 0;
        if (!generic) return char.ToUpperInvariant(c.Title[0]) + c.Title[1..];
        var m = Regex.Match(c.Cause, "([\\w$]+(?:Exception|Error))(?::\\s*(.*))?");
        if (!m.Success) return "The game crashed";
        return Regex.Replace(m.Groups[1].Value, "(?<=[a-z])(?=[A-Z])", " ");
    }

    static (string Why, string[] Try) Explain(CrashInfo c, string culprit)
    {
        var all = c.Broke + " " + c.Cause + " " + c.Title;
        var cls = Regex.Match(all, "class ([\\w.$]+)").Groups[1].Value;
        if (cls.StartsWith("khi.pentecost"))
            return ("Part of Pentecost itself did not load, so the game reached for code that was not there. Your mods are not to blame for this one.",
                new[] { "Update Pentecost from the Manager.", "If it keeps happening, reinstall Pentecost and send the report in the help forum." });
        if (all.Contains("ClassNotFound") || all.Contains("NoClassDefFound") || cls.Length > 0)
            return ("A mod reached for code that is not installed. That usually means a missing library, or a mod built for a different loader or Minecraft version.",
                new[] { "Check " + culprit + "'s page for the libraries it needs.", "Make sure it is built for 1.20.1.", "Switch it off if you do not need it." });
        if (all.Contains("OutOfMemory"))
            return ("The game ran out of memory.", new[] { "Raise memory in Settings.", "Switch off heavy mods you are not using." });
        if (all.Contains("Mixin") || all.Contains("mixin"))
            return ("Two mods tried to change the same part of the game and collided.", new[] { "Switch off " + culprit + " and try again.", "Update both mods if a newer version exists." });
        if (all.Contains("value was missing") || all.Contains("NullPointer") || all.Contains("is null"))
            return ("A mod expected something to be ready that was not set up yet. Often a missing companion mod or a load order problem.",
                new[] { "Update " + culprit + ".", "Check whether it needs a companion mod.", "Switch it off to get back in game." });
        return ("The game stopped on an error it could not recover from. The report has the full trace.", new[] { "Switch off " + culprit + " and try again.", "Send the report in the help forum if it keeps happening." });
    }

    Border CrashListRow(CrashInfo c, int times)
    {
        var count = new Wordmark { Text = times > 1 ? "X" + times : "", Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        var head = Ui.T(Headline(c), 13.5, "Text", trim: true);
        var meta = Ui.T(Culprit(c).ToUpperInvariant() + "   " + Ui.Ago(c.Time).ToUpperInvariant(), 11, "Faint");
        meta.FontFamily = (FontFamily)FindResource("Mono");
        var text = Ui.V(head, meta.With(new Thickness(0, 5, 0, 0)));
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(text);
        Grid.SetColumn(count, 1);
        count.Margin = new Thickness(14, 0, 0, 0);
        g.Children.Add(count);
        var bar = new Border { Width = 2, HorizontalAlignment = HorizontalAlignment.Left, Background = Ui.Br("Text"), Visibility = Visibility.Hidden };
        var holder = new Grid();
        holder.Children.Add(new Border { Padding = new Thickness(16, 12, 4, 12), Child = g });
        holder.Children.Add(bar);
        var row = new Border { Background = Brushes.Transparent, Cursor = Cursors.Hand, BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Child = holder, Tag = (bar, head) };
        row.MouseEnter += (_, _) => { if (bar.Visibility != Visibility.Visible) row.Background = Ui.Br("Hover"); };
        row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
        return row;
    }

    static void StyleRow(Border row, bool on)
    {
        if (row.Tag is not (Border bar, TextBlock head)) return;
        bar.Visibility = on ? Visibility.Visible : Visibility.Hidden;
        head.Foreground = Ui.Br(on ? "Text" : "Soft");
    }

    FrameworkElement CrashDetail(CrashInfo c, int times)
    {
        var culprit = Culprit(c);
        var p = new StackPanel();
        var cap = Ui.T((times > 1 ? times + " TIMES   LAST " : "") + c.Time.ToString("MMM d  HH:mm").ToUpperInvariant(), 11, "Faint");
        cap.FontFamily = (FontFamily)FindResource("Mono");
        p.Children.Add(cap);
        p.Children.Add(new Wordmark { Text = culprit.ToUpperInvariant(), Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 10, 0, 0) });
        p.Children.Add(Ui.T(Headline(c), 15, "Text", wrap: true).With(new Thickness(0, 14, 0, 0)));
        var (why, steps) = Explain(c, culprit);
        p.Children.Add(Ui.T(why, 13.5, "Soft", wrap: true).With(new Thickness(0, 8, 0, 0)));

        var tryCap = Ui.T("TRY THIS", 11, "Faint");
        tryCap.FontFamily = (FontFamily)FindResource("Mono");
        p.Children.Add(tryCap.With(new Thickness(0, 22, 0, 8)));
        for (var i = 0; i < steps.Length; i++)
        {
            var n = Ui.T((i + 1).ToString(), 12, "Faint");
            n.FontFamily = (FontFamily)FindResource("Mono");
            n.Width = 22;
            p.Children.Add(Ui.H(n, Ui.T(steps[i], 13.5, "Text", wrap: true)).With(new Thickness(0, 0, 0, 6)));
        }

        var buttons = new WrapPanel { Margin = new Thickness(0, 16, 0, 0) };
        if (c.Mod is { Enabled: true } mod)
        {
            var dis = Ui.Btn("Switch off " + mod.Name, "Primary");
            dis.Padding = new Thickness(16, 7, 16, 7);
            dis.FontSize = 12.5;
            dis.Click += (_, _) =>
            {
                try { ModScan.SetEnabled(mod, false); dis.Content = "Switched off"; dis.IsEnabled = false; Toast(mod.Name + " is off."); }
                catch (Exception ex) { Toast("Could not switch it off: " + ex.Message, "Bad"); }
            };
            buttons.Children.Add(dis.With(new Thickness(0, 0, 8, 0)));
        }
        var file = c.File;
        var open = Ui.Btn("Open report", "Secondary", (_, _) => OpenPath(file));
        open.Padding = new Thickness(16, 7, 16, 7);
        open.FontSize = 12.5;
        buttons.Children.Add(open);
        var copy = Ui.Btn("Copy", "Ghost", (_, _) =>
        {
            var text = Headline(c) + "\nBlamed: " + culprit + (c.Cause.Length > 0 ? "\n" + c.Cause : "") + "\n" + Path.GetFileName(file);
            try { Clipboard.SetText(text); Toast("Copied."); } catch { Toast("Could not copy.", "Warn"); }
        });
        copy.FontSize = 12.5;
        buttons.Children.Add(copy.With(new Thickness(4, 0, 0, 0)));
        var show = Ui.Btn("Show in folder", "Ghost", (_, _) => { try { Process.Start("explorer.exe", "/select,\"" + file + "\""); } catch { } });
        show.FontSize = 12.5;
        buttons.Children.Add(show);
        p.Children.Add(buttons);

        var raw = Ui.T((c.Blamed.Length > 0 ? c.Blamed + (c.BlamedFile.Length > 0 ? "   " + c.BlamedFile : "") + "\n" : "") + (c.Cause.Length > 0 ? (c.Cause.Length > 300 ? c.Cause[..300] + "..." : c.Cause) + "\n" : "") + Path.GetFileName(file), 11.5, "Muted", wrap: true);
        raw.FontFamily = (FontFamily)FindResource("Mono");
        p.Children.Add(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 1, 0, 0), Margin = new Thickness(0, 22, 0, 0), Padding = new Thickness(0, 14, 0, 0), Child = raw });
        return p;
    }

    FrameworkElement BuildSettings()
    {
        var page = new StackPanel();
        const double headH = 400;
        var head = new Grid { Height = headH, ClipToBounds = true };
        var fade = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 0));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.04));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.96));
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 1));
        var helm = new Helm();
        var scene = new Grid { OpacityMask = fade };
        scene.Children.Add(helm);
        var seaH = headH * (1 - 0.70) + 24;
        scene.Children.Add(new CloudSea { Big = true, BareBg = "Bg", BareTop = 40 / seaH, Height = seaH, VerticalAlignment = VerticalAlignment.Bottom, IsHitTestVisible = false });
        head.Children.Add(scene);

        var mono = (FontFamily)FindResource("Mono");
        var plan = GameRun.PlanFor(ProfileStore.Active);
        var modCount = 0;
        try { modCount = Directory.Exists(Paths.Mods) ? Directory.GetFiles(Paths.Mods, "*.jar").Length : 0; } catch { }
        var title = new Wordmark { Text = "SETTINGS", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var tally = Ui.T(plan.Heap + " GB MEMORY   " + modCount + " MODS   " + (Settings.HighGpu ? "FAST GPU" : "DEFAULT GPU"), 12, "Soft");
        tally.FontFamily = mono;
        var topLeft = Ui.V(title, tally.With(new Thickness(2, 8, 0, 0)));
        topLeft.HorizontalAlignment = HorizontalAlignment.Left;
        topLeft.VerticalAlignment = VerticalAlignment.Top;
        topLeft.Margin = new Thickness(0, 6, 0, 0);
        topLeft.IsHitTestVisible = false;
        head.Children.Add(topLeft);

        var heroCap = Ui.T("RUNNING", 11, "Faint");
        heroCap.FontFamily = mono;
        var heroVer = new Wordmark { Text = "PENTECOST", Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var heroSub = Ui.T((Paths.Version ?? "") + "   " + (Paths.Version == null ? "NOT INSTALLED" : latest == null ? "CHECKING FOR UPDATES" : Updates.Newer(latest.Version, Paths.Version) ? latest.Version + " IS OUT" : "UP TO DATE") + "   MANAGER " + (Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "?"), 12, "Soft");
        heroSub.FontFamily = mono;
        var hero = Ui.V(heroCap, heroVer.With(new Thickness(0, 8, 0, 0)), heroSub.With(new Thickness(0, 8, 0, 0)));
        hero.HorizontalAlignment = HorizontalAlignment.Left;
        hero.VerticalAlignment = VerticalAlignment.Top;
        hero.Margin = new Thickness(2, 168, 0, 0);
        hero.IsHitTestVisible = false;
        head.Children.Add(hero);

        var hint = Ui.T("TURN THE HELM", 10.5, "Faint");
        hint.FontFamily = mono;
        hint.HorizontalAlignment = HorizontalAlignment.Right;
        hint.VerticalAlignment = VerticalAlignment.Top;
        hint.Margin = new Thickness(0, 12, 6, 0);
        hint.IsHitTestVisible = false;
        head.Children.Add(hint);
        page.Children.Add(head);

        var stations = new StackPanel();

        var cur = Paths.Version;
        var stateLine = latest == null ? "" : cur != null && !Updates.Newer(latest.Version, cur) ? "up to date" : $"{latest.Version} out";

        var pent = Section("Pentecost",
            SRow("Version", (cur ?? "Not installed") + (stateLine.Length > 0 ? ", " + stateLine : ""),
                Ui.Btn("Check for updates", "Secondary", (_, _) => _ = CheckUpdates(true)),
                Ui.Btn("Versions", "Secondary", (_, _) => OpenVersions()),
                latest != null && (cur == null || Updates.Newer(latest.Version, cur))
                    ? Ui.Btn(cur == null ? "Install" : "Update", "Primary", (_, _) => { if (cur == null) { Go("Play"); AskInstall(true); } else _ = RunInstall(false); })
                    : Ui.Btn("Reinstall", "Secondary", (_, _) => Confirm("Reinstall Pentecost?", "The program files are written again. Worlds, mods and settings stay as they are.", "Reinstall", () => _ = RunInstall(false)))),
            SRow("Folder", Paths.Home, Ui.Btn("Open", "Secondary", (_, _) => OpenFolder(Paths.Home)), Ui.Btn("Change", "Secondary", (_, _) => ChangeHome())),
            Toggle("Check for updates in the background", "Look for new Pentecost and Manager releases when the Manager opens, then every 30 minutes.",
                Settings.AutoUpdate, v => Settings.AutoUpdate = v),
            Toggle("Show betas in Versions", "List test releases next to the stable ones when you pick a version.",
                Settings.ShowBeta, v => Settings.ShowBeta = v));
        stations.Children.Add(pent);

        var javaText = !javaDone ? "Looking for Java" : java ?? "Java 21 was not found.";
        stations.Children.Add(Section("Java",
            SRow("Java 21 or newer", javaText,
                Ui.Btn("Choose java.exe", "Secondary", (_, _) => ChooseJava()),
                Ui.Btn("Find again", "Secondary", async (_, _) =>
                {
                    try { var j = Path.Combine(Paths.Home, "java.txt"); if (File.Exists(j)) File.Delete(j); } catch { }
                    java = await Task.Run(JavaFinder.Find);
                    Toast(java != null ? "Java found." : "Java 21 was not found.", java != null ? "Ok" : "Warn");
                    Refresh();
                })),
            Choice("Memory for " + ProfileStore.Active.Name, "How much memory the game gets in this profile. Auto picks from what your computer has.",
                new[] { ("Auto", "0"), ("4 GB", "4"), ("6 GB", "6"), ("8 GB", "8"), ("12 GB", "12"), ("16 GB", "16") },
                ProfileStore.Active.MemoryGb.ToString(), v => { ProfileStore.Active.MemoryGb = int.Parse(v); ProfileStore.Save(); }),
            Choice("Backup memory", "Extra room the game can grow into before it runs out. Auto sizes it for you.",
                new[] { ("Auto", "-1"), ("None", "0"), ("1 GB", "1"), ("2 GB", "2"), ("4 GB", "4") },
                ProfileStore.Active.ReserveGb.ToString(), v => { ProfileStore.Active.ReserveGb = int.Parse(v); ProfileStore.Save(); }),
            TextRow("Java arguments", "Extra flags for this profile. Leave empty unless a mod asks for one.",
                ProfileStore.Active.JvmArgs, v => { ProfileStore.Active.JvmArgs = v; ProfileStore.Save(); Toast("Java arguments saved."); })));

        AddPreferences(stations);

        var name = Paths.AccountName();
        var signOut = Ui.Btn("Sign out", "Secondary", (_, _) => Confirm("Sign out?", "The saved sign in is removed from this computer. You can sign in again from the Pentecost main menu.", "Sign out", () =>
        {
            Paths.SignOut();
            RefreshAccount();
            Toast("Signed out.");
            Refresh();
        }));
        signOut.IsEnabled = name != null;
        var others = Paths.SavedAccounts().Where(n => n != name).ToList();
        var rows = new List<UIElement>
        {
            SRow(name ?? "Not signed in",
                name != null ? "Microsoft account" : "Sign in from the game's main menu",
                signOut)
        };
        if (others.Count > 0)
            rows.Add(SRow("Other accounts", string.Join(", ", others)));
        rows.Add(SRow("Friends", Online.State == "online"
                ? "Online, code " + Online.Pretty(Online.MyCode)
                : Online.State == "nosession" ? "Offline" : "Connecting",
            Ui.Btn("Open Friends", "Secondary", (_, _) => Go("Friends"))));
        stations.Children.Add(Section("Account", rows.ToArray()));

        stations.Children.Add(Section("Graphics",
            Toggle("Use the fastest graphics card", "Asks Windows to run the Manager on your high performance GPU. Takes effect the next time the Manager starts.",
                Settings.HighGpu, v =>
                {
                    Settings.HighGpu = v;
                    GpuPref.Apply(v);
                    Toast(v ? "The Manager will use the fastest GPU on its next start." : "The Manager will use the Windows default GPU on its next start.");
                }),
            Choice("Frame rate", "How often the scenes redraw. Lower uses less power, higher looks smoother.",
                new[] { ("30", "30"), ("60", "60"), ("120", "120") }, Settings.Fps.ToString(), v => { Settings.Fps = int.Parse(v); Anim.Fps = Settings.Fps; })));

        stations.Children.Add(Section("Privacy",
            Toggle("Anonymous crash counts", "When the game crashes, the Manager sends the mod it blames, that mod's version and the crash title. Never your name, account, files or computer.",
                Settings.CrashCounts, v =>
                {
                    Settings.CrashCounts = v;
                    Toast(v ? "Crash counts are on." : "Crash counts are off.");
                    if (v) _ = Task.Run(() => CrashScan.Recent(ModScan.Scan(), 10)).ContinueWith(t => { if (!t.IsFaulted) _ = Online.ReportCrashes(t.Result); });
                }),
            JoinToggle(),
            Toggle("Show Pentecost in Discord", "Your Discord profile shows that you use Pentecost and what you are doing in the Manager. While the game runs, the game shows its own status instead.",
                Settings.DiscordStatus, v => Settings.DiscordStatus = v)));

        var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "?";
        var aboutButtons = new List<Button>
        {
            Ui.Btn("Data folder", "Secondary", (_, _) => OpenFolder(Paths.Data)),
            Ui.Btn("Manager log", "Secondary", (_, _) => { if (File.Exists(AppLog.File_)) OpenPath(AppLog.File_); else Toast("The log is empty.", "Warn"); }),
        };
        var mgrText = !SelfInstall.CanUpdate && !testing ? "Not the installed copy"
            : latest == null ? "Checking"
            : ManagerUpdate ? $"{latest.ManagerVersion} ready, installs on close"
            : "Up to date";
        var mgrButtons = new List<Button> { Ui.Btn("Check for updates", "Secondary", (_, _) => _ = CheckUpdates(true)) };
        if (ManagerUpdate) mgrButtons.Add(Ui.Btn("Update now", "Primary", (_, _) => _ = UpdateManagerNow()));
        stations.Children.Add(Section("Log",
            Toggle("Load latest.log on open", "When the game is not running, the Log page shows the last game's log.",
                Settings.LogAutoLoad, v => Settings.LogAutoLoad = v),
            Toggle("Wrap long lines", "Long lines break onto the next line. Off, they run off the side and scroll sideways.",
                Settings.LogWrap, v => Settings.LogWrap = v),
            Choice("Lines to keep", "Older lines drop off past this. More lines use more memory.",
                new[] { ("2,000", "2000"), ("5,000", "5000"), ("20,000", "20000") }, Settings.LogLines.ToString(), v => Settings.LogLines = int.Parse(v))));

        stations.Children.Add(Section("About",
            SRow("Pentecost Manager " + version,
                Paths.DevSource.Length > 0 ? "Source: " + Paths.DevSource : "",
                aboutButtons.ToArray()),
            SRow("Manager updates", mgrText, mgrButtons.ToArray()),
            Toggle("Install Manager updates when it closes", "A downloaded update installs quietly the next time you close the Manager.",
                Settings.UpdateOnClose, v => Settings.UpdateOnClose = v)));
        var parts = stations.Children.OfType<StackPanel>().Where(p => p.Tag is (string, int)).ToList();
        stations.Children.Clear();

        var body = new Grid { Margin = new Thickness(0, 8, 0, 36) };
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.34, GridUnitType.Star), MinWidth = 260 });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.66, GridUnitType.Star) });
        var list = new StackPanel();
        var detail = new StackPanel();
        body.Children.Add(list);
        Grid.SetColumn(detail, 2);
        body.Children.Add(detail);
        page.Children.Add(body);

        var listCap = Ui.T("STATIONS", 11, "Faint");
        listCap.FontFamily = mono;
        list.Children.Add(listCap.With(new Thickness(0, 0, 0, 8)));
        var rowsUi = new List<Border>();

        void Show(int i, bool turn)
        {
            if (i < 0 || i >= parts.Count) return;
            settingsStation = i;
            for (var k = 0; k < rowsUi.Count; k++) StyleRow(rowsUi[k], k == i);
            if (turn) helm.Select(i);
            var (sName, _) = ((string, int))parts[i].Tag;
            foreach (var p in parts) { if (p.Parent is Border box) box.Child = null; else if (p.Parent is Panel old) old.Children.Remove(p); }
            detail.Children.Clear();
            var cap = Ui.T("STATION " + (i + 1).ToString("00") + " OF " + parts.Count.ToString("00"), 11, "Faint");
            cap.FontFamily = mono;
            detail.Children.Add(cap);
            detail.Children.Add(new Wordmark { Text = sName.ToUpperInvariant(), Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 10, 0, 0) });
            detail.Children.Add(Ui.T(StationBlurb(sName), 13.5, "Soft", wrap: true).With(new Thickness(0, 10, 0, 0)));
            var holder = new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 1, 0, 0), Margin = new Thickness(0, 20, 0, 0), Padding = new Thickness(0, 18, 0, 0), Child = parts[i] };
            detail.Children.Add(holder);
        }

        for (var i = 0; i < parts.Count; i++)
        {
            var k = i;
            var (sName, count) = ((string, int))parts[i].Tag;
            var head2 = Ui.T(sName, 13.5, "Soft", trim: true);
            var meta = Ui.T((i + 1).ToString("00") + "   " + (count == 1 ? "1 SETTING" : count + " SETTINGS"), 11, "Faint");
            meta.FontFamily = mono;
            var text = Ui.V(head2, meta.With(new Thickness(0, 5, 0, 0)));
            var bar = new Border { Width = 2, HorizontalAlignment = HorizontalAlignment.Left, Background = Ui.Br("Text"), Visibility = Visibility.Hidden };
            var holder = new Grid();
            holder.Children.Add(new Border { Padding = new Thickness(16, 11, 4, 11), Child = text });
            holder.Children.Add(bar);
            var row = new Border { Background = Brushes.Transparent, Cursor = Cursors.Hand, BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Child = holder, Tag = (bar, head2) };
            row.MouseEnter += (_, _) => { if (bar.Visibility != Visibility.Visible) row.Background = Ui.Br("Hover"); };
            row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
            row.MouseLeftButtonUp += (_, _) => Show(k, true);
            rowsUi.Add(row);
            list.Children.Add(row);
        }

        var start = Math.Clamp(settingsStation, 0, Math.Max(0, parts.Count - 1));
        helm.SetStations(parts.Select(p => (((string, int))p.Tag).Item1), start);
        helm.Turned += i => Show(i, false);
        Show(start, false);
        return Scroll(page);
    }

    int settingsStation;

    static string StationBlurb(string name) => name switch
    {
        "Pentecost" => "The loader itself. The version you run, where it lives, and every release you can switch to.",
        "Java" => "The Java that starts the game, and the memory and flags it gets in your active profile.",
        "Log" => "How the Log page loads and shows the game's output.",
        "Look and motion" => "How much the Manager moves. Turn things off to save power.",
        "Start and play" => "What the Manager does when it opens and when you press Play.",
        "Mods" => "The star chart over your mods and how the list behaves.",
        "Browse" => "Where Browse starts when you open it.",
        "Profiles and packs" => "How a new modpack fills its profile.",
        "Friends" => "Your link to Pentecost online.",
        "Account" => "The Microsoft account the game signs in with.",
        "Graphics" => "Which graphics card the Manager draws with.",
        "Privacy" => "What the Manager may send. Nothing leaves your computer unless you turn it on.",
        "About" => "The Manager itself, its log and its own updates.",
        _ => "",
    };

    FrameworkElement Section(string title, params UIElement[] rows)
    {
        var p = new StackPanel { Tag = (title, rows.Length) };
        for (var i = 0; i < rows.Length; i++)
        {
            if (i > 0) p.Children.Add(new Border { Height = 1, Background = Ui.Br("Line"), Opacity = 0.6, Margin = new Thickness(0, 14, 0, 14) });
            rows[i].SetValue(FrameworkElement.MarginProperty, new Thickness(0));
            p.Children.Add(rows[i]);
        }
        return p;
    }

    static Grid SRow(string title, string sub, params Button[] buttons)
    {
        var text = Ui.V(Ui.T(title, 13.5, "Text", trim: true));
        if (sub.Length > 0) text.Children.Add(Ui.T(sub, 12, "Muted", trim: true).With(new Thickness(0, 3, 0, 0)));
        text.Margin = new Thickness(0, 0, 20, 0);
        text.VerticalAlignment = VerticalAlignment.Center;
        var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        for (var i = 0; i < buttons.Length; i++)
        {
            buttons[i].Margin = new Thickness(i == 0 ? 0 : 8, 0, 0, 0);
            right.Children.Add(buttons[i]);
        }
        return Ui.Row(text, right);
    }

    void ChangeHome()
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Choose the Pentecost folder (the one with lib and run)", InitialDirectory = Directory.Exists(Paths.Home) ? Paths.Home : null };
        if (dlg.ShowDialog(this) != true) return;
        Paths.Home = dlg.FolderName;
        Paths.Save();
        kept.Clear();
        Toast("Folder changed.");
        _ = Task.Run(JavaFinder.Find).ContinueWith(t => Dispatcher.BeginInvoke(() => { java = t.Result; RefreshAccount(); Refresh(); }));
        Refresh();
    }
}
