using System.Windows.Controls.Primitives;
using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow
{
    FrameworkElement BuildBrowse()
    {
        var root = new Grid();
        for (var r = 0; r < 5; r++) root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var type = "mod";
        var loader = Settings.BrowseLoader;
        var sort = Settings.BrowseSort;
        var cats = new HashSet<string>();
        var hideInstalled = false;
        var hits = new List<Hit>();
        var shown = new HashSet<string>();
        var cols = 3;
        var offset = 0;
        var total = 0;
        var busy = false;
        CancellationTokenSource? cts = null;
        var installedIds = new HashSet<string>();
        var installedFiles = new Dictionary<string, HashSet<string>>();
        var unknownFiles = new HashSet<string>();
        var packs = new HashSet<string>();
        var working = new HashSet<string>();
        var allCats = new List<Category>();
        var paged = true;
        var pageNo = 0;
        const int PageSize = 100;
        var pager = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 4), Visibility = Visibility.Collapsed };

        var harbor = new Harbor { Height = 270, Margin = new Thickness(-34, -24, -28, 0) };
        var headCount = Ui.T("Modrinth and CurseForge, Minecraft 1.20.1", 13, "Soft");
        headCount.FontFamily = (FontFamily)FindResource("Mono");
        var status = Ui.T("", 12.5, "Muted", trim: true);
        status.VerticalAlignment = VerticalAlignment.Center;
        var progress = new ProgressBar { Style = Ui.St("Bar"), Margin = new Thickness(0, 10, 0, 0) };
        int Rows() => (hits.Count + cols - 1) / cols;
        LazyList lazy = null!;
        lazy = new LazyList(150, CardRow);
        lazy.View.Margin = new Thickness(0, 10, 0, 0);

        var boxHost = Ui.SearchBox("Search Modrinth and CurseForge", out var box);
        var typing = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        typing.Tick += (_, _) => { typing.Stop(); _ = Search(true); };
        box.TextChanged += (_, _) => { typing.Stop(); typing.Start(); };
        box.KeyDown += (_, e) => { if (e.Key == Key.Enter) { typing.Stop(); _ = Search(true); } };
        searchFocus["Browse"] = () => { box.Focus(); box.SelectAll(); };
        searchWith = term => { box.Text = term; typing.Stop(); _ = Search(true); };

        RadioButton Seg(string label, string group, bool on, Action pick)
        {
            var rb = new RadioButton { Content = label, GroupName = group, Style = Ui.St("Seg"), IsChecked = on };
            rb.Click += (_, _) => pick();
            return rb;
        }

        StackPanel loaders = null!; WrapPanel catPanel = null!; ToggleButton filtersBtn = null!;
        var types = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var (label, kind) in new[] { ("MODS", "mod"), ("SHADERS", "shader"), ("RESOURCE PACKS", "resourcepack"), ("MODPACKS", "modpack") })
        {
            var k = kind;
            var mark = new Wordmark { Text = label, Pixel = 1, Fill = Ui.Br(k == "mod" ? "Text" : "Muted") };
            var rb = new RadioButton { GroupName = "bt", Style = Ui.St("Tab"), IsChecked = k == "mod", Margin = new Thickness(0, 0, 4, 0), Content = mark };
            rb.Checked += (_, _) => { mark.Fill = Ui.Br("Text"); if (type != k) SetType(k); };
            rb.Unchecked += (_, _) => mark.Fill = Ui.Br("Muted");
            types.Children.Add(rb);
        }
        loaders = Ui.H(Seg("All", "bl", loader == "All", () => { loader = "All"; _ = Search(true); }),
            Seg("Forge", "bl", loader == "Forge", () => { loader = "Forge"; _ = Search(true); }),
            Seg("Fabric", "bl", loader == "Fabric", () => { loader = "Fabric"; _ = Search(true); }),
            Seg("Quilt", "bl", loader == "Quilt", () => { loader = "Quilt"; _ = Search(true); }));
        var sorts = Ui.H(Seg("Relevance", "bs", sort == "relevance", () => { sort = "relevance"; _ = Search(true); }),
            Seg("Downloads", "bs", sort == "downloads", () => { sort = "downloads"; _ = Search(true); }),
            Seg("Follows", "bs", sort == "follows", () => { sort = "follows"; _ = Search(true); }),
            Seg("Newest", "bs", sort == "newest", () => { sort = "newest"; _ = Search(true); }),
            Seg("Updated", "bs", sort == "updated", () => { sort = "updated"; _ = Search(true); }));

        catPanel = new WrapPanel { Margin = new Thickness(0, 8, 0, 0), Visibility = Visibility.Collapsed };
        filtersBtn = new ToggleButton { Content = "Filters", Style = Ui.St("Chip"), VerticalAlignment = VerticalAlignment.Center, ToolTip = "Show or hide the category filters" };
        filtersBtn.Click += (_, _) =>
        {
            catPanel.Visibility = filtersBtn.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            if (filtersBtn.IsChecked == true) Ui.Rise(catPanel, 6, 180);
        };
        var hideBtn = new ToggleButton { Content = "Hide installed", Style = Ui.St("Chip"), VerticalAlignment = VerticalAlignment.Center };
        hideBtn.Click += (_, _) => { hideInstalled = hideBtn.IsChecked == true; _ = Search(true); };

        void FillCats()
        {
            catPanel.Children.Clear();
            foreach (var name in allCats.Where(c => c.Type == type).Select(c => c.Name).Distinct())
            {
                var n = name;
                var chip = new ToggleButton { Content = Ui.Nice(n), Style = Ui.St("Chip"), IsChecked = cats.Contains(n) };
                chip.Click += (_, _) =>
                {
                    if (chip.IsChecked == true) cats.Add(n); else cats.Remove(n);
                    filtersBtn.Content = cats.Count > 0 ? $"Filters ({cats.Count})" : "Filters";
                    _ = Search(true);
                };
                catPanel.Children.Add(chip);
            }
            if (catPanel.Children.Count == 0) catPanel.Children.Add(Ui.T("Categories could not be loaded.", 12, "Faint"));
        }

        void SetType(string t)
        {
            type = t;
            cats.Clear();
            filtersBtn.Content = "Filters";
            FillCats();
            loaders.Visibility = t is "mod" or "modpack" ? Visibility.Visible : Visibility.Collapsed;
            _ = Search(true);
        }

        async Task LoadCats()
        {
            try { allCats = await Modrinth.Categories(CancellationToken.None); }
            catch (Exception ex) { AppLog.Write("categories: " + ex.Message); }
            FillCats();
        }

        var paging = Ui.H(Seg("Pages", "bp", true, () => { paged = true; _ = Search(true); }),
            Seg("Endless", "bp", false, () => { paged = false; _ = Search(true); }));

        var headText = Ui.V(new Wordmark { Text = "BROWSE", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left },
            headCount.With(new Thickness(2, 10, 0, 0)));
        headText.HorizontalAlignment = HorizontalAlignment.Left;
        headText.VerticalAlignment = VerticalAlignment.Bottom;
        headText.Margin = new Thickness(2, 0, 0, 46);
        headText.IsHitTestVisible = false;
        var head = new Grid();
        head.Children.Add(harbor);
        head.Children.Add(headText);
        root.Children.Add(head);
        harbor.Picked += id =>
        {
            if (hits.FirstOrDefault(x => x.ProjectId == id) is not { } h) return;
            ShowProject(h.ProjectId, h.Type, h.Title, h.IconUrl, h.Author, () => { unknownFiles.Add(h.ProjectId); installedIds.Add(h.ProjectId); Redraw(h); });
        };

        var searchRow = new Grid { Margin = new Thickness(0, 4, 0, 0) };
        searchRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        searchRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        searchRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        searchRow.Children.Add(types);
        boxHost.Margin = new Thickness(18, 0, 18, 0);
        boxHost.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(boxHost, 1);
        searchRow.Children.Add(boxHost);
        Grid.SetColumn(status, 2);
        searchRow.Children.Add(status);
        Grid.SetRow(searchRow, 1);
        root.Children.Add(searchRow);
        var chips = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
        foreach (var c in new FrameworkElement[] { loaders, sorts, paging, filtersBtn, hideBtn }) { c.Margin = new Thickness(0, 0, 14, 6); chips.Children.Add(c); }
        Grid.SetRow(chips, 2);
        root.Children.Add(chips);
        Grid.SetRow(catPanel, 3);
        root.Children.Add(catPanel);
        Grid.SetRow(progress, 4);
        root.Children.Add(progress);
        Grid.SetRow(lazy.View, 5);
        root.Children.Add(lazy.View);
        Grid.SetRow(pager, 6);
        root.Children.Add(pager);

        var top = Ui.Btn("", "Primary", (_, _) => lazy.View.ScrollToTop(), "");
        top.ToolTip = "Back to the top";
        top.Padding = new Thickness(13, 10, 13, 10);
        top.HorizontalAlignment = HorizontalAlignment.Right;
        top.VerticalAlignment = VerticalAlignment.Bottom;
        top.Margin = new Thickness(0, 0, 24, 18);
        top.Visibility = Visibility.Collapsed;
        Grid.SetRow(top, 5);
        root.Children.Add(top);

        lazy.View.ScrollChanged += (_, _) =>
        {
            var want = lazy.View.VerticalOffset > 500;
            if (want && top.Visibility != Visibility.Visible) { top.Visibility = Visibility.Visible; Ui.Rise(top, 8, 160); }
            else if (!want) top.Visibility = Visibility.Collapsed;
            if (!paged && !busy && offset < total && lazy.NearEnd) _ = Search(false);
        };
        lazy.View.SizeChanged += (_, _) =>
        {
            var n = Math.Max(1, (int)(lazy.View.ActualWidth / 340));
            if (n == cols) return;
            cols = n;
            lazy.SetCount(Rows());
            lazy.InvalidateAll();
        };

        void DrawPager()
        {
            pager.Children.Clear();
            var pages = paged ? Math.Min(500, (total + PageSize - 1) / PageSize) : 0;
            pager.Visibility = pages > 1 ? Visibility.Visible : Visibility.Collapsed;
            if (pages <= 1) return;
            Button PageBtn(string text, string style, int to, bool on = true)
            {
                var b = Ui.Btn(text, style, (_, _) => { if (to != pageNo) { pageNo = to; _ = Search(true, true); } });
                b.Padding = new Thickness(11, 5, 11, 5);
                b.FontSize = 12;
                b.MinWidth = 34;
                b.Margin = new Thickness(0, 0, 4, 0);
                b.IsEnabled = on;
                return b;
            }
            pager.Children.Add(PageBtn("Previous", "Secondary", pageNo - 1, pageNo > 0));
            var wanted = new SortedSet<int> { 0, pages - 1 };
            for (var q = pageNo - 2; q <= pageNo + 2; q++) if (q >= 0 && q < pages) wanted.Add(q);
            var before = -1;
            foreach (var q in wanted)
            {
                if (q - before > 1) { var gap = Ui.T("...", 12, "Faint"); gap.Margin = new Thickness(4, 0, 8, 0); gap.VerticalAlignment = VerticalAlignment.Center; pager.Children.Add(gap); }
                pager.Children.Add(PageBtn((q + 1).ToString(), q == pageNo ? "Primary" : "Ghost", q));
                before = q;
            }
            pager.Children.Add(PageBtn("Next", "Secondary", pageNo + 1, pageNo < pages - 1));
        }

        async Task Search(bool fresh, bool keepPage = false)
        {
            if (!fresh && busy) return;
            cts?.Cancel();
            var my = cts = new CancellationTokenSource();
            if (fresh) { if (!keepPage) pageNo = 0; offset = paged ? pageNo * PageSize : 0; total = 0; hits.Clear(); shown.Clear(); lazy.Reset(); }
            busy = true;
            progress.IsIndeterminate = true;
            status.Text = fresh ? "Searching" : "Loading more";
            try
            {
                if (fresh) await RefreshInstalled(false);
                var page = await Catalog.Search(box.Text.Trim(), type, loader, cats.ToList(), sort, offset, my.Token);
                if (my.IsCancellationRequested) return;
                total = paged ? page.Total : page.Hits.Count == 0 ? offset : page.Total;
                offset += PageSize;
                var lastRow = Rows() - 1;
                foreach (var h in page.Hits.OrderByDescending(x => Feed.Flag(x.ProjectId, "pin")))
                    {
                    var twin = h.CurseId == null ? null : "cf:" + h.CurseId;
                    if (!shown.Contains(h.ProjectId) && (twin == null || !shown.Contains(twin))
                        && (!hideInstalled || !installedIds.Contains(h.ProjectId)) && !Feed.Flag(h.ProjectId, "hide"))
                    {
                        hits.Add(h);
                        shown.Add(h.ProjectId);
                        if (twin != null) shown.Add(twin);
                    }
                }
                lazy.SetCount(Rows());
                if (lastRow >= 0) lazy.Invalidate(lastRow);
                if (fresh)
            {
                harbor.SetCargo(hits.Take(3).Select(x => (x.ProjectId, x.Title)));
                headCount.Text = (hits.Count == 0 ? "Nothing found" : $"{Ui.Num(total)} for 1.20.1 on " + (Catalog.CurseOn && type != "modpack" ? "Modrinth and CurseForge" : "Modrinth"))
                    + (page.Note != null ? ", " + page.Note : "");
            }
            status.Text = hits.Count == 0 ? "Nothing found" : paged ? $"Page {pageNo + 1} of {Math.Clamp((total + PageSize - 1) / PageSize, 1, 500)}, {Ui.Num(total)} results" : $"{hits.Count} of {Ui.Num(total)}";
                DrawPager();
            }
            catch (OperationCanceledException) { return; }
            catch (Exception ex) { AppLog.Write("browse: " + ex.Message); status.Text = "Search failed: " + ex.Message; total = offset; }
            finally { if (cts == my) { busy = false; progress.IsIndeterminate = false; progress.Value = 0; } }
            if (!paged && cts == my && offset < total)
                _ = Dispatcher.BeginInvoke(DispatcherPriority.Background, () => { if (!paged && !busy && offset < total && lazy.NearEnd) _ = Search(false); });
        }

        void Redraw(Hit h)
        {
            var i = hits.IndexOf(h);
            if (i >= 0) lazy.Invalidate(i / cols);
        }

        static string Bare(string file) => file.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? file[..^9] : file;

        static bool FileThere(string name)
        {
            foreach (var dir in new[] { Paths.Mods, Modrinth.DirFor("shader"), Modrinth.DirFor("resourcepack") })
            {
                var p = Path.Combine(dir, name);
                if (File.Exists(p) || File.Exists(p + ".disabled")) return true;
            }
            return false;
        }

        async Task RefreshInstalled(bool deleted)
        {
            var mods = await Task.Run(ModScan.Scan);
            var known = installedFiles.ToDictionary(kv => kv.Key, kv => kv.Value.ToList());
            var next = await Task.Run(() =>
            {
                var map = new Dictionary<string, HashSet<string>>();
                foreach (var m in mods)
                    if (m.ProjectId != null)
                    {
                        if (!map.TryGetValue(m.ProjectId, out var set)) map[m.ProjectId] = set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                        set.Add(Bare(Path.GetFileName(m.File)));
                    }
                foreach (var (siteFile, siteId) in Catalog.Installed())
                    if (FileThere(siteFile))
                    {
                        if (!map.TryGetValue(siteId, out var held)) map[siteId] = held = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                        held.Add(siteFile);
                    }
                foreach (var (id, files) in known)
                {
                    var still = files.Where(FileThere).ToList();
                    if (still.Count == 0) continue;
                    if (!map.TryGetValue(id, out var set)) map[id] = set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                    foreach (var f in still) set.Add(f);
                }
                return map;
            });
            if (deleted) unknownFiles.Clear();
            installedFiles = next;
            installedIds.Clear();
            foreach (var id in next.Keys) installedIds.Add(id);
            foreach (var id in unknownFiles) installedIds.Add(id);
            foreach (var id in packs) installedIds.Add(id);
            lazy.InvalidateAll();
        }

        var watchers = new List<FileSystemWatcher>();
        var settle = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(600) };
        var sawDelete = false;
        settle.Tick += (_, _) => { settle.Stop(); var d = sawDelete; sawDelete = false; _ = RefreshInstalled(d); };
        void Changed(bool delete) => Dispatcher.BeginInvoke(() => { sawDelete |= delete; settle.Stop(); settle.Start(); });
        root.Loaded += (_, _) =>
        {
            if (watchers.Count > 0) return;
            foreach (var dir in new[] { Paths.Mods, Modrinth.DirFor("shader"), Modrinth.DirFor("resourcepack") })
            {
                try
                {
                    Directory.CreateDirectory(dir);
                    var w = new FileSystemWatcher(dir) { IncludeSubdirectories = false, NotifyFilter = NotifyFilters.FileName };
                    w.Created += (_, _) => Changed(false);
                    w.Deleted += (_, _) => Changed(true);
                    w.Renamed += (_, _) => Changed(true);
                    w.EnableRaisingEvents = true;
                    watchers.Add(w);
                }
                catch (Exception ex) { AppLog.Write("browse watch " + dir + ": " + ex.Message); }
            }
            _ = RefreshInstalled(true);
        };
        root.Unloaded += (_, _) =>
        {
            foreach (var w in watchers) w.Dispose();
            watchers.Clear();
            settle.Stop();
        };

        async Task Install(Hit h, string loaderName)
        {
            if (working.Contains(h.ProjectId) || installedIds.Contains(h.ProjectId)) return;
            if (!await FeedAllowsInstall(h.ProjectId, h.Title, null)) return;
            working.Add(h.ProjectId);
            Redraw(h);
            try
            {
                if (h.Type == "modpack")
                {
                    Toast("Installing " + h.Title + ". Its mods download in the background.");
                    var pack = await Packs.InstallFromModrinth(h.ProjectId, loader == "All" ? null : loader.ToLowerInvariant(), CancellationToken.None);
                    packs.Add(h.ProjectId);
                    harbor.Unload();
                    installedIds.Add(h.ProjectId);
                    Toast(pack.Summary);
                    return;
                }
                var added = h.Type == "mod"
                    ? await Modrinth.Install(h.ProjectId, loaderName, _ => { }, CancellationToken.None)
                    : await Modrinth.InstallOther(h.ProjectId, h.Type, CancellationToken.None);
                Catalog.Remember(h.ProjectId, added);
                if (added.Count > 0) installedFiles[h.ProjectId] = new HashSet<string>(new[] { Bare(added[0]) }, StringComparer.OrdinalIgnoreCase);
                else unknownFiles.Add(h.ProjectId);
                installedIds.Add(h.ProjectId);
                if (added.Count > 0) harbor.Unload();
                Toast(added.Count == 0 ? h.Title + " was already there."
                    : h.Type == "mod" ? "Installed " + h.Title + (added.Count > 1 ? $" and {added.Count - 1} needed mods" : "")
                    : "Installed " + h.Title + ". Pick it in the game's options.");
            }
            catch (Exception ex)
            {
                AppLog.Write("install " + h.Slug + ": " + ex.Message);
                Toast("Could not install " + h.Title + ": " + ex.Message, "Bad");
            }
            finally { working.Remove(h.ProjectId); Redraw(h); }
        }

        FrameworkElement CardRow(int r)
        {
            var g = new Grid { Height = 138, VerticalAlignment = VerticalAlignment.Top };
            for (var c = 0; c < cols; c++) g.ColumnDefinitions.Add(new ColumnDefinition());
            for (var c = 0; c < cols; c++)
            {
                var i = r * cols + c;
                if (i >= hits.Count) break;
                var card = HitCard(hits[i]);
                card.Margin = new Thickness(c == 0 ? 0 : 6, 0, c == cols - 1 ? 0 : 6, 0);
                Grid.SetColumn(card, c);
                g.Children.Add(card);
            }
            return g;
        }

        FrameworkElement HitCard(Hit h)
        {
            var title = Ui.T(h.Title, 14.5, bold: true, trim: true);
            var by = Ui.T("by " + h.Author, 11.5, "Faint", trim: true);
            by.Margin = new Thickness(0, 1, 0, 0);
            var desc = Ui.T(h.Desc, 12, "Muted", wrap: true);
            desc.MaxHeight = 33;
            desc.TextTrimming = TextTrimming.CharacterEllipsis;
            desc.Margin = new Thickness(0, 6, 0, 0);
            var text = Ui.V(title, by, desc);
            text.Margin = new Thickness(12, 0, 0, 0);
            var tile = Ui.Tile(h.IconUrl, h.Title, 48);
            tile.VerticalAlignment = VerticalAlignment.Top;
            var upper = new Grid();
            upper.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            upper.ColumnDefinitions.Add(new ColumnDefinition());
            upper.Children.Add(tile);
            Grid.SetColumn(text, 1);
            upper.Children.Add(text);

            var options = h.Type == "mod"
                ? new[] { "forge", "fabric", "quilt" }.Where(l => h.Loaders.Contains(l)).ToArray()
                : Array.Empty<string>();
            if (options.Length == 0) options = new[] { h.Type == "mod" ? "forge" : "" };
            var installed = installedIds.Contains(h.ProjectId);
            var isWorking = working.Contains(h.ProjectId);

            Button btn;
            if (installed) { btn = Ui.Btn("Installed", "Secondary"); btn.IsEnabled = false; }
            else if (isWorking) { btn = Ui.Btn("Installing", "Secondary"); btn.IsEnabled = false; }
            else if (options.Length <= 1)
                btn = Ui.Btn("Install", "Primary", async (_, _) => await Install(h, options[0]), "");
            else
            {
                btn = Ui.Btn("Install", "Primary", null, "");
                btn.ToolTip = "This mod has a " + string.Join(" and a ", options.Select(Ui.Nice)) + " build";
                Ui.Menu(btn, () => options.Select(l => ("Install the " + Ui.Nice(l) + " build", (Action)(() => _ = Install(h, l)), false)));
            }            btn.Padding = new Thickness(12, 5, 12, 5);
            btn.FontSize = 11.5;

            var metaStats = Ui.H(Ui.Glyph("", 10, "Faint"), Ui.T(Ui.Num(h.Downloads), 11.5, "Faint").With(new Thickness(5, 0, 12, 0)),
                Ui.Glyph("", 10, "Faint"), Ui.T(Ui.Num(h.Follows), 11.5, "Faint").With(new Thickness(5, 0, 0, 0)));
            var meta = Ui.H();
            if (Catalog.CurseOn)
            {
                var site = Ui.Pill(Catalog.Short(h.Sites));
                site.ToolTip = "Found on " + Catalog.SiteName(h.Sites);
                site.Margin = new Thickness(0, 0, 6, 0);
                meta.Children.Add(site);
            }
            foreach (var pill in FeedPills(h.ProjectId).Take(Catalog.CurseOn ? 1 : 2)) meta.Children.Add(pill);
            meta.Children.Add(metaStats);
            if (h.Type == "modpack" && Settings.PackDetails)
            {
                metaStats.Visibility = Visibility.Collapsed;
                var packLine = Ui.T("Reading the pack", 11.5, "Faint", trim: true);
                meta.Children.Add(packLine);
                _ = FillPackLine(packLine, h.ProjectId);
            }
            meta.VerticalAlignment = VerticalAlignment.Center;
            var lower = Ui.Row(meta, btn);
            lower.VerticalAlignment = VerticalAlignment.Bottom;

            var inner = new Grid();
            inner.Children.Add(upper);
            inner.Children.Add(lower);
            var card = Ui.Lift(Ui.SheetCard(inner, 14));
            card.Cursor = Cursors.Hand;
            card.MouseLeftButtonUp += (_, _) => ShowProject(h.ProjectId, h.Type, h.Title, h.IconUrl, h.Author, () => { unknownFiles.Add(h.ProjectId); installedIds.Add(h.ProjectId); Redraw(h); });
            Ui.HoverCard(upper, () => Peek.Card(h, installedIds.Contains(h.ProjectId), FeedPills(h.ProjectId).ToList(),
                Feed.Mod(h.ProjectId)?["warn"]?["text"]?.ToString()));
            return card;
        }

        _ = LoadCats();
        _ = Search(true);
        return root;
    }
}
