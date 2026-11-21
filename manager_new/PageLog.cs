using System.Windows.Documents;
using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow
{
    static int LogCap => Math.Max(500, Settings.LogLines);
    int logFilter;
    string logQuery = "";
    Action<string>? logLive;
    Action? logReset;

    static int LogKind(string l)
    {
        if (l.Length == 0) return 3;
        var t = l.TrimStart();
        if (((l[0] == ' ' || l[0] == '\t') && t.StartsWith("at ")) || t.StartsWith("Caused by") || (t.StartsWith("... ") && t.EndsWith(" more"))) return 4;
        if (l.Contains("/FATAL]") || l.Contains("/ERROR]") || l.Contains("Exception:") || l.Contains("Exception in thread")) return 2;
        if (l.Contains("/WARN]")) return 1;
        if (l.Contains("/DEBUG]") || l.Contains("/TRACE]")) return 3;
        return 0;
    }

    static string LogBrush(int kind) => kind switch { 1 => "Warn", 2 => "Bad", 3 => "Faint", 4 => "Muted", _ => "Soft" };

    void AppendLog(string line)
    {
        logLines.Add(line);
        if (logLines.Count > LogCap)
        {
            logLines.RemoveRange(0, LogCap / 2);
            logReset?.Invoke();
            return;
        }
        logLive?.Invoke(line);
    }

    void ClearLog()
    {
        logLines.Clear();
        logReset?.Invoke();
    }

    FrameworkElement BuildLog()
    {
        var mono = (FontFamily)FindResource("Mono");
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        const double headH = 230;
        const double seaH = 150;
        var head = new Grid { Height = headH, ClipToBounds = true };
        var fade = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 0));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.04));
        fade.GradientStops.Add(new GradientStop(Colors.Black, 0.96));
        fade.GradientStops.Add(new GradientStop(Colors.Transparent, 1));
        var scene = new Grid { OpacityMask = fade };
        scene.Children.Add(new CloudSea { Big = true, BareBg = "Bg", BareTop = 40 / seaH, Height = seaH, VerticalAlignment = VerticalAlignment.Bottom, IsHitTestVisible = false });
        head.Children.Add(scene);

        var title = new Wordmark { Text = "LOG", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left };
        var tally = Ui.T("", 12, "Soft");
        tally.FontFamily = mono;
        var topLeft = Ui.V(title, tally.With(new Thickness(2, 8, 0, 0)));
        topLeft.HorizontalAlignment = HorizontalAlignment.Left;
        topLeft.VerticalAlignment = VerticalAlignment.Top;
        topLeft.Margin = new Thickness(0, 6, 0, 0);
        topLeft.IsHitTestVisible = false;
        head.Children.Add(topLeft);

        var doc = new FlowDocument { PagePadding = new Thickness(16, 12, 16, 12), FontFamily = mono, FontSize = 12, Foreground = Ui.Br("Soft") };
        var box = new RichTextBox
        {
            IsReadOnly = true, IsDocumentEnabled = false, Document = doc, Background = Brushes.Transparent, BorderThickness = new Thickness(0),
            Foreground = Ui.Br("Soft"), VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };

        if (!Settings.LogWrap) { doc.PageWidth = 20000; box.HorizontalScrollBarVisibility = ScrollBarVisibility.Auto; }
        var follow = true;
        var warns = 0;
        var errors = 0;
        var pending = new List<string>();
        Button followBtn = null!;

        void Tally()
        {
            var n = logLines.Count;
            tally.Text = (game.Running ? "LIVE   " : "") + (n == 1 ? "1 LINE" : n.ToString("N0") + " LINES") + "   " + warns + " WARN   " + errors + " ERROR";
        }

        bool Show(string l)
        {
            var k = LogKind(l);
            if (logFilter == 1 && k is 0 or 3) return false;
            if (logFilter == 2 && k is not (2 or 4)) return false;
            return logQuery.Length == 0 || l.Contains(logQuery, StringComparison.OrdinalIgnoreCase);
        }

        Paragraph Para(string l, string? brush = null) =>
            new(new Run(l) { Foreground = Ui.Br(brush ?? LogBrush(LogKind(l))) }) { Margin = new Thickness(0), LineHeight = 18 };

        void Fill()
        {
            pending.Clear();
            doc.Blocks.Clear();
            warns = 0;
            errors = 0;
            if (logLines.Count == 0)
            {
                doc.Blocks.Add(Para("Nothing here yet. Press Play, or load latest.log.", "Faint"));
                Tally();
                return;
            }
            var shown = new List<Block>();
            foreach (var l in logLines)
            {
                var k = LogKind(l);
                if (k == 1) warns++; else if (k == 2) errors++;
                if (Show(l)) shown.Add(Para(l));
            }
            doc.Blocks.AddRange(shown);
            Tally();
            if (follow) box.ScrollToEnd();
        }

        void Flush()
        {
            if (pending.Count == 0) return;
            if (logLines.Count == pending.Count) doc.Blocks.Clear();
            foreach (var l in pending)
            {
                var k = LogKind(l);
                if (k == 1) warns++; else if (k == 2) errors++;
                if (Show(l)) doc.Blocks.Add(Para(l));
            }
            pending.Clear();
            while (doc.Blocks.Count > LogCap) doc.Blocks.Remove(doc.Blocks.FirstBlock);
            Tally();
            if (follow) box.ScrollToEnd();
        }

        var flush = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        flush.Tick += (_, _) => Flush();
        logLive = l => pending.Add(l);
        logReset = Fill;
        box.Loaded += (_, _) => { Fill(); flush.Start(); };
        box.Unloaded += (_, _) => flush.Stop();
        box.AddHandler(ScrollViewer.ScrollChangedEvent, new ScrollChangedEventHandler((_, e) =>
        {
            if (e.ExtentHeightChange != 0 || e.VerticalChange == 0) return;
            var atEnd = e.VerticalOffset + e.ViewportHeight >= e.ExtentHeight - 4;
            if (atEnd == follow) return;
            follow = atEnd;
            followBtn.Style = Ui.St(follow ? "Primary" : "Ghost");
        }));

        followBtn = Ui.Btn("Follow", follow ? "Primary" : "Ghost", (_, _) =>
        {
            follow = !follow;
            followBtn.Style = Ui.St(follow ? "Primary" : "Ghost");
            if (follow) box.ScrollToEnd();
        });

        if (Settings.LogAutoLoad && logLines.Count == 0 && !game.Running) LoadLatestLog(false);

        var latestBtn = Ui.Btn("Load latest.log", "Secondary", (_, _) => LoadLatestLog(true));
        var copy = Ui.Btn("Copy all", "Secondary", (_, _) =>
        {
            if (logLines.Count == 0) return;
            try { Clipboard.SetText(string.Join("\n", logLines)); Toast("Log copied."); } catch { Toast("Could not copy.", "Warn"); }
        });
        var clear = Ui.Btn("Clear", "Secondary", (_, _) => ClearLog());
        var folder = Ui.Btn("Folder", "Secondary", (_, _) => OpenFolder(Paths.Logs));
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 10, 0, 0) };
        actions.Children.Add(latestBtn.With(new Thickness(0, 0, 8, 0)));
        actions.Children.Add(copy.With(new Thickness(0, 0, 8, 0)));
        actions.Children.Add(clear.With(new Thickness(0, 0, 8, 0)));
        actions.Children.Add(folder);
        head.Children.Add(actions);
        root.Children.Add(head);

        var chips = new List<Button>();
        var chipRow = new StackPanel { Orientation = Orientation.Horizontal };
        var names = new[] { "All", "Warnings", "Errors" };
        for (var i = 0; i < names.Length; i++)
        {
            var k = i;
            var chip = Ui.Btn(names[i], k == logFilter ? "Primary" : "Ghost");
            chip.Click += (_, _) =>
            {
                logFilter = k;
                for (var j = 0; j < chips.Count; j++) chips[j].Style = Ui.St(j == k ? "Primary" : "Ghost");
                Fill();
            };
            chips.Add(chip);
            chipRow.Children.Add(chip.With(new Thickness(0, 0, 6, 0)));
        }

        var search = new TextBox
        {
            Width = 240, Height = 32, Padding = new Thickness(10, 0, 10, 0), VerticalContentAlignment = VerticalAlignment.Center, FontFamily = mono, FontSize = 12,
            Background = Ui.Br("Panel"), BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Foreground = Ui.Br("Text"), CaretBrush = Ui.Br("Text"), Text = logQuery,
        };
        var hint = Ui.T("SEARCH", 11, "Faint");
        hint.FontFamily = mono;
        hint.IsHitTestVisible = false;
        hint.Margin = new Thickness(11, 0, 0, 0);
        hint.VerticalAlignment = VerticalAlignment.Center;
        hint.Visibility = logQuery.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        search.TextChanged += (_, _) =>
        {
            logQuery = search.Text;
            hint.Visibility = logQuery.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            Fill();
        };
        var searchHolder = new Grid { Margin = new Thickness(14, 0, 14, 0) };
        searchHolder.Children.Add(search);
        searchHolder.Children.Add(hint);

        var bar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 8, 0, 0) };
        bar.Children.Add(chipRow);
        bar.Children.Add(searchHolder);
        bar.Children.Add(followBtn);
        Grid.SetRow(bar, 1);
        root.Children.Add(bar);

        var card = Ui.Card(box, 0);
        card.Margin = new Thickness(0, 14, 0, 28);
        Grid.SetRow(card, 2);
        root.Children.Add(card);
        return root;
    }

    void LoadLatestLog(bool announce)
    {
        try
        {
            var f = Path.Combine(Paths.Logs, "latest.log");
            if (!File.Exists(f)) { if (announce) Toast("There is no latest.log yet.", "Warn"); return; }
            using var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            var keep = 250_000L;
            var cut = fs.Length > keep;
            if (cut) fs.Seek(-keep, SeekOrigin.End);
            using var sr = new StreamReader(fs);
            var lines = sr.ReadToEnd().Split('\n').Select(s => s.TrimEnd('\r')).ToList();
            if (cut && lines.Count > 0) lines.RemoveAt(0);
            if (lines.Count > 0 && lines[^1].Length == 0) lines.RemoveAt(lines.Count - 1);
            logLines.Clear();
            logLines.AddRange(lines.TakeLast(LogCap));
            logReset?.Invoke();
            if (announce) Toast("Loaded latest.log.");
        }
        catch (Exception ex) { if (announce) Toast("Could not read the log: " + ex.Message, "Bad"); }
    }
}
