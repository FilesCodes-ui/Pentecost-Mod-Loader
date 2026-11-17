using System.Windows.Controls.Primitives;

namespace Pentecost.Manager;

public partial class MainWindow
{
    /// <summary>Home: what needs doing, how the install is, the newest mods and the game's folders.</summary>
    FrameworkElement BuildPlay()
    {
        var page = new StackPanel();
        var cur = Paths.Version;
        var installed = Paths.Installed;
        var hasUpdate = latest != null && cur != null && Updates.Newer(latest.Version, cur);

        // ---- greeting, on a band of dither
        var who = Paths.AccountName();
        var hello = Ui.V(Ui.T(who != null ? "Welcome back, " + who : "Welcome", 24, bold: true),
            Ui.T("Forge, Fabric and Quilt mods in one game.", 13.5, "Muted").With(new Thickness(0, 5, 0, 0)));
        hello.VerticalAlignment = VerticalAlignment.Center;
        hello.Margin = new Thickness(26, 0, 0, 0);
        var band = new Grid { Height = 92, ClipToBounds = true };
        band.Children.Add(new DitherBackdrop());
        band.Children.Add(hello);
        page.Children.Add(new PixelBox { Background = Ui.Br("Panel"), BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Notch = 3, Padding = new Thickness(1), Child = band });

        // ---- a notice when something needs doing
        Border? notice = null;
        if (installing)
            notice = InstallCard();
        else if (!installed)
            notice = Notice("Pentecost is not installed here",
                "Install it to start playing. The folder is " + Paths.Home + ". You can change the folder in Settings.",
                "Install Pentecost", () => AskInstall(true));
        else if (hasUpdate)
            notice = Notice($"Pentecost {latest!.Version} is available", $"You have {cur}. Your worlds, mods and settings stay as they are.",
                "Update", () => _ = RunInstall(false), "What's new", () => OpenPath(latest.Url));
        else if (javaDone && java == null && mode != LaunchMode.Launcher)
            notice = Notice("Java 21 was not found", "Pentecost needs Java 21 or newer. Choose its java.exe, or start with the Launcher instead.",
                "Choose java.exe", ChooseJava);
        if (notice != null)
        {
            notice.Margin = new Thickness(0, 12, 0, 0);
            page.Children.Add(notice);
        }
        if (!installing && ManagerUpdate)
        {
            var next = latest!.ManagerVersion;
            var managerNotice = Notice($"Pentecost Manager {next} is available",
                $"You have {SelfInstall.Version}. It installs when you close the Manager.",
                "Update now", () => _ = UpdateManagerNow(), "What's new", () => OpenPath(latest.Url));
            managerNotice.Margin = new Thickness(0, 12, 0, 0);
            page.Children.Add(managerNotice);
        }

        // ---- the state of things. Each card opens its page.
        var facts = new UniformGrid { Columns = 4 };
        facts.Children.Add(Fact("Pentecost", installed ? "v" + (cur ?? "?") : "Not installed", hasUpdate ? latest!.Version + " is available" : installed ? "Up to date" : "Press Install on the left", "Settings"));
        facts.Children.Add(Fact("Java", !javaDone ? "Looking" : java == null ? "Not found" : "Ready", java ?? (javaDone ? "Needs Java 21 or newer" : ""), "Settings"));
        var modsValue = Ui.T("...", 20, bold: true);
        var modsSub = Ui.T("Checking", 11.5, "Faint", trim: true);
        facts.Children.Add(FactCard("Mods", modsValue, modsSub, "Mods"));
        var p = ProfileStore.Active;
        facts.Children.Add(Fact("Profile", p.Name, "Memory " + (p.MemoryGb > 0 ? p.MemoryGb + " GB" : "automatic, " + GameRun.AutoMemory().Heap + " GB"), "Profiles"));
        for (var i = 1; i < 4; i++) ((Border)facts.Children[i]).BorderThickness = new Thickness(1, 0, 0, 0);
        var strip = Ui.SheetCard(facts, 0);
        strip.Margin = new Thickness(0, 12, 0, 0);
        page.Children.Add(strip);

        // ---- newest mods
        var newest = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
        var newestCard = Ui.SheetCard(Ui.V(Ui.Row(Ui.Cap("Newest mods"), Ui.Btn("All mods", "Ghost", (_, _) => Go("Mods"))), newest), 18);
        ((Button)((Grid)((StackPanel)((Grid)newestCard.Child).Children[1]).Children[0]).Children[1]).Margin = new Thickness(0, -8, -8, -8);
        newestCard.Margin = new Thickness(0, 12, 0, 0);
        page.Children.Add(newestCard);

        // ---- last crash, and folders
        var lower = new Grid { Margin = new Thickness(0, 12, 0, 0) };
        lower.ColumnDefinitions.Add(new ColumnDefinition());
        lower.ColumnDefinitions.Add(new ColumnDefinition());
        var crashBody = new StackPanel();
        crashBody.Children.Add(Ui.Cap("Last crash"));
        var crashText = Ui.T("Checking", 13.5, "Muted", wrap: true);
        crashText.Margin = new Thickness(0, 10, 0, 0);
        crashBody.Children.Add(crashText);
        var crashCard = Ui.SheetCard(crashBody, 18);
        crashCard.Margin = new Thickness(0, 0, 6, 0);
        lower.Children.Add(crashCard);

        var folders = new WrapPanel { Margin = new Thickness(0, 10, 0, -8) };
        void Folder(string label, string dir)
        {
            var b = Ui.Btn(label, "Secondary", (_, _) => OpenFolder(dir), "");
            b.ToolTip = dir;
            b.Margin = new Thickness(0, 0, 8, 8);
            b.Padding = new Thickness(13, 7, 13, 7);
            folders.Children.Add(b);
        }
        var run = Path.Combine(Paths.Home, "run");
        Folder("Mods", Paths.Mods);
        Folder("Worlds", Path.Combine(run, "saves"));
        Folder("Screenshots", Path.Combine(run, "screenshots"));
        Folder("Shaders", Path.Combine(run, "shaderpacks"));
        Folder("Resource packs", Path.Combine(run, "resourcepacks"));
        Folder("Config", Path.Combine(run, "config"));
        var folderCard = Ui.SheetCard(Ui.V(Ui.Cap("Folders"), folders), 18);
        folderCard.Margin = new Thickness(6, 0, 0, 0);
        Grid.SetColumn(folderCard, 1);
        lower.Children.Add(folderCard);
        page.Children.Add(lower);

        _ = FillHome(modsValue, modsSub, newest, crashBody, crashText);
        return Scroll(page);
    }

    async Task FillHome(TextBlock modsValue, TextBlock modsSub, StackPanel newest, StackPanel crashBody, TextBlock crashText)
    {
        List<ModEntry> mods;
        CrashInfo? crash;
        try
        {
            (mods, crash) = await Task.Run(() =>
            {
                var all = ModScan.Scan();
                return (all, CrashScan.Recent(all, 1).FirstOrDefault());
            });
        }
        catch (Exception ex)
        {
            AppLog.Write("home: " + ex.Message);
            crashText.Text = "Could not read the game folder.";
            return;
        }

        var on = mods.Count(m => m.Enabled);
        modsValue.Text = mods.Count == 0 ? "None yet" : on + " on";
        modsSub.Text = mods.Count == 0 ? "Find some on Browse" : mods.Count - on + " switched off";

        if (crash == null) crashText.Text = "No crash reports.";
        else
        {
            crashText.Text = "The game crashed " + Ui.Ago(crash.Time) + ". " + (crash.Mod != null ? "Most likely cause: " + crash.Mod.Name + "." : crash.Title);
            if ((DateTime.Now - crash.Time).TotalDays < 3) crashText.Foreground = Ui.Br("Text");
            var view = Ui.Btn("View crashes", "Secondary", (_, _) => Go("Crashes"), "");
            view.HorizontalAlignment = HorizontalAlignment.Left;
            view.Margin = new Thickness(0, 12, 0, 0);
            view.Padding = new Thickness(13, 7, 13, 7);
            crashBody.Children.Add(view);
        }

        void ShowNewest()
        {
            newest.Children.Clear();
            if (mods.Count == 0) { newest.Children.Add(Ui.T("No mods yet. The Browse tab has thousands.", 13, "Muted")); return; }
            foreach (var m in mods.OrderByDescending(x => x.Stamp).Take(7))
            {
                var name = Ui.T(m.Name, 11.5, "Soft", trim: true);
                name.Margin = new Thickness(0, 7, 0, 0);
                name.HorizontalAlignment = HorizontalAlignment.Center;
                var tile = Ui.Tile(m.IconUrl, m.Name, 52, m);
                tile.HorizontalAlignment = HorizontalAlignment.Center;
                var item = Ui.HoverRow(new Border { Width = 92, Padding = new Thickness(6, 8, 6, 8), Margin = new Thickness(0, 0, 4, 0), Child = Ui.V(tile, name), Cursor = Cursors.Hand, ToolTip = m.Name + (m.Version.Length > 0 ? "  " + m.Version : "") });
                item.MouseLeftButtonUp += (_, _) => Go("Mods");
                newest.Children.Add(item);
            }
        }
        ShowNewest();
        // Icons come from Modrinth, so they arrive a moment later.
        if (mods.Any(m => !m.Looked))
        {
            try { await Modrinth.Lookup(mods, CancellationToken.None); ShowNewest(); }
            catch (Exception ex) { AppLog.Write("home icons: " + ex.Message); }
        }
    }

    Border Fact(string label, string value, string sub, string page) =>
        FactCard(label, Ui.T(value, 20, bold: true, trim: true), Ui.T(sub, 11.5, "Faint", trim: true), page);

    Border FactCard(string label, TextBlock value, TextBlock sub, string page)
    {
        value.Margin = new Thickness(0, 9, 0, 5);
        var card = new Border { Background = Brushes.Transparent, BorderBrush = Ui.Br("Line"), Padding = new Thickness(20, 18, 16, 18), Cursor = Cursors.Hand, Child = Ui.V(Ui.Cap(label), value, sub) };
        card.MouseEnter += (_, _) => card.Background = Ui.Br("Panel2");
        card.MouseLeave += (_, _) => card.Background = Brushes.Transparent;
        card.MouseLeftButtonUp += (_, _) => Go(page);
        return card;
    }

    Border Notice(string title, string body, string primary, Action onPrimary, string? second = null, Action? onSecond = null)
    {
        var buttons = Ui.H(Ui.Btn(primary, "Primary", (_, _) => onPrimary()));
        if (second != null) buttons.Children.Add(Ui.Btn(second, "Secondary", (_, _) => onSecond?.Invoke()).With(new Thickness(10, 0, 0, 0)));
        buttons.VerticalAlignment = VerticalAlignment.Center;
        var text = Ui.V(Ui.T(title, 14.5, bold: true), Ui.T(body, 12.5, "Muted", wrap: true).With(new Thickness(0, 4, 0, 0)));
        text.Margin = new Thickness(0, 0, 20, 0);
        var row = Ui.Row(Ui.H(Ui.Dot("Warn").With(new Thickness(0, 0, 14, 0)), text), buttons);
        var card = Ui.Card(row, 18);
        card.BorderBrush = Ui.Br("Edge");
        return card;
    }

    Border InstallCard()
    {
        installBar = new ProgressBar { Style = Ui.St("Bar"), IsIndeterminate = true, Margin = new Thickness(0, 14, 0, 0) };
        installLabel = Ui.T("Starting", 12.5, "Muted", trim: true);
        installLabel.Margin = new Thickness(0, 4, 0, 0);
        return Ui.Card(Ui.V(Ui.T("Installing Pentecost", 14.5, bold: true), installLabel, installBar), 18);
    }

    /// <summary>The first install asks whether to fetch the main menu worlds.</summary>
    void AskInstall(bool first)
    {
        var worlds = new CheckBox { Style = Ui.St("Switch"), IsChecked = true, Content = Ui.T("Download the main menu worlds (about 480 MB)", 13, "Soft") };
        var note = Ui.T("Pentecost shows these behind the title screen. You can skip them and add them later.", 12, "Faint", wrap: true);
        note.Margin = new Thickness(50, 4, 0, 0);
        var no = Ui.Btn("Cancel", "Secondary", (_, _) => HideModal());
        var go = Ui.Btn("Install", "Primary", (_, _) => { var w = worlds.IsChecked == true; HideModal(); _ = RunInstall(w); });
        var buttons = Ui.H(no, go.With(new Thickness(10, 0, 0, 0)));
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        var box = Ui.V(Ui.T("Install Pentecost", 17, bold: true),
            Ui.T(latest != null ? $"Version {latest.Version} for Minecraft 1.20.1, into {Paths.Home}." : "The latest version for Minecraft 1.20.1, into " + Paths.Home + ".", 13, "Soft", wrap: true).With(new Thickness(0, 8, 0, 18)),
            worlds, note, buttons.With(new Thickness(0, 24, 0, 0)));
        box.Width = 470;
        ShowModal(Ui.Card(box, 26));
    }

    void ChooseJava()
    {
        if (testing) return;
        var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Choose java.exe (Java 21 or newer)", Filter = "java.exe|java.exe", CheckFileExists = true };
        if (dlg.ShowDialog(this) != true) return;
        var err = JavaFinder.Choose(dlg.FileName);
        if (err != null) { Toast(err, "Bad"); return; }
        java = dlg.FileName;
        Toast("Java saved.");
        Refresh();
    }
}
