namespace Pentecost.Manager;

public partial class MainWindow
{
    static async Task FillPackLine(TextBlock line, string projectId)
    {
        var s = await PackInfo.For(projectId);
        if (s == null) { line.Text = "Large pack: details after install"; return; }
        var here = await PackInfo.Local();
        line.Text = PackInfo.Line(s, PackInfo.Have(s, here));
        var extra = new List<string>();
        if (s.Shaders > 0) extra.Add(s.Shaders == 1 ? "1 shader pack" : s.Shaders + " shader packs");
        if (s.Resources > 0) extra.Add(s.Resources == 1 ? "1 resource pack" : s.Resources + " resource packs");
        line.ToolTip = "Version " + s.Number + (extra.Count > 0 ? ", with " + string.Join(" and ", extra) : "");
    }

    FrameworkElement PackInside(List<VersionInfo> versions)
    {
        var host = new StackPanel();
        var status = Ui.T("Reading the pack", 12.5, "Muted");
        host.Children.Add(status);
        _ = Fill();
        return PlainSection("Inside the pack", host);

        async Task Fill()
        {
            try
            {
                var pick = versions.FirstOrDefault(v => v.Channel == "release") ?? versions.FirstOrDefault();
                if (pick == null) { status.Text = "Nothing is published for Minecraft 1.20.1."; return; }
                var s = await PackInfo.ForVersion(pick);
                if (s == null) { status.Text = "This pack carries its mods inside a large file, so it is counted after install."; return; }
                var here = await PackInfo.Local();
                var have = PackInfo.Have(s, here);
                host.Children.Clear();

                var stats = new WrapPanel();
                void Stat(string big, string small) =>
                    stats.Children.Add(Ui.V(Ui.T(big, 20, bold: true), Ui.Cap(small).With(new Thickness(0, 3, 0, 0))).With(new Thickness(0, 0, 32, 12)));
                Stat(s.Mods.ToString(), s.Mods == 1 ? "MOD" : "MODS");
                if (s.Shaders > 0) Stat(s.Shaders.ToString(), s.Shaders == 1 ? "SHADER PACK" : "SHADER PACKS");
                if (s.Resources > 0) Stat(s.Resources.ToString(), s.Resources == 1 ? "RESOURCE PACK" : "RESOURCE PACKS");
                Stat(s.Loader, "LOADER");
                Stat(Ui.Bytes(s.Bytes), "DOWNLOAD");
                Stat(have.ToString(), "ALREADY HERE");
                host.Children.Add(stats);
                host.Children.Add(Ui.T("Version " + s.Number + ". Installing makes a new profile for it. "
                    + (have > 0 ? "Mods already in your other profiles are copied instead of downloaded again." : "None of its mods are on this computer yet."),
                    12, "Faint", wrap: true).With(new Thickness(0, 0, 0, 12)));

                var mods = s.Files.Where(f => f.Kind == "mod").ToList();
                var ids = mods.Select(f => f.ProjectId).OfType<string>().Distinct().ToList();
                var names = ids.Count == 0 ? new Dictionary<string, (string Title, string? Icon, string Desc)>() : await Modrinth.Projects(ids, CancellationToken.None);
                var rows = mods.Select(f =>
                {
                    var known = f.ProjectId != null && names.TryGetValue(f.ProjectId, out var n) ? n : default;
                    return (Name: known.Title is { Length: > 0 } ? known.Title : f.Name, Icon: known.Icon, Here: f.Sha1.Length > 0 && here.ContainsKey(f.Sha1));
                }).OrderBy(r => r.Name, StringComparer.OrdinalIgnoreCase).ToList();

                var list = new WrapPanel();
                const int first = 24;
                void Show(int count)
                {
                    list.Children.Clear();
                    foreach (var r in rows.Take(count))
                    {
                        var row = Ui.H(Ui.Tile(r.Icon, r.Name, 22), Ui.T(r.Name, 12.5, r.Here ? "Muted" : "Text", trim: true).With(new Thickness(8, 0, 0, 0)));
                        if (r.Here) row.Children.Add(Ui.Cap("HERE").With(new Thickness(8, 0, 0, 0)));
                        row.VerticalAlignment = VerticalAlignment.Center;
                        list.Children.Add(new Border { Width = 300, Padding = new Thickness(0, 4, 12, 4), Child = row });
                    }
                }
                Show(first);
                host.Children.Add(list);
                if (rows.Count > first)
                {
                    Button more = null!;
                    more = Ui.Btn("Show all " + rows.Count, "Ghost", (_, _) => { Show(rows.Count); more.Visibility = Visibility.Collapsed; });
                    more.HorizontalAlignment = HorizontalAlignment.Left;
                    more.Margin = new Thickness(0, 6, 0, 0);
                    host.Children.Add(more);
                }
            }
            catch (Exception ex)
            {
                AppLog.Write("pack inside: " + ex.Message);
                status.Text = "The pack could not be read: " + ex.Message;
                if (!host.Children.Contains(status)) { host.Children.Clear(); host.Children.Add(status); }
            }
        }
    }
}
