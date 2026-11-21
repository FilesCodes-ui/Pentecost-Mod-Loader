using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public partial class MainWindow
{
    public async Task Shoot(string dir, string[] pages)
    {
        Directory.CreateDirectory(dir);
        var size = Environment.GetEnvironmentVariable("PENTECOST_SHOT_SIZE")?.Split('x');
        if (size is { Length: 2 } && double.TryParse(size[0], out var sw) && double.TryParse(size[1], out var sh))
        {
            WindowState = WindowState.Normal;
            Width = sw;
            Height = sh;
        }
        await Task.Delay(2500);

        async Task Snap(string name)
        {
            UpdateLayout();
            var w = (int)Frame.ActualWidth;
            var h = (int)Frame.ActualHeight;
            var rtb = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(Frame);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(rtb));
            await using var fs = File.Create(Path.Combine(dir, name + ".png"));
            enc.Save(fs);
        }

        foreach (var page in pages)
        {
            if (page == "Updates")
            {
                Go("Mods");
                var all = await Task.Run(ModScan.Scan);
                try { await Modrinth.Lookup(all, CancellationToken.None); } catch { }
                if (all.Count == 0)
                    all = Enumerable.Range(0, 24).Select(i => new ModEntry { File = "sample-" + i + ".jar", Name = "Sample mod " + i, Version = "1.0", Enabled = true, Looked = true, ProjectId = "p" + i, Update = i % 5 == 1 ? new ModUpdate("x.jar", "", "", "1.1") : null }).ToList();
                OpenUpdates(all, _ => Task.CompletedTask);
                await Task.Delay(2500);
                await Snap("Updates");
                await Task.Delay(1400);
                await Snap("Updates-2");
                continue;
            }
            if (page == "Sky")
            {
                Go("Play");
                await Task.Delay(2500);
                await Snap("Sky-1");
                await Task.Delay(2600);
                await Snap("Sky-2");
                await Task.Delay(2400);
                await Snap("Sky-3");
                homeSea?.Ring();
                await Task.Delay(500);
                await Snap("Sky-4");
                await Task.Delay(2600);
                await Snap("Sky-5");
                await Task.Delay(7300);
                await Snap("Sky-6");
                await Task.Delay(1500);
                await Snap("Sky-7");
                continue;
            }
            if (page == "Fleet")
            {
                Go("Friends");
                await Task.Delay(1200);
                if (fleetView != null)
                {
                    var (g, fw, fh) = await Heads.Flag(DemoFriend.Crowd, 4, 2);
                    fleetView.SetFlag(DemoFriend.Id, g, fw, fh);
                }
                fleetView?.SetFleet(new[]
                {
                    (DemoFriend.Id, DemoFriend.Name, DemoFriend.Status, true, 0),
                    ("a", "Victor2812", "In a world, Create Above and Beyond", true, 2),
                    ("b", "Davethediva", "In the menu, profile Default", true, 0),
                    ("c", "Mira", "Offline", false, 0),
                    ("d", "Tobias_K", "Offline", false, 0),
                    ("e", "northwind", "On a server", true, 0),
                    ("f", "Halden", "Offline", false, 0),
                }, true, 1);
                for (var k = 1; k <= 3; k++)
                {
                    await Task.Delay(2500);
                    await Snap("Fleet-" + k);
                }
                continue;
            }
            if (page == "Sink")
            {
                Go("Profiles");
                await Task.Delay(1500);
                var victim = ProfileStore.Load().Profiles.Select(p => p.Name).FirstOrDefault(n => n != ProfileStore.Load().Active);
                if (victim != null) Chart().Sink(victim, () => { });
                for (var k = 1; k <= 9; k++)
                {
                    await Task.Delay(900);
                    await Snap("Sink-" + k);
                }
                continue;
            }
            if (page == "Voyage")
            {
                Go("Profiles");
                await Task.Delay(1500);
                voyage?.Sail("Tortuga", true, 1);
                foreach (var (at, name) in new[] { (2500, "Voyage-1"), (3500, "Voyage-2"), (2500, "Voyage-3"), (2500, "Voyage-4"), (6000, "Voyage-5") })
                {
                    await Task.Delay(at);
                    await Snap(name);
                }
                continue;
            }
            if (page == "Peek")
            {
                var found = new List<Hit>();
                foreach (var q in new[] { "terralith", "alexs mobs", "create" })
                {
                    try { found.AddRange((await Modrinth.Search(q, "mod", "All", Array.Empty<string>(), "relevance", 0, CancellationToken.None)).Hits.Take(1)); } catch { }
                }
                if (found.Count > 0) found.Add(found[0] with { ProjectId = "zzzz-none", Gallery = Array.Empty<string>(), Title = "A mod with no pictures" });
                for (var k = 0; k < found.Count; k++)
                {
                    var card = Peek.Card(found[k], k == 1, new List<FrameworkElement>(), k == 1 ? "Breaks on Pentecost 0.6. Use 0.7 or newer." : null);
                    var host = new Window { Content = new Border { Padding = new Thickness(24), Background = Ui.Br("Bg"), Child = card }, SizeToContent = SizeToContent.WidthAndHeight, WindowStyle = WindowStyle.None, ShowInTaskbar = false, Left = 40, Top = 40 };
                    host.Show();
                    foreach (var (at, tag) in new[] { (500, "a"), (2600, "b") })
                    {
                        await Task.Delay(at);
                        host.UpdateLayout();
                        var el = (FrameworkElement)host.Content;
                        var rtb = new RenderTargetBitmap((int)el.ActualWidth, (int)el.ActualHeight, 96, 96, PixelFormats.Pbgra32);
                        rtb.Render(el);
                        var enc = new PngBitmapEncoder();
                        enc.Frames.Add(BitmapFrame.Create(rtb));
                        await using var fs = File.Create(Path.Combine(dir, $"Peek-{k}{tag}.png"));
                        enc.Save(fs);
                    }
                    host.Close();
                }
                continue;
            }
            if (page == "Pack")
            {
                Go("Browse");
                ShowProject("1KVo5zza", "modpack", "Fabulously Optimized", null, "robotkoer", () => { });
                await Task.Delay(9000);
                await Snap("Pack");
                CloseFull();
                continue;
            }
            Go(page);
            await Task.Delay(page is "Browse" or "Mods" ? 4500 : 1800);
            await Snap(page);
            if (page == "Browse")
            {
                await Task.Delay(9000);
                await Snap("Browse-2");
            }
            if (page == "Play")
            {
                for (var k = 2; k <= 4; k++)
                {
                    await Task.Delay(900);
                    await Snap("Play-" + k);
                }
            }
        }

        Go("Browse");
        ShowProject("sodium", "mod", "Sodium", null, "jellysquid3", () => { });
        await Task.Delay(5000);
        await Snap("Project");
        ShowShortcuts();
        await Task.Delay(600);
        await Snap("Keys");
        HideModal();
    }
}
