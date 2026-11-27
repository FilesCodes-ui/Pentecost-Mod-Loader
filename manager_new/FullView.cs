using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public partial class MainWindow
{
    bool inFull;

    ModBanner? currentBanner;
    Action? bannerNote;

    FrameworkElement BannerBar(string title, FrameworkElement? tile, TextBlock sub, string? projectId, params UIElement[] right)
    {
        var banner = new ModBanner(title) { Margin = new Thickness(-34, -24, -28, 0) };
        currentBanner = banner;
        var root = new Grid { Height = 236 };
        root.Children.Add(banner);

        var back = Ui.Btn("Back to " + current, "Secondary", (_, _) => CloseFull(), "\uE72B");
        back.HorizontalAlignment = HorizontalAlignment.Left;
        back.Background = Ui.Br("Bg");
        foreach (var b in right.OfType<Button>()) if (b.Background is null or SolidColorBrush { Color.A: < 255 }) b.Background = Ui.Br("Bg");
        var buttons = Ui.H(right);
        buttons.VerticalAlignment = VerticalAlignment.Center;
        var top = Ui.Row(back, buttons);
        top.VerticalAlignment = VerticalAlignment.Top;
        root.Children.Add(top);

        var upper = title.ToUpperInvariant();
        FrameworkElement name = upper.Length <= 24 && upper.All(ch => ch == ' ' || Pixels.FineGlyph(ch) != null)
            ? new Wordmark { Text = upper, Pixel = upper.Length <= 12 ? 3 : 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left }
            : Ui.T(title, 30, bold: true, trim: true);
        sub.FontFamily = (FontFamily)FindResource("Mono");
        sub.FontSize = 12.5;
        sub.Foreground = Ui.Br("Soft");
        sub.Margin = new Thickness(2, 10, 0, 0);
        var text = Ui.V(name, sub);
        text.VerticalAlignment = VerticalAlignment.Bottom;

        var lower = new Grid { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 22) };
        lower.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        lower.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        lower.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        if (tile != null)
        {
            var frame = new PixelBox { Background = Ui.Br("Panel"), BorderBrush = Ui.Br("Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(3), Notch = 2.5, Child = tile, Margin = new Thickness(0, 0, 18, 0), VerticalAlignment = VerticalAlignment.Bottom };
            lower.Children.Add(frame);
        }
        Grid.SetColumn(text, 1);
        lower.Children.Add(text);
        var noteHost = new ContentControl { IsTabStop = false, VerticalAlignment = VerticalAlignment.Bottom };
        Grid.SetColumn(noteHost, 2);
        lower.Children.Add(noteHost);
        void Note()
        {
            var n = Feed.Mod(projectId)?["note"]?.ToString();
            noteHost.Content = string.IsNullOrWhiteSpace(n) ? null : PinnedNote(n);
        }
        Note();
        bannerNote = Note;
        root.Children.Add(lower);

        var rule = Ui.Dotted();
        rule.VerticalAlignment = VerticalAlignment.Bottom;
        root.Children.Add(rule);
        return root;
    }

    static FrameworkElement PinnedNote(string note)
    {
        var head = Ui.H(Ui.Glyph("\uE718", 11, "Text"), Ui.Cap("DEV NOTE").With(new Thickness(8, 0, 0, 0)));
        var body = Ui.V(head, Ui.T(note, 12.5, "Soft", wrap: true).With(new Thickness(0, 8, 0, 0)));
        return new PixelBox
        {
            Background = Ui.Br("Panel"), BorderBrush = Ui.Br("Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(14, 12, 14, 12),
            Notch = 2.5, Child = body, MaxWidth = 360, Margin = new Thickness(18, 0, 0, 0),
        };
    }

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

    void CloseFull()
    {
        if (!inFull) return;
        ShowPage(current, false);
        if (PageHost.Content is FrameworkElement el) Enter(el, -26);
    }

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

        scroll.Padding = new Thickness(30, 18, 14, 28);
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

    void ShowProject(string id, string type, string title, string? icon, string author, Action onInstalled, ModEntry? mod = null)
    {
        if (mod != null) { OpenMod(mod, onInstalled); return; }
        var overview = new StackPanel();
        var facts = Ui.T(author.Length > 0 ? "by " + author : "", 12, "Faint", trim: true);
        var web = Ui.Btn("Open on Modrinth", "Secondary", (_, _) => OpenPath("https://modrinth.com/" + type + "/" + id));
        var tile = (Border)Ui.Tile(icon, title, 56);
        bannerTile = icon == null ? tile : null;
        var bar = BannerBar(title, tile, facts, id, web);
        OpenFull(FullLayout(bar, overview, null));
        FeedHost(overview, id, title, type, new[] { id, title });
        var (main, side) = PageColumns(overview);
        _ = FillProject(main, facts, id, type, title, author, onInstalled, null, side);
    }

    Border? bannerTile;

    async Task FillProject(StackPanel body, TextBlock facts, string id, string type, string title, string author, Action onInstalled, ModEntry? mod, StackPanel? side = null)
    {
        side ??= body;
        var loading = Ui.T("Loading from Modrinth", 12.5, "Muted");
        body.Children.Add(loading);
        try
        {
            var infoTask = Modrinth.Project(id, CancellationToken.None);
            var versionsTask = Modrinth.Versions(id, null, CancellationToken.None);
            var p = await infoTask;
            currentBanner?.SetImage(p.Gallery.FirstOrDefault() ?? p.IconUrl);
            if (bannerTile != null && p.IconUrl != null)
            {
                var fresh = (Border)Ui.Tile(p.IconUrl, title, 56);
                var inner = fresh.Child;
                fresh.Child = null;
                bannerTile.Child = inner;
                bannerTile = null;
            }
            var versions = await versionsTask;
            body.Children.Remove(loading);
            devVersions?.Invoke(versions.Select(v => v.Number).ToList());
            facts.Text = (author.Length > 0 ? "by " + author + "    " : "") + Ui.Num(p.Downloads) + " downloads    " + Ui.Num(p.Follows) + " follows";

            var tags = new WrapPanel { Margin = new Thickness(0, 0, 0, 10) };
            foreach (var c in p.Categories.Take(8)) tags.Children.Add(Ui.Cap(Ui.Nice(c).ToUpperInvariant()).With(new Thickness(0, 0, 16, 4)));
            if (tags.Children.Count > 0) body.Children.Add(tags);
            body.Children.Add(Ui.T(p.Desc, 18, "Text", wrap: true).With(new Thickness(0, 0, 0, 26)));
            if (type == "modpack") body.Children.Add(PackInside(versions));

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
                var strip = new Border
                {
                    BorderBrush = Ui.Br("Warn"), BorderThickness = new Thickness(2, 0, 0, 0), Padding = new Thickness(16, 6, 0, 6), Margin = new Thickness(0, 0, 0, 26),
                    Child = Ui.Row(text, go),
                };
                body.Children.Add(strip);
            }

            if (p.Gallery.Length > 0)
            {
                var shots = p.Gallery.Take(12).ToList();
                var gallery = new StackPanel();
                if (Ui.Picture(shots[0], 1400) is { } lead)
                {
                    var big = new PixelBox
                    {
                        BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Notch = 2.5, Padding = new Thickness(3), Cursor = Cursors.Hand,
                        ToolTip = "Click to see it full size", HorizontalAlignment = HorizontalAlignment.Left,
                        Child = Smooth(new Image { Source = lead, MaxHeight = 380, Stretch = Stretch.Uniform }),
                    };
                    big.MouseLeftButtonUp += (_, e) => { e.Handled = true; ShowPicture(shots, 0, title); };
                    gallery.Children.Add(big);
                }
                var strip = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) };
                for (var i = 1; i < shots.Count; i++)
                    if (Ui.Picture(Modrinth.Thumb(shots[i]), 0, 240) is { } bi)
                    {
                        var at = i;
                        var thumb = Ui.Lift(new PixelBox
                        {
                            BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Notch = 2.5, Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(2),
                            Cursor = Cursors.Hand, ToolTip = "Click to see it full size",
                            Child = Smooth(new Image { Source = bi, Height = 72, Stretch = Stretch.Uniform }),
                        });
                        thumb.MouseLeftButtonUp += (_, e) => { e.Handled = true; ShowPicture(shots, at, title); };
                        strip.Children.Add(thumb);
                    }
                if (strip.Children.Count > 0) gallery.Children.Add(strip);
                body.Children.Add(PlainSection("Pictures", gallery));
            }

            if (p.Body.Trim().Length > 0) body.Children.Add(PlainSection("About", AboutView(p.Body, title)));

            if (mod != null) FileFacts(body, mod);

            async Task InstallVersionWith(VersionInfo ver, Button btn)
            {
                if (!await FeedAllowsInstall(id, title, ver.Number)) return;
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
            }
            bool Here(VersionInfo v) => type != "modpack" && (File.Exists(Path.Combine(Modrinth.DirFor(type), v.FileName)) || File.Exists(Path.Combine(Modrinth.DirFor(type), v.FileName) + ".disabled"));

            var best = versions.FirstOrDefault(v => v.Channel == "release") ?? versions.FirstOrDefault();
            if (best != null && mod == null)
            {
                var have = Here(best);
                var get = Ui.Btn(have ? "Installed" : type == "modpack" ? "Install as a new profile" : "Install " + best.Number, "Primary");
                get.HorizontalAlignment = HorizontalAlignment.Stretch;
                get.Padding = new Thickness(16, 11, 16, 11);
                get.IsEnabled = !have;
                if (Feed.Locked(id, best.Number) is { } why) { get.Content = "Blocked"; get.IsEnabled = false; get.ToolTip = why; ToolTipService.SetShowOnDisabled(get, true); }
                get.Click += async (_, _) => await InstallVersionWith(best, get);
                var note = Ui.T((type is "mod" or "modpack" ? string.Join(", ", best.Loaders.Select(Ui.Nice)) + ", " : "") + Ui.Bytes(best.Size)
                    + (best.Channel != "release" ? ", " + best.Channel : "")
                    + (versions.Count > 0 && versions[0].Date.Year > 2000 ? ", updated " + versions[0].Date.ToString("yyyy-MM-dd") : ""), 12, "Faint", trim: true);
                note.Margin = new Thickness(2, 8, 0, 26);
                side.Children.Add(get);
                side.Children.Add(note);
            }

            var list = new StackPanel();
            const int first = 8;
            void Rows(int count)
            {
                list.Children.Clear();
                foreach (var v in versions.Take(count))
                {
                    var ver = v;
                    var here = Here(v);
                    var dot = Ui.Dot(v.Channel == "release" ? "Ok" : v.Channel == "beta" ? "Warn" : "Bad", 6);
                    dot.VerticalAlignment = VerticalAlignment.Center;
                    dot.ToolTip = v.Channel;
                    var name = Ui.H(dot, Ui.T(v.Number.Length > 0 ? v.Number : v.Name, 13, bold: true, trim: true).With(new Thickness(8, 0, 0, 0)));
                    var sub = Ui.T((v.Date.Year > 2000 ? v.Date.ToString("yyyy-MM-dd") + "   " : "") + Ui.Bytes(v.Size), 11.5, "Faint", trim: true);
                    sub.Margin = new Thickness(14, 2, 0, 0);
                    var btn = Ui.Btn(here ? "Installed" : "Install", "Ghost");
                    btn.IsEnabled = !here;
                    btn.Padding = new Thickness(10, 4, 10, 4);
                    btn.FontSize = 12;
                    btn.VerticalAlignment = VerticalAlignment.Center;
                    if (Feed.Locked(id, v.Number) != null) { btn.Content = "Blocked"; btn.IsEnabled = false; btn.ToolTip = Feed.Locked(id, v.Number); ToolTipService.SetShowOnDisabled(btn, true); }
                    btn.Click += async (_, _) => await InstallVersionWith(ver, btn);
                    var row = Ui.Row(Ui.V(name, sub), btn);
                    list.Children.Add(Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(2, 8, 2, 8), Child = row }));
                }
                if (versions.Count > count)
                {
                    var more = Ui.Btn("Show all " + versions.Count, "Ghost", (_, _) => Rows(versions.Count));
                    more.HorizontalAlignment = HorizontalAlignment.Left;
                    more.Margin = new Thickness(0, 8, 0, 0);
                    list.Children.Add(more);
                }
                if (versions.Count == 0) list.Children.Add(Ui.T("Nothing is published for Minecraft 1.20.1.", 12.5, "Faint"));
            }
            Rows(first);
            side.Children.Add(PlainSection("Versions", list));
        }
        catch (Exception ex)
        {
            AppLog.Write("project " + id + ": " + ex.Message);
            loading.Text = "Modrinth could not be reached: " + ex.Message;
            loading.Foreground = Ui.Br("Warn");
            if (mod != null) FileFacts(body, mod);
        }
    }

    static (StackPanel Main, StackPanel Side) PageColumns(StackPanel overview)
    {
        var g = new Grid { Margin = new Thickness(0, 6, 0, 0) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star), MaxWidth = 780 });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(320) });
        var main = new StackPanel();
        var side = new StackPanel();
        g.Children.Add(main);
        Grid.SetColumn(side, 2);
        g.Children.Add(side);
        overview.Children.Add(g);
        return (main, side);
    }

    static FrameworkElement PlainSection(string title, UIElement body)
    {
        var rule = Ui.Dotted();
        rule.Margin = new Thickness(0, 8, 0, 14);
        var v = Ui.V(Ui.Cap(title.ToUpperInvariant()), rule, body);
        v.Margin = new Thickness(0, 0, 0, 30);
        return v;
    }

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
        var overview = new StackPanel();
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
        var remove = Ui.Btn("Remove", "Secondary", (_, _) => Confirm("Remove " + m.Name + "?", "The file goes to the Recycle Bin.", "Remove", () =>
        {
            try { ModScan.Delete(m); Toast("Removed " + m.Name); changed(); CloseFull(); }
            catch (Exception ex) { Toast("Could not remove it: " + ex.Message, "Bad"); }
        }, true));
        remove.Foreground = Ui.Br("Bad");
        var web = m.ProjectId == null ? null : Ui.Btn("Open on Modrinth", "Secondary", (_, _) => OpenPath("https://modrinth.com/mod/" + m.ProjectId));

        var buttons = new List<UIElement>();
        buttons.AddRange(new List<UIElement>
        {
            power.With(new Thickness(0, 0, 8, 0)),
            show.With(new Thickness(0, 0, 8, 0)),
            remove,
        });

        var bar = BannerBar(m.Name, Ui.Tile(m.IconUrl, m.Name, 56, m), facts, m.ProjectId, buttons.ToArray());
        if (m.ProjectId == null) currentBanner?.SetImage(m.IconUrl);
        OpenFull(FullLayout(bar, overview, setPane => ModTree(m, setPane)));
        FeedHost(overview, m.ProjectId, m.Name, "mod", new[] { m.Id, m.Name, m.BaseName.Split('-', '_', ' ')[0] });

        var (main, side) = PageColumns(overview);
        if (web != null)
        {
            web.HorizontalAlignment = HorizontalAlignment.Stretch;
            web.Margin = new Thickness(0, 0, 0, 26);
            side.Children.Add(web);
        }
        if (m.ProjectId != null) _ = FillProject(main, facts, m.ProjectId, "mod", m.Name, m.Authors, changed, m, side);
        else FileFacts(main, m);
    }

    void FeedHost(StackPanel overview, string? projectId, string name, string type, IEnumerable<string> keys)
    {
        var host = new ContentControl { IsTabStop = false };
        devVersions = null;
        void Fill() => host.Content = projectId != null && DevAccess.Unlocked
            ? DevModPanel(projectId, name, type, keys)
            : FeedModSection(projectId);
        Fill();
        overview.Children.Insert(0, host);
        RefreshFullFeed = () => { Fill(); bannerNote?.Invoke(); };
    }

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
        if (m.Needs.Count > 0)
        {
            body.Children.Add(Ui.Cap("It needs").With(new Thickness(0, 12, 0, 8)));
            var wrap = new WrapPanel();
            foreach (var id in m.Needs.Take(14))
            {
                var found = DepMatch.Find(here, id);
                var have = found != null;
                var name = found?.Name;
                var pill = Ui.Pill(have ? name! : id + " (missing)", have ? "Ok" : "Warn");
                pill.Margin = new Thickness(0, 0, 6, 5);
                pill.ToolTip = have ? "In your mods folder" : "Not in your mods folder, so this mod might not start";
                wrap.Children.Add(pill);
            }
            body.Children.Add(wrap);
        }
        var neededBy = m.Id.Length == 0 ? new List<string>() : here.Where(x => x != m && x.Needs.Any(n => DepMatch.Provides(m, n))).Select(x => x.Name).Take(10).ToList();
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
