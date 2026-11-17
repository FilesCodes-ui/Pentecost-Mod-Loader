using System.Linq;
using System.Windows.Controls.Primitives;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

/// <summary>Small builders so the pages read as layout, not as markup.</summary>
public static class Ui
{
    public static Brush Br(string key) => (Brush)Application.Current.FindResource(key);
    public static Style St(string key) => (Style)Application.Current.FindResource(key);
    static Color Col(string key) => ((SolidColorBrush)Br(key)).Color;
    static FontFamily Icons => (FontFamily)Application.Current.FindResource("Icons");

    public static TextBlock T(string text, double size = 13.5, string? fg = null, bool bold = false, bool wrap = false, bool trim = false)
    {
        var t = new TextBlock { Text = text, FontSize = size };
        if (fg != null) t.Foreground = Br(fg);
        if (bold) t.FontWeight = FontWeights.SemiBold;
        if (wrap) t.TextWrapping = TextWrapping.Wrap;
        if (trim) { t.TextTrimming = TextTrimming.CharacterEllipsis; t.TextWrapping = TextWrapping.NoWrap; }
        return t;
    }

    public static TextBlock Cap(string text) => new() { Text = text.ToUpperInvariant(), Style = St("Cap") };

    public static TextBlock Glyph(string g, double size = 14, string fg = "Muted") =>
        new() { Text = g, FontFamily = Icons, FontSize = size, Foreground = Br(fg), VerticalAlignment = VerticalAlignment.Center };

    public static Button Btn(string text, string style, RoutedEventHandler? click = null, string? glyph = null, double size = 0)
    {
        object content = text;
        if (glyph != null)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            var g = new TextBlock { Text = glyph, FontFamily = Icons, FontSize = 13, Margin = new Thickness(0, 1, text.Length > 0 ? 9 : 0, 0), VerticalAlignment = VerticalAlignment.Center };
            sp.Children.Add(g);
            if (text.Length > 0) sp.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center });
            content = sp;
        }
        var b = new Button { Content = content, Style = St(style) };
        if (glyph != null)
        {
            b.Foreground = Br(style is "Primary" or "AccentBtn" ? "Bg" : style == "Secondary" ? "Text" : "Muted");
            foreach (var tb in ((StackPanel)content).Children.OfType<TextBlock>())
                tb.SetBinding(TextBlock.ForegroundProperty, new System.Windows.Data.Binding("Foreground") { Source = b });
        }
        if (size > 0) b.FontSize = size;
        if (click != null) b.Click += click;
        return b;
    }

    public static Button IconBtn(string glyph, string tip, RoutedEventHandler click)
    {
        var b = new Button { Content = glyph, Style = St("IconBtn"), ToolTip = tip };
        b.Click += click;
        return b;
    }

    /// <summary>A panel with pixel-cut corners.</summary>
    /// <summary>A card that also shows the shared pixel sheet. Only Home and the Browse cards use it.</summary>
    public static PixelBox SheetCard(UIElement child, double pad = 20)
    {
        var c = Card(child, pad);
        c.Padding = new Thickness(0);
        child = c.Child!;
        c.Child = null;
        var inner = new Grid { ClipToBounds = true };
        inner.Children.Add(new DitherBackdrop());
        if (child is FrameworkElement fe) fe.Margin = new Thickness(pad);
        inner.Children.Add(child);
        c.Child = inner;
        return c;
    }

    public static PixelBox Card(UIElement child, double pad = 20) => new()
    {
        Background = Br("Panel"), BorderBrush = Br("Line"), BorderThickness = new Thickness(1),
        Padding = new Thickness(pad), Child = child, Notch = 3,
    };

    public static PixelBox Pill(string text, string fg = "Muted")
    {
        var tb = new TextBlock { Text = text.ToUpperInvariant(), Style = St("Cap"), Foreground = Br(fg) };
        return new PixelBox { BorderBrush = Br("Line"), BorderThickness = new Thickness(1), Padding = new Thickness(7, 2, 7, 2), Margin = new Thickness(0, 0, 6, 0), Child = tb, Notch = 1.5, VerticalAlignment = VerticalAlignment.Center };
    }

    public static Border Dot(string color, double size = 8) => new()
    {
        Width = size, Height = size, Background = Br(color), VerticalAlignment = VerticalAlignment.Center,
    };

    /// <summary>A picture from Modrinth's own servers, or null when the link is not one of theirs.</summary>
    public static BitmapImage? Picture(string? url, int decodeWidth = 0, int decodeHeight = 0)
    {
        if (Modrinth.SafeImage(url) is not { } uri) return null;
        try
        {
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.UriSource = uri;
            if (decodeWidth > 0) bi.DecodePixelWidth = decodeWidth;
            if (decodeHeight > 0) bi.DecodePixelHeight = decodeHeight;
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.EndInit();
            if (bi.IsDownloading) bi.DownloadFailed += (_, _) => { };
            return bi;
        }
        catch { return null; }
    }

    /// <summary>A mod''s picture, kept once it has been decoded.</summary>
    static BitmapSource? FromJar(ModEntry m)
    {
        if (m.IconCache is BitmapSource done) return done;
        if (m.IconData == null) return null;
        try
        {
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.StreamSource = new MemoryStream(m.IconData);
            bi.DecodePixelWidth = 128;
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.EndInit();
            bi.Freeze();
            m.IconCache = bi;
            return bi;
        }
        catch { m.IconData = null; return null; }
    }

    /// <summary>
    /// A square icon. The mod''s own picture out of its jar comes first, because that works with no internet and for
    /// mods Modrinth has never heard of. Then Modrinth''s picture, then the first letter.
    /// </summary>
    public static FrameworkElement Tile(string? iconUrl, string name, double size, ModEntry? mod = null)
    {
        var grid = new Grid();
        var letter = name.Trim().Length > 0 ? char.ToUpperInvariant(name.Trim()[0]).ToString() : "?";
        grid.Children.Add(new TextBlock { Text = letter, FontSize = size * 0.42, Foreground = Br("Faint"), FontWeight = FontWeights.SemiBold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center });
        var source = (mod != null ? FromJar(mod) : null) ?? (BitmapSource?)Picture(iconUrl, (int)(size * 2));
        if (source != null)
        {
            var img = new Image { Source = source, Stretch = Stretch.UniformToFill };
            // Mod icons are small pixel art, so they are kept crisp instead of smoothed.
            RenderOptions.SetBitmapScalingMode(img, source.PixelWidth <= 64 ? BitmapScalingMode.NearestNeighbor : BitmapScalingMode.HighQuality);
            grid.Children.Add(img);
        }
        return new Border { Width = size, Height = size, Background = Br("Panel2"), BorderBrush = Br("Line"), BorderThickness = new Thickness(1), Child = grid, ClipToBounds = true, VerticalAlignment = VerticalAlignment.Center };
    }
    public static string Num(long n) => n switch
    {
        >= 1_000_000_000 => (n / 1_000_000_000.0).ToString("0.#") + "B",
        >= 1_000_000 => (n / 1_000_000.0).ToString("0.#") + "M",
        >= 1_000 => (n / 1_000.0).ToString("0.#") + "K",
        _ => n.ToString(),
    };

    public static string Bytes(long n) => n >= 1_048_576 ? (n / 1_048_576.0).ToString("0.#") + " MB" : Math.Max(1, n / 1024) + " KB";

    public static string Ago(DateTime t)
    {
        var s = DateTime.Now - t;
        if (s.TotalMinutes < 1) return "just now";
        if (s.TotalMinutes < 60) return $"{(int)s.TotalMinutes} min ago";
        if (s.TotalHours < 24) return $"{(int)s.TotalHours} h ago";
        if (s.TotalDays < 30) return $"{(int)s.TotalDays} days ago";
        return t.ToString("yyyy-MM-dd");
    }

    /// <summary>A two-column row: content on the left, controls on the right.</summary>
    public static Grid Row(UIElement left, UIElement? right = null)
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition());
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(left);
        if (right != null) { Grid.SetColumn(right, 1); g.Children.Add(right); }
        return g;
    }

    public static StackPanel H(params UIElement[] kids)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var k in kids) p.Children.Add(k);
        return p;
    }

    public static StackPanel V(params UIElement[] kids)
    {
        var p = new StackPanel();
        foreach (var k in kids) p.Children.Add(k);
        return p;
    }

    public static Grid SearchBox(string hint, out TextBox box)
    {
        var tb = new TextBox { Padding = new Thickness(40, 0, 36, 0), Height = 40, VerticalContentAlignment = VerticalAlignment.Center };
        var icon = Glyph("", 13, "Faint");
        icon.Margin = new Thickness(15, 0, 0, 0);
        icon.HorizontalAlignment = HorizontalAlignment.Left;
        icon.IsHitTestVisible = false;
        var ph = T(hint, 13.5, "Faint");
        ph.Margin = new Thickness(41, 0, 0, 0);
        ph.VerticalAlignment = VerticalAlignment.Center;
        ph.IsHitTestVisible = false;
        var clear = IconBtn("", "Clear the search (Esc)", (_, _) => { tb.Clear(); tb.Focus(); });
        clear.Width = 30;
        clear.Height = 30;
        clear.HorizontalAlignment = HorizontalAlignment.Right;
        clear.Margin = new Thickness(0, 0, 5, 0);
        clear.Visibility = Visibility.Collapsed;
        tb.TextChanged += (_, _) =>
        {
            var empty = tb.Text.Length == 0;
            ph.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
            clear.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
        };
        tb.KeyDown += (_, e) => { if (e.Key == Key.Escape && tb.Text.Length > 0) { tb.Clear(); e.Handled = true; } };
        var g = new Grid();
        g.Children.Add(tb);
        g.Children.Add(icon);
        g.Children.Add(ph);
        g.Children.Add(clear);
        box = tb;
        return g;
    }
    public static FrameworkElement With(this FrameworkElement e, Thickness margin) { e.Margin = margin; return e; }

    /// <summary>
    /// Opens a list of choices under a button, and closes it when the same button is pressed again. Attach it once:
    /// it keeps one menu per button, so pressing the button is always a toggle.
    /// </summary>
    public static void Menu(ButtonBase owner, Func<IEnumerable<(string Label, Action Do, bool Ticked)>> items)
    {
        var list = new StackPanel();
        var pop = new Popup
        {
            PlacementTarget = owner, Placement = PlacementMode.Bottom, VerticalOffset = 4, StaysOpen = false, AllowsTransparency = true,
            Child = new PixelBox { Background = Br("Panel2"), BorderBrush = Br("Edge"), BorderThickness = new Thickness(1), Notch = 3, Padding = new Thickness(4), Child = list },
        };
        var closedAt = DateTime.MinValue;
        pop.Closed += (_, _) => closedAt = DateTime.Now;
        pop.Opened += (_, _) => { if (pop.Child is FrameworkElement c) Rise(c, 8, 150); };

        owner.Click += (_, _) =>
        {
            // Clicking the button while the menu is open already closed it, so a press just then means "close".
            if (pop.IsOpen || (DateTime.Now - closedAt).TotalMilliseconds < 250) { pop.IsOpen = false; return; }
            list.Children.Clear();
            foreach (var (label, act, ticked) in items())
            {
                var b = Btn(label, "Ghost", (_, _) => { pop.IsOpen = false; act(); }, ticked ? "" : null);
                b.HorizontalContentAlignment = HorizontalAlignment.Left;
                b.Padding = new Thickness(12, 8, 18, 8);
                if (ticked) b.Foreground = Br("Text");
                list.Children.Add(b);
            }
            pop.MinWidth = owner.ActualWidth;
            pop.IsOpen = true;
        };
        owner.KeyDown += (_, e) => { if (e.Key == Key.Escape) pop.IsOpen = false; };
    }
    /// <summary>A list of choices at the mouse, for a right-click.</summary>
    public static void MenuAt(FrameworkElement near, IEnumerable<(string Label, string? Glyph, Action Do)> items)
    {
        var list = new StackPanel();
        var pop = new Popup
        {
            PlacementTarget = near, Placement = PlacementMode.MousePoint, StaysOpen = false, AllowsTransparency = true,
            Child = new PixelBox { Background = Br("Panel2"), BorderBrush = Br("Edge"), BorderThickness = new Thickness(1), Notch = 3, Padding = new Thickness(4), Child = list },
        };
        foreach (var (label, glyph, act) in items)
        {
            var b = Btn(label, "Ghost", (_, _) => { pop.IsOpen = false; act(); }, glyph);
            b.HorizontalContentAlignment = HorizontalAlignment.Left;
            b.Padding = new Thickness(12, 8, 22, 8);
            list.Children.Add(b);
        }
        pop.Opened += (_, _) => { if (pop.Child is FrameworkElement c) Rise(c, 6, 130); };
        pop.IsOpen = true;
    }

    // ---------------------------------------------------------------- motion

    static readonly Duration Quick = TimeSpan.FromMilliseconds(140);
    static CubicEase Out => new() { EasingMode = EasingMode.EaseOut };

    /// <summary>Fades an element in while it slides up into place.</summary>
    public static void Rise(UIElement e, double from = 10, int ms = 220, int delayMs = 0)
    {
        var move = new TranslateTransform(0, from);
        e.RenderTransform = move;
        e.Opacity = 0;
        var begin = TimeSpan.FromMilliseconds(delayMs);
        e.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(ms)) { BeginTime = begin });
        move.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(from, 0, TimeSpan.FromMilliseconds(ms)) { BeginTime = begin, EasingFunction = Out });
    }

    /// <summary>The same, one after another, so a page arrives in order.</summary>
    public static void Stagger(IEnumerable<UIElement> items, int step = 45, int ms = 240)
    {
        var i = 0;
        foreach (var e in items.Take(8)) Rise(e, 12, ms, i++ * step);
    }

    /// <summary>A card that brightens and takes a white edge under the mouse. Nothing moves, so nothing spills out of a list.</summary>
    public static T Lift<T>(T b) where T : Border
    {
        var edge = new SolidColorBrush(Col("Line"));
        var back = new SolidColorBrush(Col("Panel"));
        b.BorderBrush = edge;
        b.Background = back;
        b.MouseEnter += (_, _) =>
        {
            edge.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Col("Edge"), Quick));
            back.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Col("Panel2"), Quick));
        };
        b.MouseLeave += (_, _) =>
        {
            edge.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Col("Line"), Quick));
            back.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Col("Panel"), Quick));
        };
        return b;
    }

    /// <summary>A list row that lights up under the mouse.</summary>
    public static T HoverRow<T>(T b) where T : Border
    {
        var brush = new SolidColorBrush(Color.FromArgb(0, 255, 255, 255));
        b.Background = brush;
        b.MouseEnter += (_, _) => brush.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Color.FromArgb(16, 255, 255, 255), Quick));
        b.MouseLeave += (_, _) => brush.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Color.FromArgb(0, 255, 255, 255), Quick));
        return b;
    }

    /// <summary>A slow glow, for the one button that matters.</summary>
    public static void Pulse(FrameworkElement e)
    {
        var glow = new DropShadowEffect { Color = Col("Accent"), BlurRadius = 26, ShadowDepth = 0, Opacity = 0.2 };
        e.Effect = glow;
        glow.BeginAnimation(DropShadowEffect.OpacityProperty, new DoubleAnimation(0.16, 0.5, TimeSpan.FromSeconds(2.2))
        {
            AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut },
        });
    }

    public static void FadeIn(UIElement e, int ms = 160) =>
        e.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(ms)));

    // ---------------------------------------------------------------- hover cards

    /// <summary>Shows a card beside the mouse after a short hover. The card is built the first time it is needed.</summary>
    public static void HoverCard(FrameworkElement target, Func<FrameworkElement> build)
    {
        var tip = new ToolTip { Padding = new Thickness(0), BorderThickness = new Thickness(0), Background = Brushes.Transparent, Placement = PlacementMode.Mouse, HorizontalOffset = 18, VerticalOffset = 20 };
        target.ToolTip = tip;
        target.ToolTipOpening += (_, _) => { if (tip.Content == null) tip.Content = build(); };
        ToolTipService.SetInitialShowDelay(target, 450);
        ToolTipService.SetShowDuration(target, 60000);
    }

    /// <summary>A row of square dots, the pixel version of a thin rule.</summary>
    public static Border Dotted(string color = "Edge", double step = 4)
    {
        var dot = new GeometryDrawing(Br(color), null, new RectangleGeometry(new Rect(0, 0, step / 2, 2)));
        var brush = new DrawingBrush(dot)
        {
            TileMode = TileMode.Tile, Stretch = Stretch.None,
            Viewport = new Rect(0, 0, step, 2), ViewportUnits = BrushMappingMode.Absolute,
            Viewbox = new Rect(0, 0, step, 2), ViewboxUnits = BrushMappingMode.Absolute,
        };
        return new Border { Height = 2, Background = brush, SnapsToDevicePixels = true };
    }

    /// <summary>A titled panel: a small square, the title in pixel caps, a dotted rule, then the content.</summary>
    public static PixelBox Section(string title, UIElement body, UIElement? right = null)
    {
        var name = H(Dot("Accent", 6).With(new Thickness(0, 0, 10, 0)), Cap(title));
        name.VerticalAlignment = VerticalAlignment.Center;
        var head = Row(name, right);
        var box = V(head, Dotted().With(new Thickness(0, 10, 0, 14)), body);
        var card = Card(box, 18);
        card.Margin = new Thickness(0, 0, 0, 14);
        return card;
    }

    /// <summary>The card a hover shows: a framed icon, the name, a dotted rule, a short description and tags.</summary>
    public static FrameworkElement Preview(string? icon, string title, string? sub, string? desc, IEnumerable<string>? tags, string? foot, ModEntry? mod = null)
    {
        var text = new StackPanel { Margin = new Thickness(14, 0, 0, 0), Width = 290 };
        text.Children.Add(T(title, 15, bold: true, wrap: true));
        if (!string.IsNullOrWhiteSpace(sub)) text.Children.Add(T(sub!, 11.5, "Faint", wrap: true).With(new Thickness(0, 2, 0, 0)));
        text.Children.Add(Dotted().With(new Thickness(0, 10, 0, 9)));
        if (!string.IsNullOrWhiteSpace(desc)) text.Children.Add(T(desc!, 12.5, "Soft", wrap: true));
        var list = tags?.Where(t => t.Length > 0).Take(6).ToList();
        if (list is { Count: > 0 })
        {
            var wrap = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
            foreach (var t in list) { var p = Pill(t); p.Margin = new Thickness(0, 0, 6, 4); wrap.Children.Add(p); }
            text.Children.Add(wrap);
        }
        if (!string.IsNullOrWhiteSpace(foot)) text.Children.Add(Cap(foot!).With(new Thickness(0, 8, 0, 0)));
        var tile = Tile(icon, title, 64, mod);
        var frame = new PixelBox { Background = Br("Panel"), BorderBrush = Br("Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(4), Notch = 2, Child = tile, VerticalAlignment = VerticalAlignment.Top };
        return new PixelBox
        {
            Background = Br("Panel2"), BorderBrush = Br("Edge"), BorderThickness = new Thickness(1), Padding = new Thickness(16), Notch = 4,
            Child = H(frame, text),
        };
    }

    public static string Nice(string tag) => tag.Length == 0 ? tag : char.ToUpperInvariant(tag[0]) + tag[1..].Replace('-', ' ');
}

/// <summary>
/// A long list that only keeps the rows near the screen. Each row has a fixed height, so a row far away is an empty box:
/// its content is built when it scrolls close and dropped again when it scrolls away.
/// </summary>
public sealed class LazyList
{
    public readonly ScrollViewer View;
    readonly StackPanel panel = new();
    readonly double rowHeight;
    readonly Func<int, UIElement> build;
    int lo, hi = -1;

    public LazyList(double rowHeight, Func<int, UIElement> build)
    {
        this.rowHeight = rowHeight;
        this.build = build;
        // The padding at the top leaves room for a hover edge on the first row.
        View = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Content = panel, Padding = new Thickness(2, 3, 10, 24) };
        View.ScrollChanged += (_, _) => Sync(true);
        View.SizeChanged += (_, _) => Sync(true);
    }

    public int Count => panel.Children.Count;
    Border Slot(int i) => (Border)panel.Children[i];

    /// <summary>True when the view is close to the last row.</summary>
    public bool NearEnd => View.VerticalOffset + View.ViewportHeight >= View.ExtentHeight - 700;

    public void Reset()
    {
        panel.Children.Clear();
        lo = 0;
        hi = -1;
        View.ScrollToTop();
    }

    public void SetCount(int n)
    {
        while (panel.Children.Count > n) panel.Children.RemoveAt(panel.Children.Count - 1);
        while (panel.Children.Count < n) panel.Children.Add(new Border { Height = rowHeight });
        if (hi >= n) hi = n - 1;
        Sync(true);
    }

    /// <summary>Builds one row again, when what it shows has changed.</summary>
    public void Invalidate(int i)
    {
        if (i >= 0 && i < Count && i >= lo && i <= hi) Slot(i).Child = build(i);
    }

    public void InvalidateAll()
    {
        for (var i = lo; i <= hi && i < Count; i++) Slot(i).Child = build(i);
    }

    void Sync(bool animate)
    {
        var n = Count;
        if (n == 0) { lo = 0; hi = -1; return; }
        var top = View.VerticalOffset;
        var height = View.ViewportHeight > 0 ? View.ViewportHeight : 800;
        var a = Math.Max(0, (int)(top / rowHeight) - 6);
        var b = Math.Min(n - 1, (int)((top + height) / rowHeight) + 6);
        for (var i = lo; i <= hi && i < n; i++)
            if (i < a || i > b) Slot(i).Child = null;
        for (var i = a; i <= b; i++)
            if (Slot(i).Child == null)
            {
                var child = build(i);
                Slot(i).Child = child;
                if (animate) Ui.FadeIn(child);
            }
        lo = a;
        hi = b;
    }
}
