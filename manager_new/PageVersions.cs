using System.Windows.Controls.Primitives;

namespace Pentecost.Manager;

public partial class MainWindow
{
    void OpenVersions()
    {
        var releases = new List<ReleaseInfo>();
        var loading = true;
        var failed = false;
        var showBeta = Settings.ShowBeta;
        var summary = Ui.T("Reading the release list", 12, "Faint", trim: true);
        var list = new StackPanel { MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };

        void Pick(ReleaseInfo r)
        {
            var cur = Paths.Version;
            var older = cur != null && Updates.Newer(cur, r.Version);
            var swap = cur == null ? "" : cur == r.Version ? "Pentecost " + cur + " is written again. " : older ? "This replaces Pentecost " + cur + " with the older " + r.Version + ". " : "This replaces Pentecost " + cur + " with " + r.Version + ". ";
            var body = swap + "Worlds, mods and settings stay as they are." + (r.Pre ? " This is a beta, so expect rough edges." : "");
            Confirm("Install Pentecost " + r.Version + "?", body, "Install", () => { CloseFull(); _ = RunInstall(false, r); });
        }

        FrameworkElement Row(ReleaseInfo r, bool isLatest)
        {
            var cur = Paths.Version;
            var installed = cur != null && cur == r.Version;
            var tags = Ui.H(Ui.T(r.Tag, 13.5, "Text", bold: true));
            tags.VerticalAlignment = VerticalAlignment.Center;
            if (installed) tags.Children.Add(Ui.Pill("Installed", "Text").With(new Thickness(10, 0, 0, 0)));
            if (isLatest) tags.Children.Add(Ui.Pill("Latest", "Ok").With(new Thickness(6, 0, 0, 0)));
            if (r.Pre) tags.Children.Add(Ui.Pill("Beta", "Warn").With(new Thickness(6, 0, 0, 0)));

            var first = r.Notes.Split('\n').Select(l => l.Trim().TrimStart('#', '-', '*', ' ')).FirstOrDefault(l => l.Length > 0) ?? "";
            var sub = (r.Date != null ? r.Date.Value.ToString("MMM d, yyyy") : "") + (first.Length > 0 ? (r.Date != null ? "   " : "") + first : "");
            var text = Ui.V(tags, Ui.T(sub.Length > 0 ? sub : "No notes", 11.5, "Faint", trim: true).With(new Thickness(0, 4, 0, 0)));
            text.VerticalAlignment = VerticalAlignment.Center;
            text.Margin = new Thickness(0, 0, 18, 0);

            var label = installed ? "Reinstall" : cur != null && Updates.Newer(r.Version, cur) ? "Update" : cur == null ? "Install" : "Switch";
            var go = Ui.Btn(r.InstallerUrl == null ? "No installer" : label, label == "Update" || label == "Install" ? "Primary" : "Secondary", (_, _) => Pick(r));
            go.Padding = new Thickness(14, 5, 14, 5);
            go.FontSize = 12;
            go.IsEnabled = r.InstallerUrl != null && !installing;
            go.VerticalAlignment = VerticalAlignment.Center;

            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.Children.Add(text);
            Grid.SetColumn(go, 1);
            g.Children.Add(go);
            return Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(6, 13, 8, 13), Child = g });
        }

        void Draw()
        {
            list.Children.Clear();
            var cur = Paths.Version;
            var shown = releases.Where(r => showBeta || !r.Pre || r.Version == cur).ToList();
            var newest = shown.FirstOrDefault(r => !r.Pre);
            summary.Text = loading ? "Reading the release list"
                : failed ? "Could not reach GitHub."
                : shown.Count == 0 ? "No versions found."
                : cur == null ? "Pentecost is not installed." : "Installed: " + cur;
            foreach (var r in shown) list.Children.Add(Row(r, newest != null && r.Tag == newest.Tag));
        }

        var beta = new ToggleButton { Content = "Show betas", Style = Ui.St("Chip"), IsChecked = showBeta };
        beta.Click += (_, _) => { showBeta = beta.IsChecked == true; Settings.ShowBeta = showBeta; Draw(); };

        var bar = FullBar("Pentecost versions", null, summary, beta);
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.Children.Add(bar);
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = list, Padding = new Thickness(0, 24, 14, 28), HorizontalAlignment = HorizontalAlignment.Left };
        Grid.SetRow(scroll, 1);
        root.Children.Add(scroll);
        OpenFull(root);
        Draw();

        _ = Task.Run(() => Updates.All()).ContinueWith(t => Dispatcher.BeginInvoke(() =>
        {
            loading = false;
            if (t.IsFaulted) { failed = true; AppLog.Write("versions: " + t.Exception?.GetBaseException().Message); }
            else releases = t.Result;
            Draw();
        }));
    }
}
