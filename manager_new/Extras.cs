using System.Text.RegularExpressions;
using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow
{
    readonly Dictionary<string, Action> searchFocus = new();
    static readonly string[] PageOrder = { "Play", "Mods", "Browse", "Profiles", "Crashes", "Log", "Settings" };

    void InitExtras()
    {
        PreviewKeyDown += OnKey;
        Modal.MouseLeftButtonDown += (_, e) => { if (e.OriginalSource == Modal) HideModal(); };
        Downloads.Changed += () => Dispatcher.BeginInvoke(RenderDownloads);
        var i = 1;
        foreach (var rb in NavPanel.Children.OfType<RadioButton>()) rb.ToolTip = "Ctrl+" + i++;
    }

    static Grid Header(string title, string sub, params UIElement[] right)
    {
        var left = new Wordmark { Text = title.ToUpperInvariant(), Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Center };
        var g = Ui.Row(left, right.Length == 0 ? null : Ui.H(right));
        if (right.Length > 0) ((StackPanel)g.Children[1]).VerticalAlignment = VerticalAlignment.Center;
        return g;
    }

    void OnKey(object s, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && Modal.Visibility == Visibility.Visible) { HideModal(); e.Handled = true; return; }
        if (e.Key == Key.Escape && inFull && Modal.Visibility != Visibility.Visible && !(Keyboard.FocusedElement is TextBox focused && focused.Text.Length > 0)) { CloseFull(); e.Handled = true; return; }
        if (pictureStep != null && Modal.Visibility == Visibility.Visible && (e.Key == Key.Left || e.Key == Key.Right))
        { pictureStep(e.Key == Key.Left ? -1 : 1); e.Handled = true; return; }
        if (e.Key == Key.Enter && Modal.Visibility == Visibility.Visible && modalEnter != null) { var go = modalEnter; modalEnter = null; go(); e.Handled = true; return; }
        if (e.Key == Key.F1) { ShowShortcuts(); e.Handled = true; return; }
        if (e.Key == Key.F5) { Refresh(); e.Handled = true; return; }
        if (Keyboard.Modifiers != ModifierKeys.Control) return;
        if (e.Key >= Key.D1 && e.Key <= Key.D7) { Go(PageOrder[e.Key - Key.D1]); e.Handled = true; }
        else if (e.Key == Key.F)
        {
            if (current != "Mods" && current != "Browse") Go("Mods");
            Dispatcher.BeginInvoke(DispatcherPriority.Input, () => { if (searchFocus.TryGetValue(current, out var focus)) focus(); });
            e.Handled = true;
        }
        else if (e.Key == Key.P && Modal.Visibility != Visibility.Visible) { StartGame(); e.Handled = true; }
        else if (e.Key == Key.L) { Go("Log"); e.Handled = true; }
        else if (e.Key == Key.U) { _ = CheckUpdates(true); e.Handled = true; }
        else if (e.Key == Key.O) { OpenFolder(Path.Combine(Paths.Home, "run")); e.Handled = true; }
    }

    void Keys_Click(object s, RoutedEventArgs e) => ShowShortcuts();

    void ShowShortcuts()
    {
        var rows = new StackPanel { Margin = new Thickness(0, 16, 0, 0) };
        void Line(string keys, string what)
        {
            var k = new PixelBox { BorderBrush = Ui.Br("Edge"), BorderThickness = new Thickness(1), Notch = 1.5, Padding = new Thickness(8, 3, 8, 3), Child = new TextBlock { Text = keys, Style = Ui.St("Cap"), Foreground = Ui.Br("Text") }, HorizontalAlignment = HorizontalAlignment.Left };
            var g = new Grid { Margin = new Thickness(0, 0, 0, 9) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(130) });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.Children.Add(k);
            var t = Ui.T(what, 13, "Soft");
            t.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(t, 1);
            g.Children.Add(t);
            rows.Children.Add(g);
        }
        Line("Ctrl+1 to 7", "Open a tab, in the order they sit along the top");
        Line("Ctrl+F", "Search your mods, or Modrinth on the Browse tab");
        Line("Ctrl+P", "Play, or stop the game");
        Line("Ctrl+L", "Open the game log");
        Line("Ctrl+U", "Check for a new Pentecost version");
        Line("Ctrl+O", "Open the game folder (worlds, mods, logs)");
        Line("F5", "Reload the page");
        Line("Esc", "Close a box");
        Line("F1", "Show this list");
        var close = Ui.Btn("Close", "Primary", (_, _) => HideModal());
        close.HorizontalAlignment = HorizontalAlignment.Right;
        close.Margin = new Thickness(0, 12, 0, 0);
        var box = Ui.V(Ui.T("Keyboard shortcuts", 17, bold: true), rows, close);
        box.Width = 520;
        ShowModal(Ui.Card(box, 26));
    }

    void RenderDownloads()
    {
        var jobs = Downloads.Snapshot();
        DlPanel.Children.Clear();
        DlPanel.ToolTip = null;
        if (jobs.Count == 0) return;
        var active = jobs.Where(j => j.State is "Queued" or "Downloading").ToList();
        var failed = jobs.Count(j => j.State == "Failed");
        var text = active.Count == 1 ? active[0].Name
            : active.Count > 1 ? $"Downloading {active.Count} files"
            : failed > 0 ? $"{failed} downloads failed" : "Downloads finished";
        DlPanel.Children.Add(Ui.T(text, 11.5, failed > 0 && active.Count == 0 ? "Bad" : "Soft", trim: true));
        DlPanel.Children.Add(new ProgressBar { Style = Ui.St("Bar"), Margin = new Thickness(0, 7, 0, 0), Value = active.Count == 0 ? 1 : active.Average(j => j.Progress) });
        DlPanel.ToolTip = string.Join("\n", jobs.Select(j => j.Name + "  " + j.State.ToLowerInvariant()));
    }

    void ShowInFolder(string file)
    {
        if (testing) return;
        try { System.Diagnostics.Process.Start("explorer.exe", "/select,\"" + file + "\""); }
        catch (Exception ex) { AppLog.Write("show " + file + ": " + ex.Message); }
    }

    Action<string>? searchWith;

    void ShowPicture(List<string> links, int at, string title)
    {
        if (links.Count == 0) return;
        at = Math.Clamp(at, 0, links.Count - 1);
        var frame = Smooth(new Image { Stretch = Stretch.Uniform, MaxHeight = 620, MaxWidth = 980 });
        var caption = Ui.T("", 12, "Muted");
        caption.HorizontalAlignment = HorizontalAlignment.Center;

        void Show(int i)
        {
            at = (i + links.Count) % links.Count;
            frame.Source = Ui.Picture(links[at], 1960);
            caption.Text = title + "    " + (at + 1) + " of " + links.Count + (links.Count > 1 ? "    use the arrow keys" : "");
            Ui.FadeIn(frame, 140);
        }

        var back = Ui.IconBtn("", "Previous (left arrow)", (_, _) => Show(at - 1));
        var next = Ui.IconBtn("", "Next (right arrow)", (_, _) => Show(at + 1));
        var shut = Ui.IconBtn("", "Close (Esc)", (_, _) => HideModal());
        back.Visibility = next.Visibility = links.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        var bar = Ui.H(back, caption.With(new Thickness(14, 0, 14, 0)), next, shut.With(new Thickness(14, 0, 0, 0)));
        bar.HorizontalAlignment = HorizontalAlignment.Center;
        bar.Margin = new Thickness(0, 14, 0, 0);
        caption.VerticalAlignment = VerticalAlignment.Center;

        var box = Ui.V(frame, bar);
        var card = Ui.Card(box, 18);
        card.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Left) Show(at - 1);
            else if (e.Key == Key.Right) Show(at + 1);
        };
        card.Focusable = true;
        Show(at);
        pictureStep = step => Show(at + step);
        ShowModal(card);
        card.Focus();
    }

    Action<int>? pictureStep;

    async void ImportPack()
    {
        if (testing) return;
        var exports = Path.Combine(Paths.Home, "exports");
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Choose a pack",
            Filter = "Packs (*.mrpack, *.zip)|*.mrpack;*.zip|Modrinth pack (*.mrpack)|*.mrpack|Pentecost pack from the game (*.zip)|*.zip",
            InitialDirectory = Directory.Exists(exports) ? exports : null,
        };
        if (dlg.ShowDialog(this) != true) return;
        if (game.Running) { Toast("Close the game first.", "Warn"); return; }
        Toast("Importing the pack. Downloads show on the left.");
        try
        {
            var r = dlg.FileName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)
                ? await Packs.ImportPentecost(dlg.FileName, CancellationToken.None)
                : await Packs.Import(dlg.FileName, CancellationToken.None);
            if (current is "Profiles" or "Mods" or "Play") Refresh();
            var text = $"Imported {r.Name} as a new profile: {r.Mods} mods, {r.Downloaded} downloaded.";
            if (r.Problems.Count > 0)
            {
                foreach (var p in r.Problems) AppLog.Write("pack import: " + p);
                Toast(text + $" {r.Problems.Count} files failed. See the manager log.", "Warn");
            }
            else Toast(text);
        }
        catch (Exception ex)
        {
            AppLog.Write("pack import: " + ex);
            Toast("Could not import the pack: " + ex.Message, "Bad");
        }
    }

    async void ExportPack(ModProfile profile)
    {
        if (testing) return;
        var dlg = new Microsoft.Win32.SaveFileDialog { Title = "Save the pack", Filter = "Modrinth pack (*.mrpack)|*.mrpack", FileName = profile.Name + ".mrpack" };
        if (dlg.ShowDialog(this) != true) return;
        Toast("Building the pack");
        try
        {
            var (linked, bundled) = await Packs.Export(profile, dlg.FileName, CancellationToken.None);
            Toast($"Saved the pack: {linked} mods as Modrinth links, {bundled} files inside the pack.");
        }
        catch (Exception ex)
        {
            AppLog.Write("pack export: " + ex);
            Toast("Could not save the pack: " + ex.Message, "Bad");
        }
    }
}
