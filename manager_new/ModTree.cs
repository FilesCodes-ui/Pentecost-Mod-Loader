using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace Pentecost.Manager;

/// <summary>A folder or file inside a jar.</summary>
sealed class ZNode
{
    public string Name = "", Path = "";
    public bool Dir, Open;
    public long Size, Packed;
    public DateTime Stamp;
    public int Files;
    public List<ZNode> Kids = new();
    public Dictionary<string, ZNode> Map = new(StringComparer.Ordinal);
}

/// <summary>The file tree of a mod, laid out like a file explorer, with a preview of whatever file is picked.</summary>
public partial class MainWindow
{
    static ZNode ReadTree(string file)
    {
        var root = new ZNode { Dir = true, Open = true };
        using var z = ZipFile.OpenRead(file);
        foreach (var e in z.Entries)
        {
            var parts = e.FullName.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) continue;
            var dirOnly = e.FullName.EndsWith('/');
            var cur = root;
            var path = "";
            for (var i = 0; i < parts.Length; i++)
            {
                path = path.Length == 0 ? parts[i] : path + "/" + parts[i];
                var last = i == parts.Length - 1;
                var wantDir = !last || dirOnly;
                if (!cur.Map.TryGetValue(parts[i], out var next))
                {
                    next = new ZNode { Name = parts[i], Path = path, Dir = wantDir };
                    cur.Map[parts[i]] = next;
                    cur.Kids.Add(next);
                }
                if (last && !wantDir)
                {
                    next.Size = e.Length;
                    next.Packed = e.CompressedLength;
                    next.Stamp = e.LastWriteTime.UtcDateTime;
                }
                cur = next;
            }
        }
        Finish(root);
        return root;
    }

    /// <summary>Sorts folders before files, and counts the files and bytes under each folder.</summary>
    static void Finish(ZNode n)
    {
        if (!n.Dir) { n.Files = 1; return; }
        n.Kids.Sort((a, b) => a.Dir != b.Dir ? (a.Dir ? -1 : 1) : string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase));
        n.Files = 0;
        n.Size = 0;
        foreach (var k in n.Kids) { Finish(k); n.Files += k.Files; n.Size += k.Size; }
    }

    static IEnumerable<ZNode> Flat(ZNode n)
    {
        foreach (var k in n.Kids)
        {
            yield return k;
            if (k.Dir) foreach (var x in Flat(k)) yield return x;
        }
    }

    static (byte[]? Data, bool Cut) ReadEntry(string file, string path, long limit)
    {
        try
        {
            using var z = ZipFile.OpenRead(file);
            var e = z.GetEntry(path);
            if (e == null) return (null, false);
            using var s = e.Open();
            var want = (int)Math.Min(limit, e.Length);
            var buf = new byte[want];
            var got = 0;
            while (got < want)
            {
                var r = s.Read(buf, got, want - got);
                if (r <= 0) break;
                got += r;
            }
            return (got == want ? buf : buf[..got], e.Length > limit);
        }
        catch (Exception ex)
        {
            AppLog.Write("read " + path + ": " + ex.Message);
            return (null, false);
        }
    }

    static string Sz(long n) => n < 1024 ? n + " B" : Ui.Bytes(n);

    FrameworkElement ModTree(ModEntry m, Action<FrameworkElement?> show)
    {
        var list = new StackPanel();
        var scroller = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Content = list, Padding = new Thickness(0, 0, 6, 10) };
        var status = Ui.T("Reading the file", 11.5, "Faint", trim: true);
        status.VerticalAlignment = VerticalAlignment.Center;
        ZNode? root = null;
        ZNode? picked = null;
        var filter = "";

        var filterBox = Ui.SearchBox("Filter files", out var ftb);
        ftb.TextChanged += (_, _) => { filter = ftb.Text.Trim(); Draw(); scroller.ScrollToTop(); };
        searchFocus["Mod"] = () => { ftb.Focus(); ftb.SelectAll(); };

        void SetAll(bool open)
        {
            if (root == null) return;
            foreach (var n in Flat(root)) if (n.Dir) n.Open = open;
            Draw();
        }
        var openAll = Ui.Btn("Open all", "Ghost", (_, _) => SetAll(true));
        var closeAll = Ui.Btn("Close all", "Ghost", (_, _) => SetAll(false));
        openAll.Padding = closeAll.Padding = new Thickness(8, 3, 8, 3);
        openAll.FontSize = closeAll.FontSize = 11.5;

        Border Item(string label, string meta, int depth, bool dir, bool open, bool on, Action click, IEnumerable<(string, string?, Action)>? menu)
        {
            var caret = Ui.T(dir ? (open ? "▾" : "▸") : "", 11, "Faint");
            caret.Width = 14;
            caret.TextAlignment = TextAlignment.Center;
            caret.VerticalAlignment = VerticalAlignment.Center;
            var name = Ui.T(label, 12.5, dir || on ? "Text" : "Soft", trim: true);
            name.VerticalAlignment = VerticalAlignment.Center;
            var size = Ui.T(meta, 11, "Faint");
            size.VerticalAlignment = VerticalAlignment.Center;
            size.Margin = new Thickness(10, 0, 0, 0);
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.Children.Add(caret);
            Grid.SetColumn(name, 1);
            g.Children.Add(name);
            Grid.SetColumn(size, 2);
            g.Children.Add(size);
            var row = Ui.HoverRow(new Border
            {
                Child = g, Cursor = Cursors.Hand, Padding = new Thickness(8 + depth * 14, 5, 8, 5),
                BorderThickness = new Thickness(2, 0, 0, 0), BorderBrush = on ? Ui.Br("Accent") : Brushes.Transparent,
            });
            row.MouseLeftButtonUp += (_, _) => click();
            if (menu != null) row.MouseRightButtonUp += (_, _) => Ui.MenuAt(row, menu);
            return row;
        }

        void Draw()
        {
            var keep = scroller.VerticalOffset;
            list.Children.Clear();
            list.Children.Add(Item("Overview", "", 0, false, false, picked == null, () => { picked = null; Draw(); show(null); }, null));
            if (root == null) return;
            var shown = 0;
            void Walk(ZNode d, int depth)
            {
                foreach (var k in d.Kids)
                {
                    if (shown++ > 2500) return;
                    list.Children.Add(Row(k, depth, false));
                    if (k.Dir && k.Open) Walk(k, depth + 1);
                }
            }
            if (filter.Length > 0)
            {
                var hits = Flat(root).Where(n => !n.Dir && n.Path.Contains(filter, StringComparison.OrdinalIgnoreCase)).Take(400).ToList();
                foreach (var n in hits) list.Children.Add(Row(n, 0, true));
                if (hits.Count == 0) list.Children.Add(Ui.T("No file matches that.", 12, "Faint").With(new Thickness(10, 10, 0, 0)));
            }
            else Walk(root, 0);
            if (shown > 2500) list.Children.Add(Ui.T("The list is cut here. Use the filter to find the rest.", 11.5, "Faint", wrap: true).With(new Thickness(10, 8, 0, 0)));
            Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => scroller.ScrollToVerticalOffset(keep));
        }

        Border Row(ZNode n, int depth, bool flat)
        {
            var on = picked == n;
            if (n.Dir)
                return Item(n.Name, Ui.Num(n.Files), depth, true, n.Open, false, () => { n.Open = !n.Open; Draw(); }, new (string, string?, Action)[]
                {
                    (n.Open ? "Close this folder" : "Open this folder", null, () => { n.Open = !n.Open; Draw(); }),
                    ("Open everything inside", null, () => { n.Open = true; foreach (var k in Flat(n)) if (k.Dir) k.Open = true; Draw(); }),
                });
            return Item(flat ? n.Path : n.Name, Sz(n.Size), depth, false, false, on, () => { picked = n; Draw(); show(Preview(m, n)); }, new (string, string?, Action)[]
            {
                ("Look at it", null, () => { picked = n; Draw(); show(Preview(m, n)); }),
                ("Copy its path", null, () => CopyText(n.Path)),
                ("Save a copy", null, () => SaveCopy(m, n)),
            });
        }

        async void Load()
        {
            var (tree, err) = await Task.Run(() =>
            {
                try { return (ReadTree(m.File), (string?)null); }
                catch (Exception ex) { return ((ZNode?)null, ex.Message); }
            });
            if (tree == null)
            {
                status.Text = "Could not open the file";
                list.Children.Add(Ui.T(err ?? "", 11.5, "Warn", wrap: true).With(new Thickness(10, 10, 0, 0)));
                return;
            }
            root = tree;
            status.Text = $"{Ui.Num(tree.Files)} files, {Sz(new FileInfo(m.File).Length)}";
            // The folders people look at first are already open.
            foreach (var k in tree.Kids.Where(k => k.Dir && k.Name is "assets" or "data" or "META-INF")) { k.Open = false; }
            Draw();
        }

        var head = Ui.V(filterBox.With(new Thickness(0, 0, 0, 8)),
            Ui.Row(status, Ui.H(openAll, closeAll)).With(new Thickness(0, 0, 0, 6)));
        var grid = new Grid { Margin = new Thickness(0, 0, 0, 0) };
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        grid.Children.Add(head);
        Grid.SetRow(scroller, 1);
        grid.Children.Add(scroller);

        Draw();
        Load();
        return new Border
        {
            Padding = new Thickness(0, 18, 12, 0), BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 1, 0), Child = grid,
        };
    }

    void CopyText(string text)
    {
        try { Clipboard.SetText(text); Toast("Copied."); }
        catch (Exception ex) { AppLog.Write("copy: " + ex.Message); }
    }

    async void SaveCopy(ModEntry m, ZNode n)
    {
        if (testing) return;
        var dlg = new Microsoft.Win32.SaveFileDialog { Title = "Save a copy of " + n.Name, FileName = n.Name };
        if (dlg.ShowDialog(this) != true) return;
        var (data, _) = await Task.Run(() => ReadEntry(m.File, n.Path, long.MaxValue));
        if (data == null) { Toast("Could not read that file out of the mod.", "Bad"); return; }
        try { File.WriteAllBytes(dlg.FileName, data); Toast("Saved " + n.Name); }
        catch (Exception ex) { Toast("Could not save it: " + ex.Message, "Bad"); }
    }

    // ---------------------------------------------------------------- preview of one file

    static readonly HashSet<string> Pictures = new(StringComparer.OrdinalIgnoreCase) { ".png", ".jpg", ".jpeg", ".gif", ".bmp" };

    FrameworkElement Preview(ModEntry m, ZNode n)
    {
        var grid = new Grid { Margin = new Thickness(26, 18, 26, 20) };
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var copy = Ui.Btn("Copy path", "Secondary", (_, _) => CopyText(n.Path));
        var save = Ui.Btn("Save a copy", "Secondary", (_, _) => SaveCopy(m, n));
        var buttons = Ui.H(copy.With(new Thickness(0, 0, 8, 0)), save);
        buttons.VerticalAlignment = VerticalAlignment.Center;
        var title = Ui.V(Ui.T(n.Name, 17, bold: true, trim: true), Ui.T(n.Path, 11.5, "Faint", wrap: true).With(new Thickness(0, 3, 0, 0)));
        var top = Ui.Row(title, buttons.With(new Thickness(16, 0, 0, 0)));
        grid.Children.Add(top);

        var date = n.Stamp.Year > 1990 ? "    changed " + n.Stamp.ToLocalTime().ToString("yyyy-MM-dd") : "";
        var facts = Ui.T(Sz(n.Size) + (n.Packed > 0 && n.Packed != n.Size ? "  (" + Sz(n.Packed) + " packed)" : "") + date, 12, "Muted");
        facts.Margin = new Thickness(0, 10, 0, 12);
        Grid.SetRow(facts, 1);
        grid.Children.Add(facts);

        var host = new ContentControl { IsTabStop = false, HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
        host.Content = Ui.T("Reading", 12.5, "Faint");
        Grid.SetRow(host, 2);
        grid.Children.Add(host);
        Fill(m, n, host);
        return grid;
    }

    async void Fill(ModEntry m, ZNode n, ContentControl host)
    {
        var ext = System.IO.Path.GetExtension(n.Name);
        if (n.Size == 0) { host.Content = Note("This file is empty."); return; }
        var isPicture = Pictures.Contains(ext);
        var (data, cut) = await Task.Run(() => ReadEntry(m.File, n.Path, isPicture ? 4_000_000 : 200_000));
        if (data == null) { host.Content = Note("Could not read this file."); return; }
        if (isPicture) { host.Content = PictureView(data); return; }

        var sniff = Math.Min(data.Length, 1500);
        var binary = false;
        for (var i = 0; i < sniff; i++) if (data[i] == 0) { binary = true; break; }
        if (binary || data.Length >= 4 && data[0] == 0xCA && data[1] == 0xFE && data[2] == 0xBA && data[3] == 0xBE)
        {
            host.Content = Note(ext.Equals(".class", StringComparison.OrdinalIgnoreCase)
                ? "Compiled Java code. The game runs it, so there is nothing here to read."
                : ext.Equals(".ogg", StringComparison.OrdinalIgnoreCase) ? "A sound file. Use Save a copy to listen to it."
                : "This is not a text or picture file, so there is no preview. Use Save a copy to take it out.");
            return;
        }

        var text = Encoding.UTF8.GetString(data);
        if (!cut && (ext.Equals(".json", StringComparison.OrdinalIgnoreCase) || ext.Equals(".mcmeta", StringComparison.OrdinalIgnoreCase)))
        {
            try { text = JsonNode.Parse(text)?.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }) ?? text; }
            catch { }
        }
        var box = new TextBox
        {
            IsReadOnly = true, Text = text, BorderThickness = new Thickness(0), Background = Brushes.Transparent, Padding = new Thickness(14, 10, 14, 10),
            FontFamily = (FontFamily)FindResource("Mono"), FontSize = 12, Foreground = Ui.Br("Soft"),
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, TextWrapping = TextWrapping.NoWrap,
        };
        var frame = new Grid();
        frame.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        frame.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        frame.Children.Add(box);
        if (cut)
        {
            var more = Ui.T("Showing the first 200 KB. Use Save a copy for the rest.", 11.5, "Faint");
            more.Margin = new Thickness(2, 8, 0, 0);
            Grid.SetRow(more, 1);
            frame.Children.Add(more);
        }
        host.Content = new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Background = Ui.Br("Panel"), Child = frame };
        Ui.FadeIn((UIElement)host.Content, 140);
    }

    static FrameworkElement Note(string text) => Ui.T(text, 13, "Soft", wrap: true);

    static FrameworkElement PictureView(byte[] data)
    {
        try
        {
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.StreamSource = new MemoryStream(data);
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.EndInit();
            bi.Freeze();
            var small = bi.PixelWidth <= 128;
            var img = new Image { Source = bi, Stretch = Stretch.Uniform, MaxHeight = 520, MaxWidth = 900 };
            if (small) img.Width = Math.Min(512, bi.PixelWidth * 8);
            RenderOptions.SetBitmapScalingMode(img, small ? BitmapScalingMode.NearestNeighbor : BitmapScalingMode.HighQuality);
            var frame = new Border { Background = Ui.Br("Panel2"), BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(1), Padding = new Thickness(12), Child = img, HorizontalAlignment = HorizontalAlignment.Left };
            var box = Ui.V(frame, Ui.T($"{bi.PixelWidth} by {bi.PixelHeight} pixels", 11.5, "Faint").With(new Thickness(0, 8, 0, 0)));
            return new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = box };
        }
        catch { return Note("Pentecost cannot show this picture."); }
    }
}
