using System.Windows.Controls.Primitives;

namespace Pentecost.Manager;

public partial class MainWindow
{
    void OpenUpdates(List<ModEntry> mods, Func<bool, Task> recheck)
    {
        var due = new List<(ModEntry M, ModUpdate U)>();
        var ticked = new HashSet<ModEntry>();
        var state = new Dictionary<ModEntry, string>();
        var showCurrent = false;
        var showOther = false;
        var checking = false;

        var summary = Ui.T("", 12, "Faint", trim: true);
        var scene = new Lighthouse { Margin = new Thickness(-34, 0, -28, -28) };
        var list = new StackPanel { MaxWidth = 600, HorizontalAlignment = HorizontalAlignment.Left };
        Button again = null!, all = null!;
        again = Ui.Btn("Check again", "Secondary", async (_, _) => { await Check(true); }, "");
        all = Ui.Btn("Update all", "Primary", async (_, _) => { await Apply(due.Where(d => ticked.Contains(d.M)).Select(d => d.M).ToList()); });

        void Collect()
        {
            due = mods.Where(m => m.Update != null).Select(m => (m, m.Update!)).ToList();
            ticked = due.Select(d => d.M).ToHashSet();
            state.Clear();
        }

        async Task Check(bool force)
        {
            if (checking) return;
            checking = true;
            again.IsEnabled = false;
            summary.Text = "Checking Modrinth";
            Draw();
            try { await recheck(force); }
            catch (Exception ex) { AppLog.Write("update page check: " + ex.Message); }
            checking = false;
            again.IsEnabled = true;
            Collect();
            Draw();
        }

        async Task Apply(List<ModEntry> which)
        {
            var todo = which.Where(m => state.GetValueOrDefault(m) is not ("Updating" or "Updated")).ToList();
            if (todo.Count == 0) return;
            foreach (var m in todo) state[m] = "Updating";
            Draw();
            var bad = 0;
            await Task.WhenAll(todo.Select(async m =>
            {
                try { await Modrinth.ApplyUpdate(m, _ => { }, CancellationToken.None); state[m] = "Updated"; }
                catch (Exception ex)
                {
                    bad++;
                    state[m] = "Failed";
                    AppLog.Write("update " + m.Name + ": " + ex.Message);
                }
                Draw();
            }));
            Toast(bad > 0 ? $"{bad} of {todo.Count} updates failed. See the manager log." : todo.Count == 1 ? "Updated " + todo[0].Name : $"Updated {todo.Count} mods.", bad > 0 ? "Warn" : "Ok");
        }

        FrameworkElement Plain(string title, UIElement body, UIElement? right = null)
        {
            var head = Ui.Row(Ui.Cap(title), right);
            return Ui.V(head, Ui.Dotted().With(new Thickness(0, 8, 0, 6)), body).With(new Thickness(0, 0, 0, 18));
        }

        FrameworkElement Line(ModEntry m, string sub, string? status, string statusBrush, UIElement? lead, UIElement? action, double tile = 38)
        {
            var name = Ui.V(Ui.T(m.Name, 13.5, bold: true, trim: true), Ui.T(sub, 11.5, "Faint", trim: true).With(new Thickness(0, 2, 0, 0)));
            name.VerticalAlignment = VerticalAlignment.Center;
            name.Margin = new Thickness(12, 0, 12, 0);
            var g = new Grid();
            for (var i = 0; i < 4; i++) g.ColumnDefinitions.Add(new ColumnDefinition { Width = i == 2 ? new GridLength(1, GridUnitType.Star) : GridLength.Auto });
            if (lead != null) g.Children.Add(lead);
            var t = Ui.Tile(m.IconUrl, m.Name, tile, m);
            t.Margin = new Thickness(lead == null ? 0 : 8, 0, 0, 0);
            Grid.SetColumn(t, 1);
            g.Children.Add(t);
            Grid.SetColumn(name, 2);
            g.Children.Add(name);
            var right = Ui.H();
            right.VerticalAlignment = VerticalAlignment.Center;
            if (status != null) right.Children.Add(Ui.T(status, 12, statusBrush).With(new Thickness(0, 0, 12, 0)));
            if (action != null) right.Children.Add(action);
            Grid.SetColumn(right, 3);
            g.Children.Add(right);
            return Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(6, 9, 8, 9), Child = g });
        }

        void Draw()
        {
            var dueSet0 = due.Select(d => d.M).ToHashSet();
            scene.SetBuoys(mods.OrderBy(m => m.Name, StringComparer.OrdinalIgnoreCase).Select(m => (m.BaseName, m.Name,
                dueSet0.Contains(m)
                    ? state.GetValueOrDefault(m) switch
                    {
                        "Updating" => Lighthouse.Buoy.Updating,
                        "Updated" => Lighthouse.Buoy.Updated,
                        "Failed" => Lighthouse.Buoy.Failed,
                        _ => Lighthouse.Buoy.Due,
                    }
                    : m.Looked && m.ProjectId == null ? Lighthouse.Buoy.Unknown : Lighthouse.Buoy.Current)));
            list.Children.Clear();
            var left = due.Count(d => state.GetValueOrDefault(d.M) != "Updated");
            var known = mods.Where(m => m.Looked && m.ProjectId != null).ToList();
            var unknown = mods.Where(m => m.Looked && m.ProjectId == null).ToList();
            var notChecked = mods.Count(m => !m.Looked);
            if (!checking)
                summary.Text = mods.Count == 0 ? "You have no mods yet."
                    : notChecked == mods.Count ? "Modrinth could not be reached. Press Check again."
                    : left == 0 ? "All current."
                    : left == 1 ? "1 of your mods has an update." : $"{left} of your mods have updates.";

            var n = due.Count(d => ticked.Contains(d.M) && state.GetValueOrDefault(d.M) is not ("Updated" or "Updating"));
            all.Content = n == 0 ? "Update all" : n == left ? $"Update all ({n})" : $"Update {n} selected";
            all.IsEnabled = n > 0 && !checking;

            if (due.Count > 0)
            {
                var pickAll = Ui.Btn(ticked.Count == due.Count ? "Select none" : "Select all", "Ghost", (_, _) =>
                {
                    if (ticked.Count == due.Count) ticked.Clear(); else ticked = due.Select(d => d.M).ToHashSet();
                    Draw();
                });
                pickAll.Padding = new Thickness(8, 3, 8, 3);
                pickAll.FontSize = 11.5;
                var rows = new StackPanel();
                foreach (var (m, u) in due)
                {
                    var mod = m;
                    var s = state.GetValueOrDefault(m) ?? "";
                    var tick = new CheckBox { Style = Ui.St("Tick"), IsChecked = ticked.Contains(m), VerticalAlignment = VerticalAlignment.Center, IsEnabled = s is not ("Updating" or "Updated"), ToolTip = "Include in Update" };
                    tick.Click += (_, _) => { if (tick.IsChecked == true) ticked.Add(mod); else ticked.Remove(mod); Draw(); };
                    var go = Ui.Btn(s == "Failed" ? "Try again" : "Update", "Primary", async (_, _) => await Apply(new List<ModEntry> { mod }));
                    go.Padding = new Thickness(14, 5, 14, 5);
                    go.FontSize = 12;
                    go.IsEnabled = s is not ("Updating" or "Updated");
                    go.ToolTip = "Update " + m.Name + " to " + u.VersionNumber;
                    var sub = (m.Version.Length > 0 ? m.Version : "this version") + "  to  " + (u.VersionNumber.Length > 0 ? u.VersionNumber : u.FileName);
                    var (text, brush) = s switch
                    {
                        "Updating" => ("Updating", "Soft"),
                        "Updated" => ("Updated", "Ok"),
                        "Failed" => ("Failed", "Bad"),
                        _ => (null, "Soft"),
                    };
                    var row = Line(m, sub, text, brush, tick, s == "Updated" ? null : go);
                    row.Cursor = Cursors.Arrow;
                    rows.Children.Add(row);
                }
                list.Children.Add(Plain("Needs an update", rows, pickAll));
            }

            if (known.Count > 0 || unknown.Count > 0)
            {
                var dueSet = due.Select(d => d.M).ToHashSet();
                var cur = known.Where(k => !dueSet.Contains(k) || state.GetValueOrDefault(k) == "Updated").ToList();
                var chips = new WrapPanel { Margin = new Thickness(0, 4, 0, 12) };
                if (cur.Count > 0)
                {
                    var c = new ToggleButton { Content = $"Up to date ({cur.Count})", Style = Ui.St("Chip"), IsChecked = showCurrent, Margin = new Thickness(0, 0, 8, 0) };
                    c.Click += (_, _) => { showCurrent = c.IsChecked == true; Draw(); };
                    chips.Children.Add(c);
                }
                if (unknown.Count > 0)
                {
                    var c = new ToggleButton { Content = $"Not on Modrinth ({unknown.Count})", Style = Ui.St("Chip"), IsChecked = showOther, ToolTip = "Mods Modrinth does not know. Pentecost cannot update these." };
                    c.Click += (_, _) => { showOther = c.IsChecked == true; Draw(); };
                    chips.Children.Add(c);
                }
                list.Children.Add(chips);
                if (showCurrent && cur.Count > 0)
                {
                    var rows = new StackPanel();
                    foreach (var m in cur) rows.Children.Add(Line(m, m.Version.Length > 0 ? m.Version : m.BaseName, state.GetValueOrDefault(m) == "Updated" ? "Updated" : "Latest", "Faint", null, null, 32));
                    list.Children.Add(Plain("Up to date", rows));
                }
                if (showOther && unknown.Count > 0)
                {
                    var rows = new StackPanel();
                    foreach (var m in unknown) rows.Children.Add(Line(m, (m.Version.Length > 0 ? m.Version + "   " : "") + "no update source", null, "Faint", null, null, 32));
                    list.Children.Add(Plain("Not on Modrinth", rows));
                }
            }
        }

        var bar = FullBar("Updates", null, summary, again.With(new Thickness(0, 0, 8, 0)), all);
        scene.Picked += key =>
        {
            var m = due.Select(d => d.M).FirstOrDefault(x => x.BaseName == key);
            if (m != null) _ = Apply(new List<ModEntry> { m });
        };
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetRow(scene, 1);
        Grid.SetRowSpan(scene, 2);
        root.Children.Add(scene);
        root.Children.Add(bar);
        var listScroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list, Padding = new Thickness(0, 44, 14, 28), HorizontalAlignment = HorizontalAlignment.Left };
        Grid.SetRow(listScroll, 2);
        root.Children.Add(listScroll);
        OpenFull(root);
        Collect();
        Draw();
        if (mods.Any(m => !m.Looked)) _ = Check(false);
    }
}
