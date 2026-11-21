using System.Text;
using System.Windows.Controls.Primitives;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow
{
    readonly List<string> findings = new();
    bool testing;

    void Say(string line) => findings.Add(line);

    static IEnumerable<DependencyObject> Tree(DependencyObject? root)
    {
        if (root == null) yield break;
        var n = VisualTreeHelper.GetChildrenCount(root);
        for (var i = 0; i < n; i++)
        {
            var c = VisualTreeHelper.GetChild(root, i);
            yield return c;
            foreach (var g in Tree(c)) yield return g;
        }
    }

    async Task Settle(int ms = 450)
    {
        await Dispatcher.Yield(DispatcherPriority.Background);
        await Task.Delay(ms);
        UpdateLayout();
    }

    static Rect BoundsIn(FrameworkElement e, Visual host)
    {
        var b = VisualTreeHelper.GetDescendantBounds(e);
        if (b.IsEmpty) b = new Rect(0, 0, e.ActualWidth, e.ActualHeight);
        try { return e.TransformToVisual(host).TransformBounds(b); }
        catch (InvalidOperationException) { return Rect.Empty; }
    }

    static bool Shown(FrameworkElement e) => e.IsVisible && e.ActualWidth > 0 && e.ActualHeight > 0;

    Rect PageArea => new(PageHost.TranslatePoint(new Point(0, 0), Frame), new Size(PageHost.ActualWidth, PageHost.ActualHeight));

    bool Scrolls(DependencyObject e)
    {
        for (var up = VisualTreeHelper.GetParent(e); up != null && up != PageHost; up = VisualTreeHelper.GetParent(up))
            if (up is ScrollViewer { HorizontalScrollBarVisibility: not ScrollBarVisibility.Disabled }) return true;
        return false;
    }

    void CheckLayout(string page)
    {
        var area = PageArea;
        foreach (var o in Tree(PageHost))
        {
            if (o is not FrameworkElement e || !Shown(e)) continue;
            var r = BoundsIn(e, Frame);

            if (r.Bottom < area.Top + 1 || r.Top > area.Bottom - 1 || r.Width < 1) continue;

            if (e is ButtonBase or TextBox or Image && !Scrolls(e))
            {
                if (r.Right > area.Right + 1 || r.Left < area.Left - 1)
                    Say($"{page}: {Label(e)} is wider than the page ({r.Left:0} to {r.Right:0}, page {area.Left:0} to {area.Right:0})");
                if (e is ButtonBase b && (b.ActualHeight < 18 || b.ActualWidth < 18))
                    Say($"{page}: {Label(b)} is only {b.ActualWidth:0} by {b.ActualHeight:0}");
            }

            if (e is TextBlock { TextWrapping: TextWrapping.NoWrap, TextTrimming: TextTrimming.None } t && t.Text.Length > 1 && !IsGlyph(t))
            {
                t.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
                if (t.DesiredSize.Width - t.Margin.Left - t.Margin.Right > t.ActualWidth + 1.5)
                    Say($"{page}: the text \"{Short(t.Text)}\" is cut off with no ellipsis");
            }
        }
    }
    async Task CheckHover(string page)
    {
        var lists = Tree(PageHost).OfType<ScrollViewer>().Where(Shown).ToList();
        foreach (var sv in lists)
        {
            var area = new Rect(sv.TranslatePoint(new Point(0, 0), Frame), new Size(sv.ActualWidth, sv.ActualHeight));
            var cards = Tree(sv).OfType<Border>().Where(b => Shown(b) && b.ActualHeight > 40 && b.Cursor == Cursors.Hand && BoundsIn(b, Frame) is var bb && bb.Top >= area.Top && bb.Bottom <= area.Bottom).Take(6).ToList();
            foreach (var card in cards)
            {
                var before = BoundsIn(card, Frame);
                card.RaiseEvent(new MouseEventArgs(Mouse.PrimaryDevice, 0) { RoutedEvent = MouseEnterEvent });
                await Settle(220);
                var after = BoundsIn(card, Frame);
                if (after.Top < area.Top - 0.5 || after.Bottom > area.Bottom + 0.5 || after.Left < area.Left - 0.5 || after.Right > area.Right + 0.5)
                    Say($"{page}: hovering a card paints outside its list (top {after.Top:0} against {area.Top:0}, bottom {after.Bottom:0} against {area.Bottom:0})");
                if (Math.Abs(after.Top - before.Top) > 0.5)
                    Say($"{page}: a card moves {Math.Abs(after.Top - before.Top):0.#} px when hovered");
                card.RaiseEvent(new MouseEventArgs(Mouse.PrimaryDevice, 0) { RoutedEvent = MouseLeaveEvent });
                await Settle(120);
            }
        }
    }

    async Task PressSafe(string page, string[] skip)
    {
        var buttons = Tree(PageHost).OfType<ButtonBase>().Where(Shown).ToList();
        foreach (var b in buttons)
        {
            var label = Label(b) + " " + (b.ToolTip as string ?? "");
            if (skip.Any(s => label.Contains(s, StringComparison.OrdinalIgnoreCase))) continue;
            if (!b.IsEnabled) continue;
            try
            {
                b.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent, b));
                await Settle(300);
                if (Modal.Visibility == Visibility.Visible)
                {
                    foreach (var o in Tree(ModalHost).OfType<FrameworkElement>())
                        if (o is TextBlock { TextWrapping: TextWrapping.NoWrap, TextTrimming: TextTrimming.None } mt && mt.Text.Length > 1 && Shown(mt) && !IsGlyph(mt))
                        {
                            mt.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
                            if (mt.DesiredSize.Width - mt.Margin.Left - mt.Margin.Right > mt.ActualWidth + 1.5) Say($"{page}: in a box, \"{Short(mt.Text)}\" is cut off");
                        }
                    var box = Tree(ModalHost).OfType<FrameworkElement>().FirstOrDefault();
                    if (box != null)
                    {
                        var r = BoundsIn(box, Frame);
                        if (r.Height > Frame.ActualHeight || r.Width > Frame.ActualWidth)
                            Say($"{page}: the box opened by {label} is bigger than the window");
                    }
                    HideModal();
                    await Settle(150);
                }
            }
            catch (Exception ex) { Say($"{page}: pressing {label} threw {ex.GetType().Name}: {ex.Message}"); }
            if (current != page) { Go(page); await Settle(600); }
            else if (inFull) { ShowPage(page, false); await Settle(700); }
        }
    }

    static string Label(FrameworkElement e)
    {
        if (e is ContentControl { Content: string s } && s.Length > 0) return "\"" + Short(s) + "\"";
        var text = Tree(e).OfType<TextBlock>().Where(t => !IsGlyph(t)).Select(t => t.Text).FirstOrDefault(t => t.Length > 0 && t.Length < 40);
        if (text != null) return "\"" + Short(text) + "\"";
        if (Tree(e).OfType<Wordmark>().FirstOrDefault() is { } w) return "\"" + w.Text + "\"";
        if (e.ToolTip is string tip) return $"the {e.GetType().Name} \"{Short(tip)}\"";
        return "a " + e.GetType().Name;
    }

    static bool IsGlyph(TextBlock t) =>
        t.FontFamily?.Source?.Contains("Fluent", StringComparison.OrdinalIgnoreCase) == true
        || t.FontFamily?.Source?.Contains("MDL2", StringComparison.OrdinalIgnoreCase) == true
        || (t.Text.Length == 1 && t.Text[0] >= 0xE000 && t.Text[0] <= 0xF8FF);

    static string Short(string s)
    {
        s = s.Replace("\n", " ").Trim();
        return s.Length > 34 ? s[..34] + "..." : s;
    }

    public async Task Check(string dir)
    {
        testing = true;
        Directory.CreateDirectory(dir);
        await Settle(2500);

        var skip = new[]
        {
            "Install", "Update", "Reinstall", "Remove", "Delete", "Play", "Stop", "Check for updates", "Open", "Folder",
            "Change", "Choose", "Sign out", "Import", "Export", "Add mods", "Load latest", "Clear", "Switch off", "View crash",
            "Data folder", "Manager log", "Show in folder", "Use", "In use", "Find again", "Copy", "Recycle", "Bin",
            "Worlds", "Screenshots", "Shaders", "Resource packs", "Config", "java", "What this mod is", "Back to",
        };

        foreach (var page in new[] { "Play", "Mods", "Browse", "Profiles", "Crashes", "Log", "Settings" })
        {
            Go(page);
            await Settle(page is "Browse" or "Mods" ? 4200 : 1400);
            CheckLayout(page);
            await CheckHover(page);
            await PressSafe(page, skip);
            await Settle(500);
            CheckLayout(page);
            Shoot(dir, page);
        }

        {
            var real = latest;
            latest = new ReleaseInfo("v9.9.9", "https://example.invalid", null, null, 0, null, "", "9.9.9", null, 0, null);
            Go("Play");
            await Settle(1200);
            CheckLayout("Home with a Manager update");
            Shoot(dir, "Home-manager-update");
            Go("Settings");
            await Settle(900);
            CheckLayout("Settings with a Manager update");
            Shoot(dir, "Settings-manager-update");
            Tree(PageHost).OfType<ScrollViewer>().FirstOrDefault()?.ScrollToEnd();
            await Settle(500);
            Shoot(dir, "Settings-manager-update-bottom");
            latest = real;
            Go("Play");
            await Settle(600);
        }

        Go("Browse");
        await Settle(1200);
        ShowProject("sodium", "mod", "Sodium", null, "jellysquid3", () => { });
        await Settle(5000);
        CheckFull("the mod page");
        Shoot(dir, "Page-mod");
        Go("Browse");
        await Settle(600);
        var sample = ModScan.Scan().FirstOrDefault();
        if (sample != null)
        {
            ShowModInfo(sample, () => { });
            await Settle(2500);
            CheckFull("the installed mod page");
            Shoot(dir, "Page-installed");
            Go("Mods");
            await Settle(500);
        }
        OpenUpdates(ModScan.Scan(), _ => Task.CompletedTask);
        await Settle(1500);
        CheckFull("the update page");
        Shoot(dir, "Page-updates");
        Go("Mods");
        await Settle(500);
        await Settle(300);

        ShowShortcuts();
        await Settle(700);
        CheckBox_("the shortcuts box");
        Shoot(dir, "Box-keys");
        HideModal();
        await Settle(300);

        EditProfile(null);
        await Settle(700);
        CheckBox_("the new profile box");
        Shoot(dir, "Box-profile");
        HideModal();
        await Settle(300);

        Go("Browse");
        await Settle(1500);
        searchWith?.Invoke("create");
        await Settle(5000);
        Shoot(dir, "Browse-search");
        searchWith?.Invoke("");
        await Settle(2500);

        Width = 1080;
        Height = 680;
        Go("Browse");
        await Settle(2500);
        CheckLayout("Browse narrow");
        Shoot(dir, "Narrow-browse");
        Go("Mods");
        await Settle(1800);
        CheckLayout("Mods narrow");
        Shoot(dir, "Narrow-mods");

        {
            const string releaseJson = "{\"tag_name\":\"v1.2.3\",\"html_url\":\"https://x\",\"body\":\"n\",\"assets\":[" +
                "{\"name\":\"Pentecost-Installer-1.2.3.exe\",\"size\":10,\"browser_download_url\":\"https://github.com/a\",\"digest\":\"sha256:AA\"}," +
                "{\"name\":\"Pentecost-Manager-Setup-0.4.0.exe\",\"size\":20,\"browser_download_url\":\"https://github.com/b\",\"digest\":\"sha256:BB\"}," +
                "{\"name\":\"Pentecost-Manager-Setup-0.10.1.exe\",\"size\":30,\"browser_download_url\":\"https://github.com/c\",\"digest\":\"sha256:CC\"}]}";
            var parsed = Updates.Parse(releaseJson);
            if (parsed == null || parsed.InstallerName != "Pentecost-Installer-1.2.3.exe" || parsed.InstallerSha256 != "AA") Say("release parse: the installer was not found");
            else if (parsed.ManagerVersion != "0.10.1" || parsed.ManagerUrl != "https://github.com/c" || parsed.ManagerSize != 30 || parsed.ManagerSha256 != "CC") Say("release parse: the newest Manager setup was not picked");
            if (!Updates.Newer("0.10.1", "0.4.0") || Updates.Newer("0.4.0", "0.4.0")) Say("version order is wrong");
        }

        {
            var keep = Paths.Home;
            var home = Path.Combine(Path.GetTempPath(), "pentecost-gameoff-test-" + Guid.NewGuid().ToString("N"));
            try
            {
                Paths.Home = home;
                Directory.CreateDirectory(Paths.Mods);
                Directory.CreateDirectory(Path.GetDirectoryName(Paths.Choices)!);
                using (var z = System.IO.Compression.ZipFile.Open(Path.Combine(Paths.Mods, "fake-api-1.0.jar"), System.IO.Compression.ZipArchiveMode.Create))
                using (var w = new StreamWriter(z.CreateEntry("fabric.mod.json").Open()))
                    w.Write("{\"schemaVersion\":1,\"id\":\"fake-api\",\"version\":\"1.0\"}");
                System.IO.File.WriteAllText(Paths.Choices, "{\"modsOff\":[\"fake_api\",\"other\"],\"featuresOff\":{\"x\":[\"y\"]}}");
                var one = ModScan.Scan().FirstOrDefault();
                if (one == null || one.Enabled || !one.GameOff) Say("game off: a mod the game turned off does not show as off");
                else
                {
                    ModScan.SetEnabled(one, true);
                    var text = System.IO.File.ReadAllText(Paths.Choices);
                    if (text.Contains("fake_api") || !text.Contains("other") || !text.Contains("featuresOff")) Say("game off: turning the mod on changed more than its own switch");
                    var again = ModScan.Scan().FirstOrDefault();
                    if (again == null || !again.Enabled || again.GameOff) Say("game off: the mod does not show as on after turning it on");
                }
            }
            catch (Exception ex) { Say("game off test threw " + ex.GetType().Name + ": " + ex.Message); }
            finally
            {
                Paths.Home = keep;
                try { Directory.Delete(home, true); } catch { }
            }
        }

        {
            if (!Modrinth.IsModrinthHost("cdn.modrinth.com") || !Modrinth.IsModrinthHost("modrinth.com")) Say("host rule: a real Modrinth host was refused");
            if (Modrinth.IsModrinthHost("evilmodrinth.com") || Modrinth.IsModrinthHost("modrinth.com.evil.net")) Say("host rule: a lookalike host was accepted");
            if (Packs.SafeTarget(@"C:\game\run", "mods/../../x.jar") != null || Packs.SafeTarget(@"C:\game\run", "/windows/x.jar") != null
                || Packs.SafeTarget(@"C:\game\run", @"C:\x.jar") != null) Say("pack rule: a path outside the game folder was accepted");
            if (Packs.SafeTarget(@"C:\game\run", "config/a/b.toml") == null) Say("pack rule: a normal path was refused");
            System.Text.Json.Nodes.JsonObject Idx(string deps) => System.Text.Json.Nodes.JsonNode.Parse("{\"dependencies\":" + deps + "}")!.AsObject();
            if (Packs.Problem(Idx("{\"minecraft\":\"1.20.1\",\"neoforge\":\"47.1\"}")) == null) Say("pack rule: a NeoForge only pack was accepted");
            if (Packs.Problem(Idx("{\"minecraft\":\"1.20.1\",\"forge\":\"47.2.0\"}")) != null) Say("pack rule: a Forge pack was refused");
            if (Packs.Problem(Idx("{\"minecraft\":\"1.20.1\",\"fabric-loader\":\"0.15\"}")) != null) Say("pack rule: a Fabric pack was refused");
            if (Packs.Problem(Idx("{\"minecraft\":\"1.19.2\",\"forge\":\"43.0\"}")) == null) Say("pack rule: a 1.19.2 pack was accepted");
            if (Modrinth.Strong("abc") != null || Modrinth.Strong(new string('a', 128)) == null) Say("hash rule: a SHA-512 was judged wrongly");

            var (h0, r0, y0) = GameRun.PlanFor(new ModProfile { MemoryGb = 6, ReserveGb = 0 });
            if (h0 != 6 || r0 != 0 || y0 != 3) Say("memory: 6 GB with no backup gave " + h0 + " + " + r0 + ", young " + y0);
            var (h1, r1, _) = GameRun.PlanFor(new ModProfile { MemoryGb = 6, ReserveGb = 3 });
            if (h1 != 6 || r1 < 0 || r1 > 3 || (r1 > 0 && h1 + r1 > GameRun.TotalGb - 2 + 0.001)) Say("memory: 6 GB with a 3 GB backup gave " + h1 + " + " + r1);
            var (h2, r2, _) = GameRun.PlanFor(new ModProfile());
            if (h2 < 3 || r2 < 0 || r2 > 2) Say("memory: an automatic profile gave " + h2 + " + " + r2);

            var keepHome = Paths.Home;
            var settingsHome = Path.Combine(Path.GetTempPath(), "pentecost-memory-test-" + Guid.NewGuid().ToString("N"));
            try
            {
                Paths.Home = settingsHome;
                Directory.CreateDirectory(Path.GetDirectoryName(Paths.GameSettings)!);
                System.IO.File.WriteAllText(Paths.GameSettings, "{\"sound.limit\":\"20\"}");
                GameRun.SyncGameSettings(new ModProfile { MemoryGb = 5, ReserveGb = 0 });
                var saved = System.IO.File.ReadAllText(Paths.GameSettings);
                if (!saved.Contains("\"java.memory\": \"5\"") || !saved.Contains("\"java.reserve\": \"0\"") || !saved.Contains("sound.limit"))
                    Say("memory: the game settings file was not written as expected: " + saved);
                GameRun.SyncGameSettings(new ModProfile());
                if (System.IO.File.ReadAllText(Paths.GameSettings) != saved) Say("memory: an automatic profile changed the game settings");
            }
            catch (Exception ex) { Say("memory test threw " + ex.GetType().Name + ": " + ex.Message); }
            finally
            {
                Paths.Home = keepHome;
                try { Directory.Delete(settingsHome, true); } catch { }
            }
        }

        if (Environment.GetEnvironmentVariable("PENTECOST_UPDATE_TEST") == "1")
        {
            var live = latest;
            if (live?.ManagerUrl == null) Say("update test: the live release has no Manager setup");
            else
            {
                var (path, err) = await SelfUpdate.Download(live, null, CancellationToken.None);
                Say(path != null ? "update test: downloaded and checked " + path + " (" + new FileInfo(path).Length + " bytes)" : "update test failed: " + err);
            }
        }

        var report = new StringBuilder();
        report.AppendLine($"Pentecost Manager self-test, {DateTime.Now:yyyy-MM-dd HH:mm}");
        report.AppendLine(findings.Count == 0 ? "Nothing looked wrong." : findings.Count + " things to look at:");
        foreach (var f in findings.Distinct()) report.AppendLine("  " + f);
        File.WriteAllText(Path.Combine(dir, "report.txt"), report.ToString());
        testing = false;
    }

    void CheckFull(string what)
    {
        if (!inFull) { Say(what + " did not open"); return; }
        CheckLayout(what);
    }

    void CheckBox_(string what)
    {
        if (Modal.Visibility != Visibility.Visible) { Say(what + " did not open"); return; }
        var box = Tree(ModalHost).OfType<FrameworkElement>().FirstOrDefault();
        if (box == null) { Say(what + " is empty"); return; }
        var r = BoundsIn(box, Frame);
        if (r.Height > Frame.ActualHeight - 10) Say($"{what} is taller than the window ({r.Height:0} against {Frame.ActualHeight:0})");
        if (r.Width > Frame.ActualWidth - 10) Say($"{what} is wider than the window");
        foreach (var o in Tree(ModalHost).OfType<TextBlock>())
            if (o is { TextWrapping: TextWrapping.NoWrap, TextTrimming: TextTrimming.None } t && t.Text.Length > 1 && Shown(t) && !IsGlyph(t))
            {
                t.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
                if (t.DesiredSize.Width - t.Margin.Left - t.Margin.Right > t.ActualWidth + 1.5) Say($"{what}: \"{Short(t.Text)}\" is cut off");
            }
    }

    void Shoot(string dir, string name)
    {
        try
        {
            UpdateLayout();
            var rtb = new RenderTargetBitmap((int)Frame.ActualWidth, (int)Frame.ActualHeight, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(Frame);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(rtb));
            using var fs = File.Create(Path.Combine(dir, name + ".png"));
            enc.Save(fs);
        }
        catch (Exception ex) { Say("could not draw " + name + ": " + ex.Message); }
    }
}
