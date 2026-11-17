using System.Diagnostics;
using System.Reflection;

namespace Pentecost.Manager;

public partial class MainWindow
{
    // ================================================================ crashes
    FrameworkElement BuildCrashes()
    {
        var page = new StackPanel();
        page.Children.Add(Header("Crashes", "Recent crash reports, with a guess at the mod behind each.",
            Ui.Btn("Open folder", "Secondary", (_, _) => OpenFolder(Paths.Crashes), "")));
        var list = new StackPanel { Margin = new Thickness(0, 22, 0, 0) };
        var loading = Ui.T("Reading reports", 13, "Muted");
        list.Children.Add(loading);
        page.Children.Add(list);

        _ = Task.Run(() => CrashScan.Recent(ModScan.Scan(), 20)).ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            list.Children.Clear();
            if (t.IsFaulted) { list.Children.Add(Ui.T("The reports could not be read.", 13, "Bad")); return; }
            var crashes = t.Result;
            if (crashes.Count == 0)
            {
                list.Children.Add(Ui.Card(Ui.V(Ui.T("No crashes", 15, bold: true),
                    Ui.T("No crash reports in " + Paths.Crashes, 12.5, "Muted", wrap: true).With(new Thickness(0, 5, 0, 0))), 22));
                return;
            }
            foreach (var c in crashes) list.Children.Add(CrashCard(c));
        }));
        return Scroll(page);
    }

    Border CrashCard(CrashInfo c)
    {
        var p = new StackPanel();
        p.Children.Add(Ui.Cap(c.Time.ToString("yyyy-MM-dd HH:mm") + "  " + Ui.Ago(c.Time) + "  " + Path.GetFileName(c.File)));
        p.Children.Add(Ui.T(c.Title, 15, bold: true, wrap: true).With(new Thickness(0, 6, 0, 0)));
        if (c.Cause.Length > 0) p.Children.Add(Ui.T(c.Cause, 12.5, "Muted", trim: true).With(new Thickness(0, 4, 0, 0)));
        if (c.Summary.Length > 0)
        {
            var sum = Ui.T(c.Summary, 12.5, "Soft", wrap: true);
            p.Children.Add(new Border { BorderBrush = Ui.Br("Edge"), BorderThickness = new Thickness(2, 0, 0, 0), Padding = new Thickness(12, 2, 0, 2), Margin = new Thickness(0, 12, 0, 0), Child = sum });
        }
        if (c.Mod != null)
            p.Children.Add(Ui.T("Mentions " + c.Mod.Name + " most. This is a guess from the report, not proof.", 12.5, "Soft", wrap: true).With(new Thickness(0, 10, 0, 0)));

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
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
            buttons.Children.Add(dis);
        }
        var file = c.File;
        var open = Ui.Btn("Open report", "Secondary", (_, _) => OpenPath(file));
        open.Padding = new Thickness(16, 7, 16, 7);
        open.FontSize = 12.5;
        open.Margin = new Thickness(buttons.Children.Count > 0 ? 8 : 0, 0, 0, 0);
        buttons.Children.Add(open);
        var show = Ui.Btn("Show in folder", "Ghost", (_, _) => { try { Process.Start("explorer.exe", "/select,\"" + file + "\""); } catch { } });
        show.FontSize = 12.5;
        buttons.Children.Add(show.With(new Thickness(4, 0, 0, 0)));
        p.Children.Add(buttons);

        var card = Ui.Card(p, 20);
        card.Margin = new Thickness(0, 0, 0, 12);
        return card;
    }

    // ================================================================ game log
    FrameworkElement BuildLog()
    {
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var copy = Ui.Btn("Copy all", "Secondary", (_, _) => { if (logBox.Text.Length > 0) { Clipboard.SetText(logBox.Text); Toast("Log copied."); } }, "");
        var clear = Ui.Btn("Clear", "Secondary", (_, _) => logBox.Clear());
        var latestBtn = Ui.Btn("Load latest.log", "Secondary", (_, _) => LoadLatestLog(true), "");
        var folder = Ui.IconBtn("", "Open the logs folder", (_, _) => OpenFolder(Paths.Logs));
        root.Children.Add(Header("Game log", game.Running ? "Live output from the running game." : "Output of the last run in this window.",
            latestBtn.With(new Thickness(0, 0, 8, 0)), copy.With(new Thickness(0, 0, 8, 0)), clear.With(new Thickness(0, 0, 4, 0)), folder));

        if (logBox.Parent is Border old) old.Child = null;
        if (logBox.Text.Length == 0 && !game.Running) LoadLatestLog(false);
        var card = Ui.Card(logBox, 0);
        card.Margin = new Thickness(0, 22, 0, 28);
        Grid.SetRow(card, 1);
        root.Children.Add(card);
        return root;
    }

    void LoadLatestLog(bool announce)
    {
        try
        {
            var f = Path.Combine(Paths.Logs, "latest.log");
            if (!File.Exists(f)) { if (announce) Toast("There is no latest.log yet.", "Warn"); return; }
            using var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            var keep = 250_000L;
            if (fs.Length > keep) fs.Seek(-keep, SeekOrigin.End);
            using var sr = new StreamReader(fs);
            logBox.Text = sr.ReadToEnd();
            logBox.ScrollToEnd();
            if (announce) Toast("Loaded latest.log.");
        }
        catch (Exception ex) { if (announce) Toast("Could not read the log: " + ex.Message, "Bad"); }
    }

    // ================================================================ settings
    FrameworkElement BuildSettings()
    {
        var page = new StackPanel();
        page.Children.Add(Header("Settings", "Install folder, Java and account."));

        var cur = Paths.Version;
        var stateLine = latest == null ? "Latest version unknown" : cur != null && !Updates.Newer(latest.Version, cur) ? "Up to date" : $"Latest on GitHub is {latest.Version}";

        var pent = Section("Pentecost",
            SRow("Version", cur != null ? "Installed: " + cur + ". " + stateLine + "." : "Not installed. " + stateLine + ".",
                Ui.Btn("Check for updates", "Secondary", (_, _) => _ = CheckUpdates(true)),
                latest != null && (cur == null || Updates.Newer(latest.Version, cur))
                    ? Ui.Btn(cur == null ? "Install" : "Update", "Primary", (_, _) => { if (cur == null) { Go("Play"); AskInstall(true); } else _ = RunInstall(false); })
                    : Ui.Btn("Reinstall", "Secondary", (_, _) => Confirm("Reinstall Pentecost?", "The program files are written again. Worlds, mods and settings stay as they are.", "Reinstall", () => _ = RunInstall(false)))),
            SRow("Folder", Paths.Home, Ui.Btn("Open", "Secondary", (_, _) => OpenFolder(Paths.Home)), Ui.Btn("Change", "Secondary", (_, _) => ChangeHome())));
        page.Children.Add(pent);

        var javaText = !javaDone ? "Looking for Java" : java ?? "Java 21 was not found.";
        page.Children.Add(Section("Java",
            SRow("Java 21 or newer", javaText,
                Ui.Btn("Choose java.exe", "Secondary", (_, _) => ChooseJava()),
                Ui.Btn("Find again", "Secondary", async (_, _) =>
                {
                    try { var j = Path.Combine(Paths.Home, "java.txt"); if (File.Exists(j)) File.Delete(j); } catch { }
                    java = await Task.Run(JavaFinder.Find);
                    Toast(java != null ? "Java found." : "Java 21 was not found.", java != null ? "Ok" : "Warn");
                    Refresh();
                }))));

        var name = Paths.AccountName();
        var signOut = Ui.Btn("Sign out", "Secondary", (_, _) => Confirm("Sign out?", "The saved sign in is removed from this computer. You can sign in again from the Pentecost main menu.", "Sign out", () =>
        {
            Paths.SignOut();
            RefreshAccount();
            Toast("Signed out.");
            Refresh();
        }));
        signOut.IsEnabled = name != null;
        page.Children.Add(Section("Account",
            SRow(name ?? "Not signed in",
                name != null ? "Signed in with Microsoft. The token stays encrypted on this computer." : "Sign in from the Pentecost main menu. The manager never sees your password or token.",
                signOut)));

        var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "?";
        var aboutButtons = new List<Button>
        {
            Ui.Btn("Data folder", "Secondary", (_, _) => OpenFolder(Paths.Data)),
            Ui.Btn("Manager log", "Secondary", (_, _) => { if (File.Exists(AppLog.File_)) OpenPath(AppLog.File_); else Toast("The log is empty.", "Warn"); }),
        };
        var mgrText = !SelfInstall.CanUpdate && !testing ? "This copy is not the installed Manager, so it does not update itself."
            : latest == null ? "The newest version is not known yet. The Manager checks at start and every half hour."
            : ManagerUpdate ? $"Version {latest.ManagerVersion} is available. It installs when you close the Manager, or press Update now."
            : "This Manager is up to date. It checks at start and every half hour.";
        var mgrButtons = new List<Button> { Ui.Btn("Check for updates", "Secondary", (_, _) => _ = CheckUpdates(true)) };
        if (ManagerUpdate) mgrButtons.Add(Ui.Btn("Update now", "Primary", (_, _) => _ = UpdateManagerNow()));
        page.Children.Add(Section("About",
            SRow("Pentecost Manager " + version,
                Paths.DevSource.Length > 0 ? "Run from source is on for this account: " + Paths.DevSource : "Made for Pentecost. Source mode shows when dev.json names a source folder in the data folder.",
                aboutButtons.ToArray()),
            SRow("Manager updates", mgrText, mgrButtons.ToArray())));
        return Scroll(page);
    }

    Border Section(string title, params UIElement[] rows)
    {
        var p = new StackPanel();
        p.Children.Add(Ui.Cap(title));
        for (var i = 0; i < rows.Length; i++)
        {
            if (i > 0) p.Children.Add(new Border { Height = 1, Background = Ui.Br("Line"), Margin = new Thickness(0, 14, 0, 14) });
            rows[i].SetValue(FrameworkElement.MarginProperty, new Thickness(0, 12, 0, 0));
            p.Children.Add(rows[i]);
        }
        var card = Ui.Card(p, 22);
        card.Margin = new Thickness(0, 22, 0, 0);
        return card;
    }

    static Grid SRow(string title, string sub, params Button[] buttons)
    {
        var text = Ui.V(Ui.T(title, 14, bold: true, trim: true), Ui.T(sub, 12.5, "Muted", wrap: true).With(new Thickness(0, 3, 0, 0)));
        text.Margin = new Thickness(0, 0, 20, 0);
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
