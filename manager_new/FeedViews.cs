using System.Text.Json.Nodes;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public partial class MainWindow
{
    static DateTime Local(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;

    static FrameworkElement DevMark(string text = "From the Pentecost developer")
    {
        var p = Ui.H(Ui.Cap(text));
        p.ToolTip = "Signed with the developer key. Every Manager checks the signature, so nobody else can post this.";
        return p;
    }

    static IEnumerable<FrameworkElement> FeedPills(string? projectId)
    {
        var m = Feed.Mod(projectId);
        if (m == null) yield break;
        if (m["recommended"]?.GetValue<bool>() == true) yield return Tip(Ui.Pill("Dev pick", "Text"), "Recommended by the Pentecost developer");
        if (m["tested"]?.GetValue<bool>() == true) yield return Tip(Ui.Pill("Tested", "Ok"), "Tested with Pentecost by the developer");
        if (m["warn"] is JsonObject) yield return Tip(Ui.Pill("Warning", "Warn"), m["warn"]?["text"]?.ToString() ?? "The developer left a warning");
    }

    static FrameworkElement Tip(FrameworkElement e, string tip) { e.ToolTip = tip; return e; }

    FrameworkElement? FeedModSection(string? projectId)
    {
        var m = Feed.Mod(projectId);
        if (m == null) return null;
        var body = new StackPanel();
        var pills = new WrapPanel();
        foreach (var p in FeedPills(projectId)) { p.Margin = new Thickness(0, 0, 6, 6); pills.Children.Add(p); }
        if (pills.Children.Count > 0) body.Children.Add(pills);
        if (m["warn"] is JsonObject w)
        {
            var versions = (w["versions"] as JsonArray)?.Select(x => x?.ToString()).Where(x => !string.IsNullOrEmpty(x)).ToList() ?? new();
            var head = (w["lock"]?.GetValue<bool>() == true ? "Blocked" : "Warning") + (versions.Count > 0 ? " for " + string.Join(", ", versions) : "");
            var box = new Border
            {
                BorderBrush = Ui.Br("Warn"), BorderThickness = new Thickness(2, 0, 0, 0), Padding = new Thickness(12, 4, 0, 4), Margin = new Thickness(0, 12, 0, 0),
                Child = Ui.V(Ui.T(head, 13, "Warn", bold: true), Ui.T(w["text"]?.ToString() ?? "", 12.5, "Soft", wrap: true).With(new Thickness(0, 3, 0, 0))),
            };
            body.Children.Add(box);
        }
        if (m["pairs"] is JsonArray pairs && pairs.Count > 0)
        {
            body.Children.Add(Ui.Cap("With other mods").With(new Thickness(0, 14, 0, 8)));
            foreach (var p in pairs.OfType<JsonObject>())
            {
                var good = p["good"]?.GetValue<bool>() == true;
                var line = Ui.H(Ui.Dot(good ? "Ok" : "Bad", 7).With(new Thickness(0, 0, 10, 0)),
                    Ui.T((good ? "Works well with " : "Breaks with ") + (p["name"]?.ToString() ?? p["id"]?.ToString() ?? "?")
                        + (p["text"]?.ToString() is { Length: > 0 } t ? ": " + t : ""), 12.5, "Soft", wrap: true));
                line.Margin = new Thickness(0, 0, 0, 5);
                body.Children.Add(line);
            }
        }
        if (m["config"] is JsonObject cfg && cfg["path"]?.ToString() is { Length: > 0 } rel)
        {
            var apply = Ui.Btn("Use the developer's settings", "Secondary", (_, _) => Confirm("Use the developer's settings?",
                "This writes config\\" + rel + " in your current profile. Your old file is kept next to it as a .bak.", "Use them", () =>
                {
                    var dest = Packs.SafeTarget(Path.Combine(Paths.Home, "run", "config"), rel);
                    if (dest == null) { Toast("That file name is not allowed.", "Bad"); return; }
                    try
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                        if (File.Exists(dest)) File.Copy(dest, dest + ".bak", true);
                        File.WriteAllText(dest, cfg["text"]?.ToString() ?? "");
                        Toast("Settings written. They apply the next time the game starts.");
                    }
                    catch (Exception ex) { Toast("Could not write it: " + ex.Message, "Bad"); }
                }));
            apply.HorizontalAlignment = HorizontalAlignment.Left;
            body.Children.Add(apply.With(new Thickness(0, 14, 0, 0)));
        }
        if (body.Children.Count == 0) return null;
        var card = Ui.Card(Ui.V(DevMark(), Ui.Dotted().With(new Thickness(0, 10, 0, 12)), body), 18);
        card.BorderBrush = Ui.Br("Edge");
        card.Margin = new Thickness(0, 0, 0, 14);
        return card;
    }

    async Task<bool> FeedAllowsInstall(string projectId, string title, string? version)
    {
        if (Feed.Locked(projectId, version) is { } why)
        {
            Toast("Blocked by the Pentecost developer: " + why, "Bad");
            return false;
        }
        var bad = (Feed.Mod(projectId)?["pairs"] as JsonArray)?.OfType<JsonObject>()
            .Where(p => p["good"]?.GetValue<bool>() != true && p["id"]?.ToString() is { Length: > 0 }).ToList();
        if (bad == null || bad.Count == 0) return true;
        var have = (await Task.Run(ModScan.Scan)).Select(m => m.ProjectId).Where(x => x != null).ToHashSet();
        var clash = bad.FirstOrDefault(p => have.Contains(p["id"]!.ToString()));
        if (clash == null) return true;
        var tcs = new TaskCompletionSource<bool>();
        Confirm(title + " breaks with " + (clash["name"]?.ToString() ?? "a mod you have"),
            "The Pentecost developer says: " + (clash["text"]?.ToString() is { Length: > 0 } t ? t : "these two don't work together.") + " Install anyway?",
            "Install anyway", () => tcs.TrySetResult(true), true);
        var watch = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
        watch.Tick += (_, _) => { if (Modal.Visibility != Visibility.Visible) { watch.Stop(); tcs.TrySetResult(false); } };
        watch.Start();
        return await tcs.Task;
    }

    void AddFeedToHome(StackPanel page)
    {
        var installed = installedProjects;
        var dev = DevAccess.Unlocked && Feed.Preview == null;
        if (dev) page.Children.Add(HomePostBox());
        if (Feed.Preview != null)
        {
            var end = Ui.Btn("End preview", "Primary", (_, _) => { Feed.Preview = null; var back = afterPreview; afterPreview = null; Go("Dev"); back?.Invoke(); });
            var card = Ui.Card(Ui.Row(Ui.V(Ui.T("Preview", 14.5, bold: true), Ui.T("This is your unpublished draft. Players still see the live feed.", 12.5, "Muted")), end), 16);
            card.BorderBrush = Ui.Br("Accent");
            card.Margin = new Thickness(0, 12, 0, 0);
            page.Children.Add(card);
        }
        if (Feed.Gate() is { } gate)
        {
            var n = Notice("Update needed", gate, "Update", () => _ = RunInstall(false));
            n.BorderBrush = Ui.Br("Bad");
            n.Margin = new Thickness(0, 12, 0, 0);
            page.Children.Add(n);
        }
        var dismissed = Settings.Dismissed;
        foreach (var p in Feed.Posts("announcement", installed).Where(p => dev || !dismissed.Contains(p["id"]?.ToString() ?? "")))
        {
            var post = p;
            var id = post["id"]?.ToString() ?? "";
            var close = dev
                ? Ui.IconBtn("\uE74D", "Remove it for everyone", (_, _) => { RemovePost(id, post["title"]?.ToString() ?? "post"); Refresh(); })
                : Ui.IconBtn("\uE8BB", "Close this announcement", (_, _) => { var d = Settings.Dismissed; d.Add(id); Settings.Dismissed = d; Refresh(); });
            var text = Ui.V(DevMark(), Ui.T(post["title"]?.ToString() ?? "", 15, bold: true, wrap: true).With(new Thickness(0, 8, 0, 0)));
            if (post["body"]?.ToString() is { Length: > 0 } b) text.Children.Add(Ui.T(b, 12.5, "Soft", wrap: true).With(new Thickness(0, 4, 0, 0)));
            if (post["url"]?.ToString() is { Length: > 0 } url && url.StartsWith("https://"))
                text.Children.Add(Ui.Btn("Read more", "Secondary", (_, _) => OpenPath(url)).With(new Thickness(0, 10, 0, 0)));
            ((FrameworkElement)text.Children[^1]).HorizontalAlignment = HorizontalAlignment.Left;
            var card = Ui.Card(Ui.Row(text, close), 18);
            card.BorderBrush = Ui.Br(post["level"]?.ToString() == "warn" ? "Warn" : "Edge");
            card.Margin = new Thickness(0, 12, 0, 0);
            page.Children.Add(card);
        }
        var extras = new List<FrameworkElement>();
        foreach (var p in Feed.Posts("countdown", installed))
        {
            var when = p["when"]?.GetValue<long>() ?? 0;
            if (when <= 0) continue;
            var left = Ui.T("", 22, bold: true);
            void Tick()
            {
                var d = Local(when) - DateTime.Now;
                left.Text = d.TotalSeconds <= 0 ? "Now" : d.TotalDays >= 1 ? $"{(int)d.TotalDays}d {d.Hours}h {d.Minutes}m" : $"{d.Hours}h {d.Minutes}m {d.Seconds}s";
            }
            Tick();
            var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            timer.Tick += (_, _) => Tick();
            left.Loaded += (_, _) => timer.Start();
            left.Unloaded += (_, _) => timer.Stop();
            extras.Add(Ui.SheetCard(Ui.V(Ui.Cap(p["title"]?.ToString() ?? "Coming up"), left.With(new Thickness(0, 8, 0, 4)),
                Ui.T(p["body"]?.ToString() ?? Local(when).ToString("dddd d MMMM, HH:mm"), 12, "Muted", wrap: true)), 18));
        }
        foreach (var p in Feed.Posts("featured", installed))
        {
            var id = p["project"]?.ToString();
            var type = p["type"]?.ToString() is { Length: > 0 } t ? t : "modpack";
            var title = p["title"]?.ToString() ?? "Featured";
            var open = Ui.Btn("Open", "Primary", (_, _) => { if (id != null) ShowProject(id, type, title, null, "", () => { }); });
            open.HorizontalAlignment = HorizontalAlignment.Left;
            extras.Add(Ui.SheetCard(Ui.V(Ui.Cap("Featured " + (type == "modpack" ? "pack" : type)), Ui.T(title, 16, bold: true, wrap: true).With(new Thickness(0, 8, 0, 4)),
                Ui.T(p["body"]?.ToString() ?? "", 12, "Muted", wrap: true), open.With(new Thickness(0, 12, 0, 0))), 18));
        }
        foreach (var p in Feed.Posts("sponsor", installed))
        {
            var body = Ui.V(Ui.Cap("Sponsor"));
            if (p["image"]?.ToString() is { Length: > 0 } img && img.StartsWith("https://"))
            {
                try
                {
                    var bi = new BitmapImage(new Uri(img));
                    body.Children.Add(new Image { Source = bi, MaxHeight = 90, Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 8, 0, 0) });
                }
                catch { }
            }
            body.Children.Add(Ui.T(p["title"]?.ToString() ?? "", 14.5, bold: true, wrap: true).With(new Thickness(0, 8, 0, 2)));
            body.Children.Add(Ui.T(p["body"]?.ToString() ?? "", 12, "Muted", wrap: true));
            var card = Ui.SheetCard(body, 18);
            if (p["url"]?.ToString() is { Length: > 0 } url && url.StartsWith("https://"))
            {
                card.Cursor = Cursors.Hand;
                card.ToolTip = url;
                card.MouseLeftButtonUp += (_, _) => OpenPath(url);
            }
            extras.Add(card);
        }
        if (extras.Count == 0) return;
        var row = new System.Windows.Controls.Primitives.UniformGrid { Rows = 1, Margin = new Thickness(0, 12, 0, 0) };
        for (var i = 0; i < extras.Count && i < 3; i++)
        {
            extras[i].Margin = new Thickness(i == 0 ? 0 : 6, 0, i == Math.Min(3, extras.Count) - 1 ? 0 : 6, 0);
            row.Children.Add(extras[i]);
        }
        page.Children.Add(row);
    }

    HashSet<string> installedProjects = new();

    void RefreshInstalledProjects()
    {
        _ = Task.Run(() => ModScan.Scan().Select(m => m.ProjectId).Where(x => x != null).Cast<string>().ToHashSet())
            .ContinueWith(t => { if (!t.IsFaulted) installedProjects = t.Result; });
    }

    void ShowWhatsNew()
    {
        if (testing || Modal.Visibility == Visibility.Visible) return;
        var post = Feed.Posts("whatsnew", installedProjects).OrderByDescending(p => p["at"]?.GetValue<long>() ?? 0).FirstOrDefault();
        var id = post?["id"]?.ToString();
        if (post == null || id == null || id == Settings.SeenWhatsNew) return;
        if (Feed.Preview == null) Settings.SeenWhatsNew = id;
        var ok = Ui.Btn("Got it", "Primary", (_, _) => HideModal());
        ok.HorizontalAlignment = HorizontalAlignment.Right;
        var box = Ui.V(DevMark("What's new"), Ui.T(post["title"]?.ToString() ?? "", 18, bold: true, wrap: true).With(new Thickness(0, 10, 0, 0)),
            Ui.T(post["body"]?.ToString() ?? "", 13, "Soft", wrap: true).With(new Thickness(0, 10, 0, 22)), ok);
        box.Width = 480;
        ShowModal(Ui.Card(box, 26));
    }
}
