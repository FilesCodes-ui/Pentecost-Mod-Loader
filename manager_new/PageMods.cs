namespace Pentecost.Manager;

public partial class MainWindow
{
    List<Missing> missingCache = new();

    FrameworkElement BuildMods()
    {
        var root = new Grid();
        for (var r = 0; r < 4; r++) root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var mods = new List<ModEntry>();
        var shown = new List<ModEntry>();
        var selected = new HashSet<ModEntry>();
        var filter = "";
        var view = "All";
        var sort = "Name";
        var status = Ui.T("Loading", 12.5, "Muted", trim: true);
        status.VerticalAlignment = VerticalAlignment.Center;
        var notices = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
        var empty = new ContentControl { IsTabStop = false, Margin = new Thickness(0, 12, 0, 0), Visibility = Visibility.Collapsed };
        var checking = false;
        Button check = null!;
        LazyList lazy = null!;
        lazy = new LazyList(64, i => i < shown.Count ? Row(shown[i]) : new Border());
        lazy.View.Margin = new Thickness(0, 10, 0, 0);

        // ---- header and tools
        var add = Ui.Btn("Add mods", "Secondary", (_, _) =>
        {
            var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Choose mod files", Filter = "Mods (*.jar)|*.jar", Multiselect = true };
            if (dlg.ShowDialog(this) == true) AddJars(dlg.FileNames);
        }, "");
        check = Ui.Btn("Updates", "Secondary", (_, _) => OpenUpdates(mods, Lookup), "");
        var folder = Ui.IconBtn("", "Open the mods folder", (_, _) => OpenFolder(Paths.Mods));
        root.Children.Add(Header("Mods", "Your installed mods.", check.With(new Thickness(0, 0, 8, 0)), add.With(new Thickness(0, 0, 4, 0)), folder));

        var sbox = Ui.SearchBox("Search your mods", out var search);
        search.TextChanged += (_, _) => { filter = search.Text.Trim(); Render(); };
        searchFocus["Mods"] = () => { search.Focus(); search.SelectAll(); };
        var bar = Ui.Row(sbox, status.With(new Thickness(18, 0, 0, 0)));
        bar.Margin = new Thickness(0, 20, 0, 0);
        Grid.SetRow(bar, 1);
        root.Children.Add(bar);

        RadioButton Seg(string label, string group, bool on, Action pick)
        {
            var rb = new RadioButton { Content = label, GroupName = group, Style = Ui.St("Seg"), IsChecked = on };
            rb.Click += (_, _) => pick();
            return rb;
        }
        var views = Ui.H(Seg("All", "mv", true, () => { view = "All"; Render(); }), Seg("On", "mv", false, () => { view = "On"; Render(); }),
            Seg("Off", "mv", false, () => { view = "Off"; Render(); }), Seg("Updates", "mv", false, () => { view = "Updates"; Render(); }));
        var sorts = Ui.H(Seg("Name", "ms", true, () => { sort = "Name"; Render(); }), Seg("Loader", "ms", false, () => { sort = "Loader"; Render(); }),
            Seg("Newest", "ms", false, () => { sort = "Newest"; Render(); }), Seg("Size", "ms", false, () => { sort = "Size"; Render(); }));
        sorts.Margin = new Thickness(16, 0, 0, 0);
        var pickAll = Ui.Btn("Select all", "Ghost", (_, _) =>
        {
            if (shown.Count > 0 && shown.All(selected.Contains)) selected.Clear();
            else foreach (var m in shown) selected.Add(m);
            Render();
        });
        pickAll.Margin = new Thickness(12, 0, 0, 0);
        var tools = Ui.H(views, sorts, pickAll);
        tools.Margin = new Thickness(0, 12, 0, 0);
        Grid.SetRow(tools, 2);
        root.Children.Add(tools);

        Grid.SetRow(notices, 3);
        root.Children.Add(notices);
        Grid.SetRow(lazy.View, 4);
        root.Children.Add(lazy.View);
        Grid.SetRow(empty, 4);
        root.Children.Add(empty);

        // ---- loading
        async Task Load()
        {
            mods = await Task.Run(ModScan.Scan);
            selected.IntersectWith(mods);
            Render();
            await Lookup(false);
        }

        async Task Lookup(bool force)
        {
            if (checking) return;
            checking = true;
            try
            {
                if (force) foreach (var m in mods) { m.Looked = false; m.Update = null; m.Requires.Clear(); }
                status.Text = "Checking Modrinth";
                missingCache = await Modrinth.Lookup(mods, CancellationToken.None);
                Render();
                var n = mods.Count(m => m.Update != null);
                if (force) Toast(n == 0 ? "Everything is up to date." : n == 1 ? "1 update found." : $"{n} updates found.");
            }
            catch (Exception ex)
            {
                AppLog.Write("lookup: " + ex.Message);
                Render();
                status.Text = "Modrinth could not be reached";
                if (force) Toast("Could not reach Modrinth.", "Warn");
            }
            finally { checking = false; }
        }

        /// A switch made here is also written into the active profile, so pressing Play does not undo it.
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

        Button UpdatePicked(List<ModEntry> picked)
        {
            var b = Ui.Btn("Update", "Secondary", async (s, _) => { ((Button)s).IsEnabled = false; await Update(picked.Where(x => x.Update != null).ToList()); });
            b.Visibility = picked.Any(x => x.Update != null) ? Visibility.Visible : Visibility.Collapsed;
            return b;
        }

        // ---- drawing
        void Render()
        {
            var on = mods.Count(m => m.Enabled);
            var updates = mods.Where(m => m.Update != null).ToList();
            var updatesText = updates.Count > 0 ? $"Updates ({updates.Count})" : "Updates";
            if (check.Content is StackPanel cp && cp.Children.OfType<TextBlock>().Skip(1).FirstOrDefault() is { } ct) ct.Text = updatesText;
            else if (check.Content is string) check.Content = updatesText;
            status.Text = mods.Count == 0 ? "" : $"{on} on, {mods.Count - on} off" + (updates.Count > 0 ? $", {updates.Count} {(updates.Count == 1 ? "update" : "updates")}" : "");

            IEnumerable<ModEntry> q = mods.Where(m => filter.Length == 0 || m.Name.Contains(filter, StringComparison.OrdinalIgnoreCase)
                || m.Id.Contains(filter, StringComparison.OrdinalIgnoreCase) || m.BaseName.Contains(filter, StringComparison.OrdinalIgnoreCase));
            q = view switch { "On" => q.Where(m => m.Enabled), "Off" => q.Where(m => !m.Enabled), "Updates" => q.Where(m => m.Update != null), _ => q };
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
                    UpdatePicked(picked).With(new Thickness(8, 0, 0, 0)),
                    Ui.Btn("Remove", "Secondary", (_, _) => Confirm($"Remove {picked.Count} mods?", "The files go to the Recycle Bin, so you can get them back from there.", "Remove", () =>
                    {
                        foreach (var m in picked)
                            try { ModScan.Delete(m); mods.Remove(m); selected.Remove(m); }
                            catch (Exception ex) { AppLog.Write("remove " + m.Name + ": " + ex.Message); }
                        Render();
                        Toast($"Removed {picked.Count} mods.");
                    }, true)).With(new Thickness(8, 0, 0, 0)),
                    Ui.Btn("Clear", "Ghost", (_, _) => { selected.Clear(); Render(); }).With(new Thickness(8, 0, 0, 0)));
                var card = Ui.Card(Ui.Row(Ui.H(Ui.Dot("Accent").With(new Thickness(0, 0, 12, 0)), Ui.T(picked.Count == 1 ? "1 mod selected" : $"{picked.Count} mods selected", 13.5, bold: true)), buttons), 12);
                ((StackPanel)((Grid)card.Child).Children[0]).VerticalAlignment = VerticalAlignment.Center;
                card.BorderBrush = Ui.Br("Edge");
                card.Margin = new Thickness(0, 0, 0, 8);
                notices.Children.Add(card);
            }
            if (updates.Count > 0)
            {
                var all = Ui.Btn(updates.Count == 1 ? "Update" : "Update all", "Primary", async (s, _) => { ((Button)s).IsEnabled = false; await Update(updates); });
                notices.Children.Add(Banner(updates.Count == 1 ? "1 mod has an update" : $"{updates.Count} mods have updates", "Each old file goes to the Recycle Bin.", all));
            }
            foreach (var miss in missingCache.Take(6))
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
                notices.Children.Add(Banner(mm.Title + " is needed", "Required by " + mm.NeededBy + ", and not in your mods folder.", inst));
            }

            if (mods.Count == 0)
            {
                var none = Ui.V(Ui.T("No mods yet", 15, bold: true),
                    Ui.T("Drop .jar files onto this window, add them with the button above, or find mods on the Browse page.", 13, "Muted", wrap: true).With(new Thickness(0, 6, 0, 16)),
                    Ui.Btn("Browse mods", "Primary", (_, _) => Go("Browse")));
                ((Button)none.Children[2]).HorizontalAlignment = HorizontalAlignment.Left;
                empty.Content = Ui.Card(none, 24);
            }
            else if (shown.Count == 0) empty.Content = Ui.T("No mod matches that.", 13, "Muted").With(new Thickness(2, 10, 0, 0));
            else empty.Content = null;
            empty.Visibility = empty.Content == null ? Visibility.Collapsed : Visibility.Visible;
            empty.VerticalAlignment = VerticalAlignment.Top;
            lazy.View.Visibility = empty.Content == null ? Visibility.Visible : Visibility.Collapsed;
            lazy.SetCount(shown.Count);
            lazy.InvalidateAll();
        }

        Border Banner(string title, string body, Button action)
        {
            var text = Ui.V(Ui.T(title, 13.5, bold: true), Ui.T(body, 12, "Muted").With(new Thickness(0, 2, 0, 0)));
            var card = Ui.Card(Ui.Row(Ui.H(Ui.Dot("Warn").With(new Thickness(0, 0, 12, 0)), text), action), 14);
            card.Margin = new Thickness(0, 0, 0, 8);
            return card;
        }

        FrameworkElement Row(ModEntry m)
        {
            var tick = new CheckBox { Style = Ui.St("Tick"), IsChecked = selected.Contains(m), VerticalAlignment = VerticalAlignment.Center, ToolTip = "Select" };
            tick.Click += (_, _) => { if (tick.IsChecked == true) selected.Add(m); else selected.Remove(m); Render(); };

            var name = Ui.T(m.Name, 14, m.Enabled ? null : "Muted", bold: true, trim: true);
            var sub = Ui.T((m.Update != null ? (m.Version.Length > 0 ? m.Version + "  to  " : "update to ") + m.Update.VersionNumber + "   " : m.Version.Length > 0 ? m.Version + "   " : "") + m.BaseName, 11.5, m.Update != null ? "Warn" : "Faint", trim: true);
            sub.Margin = new Thickness(0, 2, 0, 0);
            var info = Ui.V(name, sub);
            info.VerticalAlignment = VerticalAlignment.Center;
            info.Margin = new Thickness(14, 0, 14, 0);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            if (m.Loader != "Unknown") actions.Children.Add(Ui.Pill(m.Loader));
            if (m.Update != null)
            {
                var up = Ui.Btn("Update", "Primary", async (s, _) => { ((Button)s).IsEnabled = false; await Update(new List<ModEntry> { m }); });
                up.Padding = new Thickness(14, 5, 14, 5);
                up.FontSize = 12;
                up.ToolTip = "Update to " + m.Update.VersionNumber;
                up.Margin = new Thickness(4, 0, 6, 0);
                actions.Children.Add(up);
            }
            {
                var more = Ui.IconBtn("", "What this mod is, and its other versions", (_, _) => ShowModInfo(m, () => _ = Load()));
                more.Margin = new Thickness(4, 0, 0, 0);
                actions.Children.Add(more);
            }
            var del = Ui.IconBtn("", "Move to the Recycle Bin", (_, _) => Confirm("Remove " + m.Name + "?", "The file goes to the Recycle Bin, so you can get it back from there.", "Remove", () =>
            {
                try { ModScan.Delete(m); mods.Remove(m); selected.Remove(m); Render(); Toast("Removed " + m.Name); }
                catch (Exception ex) { Toast("Could not remove it: " + ex.Message, "Bad"); }
            }, true));
            del.Margin = new Thickness(0, 0, 6, 0);
            actions.Children.Add(del);
            var sw = new CheckBox { Style = Ui.St("Switch"), IsChecked = m.Enabled, ToolTip = m.Enabled ? "On" : "Off" };
            sw.Click += (_, _) =>
            {
                try { ModScan.SetEnabled(m, sw.IsChecked == true); Remember(m); Render(); }
                catch (Exception ex) { sw.IsChecked = m.Enabled; Toast("Could not change it. Close the game first. " + ex.Message, "Bad"); }
            };
            actions.Children.Add(sw);

            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.Children.Add(tick);
            var tile = Ui.Tile(m.IconUrl, m.Name, 40, m);
            tile.Margin = new Thickness(6, 0, 0, 0);
            Grid.SetColumn(tile, 1);
            g.Children.Add(tile);
            Grid.SetColumn(info, 2);
            g.Children.Add(info);
            Grid.SetColumn(actions, 3);
            g.Children.Add(actions);
            var row = Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(2, 0, 8, 0), Child = g, Cursor = Cursors.Hand });
            row.MouseLeftButtonUp += (_, _) => ShowModInfo(m, () => _ = Load());
            row.MouseRightButtonUp += (_, _) => Ui.MenuAt(row, new (string, string?, Action)[]
            {
                ("What this mod is", "", () => ShowModInfo(m, () => _ = Load())),
                (m.Enabled ? "Turn off" : "Turn on", m.Enabled ? "" : "", () => Switch(new[] { m }, !m.Enabled)),
                ("Show the file", "", () => ShowInFolder(m.File)),
                ("Move to the Recycle Bin", "", () => Confirm("Remove " + m.Name + "?", "The file goes to the Recycle Bin, so you can get it back from there.", "Remove", () =>
                {
                    try { ModScan.Delete(m); mods.Remove(m); selected.Remove(m); Render(); Toast("Removed " + m.Name); }
                    catch (Exception ex) { Toast("Could not remove it: " + ex.Message, "Bad"); }
                }, true)),
            });
            Ui.HoverCard(info, () => Ui.Preview(m.IconUrl, m.Name,
                (m.Version.Length > 0 ? "Version " + m.Version : "") + (m.Authors.Length > 0 ? (m.Version.Length > 0 ? "   by " : "by ") + m.Authors : ""),
                m.Summary.Length > 0 ? m.Summary : m.Desc,
                new[] { m.Loader, m.Enabled ? "On" : "Off" }, m.BaseName + "    " + Ui.Bytes(m.Size), m));
            return row;
        }

        _ = Load();
        return root;
    }
}
