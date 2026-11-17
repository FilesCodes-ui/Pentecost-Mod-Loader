using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

/// <summary>
/// Full-page views. A mod or a Modrinth project opens over the whole page instead of in a small box. Back, Esc or any tab
/// leaves it. An installed mod also gets a file tree of its jar on the left.
/// </summary>
public partial class MainWindow
{
    bool inFull;

    /// <summary>Boxes draw with hard pixel edges, so screenshots inside them ask for smooth scaling.</summary>
    static Image Smooth(Image i)
    {
        RenderOptions.SetBitmapScalingMode(i, BitmapScalingMode.HighQuality);
        return i;
    }

    void OpenFull(FrameworkElement page)
    {
        HideModal();
        inFull = true;
        PageHost.Content = page;
        Enter(page, 26);
    }

    /// <summary>Goes back to the page the full view was opened from.</summary>
    void CloseFull()
    {
        if (!inFull) return;
        ShowPage(current, false);
        if (PageHost.Content is FrameworkElement el) Enter(el, -26);
    }

    /// <summary>The bar across the top of a full view: the way back, who this is, and the view's own buttons.</summary>
    FrameworkElement FullBar(string title, FrameworkElement? tile, TextBlock sub, params UIElement[] right)
    {
        var back = Ui.Btn("Back to " + current, "Secondary", (_, _) => CloseFull(), "");
        back.VerticalAlignment = VerticalAlignment.Center;
        var head = Ui.V(Ui.T(title, 19, bold: true, trim: true), sub);
        sub.Margin = new Thickness(0, 2, 0, 0);
        head.Margin = new Thickness(tile == null ? 20 : 14, 0, 16, 0);
        head.VerticalAlignment = VerticalAlignment.Center;
        var buttons = Ui.H(right);
        buttons.VerticalAlignment = VerticalAlignment.Center;
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star), MinWidth = 120 });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(back);
        if (tile != null)
        {
            var frame = new PixelBox { Background = Ui.Br("Panel"), BorderBrush = Ui.Br("Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(3), Notch = 2.5, Child = tile, Margin = new Thickness(20, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(frame, 1);
            g.Children.Add(frame);
        }
        Grid.SetColumn(head, 2);
        g.Children.Add(head);
        Grid.SetColumn(buttons, 3);
        g.Children.Add(buttons);
        var rule = Ui.Dotted();
        rule.VerticalAlignment = VerticalAlignment.Bottom;
        var bar = new Grid { Margin = new Thickness(0, 0, 0, 0) };
        g.Margin = new Thickness(0, 0, 0, 16);
        bar.Children.Add(g);
        bar.Children.Add(rule);
        return bar;
    }

    /// <summary>The bar, and under it either one wide page or a tree on the left with the page beside it.</summary>
    FrameworkElement FullLayout(FrameworkElement bar, StackPanel overview, Func<Action<FrameworkElement?>, FrameworkElement>? makeSide)
    {
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.Children.Add(bar);

        var scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = overview, Padding = new Thickness(0, 18, 14, 28),
        };
        if (makeSide == null)
        {
            Grid.SetRow(scroll, 1);
            root.Children.Add(scroll);
            return root;
        }

        var pane = new ContentControl { IsTabStop = false, HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch, Content = scroll };
        var side = makeSide(el =>
        {
            var next = el ?? scroll;
            pane.Content = next;
            Ui.FadeIn(next, 150);
        });
        var body = new Grid();
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(340) });
        body.ColumnDefinitions.Add(new ColumnDefinition());
        body.Children.Add(side);
        Grid.SetColumn(pane, 1);
        body.Children.Add(pane);
        Grid.SetRow(body, 1);
        root.Children.Add(body);
        return root;
    }

    // ---------------------------------------------------------------- a project that is not installed

    /// <summary>Opens a Modrinth project full screen. A project that is installed opens with its file tree.</summary>
    void ShowProject(string id, string type, string title, string? icon, string author, Action onInstalled, ModEntry? mod = null)
    {
        if (mod != null) { OpenMod(mod, onInstalled); return; }
        var overview = new StackPanel { MaxWidth = 960, HorizontalAlignment = HorizontalAlignment.Left };
        var facts = Ui.T(author.Length > 0 ? "by " + author : "", 12, "Faint", trim: true);
        var web = Ui.Btn("Open on Modrinth", "Secondary", (_, _) => OpenPath("https://modrinth.com/" + type + "/" + id));
        var bar = FullBar(title, Ui.Tile(icon, title, 48), facts, web);
        OpenFull(FullLayout(bar, overview, null));
        _ = FillProject(overview, facts, id, type, title, author, onInstalled, null);
    }

    /// <summary>Fills a page with what Modrinth says about a project, once Modrinth answers.</summary>
    async Task FillProject(StackPanel body, TextBlock facts, string id, string type, string title, string author, Action onInstalled, ModEntry? mod)
    {
        var loading = Ui.T("Loading from Modrinth", 12.5, "Muted");
        body.Children.Add(loading);
        try
        {
            var infoTask = Modrinth.Project(id, CancellationToken.None);
            var versionsTask = Modrinth.Versions(id, null, CancellationToken.None);
            var p = await infoTask;
            var versions = await versionsTask;
            body.Children.Remove(loading);
            facts.Text = (author.Length > 0 ? "by " + author + "    " : "") + Ui.Num(p.Downloads) + " downloads    " + Ui.Num(p.Follows) + " follows";

            // What it is: tags and the one-line description.
            var intro = new StackPanel();
            var tags = new WrapPanel();
            foreach (var c in p.Categories.Take(8)) { var pill = Ui.Pill(Ui.Nice(c)); pill.Margin = new Thickness(0, 0, 6, 4); tags.Children.Add(pill); }
            if (tags.Children.Count > 0) intro.Children.Add(tags);
            intro.Children.Add(Ui.T(p.Desc, 14, "Text", wrap: true).With(new Thickness(0, tags.Children.Count > 0 ? 8 : 0, 0, 0)));
            var introCard = Ui.Card(intro, 18);
            introCard.Margin = new Thickness(0, 0, 0, 14);
            body.Children.Add(introCard);

            if (mod?.Update is { } up)
            {
                var go = Ui.Btn("Update to " + up.VersionNumber, "Primary");
                go.VerticalAlignment = VerticalAlignment.Center;
                go.Click += async (_, _) =>
                {
                    go.IsEnabled = false;
                    go.Content = "Updating";
                    try
                    {
                        await Modrinth.ApplyUpdate(mod, _ => { }, CancellationToken.None);
                        Toast("Updated " + mod.Name);
                        onInstalled();
                        CloseFull();
                    }
                    catch (Exception ex)
                    {
                        AppLog.Write("update " + mod.Name + ": " + ex.Message);
                        Toast("Could not update it: " + ex.Message, "Bad");
                        go.Content = "Update to " + up.VersionNumber;
                        go.IsEnabled = true;
                    }
                };
                var text = Ui.V(Ui.T("An update is ready", 13.5, bold: true),
                    Ui.T((mod.Version.Length > 0 ? mod.Version + " to " : "Version ") + up.VersionNumber, 12, "Muted").With(new Thickness(0, 2, 0, 0)));
                var card = Ui.Card(Ui.Row(Ui.H(Ui.Dot("Warn").With(new Thickness(0, 0, 12, 0)), text), go), 16);
                card.BorderBrush = Ui.Br("Warn");
                card.Margin = new Thickness(0, 0, 0, 14);
                body.Children.Add(card);
            }

            if (p.Gallery.Length > 0)
            {
                var strip = new WrapPanel();
                var shots = p.Gallery.Take(10).ToList();
                foreach (var g in shots)
                    if (Ui.Picture(Modrinth.Thumb(g), 0, 380) is { } bi)
                    {
                        var link = g;
                        var thumb = Ui.Lift(new PixelBox
                        {
                            BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Notch = 2.5, Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(3),
                            Cursor = Cursors.Hand, ToolTip = "Click to see it full size",
                            Child = Smooth(new Image { Source = bi, Height = 140, Stretch = Stretch.Uniform }),
                        });
                        thumb.MouseLeftButtonUp += (_, e) => { e.Handled = true; ShowPicture(shots, shots.IndexOf(link), title); };
                        strip.Children.Add(thumb);
                    }
                body.Children.Add(Ui.Section("Pictures", strip));
            }

            var about = Plain(p.Body);
            if (about.Length > 0) body.Children.Add(Ui.Section("About", Ui.T(about, 13, "Soft", wrap: true)));

            if (mod != null) FileFacts(body, mod);

            var list = new StackPanel();
            foreach (var v in versions.Take(40))
            {
                var ver = v;
                var here = type != "modpack" && (File.Exists(Path.Combine(Modrinth.DirFor(type), v.FileName)) || File.Exists(Path.Combine(Modrinth.DirFor(type), v.FileName) + ".disabled"));
                var name = Ui.H(Ui.T(v.Number.Length > 0 ? v.Number : v.Name, 13.5, bold: true, trim: true),
                    Ui.Pill(v.Channel, v.Channel == "release" ? "Ok" : v.Channel == "beta" ? "Warn" : "Bad").With(new Thickness(10, 0, 0, 0)));
                var loaders = type is "mod" or "modpack" ? string.Join(", ", v.Loaders.Select(Ui.Nice)) + "    " : "";
                var sub = Ui.T(loaders + (v.Date.Year > 2000 ? v.Date.ToString("yyyy-MM-dd") + "    " : "") + Ui.Bytes(v.Size) + "    " + Ui.Num(v.Downloads) + " downloads", 11.5, "Faint", trim: true);
                sub.Margin = new Thickness(0, 3, 0, 0);
                var btn = Ui.Btn(here ? "Installed" : "Install", "Secondary");
                btn.IsEnabled = !here;
                btn.Padding = new Thickness(14, 5, 14, 5);
                btn.FontSize = 12;
                btn.VerticalAlignment = VerticalAlignment.Center;
                btn.Click += async (_, _) =>
                {
                    btn.IsEnabled = false;
                    btn.Content = "Installing";
                    try
                    {
                        if (type == "modpack")
                        {
                            Toast("Installing " + title + ". Its mods download in the background.");
                            var pack = await Packs.InstallVersion(ver, CancellationToken.None);
                            btn.Content = "Installed";
                            Toast(pack.Summary);
                            onInstalled();
                            return;
                        }
                        var added = await Modrinth.InstallVersion(ver, type, CancellationToken.None);
                        btn.Content = "Installed";
                        Toast(added.Count == 0 ? "That version is already there." : "Installed " + title + " " + ver.Number + (added.Count > 1 ? $" and {added.Count - 1} needed mods" : ""));
                        onInstalled();
                    }
                    catch (Exception ex)
                    {
                        AppLog.Write("install version " + ver.Id + ": " + ex.Message);
                        Toast("Could not install it: " + ex.Message, "Bad");
                        btn.Content = "Install";
                        btn.IsEnabled = true;
                    }
                };
                var row = Ui.Row(Ui.V(name, sub), btn);
                list.Children.Add(Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(8, 9, 8, 9), Child = row }));
            }
            if (versions.Count == 0) list.Children.Add(Ui.T("Nothing is published for Minecraft 1.20.1.", 12.5, "Faint"));
            body.Children.Add(Ui.Section("Versions for Minecraft 1.20.1", list));
        }
        catch (Exception ex)
        {
            AppLog.Write("project " + id + ": " + ex.Message);
            loading.Text = "Modrinth could not be reached: " + ex.Message;
            loading.Foreground = Ui.Br("Warn");
            if (mod != null) FileFacts(body, mod);
        }
    }

    // ---------------------------------------------------------------- an installed mod

    /// <summary>Opens an installed mod full screen: its page, and the files inside its jar as a tree.</summary>
    void ShowModInfo(ModEntry m, Action changed) => OpenMod(m, changed);

    void RememberSwitch(ModEntry m)
    {
        var p = ProfileStore.Active;
        if (p.On.Count + p.Off.Count == 0) return;
        p.On.RemoveAll(x => x.Equals(m.BaseName, StringComparison.OrdinalIgnoreCase));
        p.Off.RemoveAll(x => x.Equals(m.BaseName, StringComparison.OrdinalIgnoreCase));
        (m.Enabled ? p.On : p.Off).Add(m.BaseName);
        ProfileStore.Save();
    }

    void OpenMod(ModEntry m, Action changed)
    {
        var overview = new StackPanel { MaxWidth = 960, HorizontalAlignment = HorizontalAlignment.Left };
        var facts = Ui.T((m.Version.Length > 0 ? "Version " + m.Version + "    " : "") + (m.Authors.Length > 0 ? "by " + m.Authors : m.Loader), 12, "Faint", trim: true);

        var power = Ui.Btn(m.Enabled ? "Turn off" : "Turn on", "Secondary");
        power.Click += (_, _) =>
        {
            try
            {
                ModScan.SetEnabled(m, !m.Enabled);
                RememberSwitch(m);
                power.Content = m.Enabled ? "Turn off" : "Turn on";
                Toast(m.Name + (m.Enabled ? " is on." : " is off."));
                changed();
            }
            catch (Exception ex) { Toast("Could not change it. Close the game first. " + ex.Message, "Bad"); }
        };
        var show = Ui.Btn("Show the file", "Secondary", (_, _) => ShowInFolder(m.File));
        var remove = Ui.Btn("Remove", "Secondary", (_, _) => Confirm("Remove " + m.Name + "?", "The file goes to the Recycle Bin, so you can get it back from there.", "Remove", () =>
        {
            try { ModScan.Delete(m); Toast("Removed " + m.Name); changed(); CloseFull(); }
            catch (Exception ex) { Toast("Could not remove it: " + ex.Message, "Bad"); }
        }, true));
        remove.Foreground = Ui.Br("Bad");
        var web = m.ProjectId == null ? null : Ui.Btn("Open on Modrinth", "Secondary", (_, _) => OpenPath("https://modrinth.com/mod/" + m.ProjectId));

        var buttons = new List<UIElement>
        {
            power.With(new Thickness(0, 0, 8, 0)),
            show.With(new Thickness(0, 0, 8, 0)),
            remove,
        };

        var bar = FullBar(m.Name, Ui.Tile(m.IconUrl, m.Name, 48, m), facts, buttons.ToArray());
        OpenFull(FullLayout(bar, overview, setPane => ModTree(m, setPane)));

        if (web != null)
        {
            web.HorizontalAlignment = HorizontalAlignment.Left;
            web.Margin = new Thickness(0, 0, 0, 14);
            overview.Children.Add(web);
        }
        if (m.ProjectId != null) _ = FillProject(overview, facts, m.ProjectId, "mod", m.Name, m.Authors, changed, m);
        else FileFacts(overview, m);
    }

    /// <summary>What the jar says about itself: its loader, its file, what it needs and what needs it.</summary>
    void FileFacts(StackPanel outer, ModEntry m)
    {
        var body = new StackPanel();
        var tags = new WrapPanel();
        tags.Children.Add(Ui.Pill(m.Loader == "Both" ? "Forge and Fabric" : m.Loader));
        tags.Children.Add(Ui.Pill(m.Enabled ? "On" : "Off", m.Enabled ? "Ok" : "Muted"));
        if (m.Version.Length > 0) tags.Children.Add(Ui.Pill(m.Version));
        body.Children.Add(tags);
        if (m.Summary.Length > 0) body.Children.Add(Ui.T(m.Summary, 13, "Soft", wrap: true).With(new Thickness(0, 10, 0, 0)));
        else if (m.ProjectId == null) body.Children.Add(Ui.T("This mod says nothing about itself inside its file.", 13, "Faint", wrap: true).With(new Thickness(0, 10, 0, 0)));

        void Line(string label, string value)
        {
            var g = new Grid { Margin = new Thickness(0, 0, 0, 6) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(90) });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.Children.Add(Ui.T(label, 12, "Faint"));
            var v = Ui.T(value, 12, "Soft", trim: true);
            Grid.SetColumn(v, 1);
            g.Children.Add(v);
            body.Children.Add(g);
        }
        body.Children.Add(Ui.Dotted().With(new Thickness(0, 14, 0, 12)));
        Line("Name", m.BaseName);
        Line("Size", Ui.Bytes(m.Size));
        Line("Added", m.Stamp.ToLocalTime().ToString("yyyy-MM-dd HH:mm"));
        if (m.Id.Length > 0) Line("Mod id", m.Id);
        if (m.Sha1 != null) Line("SHA-1", m.Sha1);

        var here = ModScan.Scan();
        var byId = here.Where(x => x.Id.Length > 0).GroupBy(x => x.Id).ToDictionary(g => g.Key, g => g.First().Name);
        if (m.Needs.Count > 0)
        {
            body.Children.Add(Ui.Cap("It needs").With(new Thickness(0, 12, 0, 8)));
            var wrap = new WrapPanel();
            foreach (var id in m.Needs.Take(14))
            {
                var have = byId.TryGetValue(id, out var name);
                var pill = Ui.Pill(have ? name! : id + " (missing)", have ? "Ok" : "Warn");
                pill.Margin = new Thickness(0, 0, 6, 5);
                pill.ToolTip = have ? "In your mods folder" : "Not in your mods folder, so this mod might not start";
                wrap.Children.Add(pill);
            }
            body.Children.Add(wrap);
        }
        var neededBy = m.Id.Length == 0 ? new List<string>() : here.Where(x => x.Needs.Contains(m.Id)).Select(x => x.Name).Take(10).ToList();
        if (neededBy.Count > 0)
        {
            body.Children.Add(Ui.Cap("Needed by").With(new Thickness(0, 12, 0, 8)));
            var wrap = new WrapPanel();
            foreach (var n in neededBy) { var pill = Ui.Pill(n); pill.Margin = new Thickness(0, 0, 6, 5); wrap.Children.Add(pill); }
            body.Children.Add(wrap);
            body.Children.Add(Ui.T("Turning this off will likely stop those from working.", 11.5, "Faint", wrap: true).With(new Thickness(0, 6, 0, 0)));
        }

        if (m.ProjectId == null)
        {
            body.Children.Add(Ui.T("Modrinth does not know this exact file, so there are no updates or pictures for it. That is usual for a mod from CurseForge or one built by hand.",
                11.5, "Faint", wrap: true).With(new Thickness(0, 14, 0, 0)));
            var find = Ui.Btn("Look for it on Modrinth", "Secondary", (_, _) => { Go("Browse"); searchWith?.Invoke(m.Name); });
            find.HorizontalAlignment = HorizontalAlignment.Left;
            body.Children.Add(find.With(new Thickness(0, 12, 0, 0)));
        }
        outer.Children.Add(Ui.Section("Your copy", body));
    }
}
