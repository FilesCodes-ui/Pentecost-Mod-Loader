namespace Pentecost.Manager;

public partial class MainWindow
{
    FrameworkElement BuildProfiles()
    {
        var file = ProfileStore.Load();
        var page = new StackPanel();
        var import = Ui.Btn("Import pack", "Secondary", (_, _) => ImportPack(), "");
        import.ToolTip = "Open a Modrinth .mrpack, or a pack made with the game's Export pack button, as a new profile";
        var add = Ui.Btn("New profile", "Primary", (_, _) => EditProfile(null), "");
        var note = Ui.T("Each profile is its own setup: its own mods, mod settings, worlds, resource packs and shader packs. "
            + "Key binds, video settings and your account are shared by all of them.", 12.5, "Text", wrap: true);
        note.VerticalAlignment = VerticalAlignment.Center;
        note.Margin = new Thickness(0, 0, 24, 0);

        void FillRows()
        {
            page.Children.Clear();
            foreach (var p in file.Profiles.ToList())
            {
                var prof = p;
                var isActive = prof.Name == file.Active;
                var name = Ui.H(Ui.T(prof.Name, 15, bold: true));
                if (isActive) name.Children.Add(Ui.Pill("Active", "Ok").With(new Thickness(10, 1, 0, 0)));
                var mem = GameRun.MemoryText(prof, "Automatic");
                var (onCount, offCount, worlds, shaders, packs) = Contents(prof);
                var setText = (onCount + offCount == 0 ? "No mods" : $"{onCount} mods on" + (offCount > 0 ? $", {offCount} off" : ""))
                    + (shaders > 0 ? "   |   " + (shaders == 1 ? "1 shader pack" : shaders + " shader packs") : "")
                    + (packs > 0 ? "   |   " + (packs == 1 ? "1 resource pack" : packs + " resource packs") : "")
                    + "   |   " + (worlds == 1 ? "1 world" : worlds + " worlds");
                var sub = Ui.T($"Memory {mem}   |   {setText}", 12.5, "Text", wrap: true);
                sub.Margin = new Thickness(0, 5, 0, 0);
                var info = Ui.V(name, sub);
                if (prof.FromPack.Length > 0)
                    info.Children.Add(Ui.T("From " + prof.FromPack, 11.5, "Soft", trim: true).With(new Thickness(0, 4, 0, 0)));
                if (prof.JvmArgs.Trim().Length > 0)
                    info.Children.Add(Ui.T(prof.JvmArgs, 11.5, "Soft", trim: true).With(new Thickness(0, 4, 0, 0)));
                info.Margin = new Thickness(0, 0, 16, 0);
                info.VerticalAlignment = VerticalAlignment.Center;

                void Use()
                {
                    if (isActive) return;
                    profileSwitch?.Invoke(prof.Name);
                }

                var use = Ui.Btn(isActive ? "In use" : "Use", isActive ? "Secondary" : "Primary", (_, _) => Use());
                use.IsEnabled = !isActive;
                use.Padding = new Thickness(18, 7, 18, 7);
                use.ToolTip = isActive ? "This profile is already active" : "Make this the active profile";
                ToolTipService.SetShowOnDisabled(use, true);
                var edit = Ui.Btn("Edit", "Secondary", (_, _) => EditProfile(prof));
                edit.Padding = new Thickness(18, 7, 18, 7);
                var export = Ui.Btn("Export", "Secondary", (_, _) => ExportPack(prof));
                export.Padding = new Thickness(18, 7, 18, 7);
                export.ToolTip = "Save this profile as a Modrinth pack file to share";
                var del = Ui.IconBtn("", file.Profiles.Count > 1 ? "Delete this profile" : "The last profile cannot be deleted",
                    (_, _) => Confirm("Delete " + prof.Name + "?", "Its folder, with its mods, settings and worlds, goes to the Recycle Bin.", "Delete", () =>
                    {
                        Chart().Sink(prof.Name, () =>
                        {
                            var err = ProfileStore.Remove(prof);
                            if (err != null) Toast(err, "Warn"); else Toast("Deleted " + prof.Name);
                            Refresh();
                        });
                    }, true));
                del.Style = Ui.St("Danger");
                var folder = Ui.Btn("Folder", "Secondary", (_, _) => OpenFolder(Instances.Folder(prof)));
                folder.Padding = new Thickness(18, 7, 18, 7);
                folder.ToolTip = "Open this profile's own folder";
                del.FontFamily = (FontFamily)FindResource("Icons");
                del.Width = 40;
                del.IsEnabled = file.Profiles.Count > 1;
                ToolTipService.SetShowOnDisabled(del, true);
                static Border Plate(FrameworkElement b) => new() { Background = new SolidColorBrush(Color.FromArgb(0xE8, 0x0B, 0x0B, 0x0C)), Child = b };
                var buttons = Ui.H(Plate(use), Plate(edit).With(new Thickness(8, 0, 0, 0)), Plate(folder).With(new Thickness(8, 0, 0, 0)), Plate(export).With(new Thickness(8, 0, 0, 0)), Plate(del).With(new Thickness(6, 0, 0, 0)));
                export.IsEnabled = isActive;
                if (!isActive) export.ToolTip = "Use this profile first, then export it";
                ToolTipService.SetShowOnDisabled(export, true);
                buttons.VerticalAlignment = VerticalAlignment.Center;

                buttons.HorizontalAlignment = HorizontalAlignment.Left;
                buttons.Margin = new Thickness(0, 12, 0, 0);
                var card = Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(6, 16, 6, 16), Child = Ui.V(info, buttons) });
                card.MouseEnter += (_, _) => Chart().Highlight(prof.Name);
                card.MouseLeave += (_, _) => Chart().Highlight(null);
                card.ToolTip = isActive ? null : "Double-click to use this profile";
                card.MouseLeftButtonUp += (_, e) => { if (e.ClickCount == 2) Use(); };
                if (isActive) card.BorderBrush = Ui.Br("Edge");
                page.Children.Add(card);
            }
        }
        FillRows();
        var chart = Chart();
        if (chart.Parent is Panel was) was.Children.Remove(chart);
        voyage ??= new Voyage { Margin = new Thickness(-16, -24, -28, -28) };
        if (voyage.Parent is Panel wasV) wasV.Children.Remove(voyage);
        var count = Ui.T("", 13, "Text");
        void Scenes()
        {
            chart.SetIslands(file.Profiles.Select(pr =>
            {
                var (on, off, worlds, shaders, packs) = Contents(pr);
                return (pr.Name, on + off, worlds, shaders + packs, pr.Name == file.Active);
            }).ToList());
            voyage.SetVoyage(file.Profiles.Count, file.Active);
            count.Text = file.Profiles.Count == 1 ? "1 profile" : file.Profiles.Count + " profiles, " + file.Active + " active";
        }
        Scenes();
        profileSwitch = target =>
        {
            var err = ProfileStore.SetActive(target);
            if (err != null) { Toast(err, "Warn"); return; }
            Toast("Profile " + target + " is active.");
            FillRows();
            Scenes();
            RefreshBar();
        };
        count.FontFamily = (FontFamily)FindResource("Mono");
        var tools = Ui.H(import.With(new Thickness(0, 0, 8, 0)), add);
        tools.Margin = new Thickness(0, 18, 0, 14);
        note.Margin = new Thickness(0, 14, 0, 0);
        var head = Ui.V(new Wordmark { Text = "PROFILES", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left },
            count.With(new Thickness(2, 10, 0, 0)), note, tools, Ui.Dotted());

        var log = new Grid();
        log.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        log.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetRowSpan(voyage, 2);
        log.Children.Add(voyage);
        log.Children.Add(head);
        var list = Scroll(page);
        Grid.SetRow(list, 1);
        log.Children.Add(list);
        void Veil()
        {
            var total = voyage.ActualHeight;
            if (total < 1) return;
            var below = head.ActualHeight + Math.Min(page.ActualHeight, list.ActualHeight) + 24 + 10;
            voyage.Veil = Math.Clamp(below / total, 0, 1);
        }
        log.SizeChanged += (_, _) => Veil();
        page.SizeChanged += (_, _) => Veil();

        var root = new Grid();
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star), MinWidth = 380 });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(32) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(430) });
        root.Children.Add(chart);
        Grid.SetColumn(log, 2);
        root.Children.Add(log);
        return root;
    }

    SeaChart Chart()
    {
        if (seaChart != null) return seaChart;
        seaChart = new SeaChart { Margin = new Thickness(-34, -24, 0, -28) };
        seaChart.Picked += name => profileSwitch?.Invoke(name);
        return seaChart;
    }

    SeaChart? seaChart;
    Voyage? voyage;

    Action<string>? profileSwitch;

    static (int On, int Off, int Worlds, int Shaders, int Packs) Contents(ModProfile p)
    {
        try
        {
            var dir = Instances.Dir(p);
            var mods = Path.Combine(dir, "mods");
            var on = Directory.Exists(mods) ? Directory.EnumerateFiles(mods, "*.jar").Count() : 0;
            var off = Directory.Exists(mods) ? Directory.EnumerateFiles(mods, "*.jar.disabled").Count() : 0;
            var saves = Path.Combine(dir, "saves");
            var worlds = Directory.Exists(saves) ? Directory.EnumerateDirectories(saves).Count() : 0;
            int Count(string sub)
            {
                var d = Path.Combine(dir, sub);
                return Directory.Exists(d) ? Directory.EnumerateFileSystemEntries(d).Count(e => !e.EndsWith(".txt", StringComparison.OrdinalIgnoreCase)) : 0;
            }
            return (on, off, worlds, Count("shaderpacks"), Count("resourcepacks"));
        }
        catch { return (0, 0, 0, 0, 0); }
    }

    void EditProfile(ModProfile? existing)
    {
        var isNew = existing == null;
        var name = new TextBox { Text = existing?.Name ?? "" };
        var args = new TextBox { Text = existing?.JvmArgs ?? "" };
        var mem = existing?.MemoryGb ?? 0;
        var reserve = existing?.ReserveGb ?? -1;
        var maxGb = (int)Math.Max(2, Math.Floor(GameRun.TotalGb * 0.75));
        var seg = new WrapPanel();
        foreach (var gb in new[] { 0, 2, 3, 4, 6, 8, 12, 16 }.Where(g => g == 0 || g <= maxGb || g == mem))
        {
            var v = gb;
            var rb = new RadioButton { Content = gb == 0 ? "Auto" : gb + " GB", Style = Ui.St("Seg"), GroupName = "mem", IsChecked = gb == mem, Margin = new Thickness(0, 0, 0, 6) };
            rb.Click += (_, _) => mem = v;
            seg.Children.Add(rb);
        }
        var memNote = Ui.T($"This computer has about {GameRun.TotalGb:0} GB. More than {maxGb} GB is not offered, so Windows keeps enough. "
            + "Auto leaves the number to the game's own Settings screen.", 11.5, "Faint", wrap: true);
        memNote.Margin = new Thickness(0, 6, 0, 0);
        var reserveSeg = new WrapPanel();
        foreach (var gb in new[] { -1, 0, 1, 2, 3, 4, 6, 8 }.Concat(reserve > 8 ? new[] { reserve } : Array.Empty<int>()))
        {
            var v = gb;
            var rb = new RadioButton { Content = gb < 0 ? "Auto" : gb == 0 ? "None" : gb + " GB", Style = Ui.St("Seg"), GroupName = "reserve", IsChecked = gb == reserve, Margin = new Thickness(0, 0, 0, 6) };
            rb.Click += (_, _) => reserve = v;
            reserveSeg.Children.Add(rb);
        }
        var reserveNote = Ui.T("Extra room above Memory, for spikes. The game stays near Memory and reaches into the backup only when it runs short. "
            + "Auto picks an amount for this computer and always leaves 2 GB free for Windows.", 11.5, "Faint", wrap: true);
        reserveNote.Margin = new Thickness(0, 6, 0, 0);

        var mods = "none";
        var modBox = new StackPanel();
        var modNote = Ui.T("", 11.5, "Faint", wrap: true);
        modNote.Margin = new Thickness(0, 8, 0, 0);
        var activeName = ProfileStore.Active.Name;

        void Pick(string value)
        {
            mods = value;
            modNote.Text = value == "copy"
                ? $"A copy of {activeName}: its mods, mod settings and packs. Worlds start empty. The copy is separate from then on."
                : "A fresh setup: no mods, no worlds. Pentecost's own settings come along.";
        }

        var choices = new WrapPanel();
        foreach (var (label, value, tip) in new[]
        {
            ("Start empty", "none", "No mods and no worlds: build it up from nothing"),
            ("Copy " + activeName, "copy", "Its mods, settings and packs, as a separate copy"),
        })
        {
            var v = value;
            var rb = new RadioButton { Content = label, Style = Ui.St("Seg"), GroupName = "mods", IsChecked = v == "none", ToolTip = tip, Margin = new Thickness(0, 0, 0, 6) };
            rb.Click += (_, _) => Pick(v);
            modBox.Children.Add(rb);
        }
        var modRow = new WrapPanel();
        foreach (var rb in modBox.Children.OfType<RadioButton>().ToList()) { modBox.Children.Remove(rb); modRow.Children.Add(rb); }
        Pick("none");

        var error = Ui.T("", 12, "Bad", wrap: true);
        error.Margin = new Thickness(0, 10, 0, 0);
        var cancel = Ui.Btn("Cancel", "Secondary", (_, _) => HideModal());

        void Save()
        {
            var wanted = name.Text.Trim();
            if (wanted.Length == 0) { error.Text = "Give the profile a name."; name.Focus(); return; }
            if (ProfileStore.Load().Profiles.Any(q => q != existing && q.Name.Equals(wanted, StringComparison.OrdinalIgnoreCase))) { error.Text = "Another profile already has that name."; return; }
            var p = existing ?? new ModProfile();
            var wasActive = existing != null && ProfileStore.Load().Active == existing.Name;
            p.Name = wanted;
            p.MemoryGb = mem;
            p.ReserveGb = reserve;
            p.JvmArgs = args.Text.Trim();
            if (existing == null)
            {
                Instances.Create(p, mods == "copy");
                ProfileStore.Load().Profiles.Add(p);
            }
            if (wasActive) ProfileStore.Load().Active = p.Name;
            ProfileStore.Save();
            HideModal();
            Toast(isNew ? "Profile " + p.Name + " created." : "Profile saved.");
            Refresh();
        }

        var save = Ui.Btn(isNew ? "Create" : "Save", "Primary", (_, _) => Save());
        var buttons = Ui.H(cancel, save.With(new Thickness(10, 0, 0, 0)));
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        name.KeyDown += (_, e) => { if (e.Key == Key.Enter) Save(); };
        args.KeyDown += (_, e) => { if (e.Key == Key.Enter) Save(); };

        var box = Ui.V(Ui.T(isNew ? "New profile" : "Edit profile", 17, bold: true),
            Ui.Cap("Name").With(new Thickness(0, 20, 0, 6)), name,
            Ui.Cap("Memory").With(new Thickness(0, 18, 0, 8)), seg, memNote,
            Ui.Cap("Backup memory").With(new Thickness(0, 18, 0, 8)), reserveSeg, reserveNote,
            Ui.Cap("Starts with").With(new Thickness(0, 18, 0, 8)), modRow, modNote,
            Ui.Cap("Extra Java arguments").With(new Thickness(0, 18, 0, 6)), args,
            error, buttons.With(new Thickness(0, 22, 0, 0)));
        box.Width = 540;
        ShowModal(Ui.Card(box, 26));
        name.Focus();
        name.SelectAll();
    }
}
