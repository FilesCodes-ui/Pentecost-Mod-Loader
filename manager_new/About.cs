using System.Text.RegularExpressions;
using System.Windows.Documents;

namespace Pentecost.Manager;

public partial class MainWindow
{
    static readonly Regex AboutImage = new(@"!\[[^\]]*\]\(\s*<?([^)\s>]+)>?[^)]*\)|<img\b[^>]*?\bsrc\s*=\s*[""']([^""']+)[""'][^>]*>", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    static readonly Regex AboutLink = new(@"\[(?<t>[^\]]+)\]\(\s*<?(?<u>[^)\s>]+)>?[^)]*\)|(?<b>https?://[^\s<>)\]]+)", RegexOptions.Compiled);

    FrameworkElement AboutView(string markdown, string title)
    {
        var stack = new StackPanel { MaxWidth = 700, HorizontalAlignment = HorizontalAlignment.Left };
        var pictures = new List<string>();
        TextBlock? current = null;
        WrapPanel? row = null;
        var blocks = 0;
        var inCode = false;
        var table = new List<string>();
        var shownPics = 0;
        var shownGifs = 0;

        void Flush() { current = null; row = null; }

        TextBlock Line()
        {
            if (current != null) return current;
            row = null;
            var tb = Ui.T("", 14, "Soft", wrap: true);
            tb.LineHeight = 23;
            tb.Margin = new Thickness(0, 0, 0, 12);
            stack.Children.Add(tb);
            current = tb;
            return tb;
        }

        void Text(TextBlock tb, string s)
        {
            var at = 0;
            foreach (Match m in AboutLink.Matches(s))
            {
                if (m.Index > at) tb.Inlines.Add(new Run(Clean(s[at..m.Index])));
                var url = m.Groups["u"].Success ? m.Groups["u"].Value : m.Groups["b"].Value;
                var label = m.Groups["t"].Success ? Clean(m.Groups["t"].Value) : url;
                if (Uri.TryCreate(url, UriKind.Absolute, out var u) && (u.Scheme == "https" || u.Scheme == "http"))
                {
                    var link = new Hyperlink(new Run(label)) { Foreground = Ui.Br("Text"), ToolTip = url, Cursor = Cursors.Hand };
                    link.Click += (_, _) => OpenPath(url);
                    tb.Inlines.Add(link);
                }
                else tb.Inlines.Add(new Run(label));
                at = m.Index + m.Length;
            }
            if (at < s.Length) tb.Inlines.Add(new Run(Clean(s[at..])));
        }

        void Picture(string url, int width)
        {
            if (GifImage.Allowed(url) is not { } uri)
            {
                if (Uri.TryCreate(url, UriKind.Absolute, out var other) && other.Scheme == "https")
                {
                    var tb = Line();
                    if (tb.Inlines.Count > 0) tb.Inlines.Add(new LineBreak());
                    var link = new Hyperlink(new Run("Picture on " + other.Host)) { Foreground = Ui.Br("Muted"), ToolTip = url, Cursor = Cursors.Hand };
                    link.Click += (_, _) => OpenPath(url);
                    tb.Inlines.Add(link);
                }
                return;
            }
            if (++shownPics > 24) return;
            current = null;
            FrameworkElement view;
            if (uri.AbsolutePath.EndsWith(".gif", StringComparison.OrdinalIgnoreCase)) view = new GifImage(url, ++shownGifs <= 3);
            else
            {
                var own = Modrinth.IsModrinthHost(uri.Host);
                var img = Smooth(new Image { Source = Remote(uri), Stretch = Stretch.Uniform, StretchDirection = StretchDirection.DownOnly, MaxHeight = 520 });
                if (img.Source is System.Windows.Media.Imaging.BitmapImage bi)
                {
                    void Small() { if (bi.PixelWidth <= 480) RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.NearestNeighbor); }
                    if (bi.IsDownloading) bi.DownloadCompleted += (_, _) => Small(); else Small();
                }
                if (own)
                {
                    var at = pictures.Count;
                    pictures.Add(url);
                    img.Cursor = Cursors.Hand;
                    img.ToolTip = "Click to see it full size";
                    img.MouseLeftButtonUp += (_, e) => { e.Handled = true; ShowPicture(pictures, at, title); };
                }
                view = img;
            }
            view.MaxWidth = 700;
            if (width > 0 && view is Image sized)
            {
                sized.Width = Math.Min(width, 700);
                sized.StretchDirection = StretchDirection.Both;
            }
            if (row == null)
            {
                row = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 4, 0, 10) };
                stack.Children.Add(row);
            }
            view.Margin = new Thickness(0, 0, 10, 8);
            row.Children.Add(view);
        }

        void FlushTable()
        {
            if (table.Count == 0) return;
            Flush();
            var rows = table.Select(r => Regex.Split(r.Trim().Trim('|'), @"(?<!\\)\|").Select(c => c.Trim()).ToList()).ToList();
            table.Clear();
            var cols = rows.Max(r => r.Count);
            var grid = new Grid { Margin = new Thickness(0, 4, 0, 16) };
            for (var c = 0; c < cols; c++)
            {
                var longest = rows.Max(r => c < r.Count ? Clean(r[c]).Length : 0);
                var word = rows.Max(r => c < r.Count ? Clean(r[c]).Split(' ').Max(w => w.Length) : 0);
                grid.ColumnDefinitions.Add(new ColumnDefinition
                {
                    Width = longest <= 18 ? GridLength.Auto : new GridLength(Math.Clamp(longest, 10, 60), GridUnitType.Star),
                    MinWidth = word * 8 + 16,
                });
            }
            for (var r = 0; r < rows.Count; r++)
            {
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                for (var c = 0; c < rows[r].Count; c++)
                {
                    var cell = Ui.T("", 13, r == 0 ? "Text" : "Soft", bold: r == 0, wrap: true);
                    cell.Margin = new Thickness(0, 5, 14, 5);
                    Text(cell, rows[r][c]);
                    Grid.SetRow(cell, r);
                    Grid.SetColumn(cell, c);
                    grid.Children.Add(cell);
                }
            }
            stack.Children.Add(grid);
            blocks++;
        }

        var source = Regex.Replace(markdown.Replace("\r\n", "\n"), @"<br\s*/?>", "\n", RegexOptions.IgnoreCase);
        foreach (var raw in source.Split('\n'))
        {
            if (blocks > 140 || pictures.Count > 14) break;
            var line = raw.TrimEnd();
            var tl = line.Trim();
            if (!inCode && tl.Contains('|') && Regex.IsMatch(tl, @"^[|:\-\s]+$")) continue;
            if (!inCode && tl.StartsWith("|")) { table.Add(tl); continue; }
            FlushTable();
            if (line.TrimStart().StartsWith("```")) { inCode = !inCode; Flush(); continue; }
            if (inCode)
            {
                var code = Ui.T(line, 12.5, "Soft", wrap: true);
                code.FontFamily = (FontFamily)FindResource("Mono");
                stack.Children.Add(code);
                blocks++;
                continue;
            }

            var images = AboutImage.Matches(line).Select(m => (Url: m.Groups[1].Success ? m.Groups[1].Value : m.Groups[2].Value, Width: TagWidth(m.Value))).ToList();
            var rest = AboutImage.Replace(line, " ");
            rest = Regex.Replace(rest, @"\[\s*\]\([^)]*\)", " ");
            rest = Regex.Replace(rest, @"</?(a|p|div|center|span|picture|source|details|summary)\b[^>]*>", " ", RegexOptions.IgnoreCase);
            rest = Regex.Replace(rest, @"\\\s*$", "").Trim();

            if (images.Count > 0)
            {
                current = null;
                foreach (var i in images) { Picture(i.Url, i.Width); blocks++; }
                if (rest.Length == 0) continue;
            }

            if (rest.Length == 0) { Flush(); continue; }
            if (Regex.IsMatch(rest, @"^\s*([-*_]\s*){3,}$")) { Flush(); stack.Children.Add(Ui.Dotted().With(new Thickness(0, 4, 0, 16))); blocks++; continue; }

            var heading = Regex.Match(rest, @"^\s{0,3}(#{1,6})\s*(.*)$");
            if (heading.Success)
            {
                Flush();
                var level = heading.Groups[1].Length;
                var h = Ui.T(Clean(heading.Groups[2].Value), level == 1 ? 20 : level == 2 ? 17 : 15, "Text", bold: true, wrap: true);
                h.Margin = new Thickness(0, 8, 0, 10);
                stack.Children.Add(h);
                blocks++;
                continue;
            }

            var bullet = Regex.Match(rest, @"^\s*(?:[-*+]|\d+[.)])\s+(.*)$");
            var tbx = Line();
            if (tbx.Inlines.Count > 0) tbx.Inlines.Add(new LineBreak());
            if (bullet.Success) { tbx.Inlines.Add(new Run("\u2022  ")); Text(tbx, bullet.Groups[1].Value); }
            else Text(tbx, rest);
            blocks++;
        }
        FlushTable();
        return stack;
    }

    static System.Windows.Media.Imaging.BitmapImage? Remote(Uri uri)
    {
        try
        {
            var bi = new System.Windows.Media.Imaging.BitmapImage();
            bi.BeginInit();
            bi.UriSource = uri;
            bi.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
            bi.EndInit();
            if (bi.IsDownloading) bi.DownloadFailed += (_, _) => { };
            return bi;
        }
        catch { return null; }
    }

    static int TagWidth(string tag)
    {
        var m = Regex.Match(tag, @"\bwidth\s*=\s*[""']?(\d+)", RegexOptions.IgnoreCase);
        return m.Success && int.TryParse(m.Groups[1].Value, out var w) ? w : 0;
    }

    static string Clean(string s)
    {
        var t = Regex.Replace(s, @"<[^>]+>", "");
        t = Regex.Replace(t, @"\\(?=[^\w\s])", "");
        t = Regex.Replace(t, @"(\*\*|__|~~|`)", "");
        t = Regex.Replace(t, @"(?<![\w*])[*_](?=\S)|(?<=\S)[*_](?![\w*])", "");
        return System.Net.WebUtility.HtmlDecode(t);
    }
}
