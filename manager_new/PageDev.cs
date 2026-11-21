using System.Globalization;
using System.Text.Json.Nodes;
using System.Windows.Controls.Primitives;
using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow
{
    static TextBox Field1(string text = "", int max = 200) =>
        new() { Text = text, Height = 36, VerticalContentAlignment = VerticalAlignment.Center, Padding = new Thickness(10, 0, 10, 0), MaxLength = max };

    static TextBox FieldN(string text = "", double height = 90, int max = 4000) =>
        new() { Text = text, MinHeight = height, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(10, 8, 10, 8), MaxLength = max };

    static FrameworkElement Labeled(string label, UIElement field, string? hint = null)
    {
        var p = Ui.V(Ui.Cap(label).With(new Thickness(0, 0, 0, 6)), field);
        if (hint != null) p.Children.Add(Ui.T(hint, 11.5, "Faint", wrap: true).With(new Thickness(0, 4, 0, 0)));
        p.Margin = new Thickness(0, 0, 0, 14);
        return p;
    }

    static Grid Hinted(TextBox box, string hint)
    {
        var ph = Ui.T(hint, 13, "Faint");
        ph.IsHitTestVisible = false;
        ph.Margin = new Thickness(12, box.AcceptsReturn ? 9 : 0, 0, 0);
        ph.VerticalAlignment = box.AcceptsReturn ? VerticalAlignment.Top : VerticalAlignment.Center;
        void Sync() => ph.Visibility = box.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        box.TextChanged += (_, _) => Sync();
        Sync();
        var g = new Grid();
        g.Children.Add(box);
        g.Children.Add(ph);
        return g;
    }

    static ToggleButton Chip(string text, bool on, string? tip = null)
    {
        var c = new ToggleButton { Content = text, Style = Ui.St("Chip"), IsChecked = on, Margin = new Thickness(0, 0, 6, 6) };
        if (tip != null) c.ToolTip = tip;
        return c;
    }

    const string TimeFormat = "yyyy-MM-dd HH:mm";

    static string When(long ms) => ms > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime.ToString(TimeFormat) : "";

    static long? ParseWhen(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return 0;
        return DateTime.TryParseExact(text.Trim(), TimeFormat, CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var t)
            ? new DateTimeOffset(t).ToUnixTimeMilliseconds() : null;
    }

    static long NowMs => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    void Live(string what, Action<JsonObject> edit)
    {
        if (testing || !DevAccess.Unlocked) return;
        var before = Feed.Change(edit);
        Toast(what + ". Live in a moment.", "Ok", "Undo", () => { Feed.Restore(before); Toast("Undone."); RefreshFullFeed?.Invoke(); if (current == "Dev" || current == "Play") Refresh(); });
    }

    Action? RefreshFullFeed;

    Action<List<string>>? devVersions;

    FrameworkElement DevModPanel(string projectId, string name, string type, IEnumerable<string> keys)
    {
        JsonObject Now() => Feed.Mod(projectId) is JsonObject o ? o : new JsonObject();
        void Edit(string what, Action<JsonObject> change) => Live(what, d =>
        {
            var mods = d["mods"] as JsonObject;
            if (mods == null) d["mods"] = mods = new JsonObject();
            if (mods[projectId] is not JsonObject m) mods[projectId] = m = new JsonObject();
            m["name"] = name;
            change(m);
            m["updated"] = NowMs;
            if (!m.Any(kv => kv.Key is not "name" and not "updated")) mods.Remove(projectId);
        });

        var body = new StackPanel();
        var state = Ui.T("", 11.5, "Faint");
        void ShowState() => state.Text = Feed.Uploading ? "Uploading" : "Live";
        ShowState();
        var head = Ui.Row(DevMark("Your notes. Players see this"), state);
        body.Children.Add(head);
        body.Children.Add(Ui.Dotted().With(new Thickness(0, 10, 0, 12)));

        var note = FieldN(Now()["note"]?.ToString() ?? "", 64, 2000);
        note.BorderThickness = new Thickness(1);
        var noteTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1300) };
        void CommitNote()
        {
            noteTimer.Stop();
            var text = note.Text.Trim();
            if (text == (Now()["note"]?.ToString() ?? "")) return;
            Edit(text.Length == 0 ? "Note removed" : "Note saved", m => { if (text.Length == 0) m.Remove("note"); else m["note"] = text; });
            ShowState();
        }
        noteTimer.Tick += (_, _) => CommitNote();
        note.TextChanged += (_, _) => { noteTimer.Stop(); noteTimer.Start(); };
        note.LostKeyboardFocus += (_, _) => CommitNote();
        body.Children.Add(Hinted(note, "Write a note for everyone who opens this mod"));

        var chips = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
        void Flag(string label, string key, string tip)
        {
            var c = Chip(label, Now()[key]?.GetValue<bool>() == true, tip);
            c.Click += (_, _) =>
            {
                var on = c.IsChecked == true;
                Edit(on ? label + " on" : label + " off", m => { if (on) m[key] = true; else m.Remove(key); });
                ShowState();
            };
            chips.Children.Add(c);
        }
        Flag("Dev pick", "recommended", "Shows a Dev pick badge on this mod everywhere");
        Flag("Tested", "tested", "Tested with Pentecost");
        Flag("Pin to top", "pin", "First on Browse pages it shows on");
        Flag("Hide from Browse", "hide", "For malware or stolen mods. Players can still see it if it's installed.");
        var warnChip = Chip("Warning", Now()["warn"] != null, "Warn players, or block bad versions");
        chips.Children.Add(warnChip);
        if (type == "modpack")
        {
            var featId = "feat-" + projectId;
            var featured = Feed.Posts("featured").Any(p => p["id"]?.ToString() == featId);
            var feat = Chip("Feature on Home", featured, "Shows this pack on everyone's Home");
            feat.Click += (_, _) =>
            {
                var on = feat.IsChecked == true;
                Live(on ? "Featured on Home" : "No longer featured", d =>
                {
                    var posts = d["posts"] as JsonArray ?? new JsonArray();
                    d["posts"] = posts;
                    foreach (var old in posts.OfType<JsonObject>().Where(p => p["id"]?.ToString() == featId).ToList()) posts.Remove(old);
                    if (on) posts.Add(new JsonObject { ["id"] = featId, ["kind"] = "featured", ["title"] = name, ["body"] = Now()["note"]?.ToString() ?? "", ["project"] = projectId, ["type"] = "modpack" });
                });
            };
            chips.Children.Add(feat);
        }
        body.Children.Add(chips);

        var warn = new StackPanel { Margin = new Thickness(0, 6, 0, 0), Visibility = Now()["warn"] != null ? Visibility.Visible : Visibility.Collapsed };
        var w0 = Now()["warn"] as JsonObject;
        var reason = Field1(w0?["text"]?.ToString() ?? "", 600);
        var picked = new HashSet<string>((w0?["versions"] as JsonArray)?.Select(x => x?.ToString() ?? "").Where(x => x.Length > 0) ?? Enumerable.Empty<string>());
        var block = Chip("Block these versions", w0?["lock"]?.GetValue<bool>() == true, "Players can't install them from the Manager");
        var versionChips = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
        var versionHint = Ui.T("Loading versions", 11.5, "Faint");
        void SaveWarn(string what)
        {
            var text = reason.Text.Trim();
            var lockIt = block.IsChecked == true;
            var list = picked.ToList();
            Edit(what, m => m["warn"] = new JsonObject
            {
                ["text"] = text,
                ["versions"] = new JsonArray(list.Select(v => (JsonNode?)JsonValue.Create(v)).ToArray()),
                ["lock"] = lockIt,
            });
            ShowState();
        }
        var reasonTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1300) };
        reasonTimer.Tick += (_, _) => { reasonTimer.Stop(); if (reason.Text.Trim() != (Now()["warn"]?["text"]?.ToString() ?? "")) SaveWarn("Warning saved"); };
        reason.TextChanged += (_, _) => { reasonTimer.Stop(); reasonTimer.Start(); };
        block.Click += (_, _) => SaveWarn(block.IsChecked == true ? "Versions blocked" : "Versions unblocked");
        warnChip.Click += (_, _) =>
        {
            if (warnChip.IsChecked == true) { warn.Visibility = Visibility.Visible; reason.Focus(); SaveWarn("Warning on"); }
            else { warn.Visibility = Visibility.Collapsed; Edit("Warning removed", m => m.Remove("warn")); ShowState(); }
        };
        warn.Children.Add(Hinted(reason, "Why? Players see this in yellow"));
        warn.Children.Add(versionChips);
        warn.Children.Add(versionHint);
        warn.Children.Add(Ui.T("Click the bad versions. None picked means every version.", 11.5, "Faint").With(new Thickness(0, 2, 0, 8)));
        warn.Children.Add(block);
        body.Children.Add(warn);
        devVersions = versions =>
        {
            versionChips.Children.Clear();
            versionHint.Visibility = Visibility.Collapsed;
            foreach (var v in versions.Where(v => v.Length > 0).Distinct().Take(30))
            {
                var ver = v;
                var c = Chip(ver, picked.Contains(ver));
                c.Click += (_, _) => { if (c.IsChecked == true) picked.Add(ver); else picked.Remove(ver); SaveWarn(ver + (c.IsChecked == true ? " marked" : " unmarked")); };
                versionChips.Children.Add(c);
            }
            if (versionChips.Children.Count == 0) { versionHint.Text = "No versions listed for 1.20.1."; versionHint.Visibility = Visibility.Visible; }
        };

        body.Children.Add(Ui.Cap("With other mods").With(new Thickness(0, 14, 0, 8)));
        var pairList = new StackPanel();
        void DrawPairs()
        {
            pairList.Children.Clear();
            foreach (var p in (Now()["pairs"] as JsonArray)?.OfType<JsonObject>().ToList() ?? new())
            {
                var pid = p["id"]?.ToString() ?? "";
                var good = p["good"]?.GetValue<bool>() == true;
                var x = Ui.IconBtn("", "Remove", (_, _) =>
                {
                    Edit("Pair removed", m =>
                    {
                        var arr = m["pairs"] as JsonArray;
                        foreach (var hit in arr?.OfType<JsonObject>().Where(q => q["id"]?.ToString() == pid).ToList() ?? new()) arr!.Remove(hit);
                        if (arr is { Count: 0 }) m.Remove("pairs");
                    });
                    DrawPairs();
                });
                x.Width = 28; x.Height = 28;
                var line = Ui.Row(Ui.H(Ui.Dot(good ? "Ok" : "Bad", 7).With(new Thickness(0, 0, 10, 0)),
                    Ui.T((good ? "Works with " : "Breaks with ") + (p["name"]?.ToString() ?? pid) + (p["text"]?.ToString() is { Length: > 0 } t ? ": " + t : ""), 12.5, "Soft", trim: true)), x);
                pairList.Children.Add(line);
            }
        }
        DrawPairs();
        body.Children.Add(pairList);
        var find = Field1("", 80);
        var why = Field1("", 200);
        var results = new StackPanel { Margin = new Thickness(0, 6, 0, 0) };
        var searchTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        CancellationTokenSource? cts = null;
        searchTimer.Tick += async (_, _) =>
        {
            searchTimer.Stop();
            cts?.Cancel();
            var my = cts = new CancellationTokenSource();
            results.Children.Clear();
            var q = find.Text.Trim();
            if (q.Length < 2) return;
            try
            {
                var page = await Modrinth.Search(q, "mod", "All", new List<string>(), "relevance", 0, my.Token);
                if (my.IsCancellationRequested) return;
                foreach (var h in page.Hits.Where(h => h.ProjectId != projectId).Take(5))
                {
                    var hit = h;
                    Button Pick(string label, bool good)
                    {
                        var b = Ui.Btn(label, "Secondary", (_, _) =>
                        {
                            var text = why.Text.Trim();
                            Edit((good ? "Works with " : "Breaks with ") + hit.Title, m =>
                            {
                                var arr = m["pairs"] as JsonArray;
                                if (arr == null) m["pairs"] = arr = new JsonArray();
                                foreach (var old in arr.OfType<JsonObject>().Where(x => x["id"]?.ToString() == hit.ProjectId).ToList()) arr.Remove(old);
                                arr.Add(new JsonObject { ["id"] = hit.ProjectId, ["name"] = hit.Title, ["good"] = good, ["text"] = text });
                            });
                            find.Clear(); why.Clear(); results.Children.Clear();
                            DrawPairs();
                        });
                        b.Padding = new Thickness(10, 4, 10, 4);
                        b.FontSize = 11.5;
                        b.Margin = new Thickness(6, 0, 0, 0);
                        return b;
                    }
                    var row = Ui.Row(Ui.H(Ui.Tile(hit.IconUrl, hit.Title, 26), Ui.T(hit.Title, 12.5, "Soft", trim: true).With(new Thickness(10, 0, 0, 0))),
                        Ui.H(Pick("Works with", true), Pick("Breaks with", false)));
                    row.Margin = new Thickness(0, 0, 0, 6);
                    results.Children.Add(row);
                }
                if (results.Children.Count == 0) results.Children.Add(Ui.T("Nothing found.", 12, "Faint"));
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) { results.Children.Add(Ui.T("Search failed: " + ex.Message, 12, "Warn")); }
        };
        find.TextChanged += (_, _) => { searchTimer.Stop(); searchTimer.Start(); };
        var findRow = new Grid();
        findRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(3, GridUnitType.Star) });
        findRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(2, GridUnitType.Star) });
        var f1 = Hinted(find, "Find a mod by name");
        var f2 = Hinted(why, "Why (optional)");
        f2.Margin = new Thickness(8, 0, 0, 0);
        findRow.Children.Add(f1);
        Grid.SetColumn(f2, 1);
        findRow.Children.Add(f2);
        body.Children.Add(findRow);
        body.Children.Add(results);

        body.Children.Add(Ui.Cap("Your settings for it").With(new Thickness(0, 14, 0, 8)));
        var cfgBox = new StackPanel();
        var configRoot = Path.Combine(Paths.Home, "run", "config");
        void DrawConfig()
        {
            cfgBox.Children.Clear();
            var shared = Now()["config"]?["path"]?.ToString();
            if (shared != null)
            {
                var stop = Ui.Btn("Stop sharing", "Ghost", (_, _) => { Edit("Settings no longer shared", m => m.Remove("config")); DrawConfig(); });
                stop.FontSize = 12;
                cfgBox.Children.Add(Ui.Row(Ui.H(Ui.Dot("Ok", 7).With(new Thickness(0, 0, 10, 0)), Ui.T("Sharing config\\" + shared, 12.5, "Soft", trim: true)), stop));
            }
            var picks = ConfigFiles(configRoot, keys).Where(p => p != shared).Take(5).ToList();
            var wrap = new WrapPanel { Margin = new Thickness(0, 6, 0, 0) };
            foreach (var rel in picks)
            {
                var r = rel;
                var b = Ui.Btn(r, "Secondary", (_, _) => ShareConfig(r));
                b.Padding = new Thickness(10, 5, 10, 5);
                b.FontSize = 11.5;
                b.Margin = new Thickness(0, 0, 6, 6);
                b.ToolTip = "Share your copy of config\\" + r;
                wrap.Children.Add(b);
            }
            var other = Ui.Btn(picks.Count > 0 ? "Another file" : "Pick a config file", "Ghost", (_, _) =>
            {
                var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Pick a config file to share", InitialDirectory = Directory.Exists(configRoot) ? configRoot : null };
                if (dlg.ShowDialog(this) != true) return;
                var full = Path.GetFullPath(dlg.FileName);
                var rootFull = Path.GetFullPath(configRoot) + Path.DirectorySeparatorChar;
                if (!full.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase)) { Toast("Pick a file inside the config folder.", "Warn"); return; }
                ShareConfig(full[rootFull.Length..].Replace('\\', '/'));
            });
            other.FontSize = 12;
            wrap.Children.Add(other);
            cfgBox.Children.Add(wrap);
        }
        void ShareConfig(string rel)
        {
            try
            {
                var file = Path.Combine(configRoot, rel);
                if (new FileInfo(file).Length > 200_000) { Toast("That file is too big to share.", "Warn"); return; }
                var text = File.ReadAllText(file);
                Edit("Sharing config\\" + rel, m => m["config"] = new JsonObject { ["path"] = rel.Replace('\\', '/'), ["text"] = text });
                DrawConfig();
            }
            catch (Exception ex) { Toast("Could not read it: " + ex.Message, "Bad"); }
        }
        DrawConfig();
        body.Children.Add(cfgBox);

        var card = Ui.Card(body, 18);
        card.BorderBrush = Ui.Br("Accent");
        card.Margin = new Thickness(0, 0, 0, 14);
        return card;
    }

    static List<string> ConfigFiles(string root, IEnumerable<string> keys)
    {
        static string Norm(string s) => new string(s.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());
        var want = keys.Select(Norm).Where(k => k.Length >= 3).Distinct().ToList();
        var found = new List<string>();
        if (want.Count == 0 || !Directory.Exists(root)) return found;
        try
        {
            var exts = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { ".toml", ".json", ".json5", ".cfg", ".properties", ".txt", ".yml", ".yaml", ".snbt", ".conf" };
            foreach (var f in Directory.EnumerateFiles(root, "*", new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 3, IgnoreInaccessible = true }))
            {
                if (!exts.Contains(Path.GetExtension(f))) continue;
                var rel = Path.GetRelativePath(root, f).Replace('\\', '/');
                if (want.Any(k => Norm(rel).Contains(k))) found.Add(rel);
                if (found.Count >= 20) break;
            }
        }
        catch { }
        return found.OrderBy(x => x.Length).ToList();
    }

    bool postOpen;

    FrameworkElement HomePostBox()
    {
        var title = Field1("", 140);
        var text = FieldN("", 54, 4000);
        var kind = "announcement";
        var kinds = Ui.H();
        RadioButton Seg(string label, string k, bool modal)
        {
            var rb = new RadioButton { Content = label, GroupName = "postkind", Style = Ui.St("Seg"), IsChecked = k == kind, Margin = new Thickness(0, 0, 6, 0) };
            rb.Click += (_, _) => { if (modal) { PostEditor(null, k); rb.IsChecked = false; } else kind = k; };
            return rb;
        }
        kinds.Children.Add(Seg("Announcement", "announcement", false));
        kinds.Children.Add(Seg("What's new", "whatsnew", false));
        kinds.Children.Add(Seg("Countdown", "countdown", true));
        kinds.Children.Add(Seg("Sponsor", "sponsor", true));

        void Send()
        {
            if (title.Text.Trim().Length == 0) { Toast("Give it a title.", "Warn"); title.Focus(); return; }
            var p = new JsonObject { ["id"] = Guid.NewGuid().ToString("N")[..12], ["kind"] = kind, ["title"] = title.Text.Trim(), ["body"] = text.Text.Trim(), ["at"] = NowMs };
            if (kind == "whatsnew") Settings.SeenWhatsNew = p["id"]!.ToString();
            postOpen = false;
            Live("Posted", d => { var arr = d["posts"] as JsonArray ?? new JsonArray(); d["posts"] = arr; arr.Add(p); });
            Refresh();
        }
        var post = Ui.Btn("Post", "Primary", (_, _) => Send());
        post.ToolTip = "Ctrl+Enter";
        var more = Ui.Btn("Schedule, link, who sees it", "Ghost", (_, _) => PostEditor(null, kind));
        more.FontSize = 12;
        title.KeyDown += (_, e) => { if (e.Key == Key.Enter && Keyboard.Modifiers == ModifierKeys.Control) Send(); };
        text.KeyDown += (_, e) => { if (e.Key == Key.Enter && Keyboard.Modifiers == ModifierKeys.Control) { Send(); e.Handled = true; } };

        var fields = new Grid();
        fields.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(2, GridUnitType.Star) });
        fields.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(3, GridUnitType.Star) });
        var t1 = Hinted(title, "Title");
        var t2 = Hinted(text, "Text (optional)");
        t2.Margin = new Thickness(10, 0, 0, 0);
        fields.Children.Add(t1);
        Grid.SetColumn(t2, 1);
        fields.Children.Add(t2);
        var foot = Ui.Row(kinds, Ui.H(more, post.With(new Thickness(8, 0, 0, 0))));
        foot.Margin = new Thickness(0, 12, 0, 2);
        var body = Ui.V(fields, foot);
        body.Margin = new Thickness(0, 12, 0, 0);
        var drop = new Border
        {
            BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1, 0, 1, 1), Background = Ui.Br("Panel"),
            Padding = new Thickness(16, 0, 16, 11), Child = body,
            Visibility = postOpen ? Visibility.Visible : Visibility.Collapsed,
        };
        var shelf = new Canvas { Height = 0, ClipToBounds = false };
        Canvas.SetLeft(drop, -17);
        Canvas.SetTop(drop, 11);
        shelf.Children.Add(drop);

        var live = Feed.Posts("announcement", installedProjects).Count();
        var chevron = Ui.Glyph(postOpen ? "\uE70E" : "\uE70D", 11, "Muted");
        chevron.Margin = new Thickness(0, 0, 12, 0);
        var label = new Wordmark { Text = "POST TO EVERYONE", Pixel = 1, Fill = Ui.Br("Text"), VerticalAlignment = VerticalAlignment.Center };
        var count = Ui.T(live == 0 ? "Nothing live" : live == 1 ? "1 post live" : live + " posts live", 12, "Muted");
        count.VerticalAlignment = VerticalAlignment.Center;
        var headRow = Ui.Row(Ui.H(chevron, label), count);
        var head = new Border { Background = Brushes.Transparent, Cursor = Cursors.Hand, Child = headRow, Padding = new Thickness(2, 2, 2, 2) };
        void Show(bool open)
        {
            postOpen = open;
            drop.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
            chevron.Text = open ? "\uE70E" : "\uE70D";
            if (open) { Ui.Rise(drop, 6, 160); title.Focus(); }
        }
        void Close() => Show(false);
        text.KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
        title.KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
        head.MouseLeftButtonUp += (_, _) => Show(!postOpen);
        var card = new Border
        {
            BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Background = Ui.Br("Panel"),
            Padding = new Thickness(16, 11, 16, 11), Margin = new Thickness(0, 12, 0, 0), Child = Ui.V(head, shelf),
        };
        card.SizeChanged += (_, _) => drop.Width = card.ActualWidth;
        Panel.SetZIndex(card, 50);
        Ui.HoverRow(card);
        card.Background = Ui.Br("Panel");
        return card;
    }

    void RemovePost(string id, string title) => Live("Removed " + title, d =>
    {
        var arr = d["posts"] as JsonArray;
        foreach (var hit in arr?.OfType<JsonObject>().Where(x => x["id"]?.ToString() == id).ToList() ?? new()) arr!.Remove(hit);
    });

    Action? afterPreview;

    void PostEditor(JsonObject? existing, string kind, JsonObject? draft = null)
    {
        var p = draft ?? existing ?? new JsonObject { ["id"] = Guid.NewGuid().ToString("N")[..12], ["kind"] = kind };
        var kinds = new[] { ("announcement", "Announcement"), ("whatsnew", "What's new"), ("countdown", "Countdown"), ("sponsor", "Sponsor") };
        var title = Field1(p["title"]?.ToString() ?? "", 140);
        var body = FieldN(p["body"]?.ToString() ?? "", 80, 4000);
        var url = Field1(p["url"]?.ToString() ?? "", 400);
        var image = Field1(p["image"]?.ToString() ?? "", 400);
        var whenAt = Field1(When(p["when"]?.GetValue<long>() ?? 0), 20);
        var at = Field1(When(p["at"]?.GetValue<long>() ?? 0), 20);
        var until = Field1(When(p["until"]?.GetValue<long>() ?? 0), 20);
        var tMod = Field1(p["target"]?["mod"]?.ToString() ?? "", 64);
        var tMin = Field1(p["target"]?["minVersion"]?.ToString() ?? "", 20);
        var tMax = Field1(p["target"]?["maxVersion"]?.ToString() ?? "", 20);
        var warnLevel = new CheckBox { Style = Ui.St("Switch"), IsChecked = p["level"]?.ToString() == "warn", Content = Ui.T("Yellow edge, for something urgent", 13, "Soft"), Margin = new Thickness(0, 0, 0, 12) };

        var urlRow = Labeled("Link (optional)", url);
        var imgRow = Labeled("Banner picture link", image);
        var whenRow = Labeled("Counts down to", whenAt, "Your time, like " + DateTime.Now.AddDays(3).ToString(TimeFormat));
        void Show()
        {
            urlRow.Visibility = kind is "announcement" or "sponsor" ? Visibility.Visible : Visibility.Collapsed;
            imgRow.Visibility = kind == "sponsor" ? Visibility.Visible : Visibility.Collapsed;
            whenRow.Visibility = kind == "countdown" ? Visibility.Visible : Visibility.Collapsed;
            warnLevel.Visibility = kind == "announcement" ? Visibility.Visible : Visibility.Collapsed;
        }
        var segs = Ui.H();
        foreach (var (k, label) in kinds)
        {
            var kk = k;
            var rb = new RadioButton { Content = label, GroupName = "editkind", Style = Ui.St("Seg"), IsChecked = kk == kind, IsEnabled = existing == null || kk == kind };
            rb.Click += (_, _) => { kind = kk; Show(); };
            segs.Children.Add(rb);
        }
        var later = new StackPanel { Visibility = Visibility.Collapsed };
        later.Children.Add(Labeled("Starts", at, "Empty means now. Your time, like " + DateTime.Now.ToString(TimeFormat)));
        later.Children.Add(Labeled("Ends", until, "Empty means it stays up."));
        later.Children.Add(Labeled("Only for players with this mod", tMod, "A Modrinth project id or slug. Empty means everyone."));
        later.Children.Add(Labeled("Pentecost version from", tMin));
        later.Children.Add(Labeled("Pentecost version up to", tMax));
        var laterBtn = Ui.Btn("Schedule and who sees it", "Ghost", null);
        laterBtn.HorizontalAlignment = HorizontalAlignment.Left;
        laterBtn.FontSize = 12;
        laterBtn.Click += (_, _) => { later.Visibility = later.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible; };

        var fields = Ui.V(segs.With(new Thickness(0, 0, 0, 16)), Labeled("Title", title), Labeled("Text", body), urlRow, imgRow, whenRow, warnLevel, laterBtn, later);
        Show();

        JsonObject? Build()
        {
            var a = ParseWhen(at.Text);
            var u = ParseWhen(until.Text);
            var w = ParseWhen(whenAt.Text);
            if (a == null || u == null || w == null) { Toast("Write times like " + DateTime.Now.ToString(TimeFormat) + ".", "Warn"); return null; }
            if (title.Text.Trim().Length == 0) { Toast("Give it a title.", "Warn"); return null; }
            if (kind == "countdown" && w == 0) { Toast("Pick when it counts down to.", "Warn"); return null; }
            foreach (var link in new[] { url.Text.Trim(), image.Text.Trim() })
                if (link.Length > 0 && !link.StartsWith("https://")) { Toast("Links start with https://", "Warn"); return null; }
            var o = new JsonObject { ["id"] = p["id"]?.ToString(), ["kind"] = kind, ["title"] = title.Text.Trim(), ["body"] = body.Text.Trim() };
            if (url.Text.Trim().Length > 0 && kind is "announcement" or "sponsor") o["url"] = url.Text.Trim();
            if (image.Text.Trim().Length > 0 && kind == "sponsor") o["image"] = image.Text.Trim();
            o["at"] = a > 0 ? a : (p["at"]?.GetValue<long>() ?? NowMs);
            if (u > 0) o["until"] = u;
            if (kind == "countdown") o["when"] = w;
            if (warnLevel.IsChecked == true && kind == "announcement") o["level"] = "warn";
            var target = new JsonObject();
            if (tMod.Text.Trim().Length > 0) target["mod"] = tMod.Text.Trim();
            if (tMin.Text.Trim().Length > 0) target["minVersion"] = tMin.Text.Trim();
            if (tMax.Text.Trim().Length > 0) target["maxVersion"] = tMax.Text.Trim();
            if (target.Count > 0) o["target"] = target;
            return o;
        }

        void Save()
        {
            var o = Build();
            if (o == null) return;
            if (kind == "whatsnew") Settings.SeenWhatsNew = o["id"]!.ToString();
            HideModal();
            Live(existing == null ? "Posted" : "Saved", d =>
            {
                var posts = d["posts"] as JsonArray ?? new JsonArray();
                d["posts"] = posts;
                var old = posts.OfType<JsonObject>().FirstOrDefault(x => x["id"]?.ToString() == o["id"]?.ToString());
                if (old != null) posts[posts.IndexOf(old)] = o; else posts.Add(o);
            });
            Refresh();
        }

        void Preview()
        {
            var o = Build();
            if (o == null) return;
            var shown = (JsonObject)o.DeepClone();
            shown["at"] = NowMs - 1000;
            shown.Remove("until");
            shown.Remove("target");
            var feed = Feed.Snapshot();
            var posts = feed["posts"] as JsonArray ?? new JsonArray();
            feed["posts"] = posts;
            var old = posts.OfType<JsonObject>().FirstOrDefault(x => x["id"]?.ToString() == shown["id"]?.ToString());
            if (old != null) posts[posts.IndexOf(old)] = shown; else posts.Add(shown);
            afterPreview = () => PostEditor(existing, kind, o);
            HideModal();
            Feed.Preview = feed;
            Go("Play");
        }

        var row = Ui.H(Ui.Btn("Cancel", "Secondary", (_, _) => HideModal()), Ui.Btn("Preview", "Secondary", (_, _) => Preview()).With(new Thickness(8, 0, 0, 0)), Ui.Btn(existing == null ? "Post" : "Save", "Primary", (_, _) => Save()).With(new Thickness(8, 0, 0, 0)));
        row.HorizontalAlignment = HorizontalAlignment.Right;
        var scroll = new ScrollViewer { Content = fields, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = Math.Max(300, ActualHeight - 220), Padding = new Thickness(0, 0, 10, 0) };
        var box = Ui.V(DevMark("Post to everyone"), Ui.T(existing == null ? "New post" : "Edit post", 17, bold: true).With(new Thickness(0, 8, 0, 16)), scroll, row.With(new Thickness(0, 16, 0, 0)));
        box.Width = 600;
        ShowModal(Ui.Card(box, 24));
    }

    static string KindName(string kind) => kind switch
    {
        "announcement" => "Announcement",
        "whatsnew" => "What's new",
        "countdown" => "Countdown",
        "featured" => "Featured",
        "sponsor" => "Sponsor",
        _ => kind,
    };

    FrameworkElement BuildDev()
    {
        var page = new StackPanel();
        if (!DevAccess.Unlocked)
        {
            page.Children.Add(Header("Dev", "Developer tools."));
            page.Children.Add(Ui.Card(Ui.T("These tools need the owner's account and the developer key on this computer.", 13, "Muted", wrap: true), 20).With(new Thickness(0, 22, 0, 0)));
            return Scroll(page);
        }
        var d = Feed.Current;
        var mono = (FontFamily)FindResource("Mono");
        var stations = new List<(string Name, UIElement Body, string Meta)>();
        var gateNode = d["gate"] as JsonObject;
        var killCount = (d["kill"] as JsonArray)?.Count ?? 0;
        var allPosts = (d["posts"] as JsonArray)?.OfType<JsonObject>().ToList() ?? new();
        var liveCount = allPosts.Count(p => Feed.Live(p, installedProjects));
        var notedCount = (d["mods"] as JsonObject)?.Count ?? 0;
        var lastSent = Feed.History().Select(h => (DateTime?)h.Item2).FirstOrDefault();

        const double headH = 400;
        var head = new Grid { Height = headH, ClipToBounds = true };
        var fade = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 0));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.04));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.96));
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 1));
        var scene = new Grid { OpacityMask = fade };
        scene.Children.Add(new Signal { Warning = gateNode != null || killCount > 0 });
        var seaH = headH * (1 - 0.70) + 24;
        scene.Children.Add(new CloudSea { Big = true, BareBg = "Bg", BareTop = 40 / seaH, Height = seaH, VerticalAlignment = VerticalAlignment.Bottom, IsHitTestVisible = false });
        head.Children.Add(scene);
        var title = new Wordmark { Text = "DEV", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var tally = Ui.T(liveCount + " POSTS LIVE   " + notedCount + " MODS NOTED   " + killCount + " SWITCHED OFF", 12, "Soft");
        tally.FontFamily = mono;
        var topLeft = Ui.V(title, tally.With(new Thickness(2, 8, 0, 0)));
        topLeft.HorizontalAlignment = HorizontalAlignment.Left;
        topLeft.VerticalAlignment = VerticalAlignment.Top;
        topLeft.Margin = new Thickness(0, 6, 0, 0);
        topLeft.IsHitTestVisible = false;
        head.Children.Add(topLeft);
        var heroCap = Ui.T("BROADCASTING", 11, "Faint");
        heroCap.FontFamily = mono;
        var heroName = new Wordmark { Text = "FEED " + Feed.Seq, Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var heroSub = Ui.T((lastSent is DateTime ls ? "LAST SENT " + Ui.Ago(ls).ToUpperInvariant() : "NOTHING SENT FROM THIS PC") + (gateNode != null ? "   UPDATE GATE ON" : ""), 12, "Soft");
        heroSub.FontFamily = mono;
        var hero = Ui.V(heroCap, heroName.With(new Thickness(0, 8, 0, 0)), heroSub.With(new Thickness(0, 8, 0, 0)));
        hero.HorizontalAlignment = HorizontalAlignment.Left;
        hero.VerticalAlignment = VerticalAlignment.Top;
        hero.Margin = new Thickness(2, 168, 0, 0);
        hero.IsHitTestVisible = false;
        head.Children.Add(hero);
        var newPost = Ui.Btn("New post", "Primary", (_, _) => PostEditor(null, "announcement"), "");
        newPost.HorizontalAlignment = HorizontalAlignment.Right;
        newPost.VerticalAlignment = VerticalAlignment.Top;
        newPost.Margin = new Thickness(0, 10, 0, 0);
        head.Children.Add(newPost);
        page.Children.Add(head);

        if (Feed.ServerDir.Length == 0)
        {
            var pick = Ui.Btn("Choose the server folder", "Primary", (_, _) =>
            {
                var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Choose the Pentecost server folder (the one with wrangler.jsonc)" };
                if (dlg.ShowDialog(this) != true) return;
                var err = Feed.ChooseServerDir(dlg.FolderName);
                Toast(err ?? "Server folder saved.", err == null ? "Ok" : "Warn");
                Refresh();
            });
            var need = Ui.Card(Ui.Row(Ui.V(Ui.T("Server folder not found", 14.5, bold: true),
                Ui.T("Changes upload from the server folder in your Pentecost source.", 12.5, "Muted", wrap: true)), pick), 18);
            need.BorderBrush = Ui.Br("Warn");
            need.Margin = new Thickness(0, 22, 0, 0);
            page.Children.Add(need);
        }

        Border Line(UIElement left, params UIElement[] right)
        {
            var r = Ui.H(right);
            r.VerticalAlignment = VerticalAlignment.Center;
            return Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(8, 10, 8, 10), Child = Ui.Row(left, r) });
        }
        Button Small(string text, string style, Action act)
        {
            var b = Ui.Btn(text, style, (_, _) => act());
            b.Padding = new Thickness(12, 5, 12, 5);
            b.FontSize = 12;
            b.Margin = new Thickness(6, 0, 0, 0);
            return b;
        }

        var posts = (d["posts"] as JsonArray)?.OfType<JsonObject>().ToList() ?? new();
        var postList = new StackPanel();
        foreach (var p in posts.OrderByDescending(p => p["at"]?.GetValue<long>() ?? 0))
        {
            var post = p;
            var kind = post["kind"]?.ToString() ?? "";
            var live = Feed.Live(post, installedProjects);
            var sub = KindName(kind) + (live ? ", showing now" : ", not showing now") + (post["target"] != null ? ", only some players" : "");
            postList.Children.Add(Line(Ui.V(Ui.T(post["title"]?.ToString() ?? "(no title)", 13.5, bold: true, trim: true), Ui.T(sub, 11.5, live ? "Faint" : "Warn", trim: true)),
                kind == "featured" ? new Border() : Small("Edit", "Secondary", () => PostEditor(post, kind)),
                Small("Remove", "Ghost", () => { RemovePost(post["id"]?.ToString() ?? "", post["title"]?.ToString() ?? "post"); Refresh(); })));
        }
        if (posts.Count == 0) postList.Children.Add(Ui.T("Nothing posted. Write one in the box on Home.", 12.5, "Faint"));
        stations.Add(("Posts", postList, liveCount + " LIVE OF " + allPosts.Count));

        var mods = (d["mods"] as JsonObject)?.ToList() ?? new();
        var modList = new StackPanel();
        foreach (var (id, node) in mods.OrderBy(kv => kv.Value?["name"]?.ToString()))
        {
            var pid = id;
            var o = node as JsonObject;
            var name = o?["name"]?.ToString() ?? id;
            var bits = new List<string>();
            if (o?["recommended"] != null) bits.Add("Dev pick");
            if (o?["tested"] != null) bits.Add("Tested");
            if (o?["pin"] != null) bits.Add("Pinned");
            if (o?["hide"] != null) bits.Add("Hidden");
            if (o?["warn"] != null) bits.Add(o["warn"]?["lock"]?.GetValue<bool>() == true ? "Blocked versions" : "Warning");
            if (o?["note"] != null) bits.Add("Note");
            if (o?["pairs"] is JsonArray pa) bits.Add(pa.Count + (pa.Count == 1 ? " pair" : " pairs"));
            if (o?["config"] != null) bits.Add("Settings");
            modList.Children.Add(Line(Ui.V(Ui.T(name, 13.5, bold: true, trim: true), Ui.T(string.Join(", ", bits), 11.5, "Faint", trim: true)),
                Small("Open", "Secondary", () => ShowProject(pid, "mod", name, null, "", () => { }))));
        }
        if (mods.Count == 0) modList.Children.Add(Ui.T("No mod notes yet. Open any mod in Mods or Browse and type in the box at the top.", 12.5, "Faint", wrap: true));
        stations.Add(("Mod notes", modList, notedCount == 1 ? "1 MOD" : notedCount + " MODS"));

        var gate = d["gate"] as JsonObject;
        var gateMin = Field1(gate?["minVersion"]?.ToString() ?? "", 20);
        var gateText = Field1(gate?["text"]?.ToString() ?? "", 400);
        var kill = FieldN(string.Join("\n", (d["kill"] as JsonArray)?.Select(x => x?.ToString()) ?? Array.Empty<string>()), 60, 2000);
        var saveSafety = Ui.Btn("Make it live", "Secondary", (_, _) =>
        {
            var min = gateMin.Text.Trim();
            var why = gateText.Text.Trim();
            var keys = kill.Text.Split('\n').Select(x => x.Trim()).Where(x => x.Length > 0).Distinct().ToList();
            Live("Safety saved", dd =>
            {
                if (min.Length > 0) dd["gate"] = new JsonObject { ["minVersion"] = min, ["text"] = why }; else dd.Remove("gate");
                dd["kill"] = new JsonArray(keys.Select(x => (JsonNode?)JsonValue.Create(x)).ToArray());
            });
        });
        saveSafety.HorizontalAlignment = HorizontalAlignment.Left;
        stations.Add(("Safety", Ui.V(
            Labeled("Oldest safe Pentecost version", Hinted(gateMin, "Like 1.0.1. Empty for none"), "Older copies can't press Play until they update."),
            Labeled("Why players must update", gateText),
            Labeled("Turn off a Pentecost feature for everyone", Hinted(kill, "One setting per line, like threads.blockEntities")),
            saveSafety), (gateNode != null ? "GATE " + (gateNode["minVersion"]?.ToString() ?? "") : "GATE OFF") + "   " + killCount + " OFF"));

        var picks = (d["skins"] as JsonArray)?.OfType<JsonObject>().ToList() ?? new();
        var pickList = new StackPanel();
        foreach (var pk in picks)
        {
            var tex = pk["texture"]?.ToString() ?? "";
            var nm = pk["name"]?.ToString() ?? "Pick";
            pickList.Children.Add(Line(Ui.V(Ui.T(nm, 13.5, bold: true, trim: true), Ui.T(tex.Length > 16 ? tex[..16] + "..." : tex, 11.5, "Faint", trim: true)),
                Small("Remove", "Ghost", () => Live("Skin pick removed", dd =>
                {
                    if (dd["skins"] is JsonArray arr)
                        foreach (var hit in arr.OfType<JsonObject>().Where(x => x["texture"]?.ToString() == tex).ToList()) arr.Remove(hit);
                }))));
        }
        if (picks.Count == 0) pickList.Children.Add(Ui.T("No picks yet. They show at the top of Browse in the game wardrobe.", 12.5, "Faint", wrap: true));
        var pickName = Field1("", 40);
        var pickTex = Field1("", 300);
        var addPick = Ui.Btn("Add pick", "Secondary", (_, _) =>
        {
            var m = System.Text.RegularExpressions.Regex.Matches(pickTex.Text, "[0-9a-f]{60,70}");
            var hash = m.Count > 0 ? m[^1].Value : "";
            var name = pickName.Text.Trim();
            if (hash.Length == 0 || name.Length == 0) { Toast("Give the pick a name and paste a texture link or hash."); return; }
            Live("Skin pick added", dd =>
            {
                if (dd["skins"] is not JsonArray arr) { arr = new JsonArray(); dd["skins"] = arr; }
                foreach (var hit in arr.OfType<JsonObject>().Where(x => x["texture"]?.ToString() == hash).ToList()) arr.Remove(hit);
                arr.Add(new JsonObject { ["name"] = name, ["texture"] = hash });
            });
        });
        addPick.HorizontalAlignment = HorizontalAlignment.Left;
        pickList.Children.Add(Ui.V(
            Labeled("Name", pickName),
            Labeled("Texture", Hinted(pickTex, "A textures.minecraft.net link, a MineSkin page link, or the hash")),
            addPick));
        stations.Add(("Skin picks", pickList, picks.Count == 1 ? "1 PICK" : picks.Count + " PICKS"));

        var crashBody = new StackPanel();
        var load = Ui.Btn("Load crash counts", "Secondary", null, "");
        load.HorizontalAlignment = HorizontalAlignment.Left;
        load.Click += async (_, _) =>
        {
            load.IsEnabled = false;
            crashBody.Children.Clear();
            try
            {
                var rows = await Feed.CrashCounts();
                if (rows.Count == 0) crashBody.Children.Add(Ui.T("No crashes reported yet.", 12.5, "Faint"));
                foreach (var r in rows.Take(100))
                    crashBody.Children.Add(Ui.Row(Ui.V(Ui.T(r.Mod + "  " + r.Version, 13, bold: true, trim: true), Ui.T(r.Kind + ", last " + Ui.Ago(r.Last), 11.5, "Faint", trim: true)),
                        Ui.T(r.Count + "x", 14, bold: true)).With(new Thickness(0, 0, 0, 8)));
            }
            catch (Exception ex) { crashBody.Children.Add(Ui.T("Could not load them: " + ex.Message, 12.5, "Warn", wrap: true)); }
            load.IsEnabled = true;
        };
        stations.Add(("Crash counts", Ui.V(load, crashBody.With(new Thickness(0, 12, 0, 0))), "LOADS ON DEMAND"));

        var hist = new StackPanel();
        foreach (var (seq, when, file) in Feed.History().Take(12))
        {
            var f = file;
            var s = seq;
            var row = Line(Ui.T($"{when:yyyy-MM-dd HH:mm}" + (s == Feed.Seq ? "  (live now)" : ""), 13, "Soft"),
                s == Feed.Seq ? new Border() : Small("Put back", "Secondary", () => Confirm("Put this back for everyone?", "Everything players see goes back to how it was at " + when.ToString("yyyy-MM-dd HH:mm") + ".", "Put back", () =>
                {
                    try { if (JsonNode.Parse(File.ReadAllText(f)) is JsonObject o) { Live("Put back", dd => { dd.Clear(); foreach (var kv in o) if (kv.Key is not "seq" and not "issued" and not "v") dd[kv.Key] = kv.Value?.DeepClone(); }); Refresh(); } }
                    catch (Exception ex) { Toast(ex.Message, "Bad"); }
                })));
            hist.Children.Add(row);
        }
        if (hist.Children.Count == 0) hist.Children.Add(Ui.T("Nothing published from this PC yet.", 12.5, "Faint"));
        var histCount = Feed.History().Count();
        stations.Add(("History", hist, histCount == 1 ? "1 VERSION" : histCount + " VERSIONS"));

        var keyText = Feed.PrimaryRetired
            ? "Your main key is retired on this computer. Only the backup key is trusted now."
            : Feed.HasBackupKey
                ? "Main key in use, backup key built in."
                : "Main key in use. No backup key yet: run tools\\devkey\\New-BackupKey.ps1 once and keep its file offline.";
        stations.Add(("Keys", Ui.T(keyText, 13, "Soft", wrap: true), Feed.PrimaryRetired ? "BACKUP ONLY" : Feed.HasBackupKey ? "MAIN AND BACKUP" : "NO BACKUP KEY"));

        var body = new Grid { Margin = new Thickness(0, 8, 0, 36) };
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.34, GridUnitType.Star), MinWidth = 260 });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.66, GridUnitType.Star) });
        var list = new StackPanel();
        var detail = new StackPanel();
        body.Children.Add(list);
        Grid.SetColumn(detail, 2);
        body.Children.Add(detail);
        page.Children.Add(body);
        var listCap = Ui.T("STATIONS", 11, "Faint");
        listCap.FontFamily = mono;
        list.Children.Add(listCap.With(new Thickness(0, 0, 0, 8)));
        var rowsUi = new List<Border>();

        void Show(int i)
        {
            if (i < 0 || i >= stations.Count) return;
            devStation = i;
            for (var k = 0; k < rowsUi.Count; k++) StyleRow(rowsUi[k], k == i);
            foreach (var st in stations)
            {
                if (st.Body is FrameworkElement fe && fe.Parent is Border box) box.Child = null;
                else if (st.Body is FrameworkElement fe2 && fe2.Parent is Panel old) old.Children.Remove(fe2);
            }
            detail.Children.Clear();
            var cap = Ui.T("STATION " + (i + 1).ToString("00") + " OF " + stations.Count.ToString("00") + "   " + stations[i].Meta, 11, "Faint");
            cap.FontFamily = mono;
            detail.Children.Add(cap);
            detail.Children.Add(new Wordmark { Text = stations[i].Name.ToUpperInvariant(), Pixel = 2, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 10, 0, 0) });
            detail.Children.Add(Ui.T(DevBlurb(stations[i].Name), 13.5, "Soft", wrap: true).With(new Thickness(0, 10, 0, 0)));
            detail.Children.Add(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 1, 0, 0), Margin = new Thickness(0, 20, 0, 0), Padding = new Thickness(0, 18, 0, 0), Child = stations[i].Body });
        }

        for (var i = 0; i < stations.Count; i++)
        {
            var k = i;
            var head2 = Ui.T(stations[i].Name, 13.5, "Soft", trim: true);
            var meta = Ui.T((i + 1).ToString("00") + "   " + stations[i].Meta, 11, "Faint");
            meta.FontFamily = mono;
            var text = Ui.V(head2, meta.With(new Thickness(0, 5, 0, 0)));
            var bar = new Border { Width = 2, HorizontalAlignment = HorizontalAlignment.Left, Background = Ui.Br("Text"), Visibility = Visibility.Hidden };
            var holder = new Grid();
            holder.Children.Add(new Border { Padding = new Thickness(16, 11, 4, 11), Child = text });
            holder.Children.Add(bar);
            var row = new Border { Background = Brushes.Transparent, Cursor = Cursors.Hand, BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Child = holder, Tag = (bar, head2) };
            row.MouseEnter += (_, _) => { if (bar.Visibility != Visibility.Visible) row.Background = Ui.Br("Hover"); };
            row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
            row.MouseLeftButtonUp += (_, _) => Show(k);
            rowsUi.Add(row);
            list.Children.Add(row);
        }
        Show(Math.Clamp(devStation, 0, stations.Count - 1));
        return Scroll(page);
    }

    int devStation;

    static string DevBlurb(string name) => name switch
    {
        "Posts" => "What players see on Home. New posts start in the box on Home or with New post.",
        "Mod notes" => "Notes, picks, warnings and pairs you put on mods. They live on each mod's page.",
        "Safety" => "The emergency levers. Force an update or switch a feature off for everyone. Nothing changes until you press Make it live.",
        "Crash counts" => "Anonymous counts from players who turned them on. Mod, version and crash type only. Only your key can read them.",
        "History" => "Every version of the feed published from this PC. Put one back to undo a change for everyone.",
        "Keys" => "The keys that sign everything you publish. Keep the backup key offline.",
        _ => "",
    };
}
