namespace Pentecost.Manager;

public partial class MainWindow
{
    FrameworkElement BuildProfiles()
    {
        var file = ProfileStore.Load();
        var page = new StackPanel();
        var import = Ui.Btn("Import pack", "Secondary", (_, _) => ImportPack(), "");
        import.ToolTip = "Open a Modrinth .mrpack file and make a profile from it";
        var add = Ui.Btn("New profile", "Primary", (_, _) => EditProfile(null), "");
        page.Children.Add(Header("Profiles", "Memory, Java arguments and mod lists.",
            import.With(new Thickness(0, 0, 8, 0)), add));
        var note = Ui.T("Pressing Play switches your mods to match the active profile. A profile that manages no mods leaves them alone.", 12.5, "Faint", wrap: true);
        note.Margin = new Thickness(0, 16, 0, 16);
        page.Children.Add(note);

        foreach (var p in file.Profiles.ToList())
        {
            var prof = p;
            var isActive = prof.Name == file.Active;
            var name = Ui.H(Ui.T(prof.Name, 15, bold: true));
            if (isActive) name.Children.Add(Ui.Pill("Active", "Ok").With(new Thickness(10, 1, 0, 0)));
            var mem = prof.MemoryGb > 0 ? prof.MemoryGb + " GB" : "Automatic, " + GameRun.AutoMemory().Heap + " GB";
            var setText = prof.On.Count + prof.Off.Count == 0
                ? "Leaves your mods alone"
                : prof.On.Count == 0 ? $"Turns all {prof.Off.Count} mods off" : $"{prof.On.Count} mods on, {prof.Off.Count} off";
            var sub = Ui.T($"Memory {mem}   |   {setText}", 12.5, "Muted", trim: true);
            sub.Margin = new Thickness(0, 5, 0, 0);
            var info = Ui.V(name, sub);
            if (prof.JvmArgs.Trim().Length > 0)
                info.Children.Add(Ui.T(prof.JvmArgs, 11.5, "Faint", trim: true).With(new Thickness(0, 4, 0, 0)));
            info.Margin = new Thickness(0, 0, 16, 0);
            info.VerticalAlignment = VerticalAlignment.Center;

            void Use()
            {
                if (isActive) return;
                ProfileStore.SetActive(prof.Name);
                Toast("Profile " + prof.Name + " is active.");
                Refresh();
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
                (_, _) => Confirm("Delete " + prof.Name + "?", "Only the profile is deleted. Your mods and worlds stay as they are.", "Delete", () =>
                {
                    ProfileStore.Remove(prof);
                    Toast("Deleted " + prof.Name);
                    Refresh();
                }, true));
            del.Style = Ui.St("Danger");
            del.FontFamily = (FontFamily)FindResource("Icons");
            del.Width = 40;
            del.IsEnabled = file.Profiles.Count > 1;
            ToolTipService.SetShowOnDisabled(del, true);
            var buttons = Ui.H(use, edit.With(new Thickness(8, 0, 0, 0)), export.With(new Thickness(8, 0, 0, 0)), del.With(new Thickness(6, 0, 0, 0)));
            buttons.VerticalAlignment = VerticalAlignment.Center;

            var card = Ui.Lift(Ui.Card(Ui.Row(info, buttons), 18));
            card.Margin = new Thickness(0, 0, 0, 10);
            card.ToolTip = isActive ? null : "Double-click to use this profile";
            card.MouseLeftButtonUp += (_, e) => { if (e.ClickCount == 2) Use(); };
            if (isActive) card.BorderBrush = Ui.Br("Edge");
            page.Children.Add(card);
        }
        return Scroll(page);
    }

    /// <summary>One box for both a new and an existing profile.</summary>
    void EditProfile(ModProfile? existing)
    {
        var isNew = existing == null;
        var name = new TextBox { Text = existing?.Name ?? "" };
        var args = new TextBox { Text = existing?.JvmArgs ?? "" };
        var mem = existing?.MemoryGb ?? 0;
        var maxGb = (int)Math.Max(2, Math.Floor(GameRun.TotalGb * 0.75));
        var seg = new WrapPanel();
        foreach (var gb in new[] { 0, 2, 3, 4, 6, 8, 12, 16 }.Where(g => g == 0 || g <= maxGb || g == mem))
        {
            var v = gb;
            var rb = new RadioButton { Content = gb == 0 ? "Auto" : gb + " GB", Style = Ui.St("Seg"), GroupName = "mem", IsChecked = gb == mem, Margin = new Thickness(0, 0, 0, 6) };
            rb.Click += (_, _) => mem = v;
            seg.Children.Add(rb);
        }
        var memNote = Ui.T($"This computer has about {GameRun.TotalGb:0} GB. More than {maxGb} GB is not offered, so Windows keeps enough.", 11.5, "Faint", wrap: true);
        memNote.Margin = new Thickness(0, 6, 0, 0);

        // Which mods the profile starts with. This used to happen silently, which was a surprise.
        var mods = "keep";
        var modBox = new StackPanel();
        var modNote = Ui.T("", 11.5, "Faint", wrap: true);
        modNote.Margin = new Thickness(0, 8, 0, 0);
        var count = ModScan.Scan().Count;

        void Pick(string value)
        {
            mods = value;
            modNote.Text = value switch
            {
                "copy" => $"The profile remembers the {count} mods in your folder exactly as they are now. Switching to it later puts them back that way.",
                "none" => $"All {count} mods start switched off. Turn on the ones you want, and the profile remembers.",
                _ => "The profile does not touch your mods. Pressing Play leaves whatever is on, on.",
            };
        }

        var choices = new WrapPanel();
        foreach (var (label, value, tip) in new[]
        {
            ("Leave mods alone", "keep", "The profile changes memory and Java arguments only"),
            ("Copy the mods on now", "copy", "Remember today's mods, so you can come back to them"),
            ("Start with none on", "none", "Switch every mod off, then build the list up"),
        })
        {
            var v = value;
            var rb = new RadioButton { Content = label, Style = Ui.St("Seg"), GroupName = "mods", IsChecked = isNew ? v == "keep" : false, ToolTip = tip, Margin = new Thickness(0, 0, 0, 6) };
            rb.Click += (_, _) => Pick(v);
            modBox.Children.Add(rb);
        }
        var modRow = new WrapPanel();
        foreach (var rb in modBox.Children.OfType<RadioButton>().ToList()) { modBox.Children.Remove(rb); modRow.Children.Add(rb); }
        Pick(isNew ? "keep" : "leave");

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
            p.JvmArgs = args.Text.Trim();
            if (mods == "copy") ProfileStore.Capture(p);
            else if (mods == "none") ProfileStore.CaptureNone(p);
            else if (isNew) { p.On.Clear(); p.Off.Clear(); }
            if (existing == null) ProfileStore.Load().Profiles.Add(p);
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
            Ui.Cap(isNew ? "Mods" : "Change the mods in this profile").With(new Thickness(0, 18, 0, 8)), modRow, modNote,
            Ui.Cap("Extra Java arguments").With(new Thickness(0, 18, 0, 6)), args,
            error, buttons.With(new Thickness(0, 22, 0, 0)));
        box.Width = 540;
        ShowModal(Ui.Card(box, 26));
        name.Focus();
        name.SelectAll();
    }
}
