namespace Pentecost.Manager;

public partial class MainWindow
{
    List<Missing> missingCache = new();

    FrameworkElement BuildMods()
    {
        var root = new Grid();
        for (var r = 0; r < 3; r++) root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var mods = new List<ModEntry>();
        var shown = new List<ModEntry>();
        var selected = new HashSet<ModEntry>();
        ModEntry? open = null;
        var openedOnce = false;
        var filter = "";
        var view = "All";
        var sort = Settings.ModSort;
        var checking = false;
        LazyList lazy = null!;
        Button updatesBtn = null!;
        StackPanel notices = null!;
        ContentControl empty = null!, side = null!;

        var sky = new StarChart { Height = 236, Margin = new Thickness(-34, -24, -28, 0) };
        var count = Ui.T("", 13, "Soft");
        count.FontFamily = (FontFamily)FindResource("Mono");
        var legend = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
        var word = new Wordmark { Text = "MODS", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var headText = Ui.V(word, count.With(new Thickness(2, 10, 0, 0)), legend);
        headText.HorizontalAlignment = HorizontalAlignment.Left;
        headText.VerticalAlignment = VerticalAlignment.Bottom;
        headText.Margin = new Thickness(2, 0, 0, 46);
        if (!Settings.StarChart)
        {
            sky.Visibility = Visibility.Collapsed;
            headText.Margin = new Thickness(2, 6, 0, 14);
        }
        headText.IsHitTestVisible = false;
        var head = new Grid();
        head.Children.Add(sky);
        head.Children.Add(headText);
        root.Children.Add(head);

        var search = new TextBox { Height = 34, VerticalContentAlignment = VerticalAlignment.Center, BorderThickness = new Thickness(0, 0, 0, 1), Background = Brushes.Transparent, Padding = new Thickness(26, 0, 6, 0), MinWidth = 220 };
        var lens = Ui.Glyph("\uE721", 12, "Faint");
        lens.Margin = new Thickness(4, 0, 0, 0);
        lens.HorizontalAlignment = HorizontalAlignment.Left;
        lens.IsHitTestVisible = false;
        var hint = Ui.T("Search", 13, "Faint");
        hint.Margin = new Thickness(26, 0, 0, 0);
        hint.VerticalAlignment = VerticalAlignment.Center;
        hint.IsHitTestVisible = false;
        search.TextChanged += (_, _) => { hint.Visibility = search.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed; filter = search.Text.Trim(); Render(); };
        search.KeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Escape) search.Clear(); };
        searchFocus["Mods"] = () => { search.Focus(); search.SelectAll(); };
        var sbox = new Grid { VerticalAlignment = VerticalAlignment.Center };
        sbox.Children.Add(search);
        sbox.Children.Add(lens);
        sbox.Children.Add(hint);

        var tabs = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var name in new[] { "All", "On", "Off", "Updates", "Forge", "Fabric" })
        {
            var n = name;
            var rb = new RadioButton { GroupName = "mv", Style = Ui.St("Tab"), IsChecked = n == "All", Margin = new Thickness(0, 0, 4, 0),
                Content = new Wordmark { Text = n.ToUpperInvariant(), Pixel = 1, Fill = Ui.Br("Muted") } };
            rb.Checked += (_, _) => { ((Wordmark)rb.Content).Fill = Ui.Br("Text"); view = n; Render(); };
            rb.Unchecked += (_, _) => ((Wordmark)rb.Content).Fill = Ui.Br("Muted");
            if (n == "All") ((Wordmark)rb.Content).Fill = Ui.Br("Text");
            tabs.Children.Add(rb);
        }
        var sortBtn = Ui.Btn(sort, "Ghost", null, "\uE8CB");
        Ui.Menu(sortBtn, () => new[] { "Name", "Loader", "Newest", "Size" }.Select(s => (s, (Action)(() =>
        {
            sort = s;
            if (sortBtn.Content is StackPanel sp && sp.Children.OfType<TextBlock>().LastOrDefault() is { } tx) tx.Text = s;
            Render();
        }), s == sort)));
        sortBtn.ToolTip = "Sort";
        updatesBtn = Ui.Btn("Updates", "Ghost", (_, _) => OpenUpdates(mods, Lookup), "\uE72C");
        var addBtn = Ui.Btn("Add mods", "Primary", (_, _) =>
        {
            var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Choose mod files", Filter = "Mods (*.jar)|*.jar", Multiselect = true };
            if (dlg.ShowDialog(this) == true) AddJars(dlg.FileNames);
        }, "\uE710");
        var folder = Ui.IconBtn("\uE838", "Open the mods folder", (_, _) => OpenFolder(Paths.Mods));
        var toolRow = new Grid { Margin = new Thickness(0, 4, 0, 0) };
        toolRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        toolRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolRow.Children.Add(tabs);
        sbox.Margin = new Thickness(10, 0, 18, 0);
        Grid.SetColumn(sbox, 1);
        toolRow.Children.Add(sbox);
        var rightTools = Ui.H(sortBtn, updatesBtn.With(new Thickness(4, 0, 0, 0)), addBtn.With(new Thickness(10, 0, 0, 0)), folder.With(new Thickness(4, 0, 0, 0)));
        rightTools.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(rightTools, 2);
        toolRow.Children.Add(rightTools);
        Grid.SetRow(toolRow, 1);
        root.Children.Add(toolRow);

        notices = new StackPanel { Margin = new Thickness(0, 10, 0, 0) };
        Grid.SetRow(notices, 2);
        root.Children.Add(notices);

        var body = new Grid { Margin = new Thickness(0, 6, 0, 0) };
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star), MinWidth = 380 });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(390) });
        Func<int, UIElement> build = _ => new Border();
        lazy = new LazyList(54, i => build(i));
        lazy.View.Padding = new Thickness(0, 0, 8, 24);
        var listHost = new Grid();
        empty = new ContentControl { IsTabStop = false, Margin = new Thickness(0, 12, 12, 0), VerticalAlignment = VerticalAlignment.Top, Visibility = Visibility.Collapsed };
        listHost.Children.Add(lazy.View);
        listHost.Children.Add(empty);
        body.Children.Add(listHost);
        side = new ContentControl { IsTabStop = false };
        var sideFrame = new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1, 0, 0, 0), Padding = new Thickness(26, 14, 0, 24), Child = side };
        Grid.SetColumn(sideFrame, 1);
        body.Children.Add(sideFrame);
        Grid.SetRow(body, 3);
        root.Children.Add(body);

        async Task Load()
        {
            mods = await Task.Run(ModScan.Scan);
            selected.IntersectWith(mods);
            if (open != null) open = mods.FirstOrDefault(m => m.File == open.File || m.BaseName == open.BaseName);
            else if (!openedOnce && mods.Count > 0)
                open = mods.OrderByDescending(m => m.Needs.Count + (m.Id.Length == 0 ? 0 : mods.Count(x => x.Needs.Contains(m.Id)))).First();
            openedOnce = true;
            Render();
            if (Settings.UpdateCheck) await Lookup(false);
        }

        async Task Lookup(bool force)
        {
            if (checking) return;
            checking = true;
            try
            {
                if (force) foreach (var m in mods) { m.Looked = false; m.Update = null; m.Requires.Clear(); }
                missingCache = await Modrinth.Lookup(mods, CancellationToken.None);
                Render();
                var n = mods.Count(m => m.Update != null);
                if (force) Toast(n == 0 ? "Everything is up to date." : n == 1 ? "1 update found." : $"{n} updates found.");
            }
            catch (Exception ex)
            {
                AppLog.Write("lookup: " + ex.Message);
                Render();
                if (force) Toast("Could not reach Modrinth.", "Warn");
            }
            finally { checking = false; }
        }

        void Remember(ModEntry m)
        {
            var p = ProfileStore.Active;
            if (p.On.Count + p.Off.Count == 0) return;
            p.On.RemoveAll(x => x.Equals(m.BaseName, StringComparison.OrdinalIgnoreCase));
            p.Off.RemoveAll(x => x.Equals(m.BaseName, StringComparison.OrdinalIgnoreCase));
            (m.Enabled ? p.On : p.Off).Add(m.BaseName);
            ProfileStore.Save();
        }

        void Switch(IEnumerable<ModEntry> which, bool on)
        {
            var failed = 0;
            foreach (var m in which.ToList())
                try { ModScan.SetEnabled(m, on); Remember(m); }
                catch (Exception ex) { failed++; AppLog.Write("switch " + m.Name + ": " + ex.Message); }
            if (failed > 0) Toast($"Could not switch {failed} mods. Close the game first.", "Bad");
            Render();
        }

        void Remove(ModEntry m) => Confirm("Remove " + m.Name + "?", "The file goes to the Recycle Bin, so you can get it back from there.", "Remove", () =>
        {
            try { ModScan.Delete(m); mods.Remove(m); selected.Remove(m); if (open == m) open = null; Render(); Toast("Removed " + m.Name); }
            catch (Exception ex) { Toast("Could not remove it: " + ex.Message, "Bad"); }
        }, true);

        async Task Update(List<ModEntry> which)
        {
            var failed = 0;
            await Task.WhenAll(which.Select(async m =>
            {
                try { await Modrinth.ApplyUpdate(m, _ => { }, CancellationToken.None); }
                catch (Exception ex) { Interlocked.Increment(ref failed); AppLog.Write("update " + m.Name + ": " + ex.Message); }
            }));
            Toast(failed > 0 ? $"{failed} of {which.Count} updates failed. See the manager log." : which.Count == 1 ? "Updated " + which[0].Name : $"Updated {which.Count} mods.",
                failed > 0 ? "Warn" : "Ok");
            await Load();
        }

        static string Loader(ModEntry m) => m.Loader switch { "Forge" => "Forge", "Fabric" => "Fabric", "Quilt" => "Quilt", "Both" => "Both", _ => "Other" };
        static string Key(ModEntry m) => m.BaseName;
        static string LoaderBrush(string l) => l switch { "Forge" => "Text", "Fabric" => "Muted", "Quilt" => "Soft", "Both" => "Edge", _ => "Line" };

        void Render()
        {
            var on = mods.Count(m => m.Enabled);
            var updates = mods.Where(m => m.Update != null).ToList();
            count.Text = mods.Count == 0 ? "empty" : $"{on} on, {mods.Count - on} off" + (updates.Count > 0 ? $", {updates.Count} to update" : "");
            if (updatesBtn.Content is StackPanel up && up.Children.OfType<TextBlock>().LastOrDefault() is { } ut)
                ut.Text = updates.Count > 0 ? $"Updates ({updates.Count})" : "Updates";
            sky.SetStars(mods.Select(m => new StarChart.Star
            {
                Key = Key(m), Name = m.Name, Loader = Loader(m), On = m.Enabled, Update = m.Update != null,
                Needs = m.Needs.Select(id => DepMatch.Find(mods, id)).Where(x => x != null).Select(x => Key(x!)).ToArray(),
                NeededBy = mods.Where(x => x != m && x.Needs.Any(n => DepMatch.Provides(m, n))).Select(Key).ToArray(),
            }).ToList());
            sky.Open = open == null ? null : Key(open);
            if (sky.Hidden > 0 && Settings.StarChart) count.Text += $", {sky.Hidden} off the chart";

            legend.Children.Clear();
            foreach (var g in mods.GroupBy(Loader).OrderByDescending(g => g.Count()))
                legend.Children.Add(Ui.H(Ui.Dot(LoaderBrush(g.Key), 5), Ui.Cap(g.Key + " " + g.Count()).With(new Thickness(6, 0, 14, 0))));

            IEnumerable<ModEntry> q = mods.Where(m => filter.Length == 0 || m.Name.Contains(filter, StringComparison.OrdinalIgnoreCase)
                || m.Id.Contains(filter, StringComparison.OrdinalIgnoreCase) || m.BaseName.Contains(filter, StringComparison.OrdinalIgnoreCase));
            q = view switch
            {
                "On" => q.Where(m => m.Enabled),
                "Off" => q.Where(m => !m.Enabled),
                "Updates" => q.Where(m => m.Update != null),
                "Forge" => q.Where(m => m.Loader is "Forge" or "Both"),
                "Fabric" => q.Where(m => m.Loader is "Fabric" or "Both" or "Quilt"),
                _ => q,
            };
            q = sort switch
            {
                "Loader" => q.OrderBy(m => m.Loader).ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase),
                "Newest" => q.OrderByDescending(m => m.Stamp),
                "Size" => q.OrderByDescending(m => m.Size),
                _ => q.OrderBy(m => m.Name, StringComparer.OrdinalIgnoreCase),
            };
            shown = q.ToList();

            notices.Children.Clear();
            if (selected.Count > 0)
            {
                var picked = selected.ToList();
                var buttons = Ui.H(
                    Ui.Btn("Turn on", "Secondary", (_, _) => Switch(picked, true)),
                    Ui.Btn("Turn off", "Secondary", (_, _) => Switch(picked, false)).With(new Thickness(8, 0, 0, 0)),
                    Ui.Btn("Remove", "Secondary", (_, _) => Confirm($"Remove {picked.Count} mods?", "You can restore them from the Recycle Bin.", "Remove", () =>
                    {
                        foreach (var m in picked)
                            try { ModScan.Delete(m); mods.Remove(m); selected.Remove(m); }
                            catch (Exception ex) { AppLog.Write("remove " + m.Name + ": " + ex.Message); }
                        Render();
                        Toast($"Removed {picked.Count} mods.");
                    }, true)).With(new Thickness(8, 0, 0, 0)),
                    Ui.Btn("Clear", "Ghost", (_, _) => { selected.Clear(); Render(); }).With(new Thickness(8, 0, 0, 0)));
                notices.Children.Add(Strip("Accent", picked.Count == 1 ? "1 mod picked" : $"{picked.Count} mods picked", null, buttons));
            }
            if (updates.Count > 0)
            {
                var all = Ui.Btn(updates.Count == 1 ? "Update" : "Update all", "Primary", async (s, _) => { ((Button)s).IsEnabled = false; await Update(updates); });
                notices.Children.Add(Strip("Warn", updates.Count == 1 ? "1 mod has an update" : $"{updates.Count} mods have updates", "Each old file goes to the Recycle Bin.", all));
            }
            foreach (var miss in missingCache.Take(4))
            {
                var mm = miss;
                var inst = Ui.Btn("Install", "Secondary", async (s, _) =>
                {
                    var b = (Button)s;
                    b.IsEnabled = false;
                    var prefer = mods.Count(m => m.Loader == "Fabric") > mods.Count(m => m.Loader == "Forge") ? "fabric" : "forge";
                    try
                    {
                        var added = await Modrinth.Install(mm.ProjectId, prefer, _ => { }, CancellationToken.None);
                        Toast(added.Count > 0 ? "Installed " + mm.Title : mm.Title + " was already there.");
                        missingCache.Remove(mm);
                        await Load();
                    }
                    catch (Exception ex) { Toast("Could not install " + mm.Title + ": " + ex.Message, "Bad"); b.IsEnabled = true; }
                });
                notices.Children.Add(Strip("Warn", mm.Title + " is missing", "Needed by " + mm.NeededBy + ".", inst));
            }

            if (mods.Count == 0)
            {
                var none = Ui.V(Ui.T("No stars yet", 15, bold: true),
                    Ui.T("Drop .jar files onto this window, use Add mods, or find mods on Browse.", 13, "Muted", wrap: true).With(new Thickness(0, 6, 0, 16)),
                    Ui.Btn("Browse mods", "Primary", (_, _) => Go("Browse")));
                ((Button)none.Children[2]).HorizontalAlignment = HorizontalAlignment.Left;
                empty.Content = none;
            }
            else if (shown.Count == 0) empty.Content = Ui.T("No mod matches that.", 13, "Muted");
            else empty.Content = null;
            empty.Visibility = empty.Content == null ? Visibility.Collapsed : Visibility.Visible;
            lazy.View.Visibility = empty.Content == null ? Visibility.Visible : Visibility.Collapsed;
            lazy.SetCount(shown.Count);
            lazy.InvalidateAll();
            Side();
        }

        FrameworkElement Strip(string color, string title, string? sub, UIElement action)
        {
            var text = Ui.H(Ui.Dot(color, 7).With(new Thickness(0, 0, 12, 0)), Ui.T(title, 13, bold: true));
            if (sub != null) text.Children.Add(Ui.T(sub, 12, "Muted").With(new Thickness(12, 0, 0, 0)));
            text.VerticalAlignment = VerticalAlignment.Center;
            ((FrameworkElement)action).VerticalAlignment = VerticalAlignment.Center;
            return new Border
            {
                BorderBrush = Ui.Br(color == "Accent" ? "Edge" : color), BorderThickness = new Thickness(2, 0, 0, 0),
                Background = Ui.Br("Panel"), Padding = new Thickness(14, 8, 10, 8), Margin = new Thickness(0, 0, 0, 6), Child = Ui.Row(text, action),
            };
        }

        FrameworkElement Row(ModEntry m)
        {
            var isOpen = m == open;
            var picked = selected.Contains(m);
            var stripe = new Border { Width = 4, Height = 4, Background = Ui.Br(LoaderBrush(Loader(m))), Margin = new Thickness(14, 0, 14, 0), ToolTip = m.Loader, VerticalAlignment = VerticalAlignment.Center };
            var tile = Ui.Tile(m.IconUrl, m.Name, 34, m);
            var name = Ui.T(m.Name, 13.5, m.Enabled ? null : "Muted", bold: true, trim: true);
            var ver = Ui.T(m.Update != null ? (m.Version.Length > 0 ? m.Version + "  >  " : "> ") + m.Update.VersionNumber : m.Version, 11, m.Update != null ? "Warn" : "Faint", trim: true);
            ver.FontFamily = (FontFamily)FindResource("Mono");
            ver.Margin = new Thickness(0, 2, 0, 0);
            var info = Ui.V(name, ver);
            info.VerticalAlignment = VerticalAlignment.Center;
            info.Margin = new Thickness(12, 0, 12, 0);

            var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            foreach (var p in FeedPills(m.ProjectId).Take(2)) { p.Margin = new Thickness(0, 0, 6, 0); right.Children.Add(p); }
            if (m.Update != null)
            {
                var upb = Ui.Btn("Update", "Primary", async (s, _) => { ((Button)s).IsEnabled = false; await Update(new List<ModEntry> { m }); });
                upb.Padding = new Thickness(12, 4, 12, 4);
                upb.FontSize = 11.5;
                upb.Margin = new Thickness(0, 0, 10, 0);
                right.Children.Add(upb);
            }
            var sw = new CheckBox { Style = Ui.St("Switch"), IsChecked = m.Enabled, ToolTip = m.Enabled ? "On" : m.GameOff ? "Off in the game's Mods screen" : "Off" };
            sw.Click += (_, _) =>
            {
                try { ModScan.SetEnabled(m, sw.IsChecked == true); Remember(m); Render(); }
                catch (Exception ex) { sw.IsChecked = m.Enabled; Toast("Could not change it. Close the game first. " + ex.Message, "Bad"); }
            };
            right.Children.Add(sw);

            var g = new Grid();
            foreach (var w in new[] { GridLength.Auto, GridLength.Auto, GridLength.Auto, new GridLength(1, GridUnitType.Star), GridLength.Auto })
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = w });
            var edge = new Border { Width = 2, Background = isOpen || picked ? Ui.Br(isOpen ? "Text" : "Edge") : Brushes.Transparent };
            g.Children.Add(edge);
            Grid.SetColumn(stripe, 1);
            g.Children.Add(stripe);
            Grid.SetColumn(tile, 2);
            g.Children.Add(tile);
            Grid.SetColumn(info, 3);
            g.Children.Add(info);
            Grid.SetColumn(right, 4);
            g.Children.Add(right);
            var row = new Border { Height = 52, Padding = new Thickness(0, 0, 12, 0), Child = g, Cursor = Cursors.Hand, ToolTip = null };
            if (isOpen) row.Background = Ui.Br("Panel"); else Ui.HoverRow(row);
            row.MouseLeftButtonUp += (_, e) =>
            {
                if (e.OriginalSource is DependencyObject d && (FindUp<CheckBox>(d) != null || FindUp<Button>(d) != null)) return;
                if (e.ClickCount >= 2) { ShowModInfo(m, () => _ = Load()); return; }
                if (Keyboard.Modifiers.HasFlag(ModifierKeys.Control)) { if (!selected.Remove(m)) selected.Add(m); Render(); return; }
                open = open == m ? null : m;
                Render();
            };
            row.MouseRightButtonUp += (_, _) => Ui.MenuAt(row, new (string, string?, Action)[]
            {
                ("Open its page", "", () => ShowModInfo(m, () => _ = Load())),
                (m.Enabled ? "Turn off" : "Turn on", "", () => Switch(new[] { m }, !m.Enabled)),
                ("Show the file", "", () => ShowInFolder(m.File)),
                ("Move to the Recycle Bin", "", () => Remove(m)),
            });
            return row;
        }

        void Side()
        {
            var m = open;
            if (m == null)
            {
                var hint = Ui.V(Ui.Cap("Star chart"),
                    Ui.T(mods.Count == 0 ? "No mods yet." : "Pick a mod in the list or on the chart to see it and the mods it needs. Double-click opens its full page. Ctrl+click picks several.", 13, "Muted", wrap: true).With(new Thickness(0, 10, 0, 18)));
                void Fact(string label, string value)
                {
                    var r = Ui.Row(Ui.T(label, 12.5, "Faint"), Ui.T(value, 12.5, "Soft"));
                    r.Margin = new Thickness(0, 0, 0, 8);
                    hint.Children.Add(r);
                }
                if (mods.Count > 0)
                {
                    Fact("On", mods.Count(x => x.Enabled).ToString());
                    Fact("Off", mods.Count(x => !x.Enabled).ToString());
                    Fact("Size on disk", Ui.Bytes(mods.Sum(x => x.Size)));
                    Fact("Newest", mods.OrderByDescending(x => x.Stamp).First().Name);
                    if (mods.Any(x => x.Update != null)) Fact("Updates", mods.Count(x => x.Update != null).ToString());
                }
                side.Content = hint;
                return;
            }
            var p = new StackPanel();
            var tile = Ui.Tile(m.IconUrl, m.Name, 64, m);
            var frame = new PixelBox { BorderBrush = Ui.Br(m.Enabled ? "Text" : "Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(3), Notch = 2.5, Child = tile, VerticalAlignment = VerticalAlignment.Top };
            var ver = Ui.T((m.Version.Length > 0 ? m.Version + ", " : "") + (m.Loader == "Both" ? "Forge and Fabric" : m.Loader), 12, "Muted", trim: true);
            ver.FontFamily = (FontFamily)FindResource("Mono");
            var headText = Ui.V(Ui.T(m.Name, 19, bold: true, wrap: true), ver.With(new Thickness(0, 4, 0, 0)),
                Ui.T(m.BaseName, 11, "Faint", trim: true).With(new Thickness(0, 3, 0, 0)));
            headText.Margin = new Thickness(14, 0, 0, 0);
            headText.VerticalAlignment = VerticalAlignment.Center;
            var top = new Grid();
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.ColumnDefinitions.Add(new ColumnDefinition());
            top.Children.Add(frame);
            Grid.SetColumn(headText, 1);
            top.Children.Add(headText);
            p.Children.Add(top);

            var desc = (m.Summary.Length > 0 ? m.Summary : m.Desc) ?? "";
            if (desc.Length > 0) p.Children.Add(Ui.T(desc, 13, "Soft", wrap: true).With(new Thickness(0, 16, 0, 0)));

            if (Feed.Mod(m.ProjectId)?["note"]?.ToString() is { Length: > 0 } note)
                p.Children.Add(new Border
                {
                    BorderBrush = Ui.Br("Warn"), BorderThickness = new Thickness(2, 0, 0, 0), Padding = new Thickness(12, 2, 0, 2), Margin = new Thickness(0, 14, 0, 0),
                    Child = Ui.V(Ui.Cap("From the developer"), Ui.T(note, 12.5, "Soft", wrap: true).With(new Thickness(0, 4, 0, 0))),
                });
            if (Feed.Mod(m.ProjectId)?["warn"]?["text"]?.ToString() is { Length: > 0 } warn)
                p.Children.Add(Ui.H(Ui.Dot("Warn", 7).With(new Thickness(0, 0, 10, 0)), Ui.T(warn, 12.5, "Warn", wrap: true)).With(new Thickness(0, 10, 0, 0)));

            if (m.Update != null)
            {
                var upb = Ui.Btn("Update to " + m.Update.VersionNumber, "Primary", async (s, _) => { ((Button)s).IsEnabled = false; await Update(new List<ModEntry> { m }); });
                upb.HorizontalAlignment = HorizontalAlignment.Left;
                p.Children.Add(upb.With(new Thickness(0, 16, 0, 0)));
            }

            WrapPanel Chips(IEnumerable<(string Text, string Color, ModEntry? Go)> items)
            {
                var w = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) };
                foreach (var (text, color, go) in items.Take(12))
                {
                    var pill = Ui.Pill(text, color);
                    pill.Margin = new Thickness(0, 0, 6, 6);
                    if (go != null) { pill.Cursor = Cursors.Hand; pill.MouseLeftButtonUp += (_, _) => { open = go; Render(); }; }
                    w.Children.Add(pill);
                }
                return w;
            }
            if (m.Needs.Count > 0)
            {
                p.Children.Add(Ui.Cap("Needs").With(new Thickness(0, 18, 0, 0)));
                p.Children.Add(Chips(m.Needs.Select(id => DepMatch.Find(mods, id) is { } have ? (have.Name, "Soft", (ModEntry?)have) : (id + " missing", "Warn", (ModEntry?)null))));
            }
            var neededBy = mods.Where(x => x != m && x.Needs.Any(n => DepMatch.Provides(m, n))).ToList();
            if (neededBy.Count > 0)
            {
                p.Children.Add(Ui.Cap("Needed by").With(new Thickness(0, 14, 0, 0)));
                p.Children.Add(Chips(neededBy.Select(x => (x.Name, "Soft", (ModEntry?)x))));
            }

            var facts = new StackPanel { Margin = new Thickness(0, 16, 0, 0) };
            void Line(string label, string value)
            {
                var r = Ui.Row(Ui.T(label, 12, "Faint"), Ui.T(value, 12, "Soft", trim: true));
                r.Margin = new Thickness(0, 0, 0, 6);
                facts.Children.Add(r);
            }
            Line("Size", Ui.Bytes(m.Size));
            Line("Added", m.Stamp.ToLocalTime().ToString("yyyy-MM-dd"));
            if (m.Authors.Length > 0) Line("By", m.Authors);
            p.Children.Add(Ui.Dotted().With(new Thickness(0, 16, 0, 0)));
            p.Children.Add(facts);

            var power = Ui.Btn(m.Enabled ? "Turn off" : "Turn on", "Secondary", (_, _) => Switch(new[] { m }, !m.Enabled));
            var pageBtn = Ui.Btn("Open page", "Primary", (_, _) => ShowModInfo(m, () => _ = Load()));
            var file = Ui.IconBtn("", "Show the file", (_, _) => ShowInFolder(m.File));
            var del = Ui.IconBtn("", "Move to the Recycle Bin", (_, _) => Remove(m));
            var buttons = Ui.H(pageBtn, power.With(new Thickness(8, 0, 0, 0)), file.With(new Thickness(6, 0, 0, 0)), del);
            p.Children.Add(buttons.With(new Thickness(0, 14, 0, 0)));

            var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = p, Padding = new Thickness(0, 0, 10, 0) };
            side.Content = scroll;
            Ui.FadeIn(scroll, 120);
        }

        build = i => i < shown.Count ? Row(shown[i]) : new Border();
        sky.Picked += key =>
        {
            open = mods.FirstOrDefault(m => Key(m) == key);
            Render();
            var at = open == null ? -1 : shown.IndexOf(open);
            if (at >= 0) lazy.View.ScrollToVerticalOffset(Math.Max(0, at * 54 - lazy.View.ViewportHeight / 2));
        };
        _ = Load();
        return root;
    }

    static T? FindUp<T>(DependencyObject? d) where T : DependencyObject
    {
        while (d != null && d is not T) d = d is Visual or System.Windows.Media.Media3D.Visual3D ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d);
        return d as T;
    }
}
