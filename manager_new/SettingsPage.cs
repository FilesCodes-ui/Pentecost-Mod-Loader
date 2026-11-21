namespace Pentecost.Manager;

public partial class MainWindow
{
    bool minimizedForGame;

    void AddPreferences(Panel page)
    {
        page.Children.Add(Section("Look and motion",
            Toggle("Moving scenes", "Home's sea and voyager, the star chart on Mods and the lighthouse on Updates move. Off, they hold still and use less power.",
                Settings.Motion, v => Settings.Motion = v),
            Toggle("Storms", "Lightning, rain and thunderbolts around the lighthouse on Updates and the harbor on Browse.",
                Settings.Storm, v => Settings.Storm = v),
            Toggle("Pause scenes while you play", "Every moving scene holds still while the game runs, so your graphics card works only on Minecraft.",
                Settings.PausePlaying, v => Settings.PausePlaying = v),
            Toggle("Pause scenes on battery", "Scenes hold still while your laptop runs on battery, and move again once it is plugged in.",
                Settings.PauseBattery, v => Settings.PauseBattery = v)));

        page.Children.Add(Section("Start and play",
            Choice("Open on", "The page the Manager shows when it starts.",
                new[] { ("Last page", "Last"), ("Home", "Play"), ("Mods", "Mods"), ("Browse", "Browse") }, Settings.OpenOn, v => Settings.OpenOn = v),
            Choice("When the game starts", "Minimize gets the Manager out of the way, and it comes back when the game closes.",
                new[] { ("Stay open", "stay"), ("Minimize", "minimize") }, Settings.OnLaunch, v => Settings.OnLaunch = v),
            Toggle("Start with Windows", "The Manager opens minimized when you sign in to Windows.",
                Settings.StartWithWindows, v => { Settings.StartWithWindows = v; Autostart.Set(v); Toast(v ? "The Manager starts with Windows." : "The Manager no longer starts with Windows."); }),
            Choice("Close button", "Keep in tray hides the Manager next to the clock. Right click its icon there to quit.",
                new[] { ("Quit", "quit"), ("Keep in tray", "tray") }, Settings.CloseAction, v => Settings.CloseAction = v),
            Toggle("Open Crashes after a crash", "When the game stops with an error, the Manager comes back and shows what broke.",
                Settings.CrashJump, v => Settings.CrashJump = v)));

        page.Children.Add(Section("Mods",
            Toggle("Star chart", "The night sky over the mod list, with a star for each mod and lines between mods that need each other.",
                Settings.StarChart, v => Settings.StarChart = v),
            Choice("Stars on the chart", "With more mods than this, the chart keeps mods with updates and the biggest constellations. The rest are left off.",
                new[] { ("30", "30"), ("40", "40"), ("50", "50"), ("80", "80") }, Settings.Stars.ToString(), v => Settings.Stars = int.Parse(v)),
            Choice("Sort by", "How the mod list is ordered when the page opens.",
                new[] { ("Name", "Name"), ("Loader", "Loader"), ("Newest", "Newest"), ("Size", "Size") }, Settings.ModSort, v => Settings.ModSort = v),
            Toggle("Check for updates on open", "Ask Modrinth for newer versions each time Mods opens. Off, use the Updates button to check.",
                Settings.UpdateCheck, v => Settings.UpdateCheck = v)));

        page.Children.Add(Section("Browse",
            Choice("Loader", "The loader filter Browse starts with. Pentecost runs them all.",
                new[] { ("All", "All"), ("Forge", "Forge"), ("Fabric", "Fabric"), ("Quilt", "Quilt") }, Settings.BrowseLoader, v => Settings.BrowseLoader = v),
            Choice("Sort by", "The order Browse starts with.",
                new[] { ("Relevance", "relevance"), ("Downloads", "downloads"), ("Follows", "follows"), ("Newest", "newest"), ("Updated", "updated") }, Settings.BrowseSort, v => Settings.BrowseSort = v),
            Toggle("Modpack details on cards", "Each modpack card reads the pack's list for its mod count, loader, size and the mods you already have. Off saves a little data.",
                Settings.PackDetails, v => Settings.PackDetails = v)));

        page.Children.Add(Section("Profiles and packs",
            Toggle("Reuse mods you already have", "Installing a modpack copies mods that are already in another profile instead of downloading them again.",
                Settings.ReuseMods, v => Settings.ReuseMods = v)));

        page.Children.Add(Section("Friends",
            Toggle("Connect to Pentecost online", "Friends, messages and shared profiles. Off, the Manager stays offline and friends see you as away.",
                Settings.FriendsOnline, v =>
                {
                    Settings.FriendsOnline = v;
                    if (v) Online.Start(); else Online.Drop();
                }),
            Toggle("Message popups", "Show a popup when a friend messages you or shares a profile.",
                Settings.MessagePopups, v => Settings.MessagePopups = v)));
    }

    Grid Toggle(string title, string sub, bool on, Action<bool> set)
    {
        var sw = new CheckBox { Style = Ui.St("Switch"), IsChecked = on, VerticalAlignment = VerticalAlignment.Center };
        sw.Click += (_, _) => set(sw.IsChecked == true);
        return Ui.Row(SettingText(title, sub), sw);
    }

    Grid Choice(string title, string sub, (string Label, string Value)[] options, string current, Action<string> set)
    {
        var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        var group = "pref-" + title + "-" + options.Length + "-" + options[0].Value;
        foreach (var (label, value) in options)
        {
            var v = value;
            var rb = new RadioButton { Content = label, Style = Ui.St("Seg"), GroupName = group, IsChecked = v == current, Margin = new Thickness(6, 0, 0, 0) };
            rb.Checked += (_, _) => set(v);
            right.Children.Add(rb);
        }
        return Ui.Row(SettingText(title, sub), right);
    }

    static FrameworkElement SettingText(string title, string sub)
    {
        var text = Ui.V(Ui.T(title, 13.5, "Text", trim: true));
        if (sub.Length > 0) text.Children.Add(Ui.T(sub, 12, "Muted", wrap: true).With(new Thickness(0, 3, 0, 0)));
        text.VerticalAlignment = VerticalAlignment.Center;
        text.Margin = new Thickness(0, 0, 24, 0);
        return text;
    }
}
