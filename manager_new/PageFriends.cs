using System.Windows.Threading;

namespace Pentecost.Manager;

public partial class MainWindow
{
    string? chatWith;

    void InitOnline()
    {
        Online.Changed += () => Dispatcher.BeginInvoke(() => { RefreshAccount(); friendsRender?.Invoke(); chatRender?.Invoke(); });
        Online.Arrived += (from, kind) => Dispatcher.BeginInvoke(() =>
        {
            var name = Online.Friends.FirstOrDefault(f => f.Uuid == from)?.Name ?? "A friend";
            if (kind == "msg" && chatWith == from && inFull) { Online.MarkRead(from); return; }
            if (Settings.MessagePopups) Toast(kind == "share" ? name + " shared a profile with you. See Friends." : "New message from " + name + ".");
        });
        Feed.Changed += () => Dispatcher.BeginInvoke(() =>
        {
            RefreshBar();
            if (!inFull && Modal.Visibility != Visibility.Visible && current is "Play" or "Dev") ShowPage(current, false);
            ShowWhatsNew();
        });
        Feed.PublishFailed += err => Dispatcher.BeginInvoke(() => Toast("Your change did not go live: " + err, "Bad"));
        Feed.LoadCached();
        if (Environment.GetEnvironmentVariable("PENTECOST_SHOT_PREVIEW") == "1") Feed.PreviewQuiet(Feed.Draft());
        if (testing) return;
        Online.Start();
        _ = Feed.Refresh();
        var feedTimer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(10) };
        feedTimer.Tick += (_, _) => _ = Feed.Refresh();
        feedTimer.Start();
        var said = "";
        var statusTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        statusTimer.Tick += (_, _) =>
        {
            var now = Online.State + "|" + ProfileStore.Active.Name;
            if (now == said || Online.State != "online") return;
            said = now;
            _ = Online.SayStatus(ProfileStore.Active.Name);
        };
        statusTimer.Start();
    }

    Action? friendsRender;
    Fleet? fleetView;
    Action? chatRender;

    FrameworkElement BuildFriends()
    {
        var left = new StackPanel();
        var right = new StackPanel();

        var fleet = new Fleet { Height = 330, Margin = new Thickness(-34, -24, -28, 0) };
        fleetView = fleet;
        fleet.Picked += id => { if (id == DemoFriend.Id) OpenDemoChat(); else OpenChat(id); };
        if (DemoFriend.Shown) _ = Heads.Flag(DemoFriend.Crowd, 4, 2).ContinueWith(t => Dispatcher.BeginInvoke(() => fleet.SetFlag(DemoFriend.Id, t.Result.Gray, t.Result.W, t.Result.H)));
        var headCount = Ui.T("", 13, "Soft");
        headCount.FontFamily = (FontFamily)FindResource("Mono");

        var code = Ui.T("", 28, bold: true);
        code.FontFamily = (FontFamily)FindResource("Mono");
        var copy = Ui.Btn("Copy", "Secondary", (_, _) =>
        {
            if (Online.MyCode == null) return;
            try { Clipboard.SetText(Online.Pretty(Online.MyCode)); Toast("Your friend code is copied."); } catch { }
        }, "\uE8C8");
        var fresh = Ui.Btn("New code", "Ghost", (_, _) => Confirm("Make a new friend code?",
            "Your old code stops working right away. Friends you already have stay your friends.", "New code", () => _ = Online.NewCode()));
        var codeCard = Ui.Section("Your friend code", Ui.V(code,
            Ui.H(copy, fresh.With(new Thickness(8, 0, 0, 0))).With(new Thickness(0, 14, 0, 0))));

        var input = new TextBox { Height = 38, VerticalContentAlignment = VerticalAlignment.Center, Padding = new Thickness(12, 0, 12, 0), MaxLength = 20 };
        var result = Ui.T("", 12, "Muted", wrap: true);
        async Task AddNow()
        {
            var text = input.Text.Trim();
            if (text.Length == 0) return;
            result.Text = "Sending";
            result.Foreground = Ui.Br("Muted");
            var err = await Online.Add(text);
            result.Text = err ?? "Request sent.";
            result.Foreground = Ui.Br(err == null ? "Ok" : "Warn");
            if (err == null) input.Clear();
        }
        input.KeyDown += (_, e) => { if (e.Key == Key.Enter) _ = AddNow(); };
        var addBtn = Ui.Btn("Add", "Primary", (_, _) => _ = AddNow());
        var addRow = new Grid();
        addRow.ColumnDefinitions.Add(new ColumnDefinition());
        addRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        addRow.Children.Add(input);
        Grid.SetColumn(addBtn, 1);
        addBtn.Margin = new Thickness(8, 0, 0, 0);
        addRow.Children.Add(addBtn);
        input.ToolTip = "Their friend code";
        var addCard = Ui.Section("Add a friend", Ui.V(addRow, result.With(new Thickness(0, 8, 0, 0))));

        var join = new CheckBox { Style = Ui.St("Switch"), Content = Ui.T("Let friends see my server", 13, "Soft") };
        join.Click += async (_, _) =>
        {
            var err = await Online.SetAllowJoin(join.IsChecked == true);
            if (err != null) { Toast(err, "Warn"); join.IsChecked = Online.AllowJoin; }
        };
        join.ToolTip = "Friends see the address of the server you play on, so they can join you.";
        var joinCard = Ui.Section("Joining", join);

        var requests = new StackPanel();
        var shares = new StackPanel();
        var list = new StackPanel();

        left.Children.Add(codeCard);
        left.Children.Add(addCard);
        left.Children.Add(joinCard);
        left.Children.Add(requests);
        left.Children.Add(shares);
        right.Children.Add(list);

        Border PersonRow(string name, string sub, string dot, params UIElement[] buttons)
        {
            var face = new Grid { Width = 30, Height = 30, VerticalAlignment = VerticalAlignment.Center };
            face.Children.Add(Heads.Tile(name, 30, dot == "Faint"));
            var d = Ui.Dot(dot, 7);
            d.HorizontalAlignment = HorizontalAlignment.Right;
            d.VerticalAlignment = VerticalAlignment.Bottom;
            d.Margin = new Thickness(0, 0, -3, -3);
            face.Children.Add(d);
            return PersonRowWith(face, name, sub, buttons);
        }

        Border PersonRowWith(FrameworkElement mark, string name, string sub, params UIElement[] buttons)
        {
            var text = Ui.V(Ui.T(name, 13.5, bold: true, trim: true), Ui.T(sub, 11.5, "Faint", trim: true).With(new Thickness(0, 2, 0, 0)));
            text.Margin = new Thickness(12, 0, 12, 0);
            var head = Ui.H(mark, text);
            head.VerticalAlignment = VerticalAlignment.Center;
            var b = Ui.H(buttons);
            b.VerticalAlignment = VerticalAlignment.Center;
            return Ui.HoverRow(new Border { BorderBrush = Ui.Br("Line"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(8, 10, 8, 10), Child = Ui.Row(head, b) });
        }

        Button Small(string text, string style, Func<Task> act)
        {
            var b = Ui.Btn(text, style);
            b.Padding = new Thickness(12, 5, 12, 5);
            b.FontSize = 12;
            b.Margin = new Thickness(6, 0, 0, 0);
            b.Click += async (_, _) => { b.IsEnabled = false; await act(); b.IsEnabled = true; };
            return b;
        }

        void Render()
        {
            var s = Online.State;
            headCount.Text = s == "online" ? $"{Online.Friends.Count(f => f.Online)} OF {Online.Friends.Count} ONLINE"
                : s == "nosession" ? "OFFLINE. OPEN PENTECOST ONCE WHILE SIGNED IN." : "CONNECTING";
            var crew = Online.Friends.Select(f => (f.Uuid, f.Name, Online.Doing(f), f.Online, Online.Unread(f.Uuid))).ToList();
            if (DemoFriend.Shown) crew.Insert(0, (DemoFriend.Id, DemoFriend.Name, DemoFriend.Status, true, 0));
            fleet.SetFleet(crew, s == "online" || DemoFriend.Shown, Online.Incoming.Count);
            code.Text = Online.MyCode != null ? Online.Pretty(Online.MyCode) : "-----";
            copy.IsEnabled = fresh.IsEnabled = Online.MyCode != null;
            join.IsChecked = Online.AllowJoin;
            join.IsEnabled = s == "online";
            addBtn.IsEnabled = s == "online";

            requests.Children.Clear();
            var inc = Online.Incoming;
            var outg = Online.Outgoing;
            if (inc.Count + outg.Count > 0)
            {
                var body = new StackPanel();
                foreach (var p in inc.OrderByDescending(x => x.Ts))
                {
                    var who = p;
                    body.Children.Add(PersonRow(who.Name, "Wants to be your friend", "Accent",
                        Small("Accept", "Primary", async () => { var e = await Online.Simple("accept", who.Uuid); if (e != null) Toast(e, "Warn"); }),
                        Small("Decline", "Secondary", async () => { var e = await Online.Simple("decline", who.Uuid); if (e != null) Toast(e, "Warn"); })));
                }
                foreach (var p in outg.OrderByDescending(x => x.Ts))
                {
                    var who = p;
                    body.Children.Add(PersonRow(who.Name, "Waiting for them to accept", "Faint",
                        Small("Cancel", "Ghost", async () => { var e = await Online.Simple("cancel", who.Uuid); if (e != null) Toast(e, "Warn"); })));
                }
                requests.Children.Add(Ui.Section("Requests", body));
            }

            shares.Children.Clear();
            var sh = Online.Shares;
            if (sh.Count > 0)
            {
                var body = new StackPanel();
                foreach (var x in sh)
                {
                    var item = x;
                    var n = (item.Pack["mods"] as System.Text.Json.Nodes.JsonArray)?.Count ?? 0;
                    body.Children.Add(PersonRow(item.Name, $"From {item.FromName}, {n} mods, {Ui.Ago(item.Time)}", "Accent",
                        Small("Make a profile", "Primary", async () =>
                        {
                            if (game.Running) { Toast("Close the game first.", "Warn"); return; }
                            Toast("Making the profile. Mods download in the background.");
                            try { var r = await Online.ImportShare(item); Toast(r.Summary); Refresh(); }
                            catch (Exception ex) { Toast("Could not make it: " + ex.Message, "Bad"); }
                        }),
                        Small("Dismiss", "Ghost", () => { Online.Dismiss(item); return Task.CompletedTask; })));
                }
                shares.Children.Add(Ui.Section("Shared with you", body));
            }

            list.Children.Clear();
            var friends = Online.Friends.OrderByDescending(f => f.Online).ThenBy(f => f.Name, StringComparer.OrdinalIgnoreCase).ToList();
            var rows = new StackPanel();
            if (DemoFriend.Shown)
            {
                var demo = PersonRowWith(Heads.Mosaic(DemoFriend.Crowd, 4, 30), DemoFriend.Name, DemoFriend.Status, Ui.Pill("Dev"));
                demo.Cursor = Cursors.Hand;
                demo.MouseLeftButtonUp += (_, _) => OpenDemoChat();
                rows.Children.Add(demo);
            }
            if (friends.Count == 0 && !DemoFriend.Shown)
                rows.Children.Add(Ui.T(s == "online" ? "No friends yet." : "Offline.", 13, "Muted"));
            foreach (var f in friends)
            {
                var fr = f;
                var unread = Online.Unread(fr.Uuid);
                var badge = unread > 0 ? (UIElement)Ui.Pill(unread + " new", "Accent") : new Border();
                var row = PersonRow(fr.Name, Online.Doing(fr), fr.Online ? "Ok" : "Faint", badge);
                row.Cursor = Cursors.Hand;
                row.MouseLeftButtonUp += (_, _) => OpenChat(fr.Uuid);
                rows.Children.Add(row);
            }
            var online = friends.Count(f => f.Online);
            list.Children.Add(Ui.Section(friends.Count == 0 ? "Friends" : $"Friends, {online} online", rows));
        }

        var grid = new Grid();
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        var headText = Ui.V(new Wordmark { Text = "FRIENDS", Pixel = 3, Fill = Ui.Br("Text"), HorizontalAlignment = HorizontalAlignment.Left },
            headCount.With(new Thickness(2, 10, 0, 0)));
        headText.HorizontalAlignment = HorizontalAlignment.Left;
        headText.VerticalAlignment = VerticalAlignment.Top;
        headText.Margin = new Thickness(2, 30, 0, 0);
        headText.IsHitTestVisible = false;
        var head = new Grid();
        head.Children.Add(fleet);
        head.Children.Add(headText);
        grid.Children.Add(head);
        var cols = new Grid { Margin = new Thickness(0, 26, 0, 0) };
        cols.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(380) });
        cols.ColumnDefinitions.Add(new ColumnDefinition());
        var ls = Scroll(left);
        var rs = Scroll(right);
        rs.Margin = new Thickness(48, 0, 0, 0);
        cols.Children.Add(ls);
        Grid.SetColumn(rs, 1);
        cols.Children.Add(rs);
        Grid.SetRow(cols, 1);
        grid.Children.Add(cols);

        Render();
        grid.Loaded += (_, _) => friendsRender = Render;
        grid.Unloaded += (_, _) => { if (friendsRender == Render) friendsRender = null; };
        friendsRender = Render;
        return grid;
    }

    void OpenDemoChat()
    {
        chatWith = DemoFriend.Id;
        var sub = Ui.T(DemoFriend.Status, 12, "Faint", trim: true);
        var lines = new StackPanel();
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = lines, Padding = new Thickness(0, 0, 10, 0) };
        var input = new TextBox { Height = 40, VerticalContentAlignment = VerticalAlignment.Center, Padding = new Thickness(12, 0, 12, 0), MaxLength = 500 };
        var send = Ui.Btn("Send", "Primary");

        void Render()
        {
            lines.Children.Clear();
            foreach (var l in DemoFriend.Chat)
            {
                var who = l.Mine ? "You" : l.Who;
                var body = Ui.V(Ui.T(l.Text, 13, "Text", wrap: true), Ui.T(who + ", " + l.Time.ToString("HH:mm"), 10.5, "Faint").With(new Thickness(0, 4, 0, 0)));
                var bubble = new PixelBox
                {
                    Background = Ui.Br(l.Mine ? "Panel2" : "Panel"), BorderBrush = Ui.Br(l.Mine ? "Edge" : "Line"), BorderThickness = new Thickness(1), Notch = 2,
                    Padding = new Thickness(12, 8, 12, 8), MaxWidth = 560, HorizontalAlignment = l.Mine ? HorizontalAlignment.Right : HorizontalAlignment.Left,
                    Margin = new Thickness(0, 0, 0, 8),
                    Child = l.Mine ? body : Ui.H(Heads.Tile(l.Who, 24).With(new Thickness(0, 0, 10, 0)), body),
                };
                lines.Children.Add(bubble);
            }
            scroll.ScrollToEnd();
        }

        void SendNow()
        {
            var text = input.Text.Trim();
            if (text.Length == 0) return;
            DemoFriend.Chat.Add((true, "", text, DateTime.Now));
            input.Clear();
            Render();
            var replies = 1 + new Random().Next(3);
            for (var i = 0; i < replies; i++)
            {
                var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700 + i * 650 + new Random().Next(400)) };
                timer.Tick += (_, _) =>
                {
                    timer.Stop();
                    DemoFriend.Chat.Add((false, DemoFriend.Who(), DemoFriend.Cheer(), DateTime.Now));
                    if (inFull && chatWith == DemoFriend.Id) Render();
                };
                timer.Start();
            }
            input.Focus();
        }
        send.Click += (_, _) => SendNow();
        input.KeyDown += (_, e) => { if (e.Key == Key.Enter) SendNow(); };
        var inputRow = new Grid { Margin = new Thickness(0, 12, 0, 0) };
        inputRow.ColumnDefinitions.Add(new ColumnDefinition());
        inputRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        inputRow.Children.Add(input);
        Grid.SetColumn(send, 1);
        send.Margin = new Thickness(8, 0, 0, 0);
        inputRow.Children.Add(send);

        var body = new Grid();
        body.RowDefinitions.Add(new RowDefinition());
        body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        body.Children.Add(Ui.Card(scroll, 16));
        Grid.SetRow(inputRow, 1);
        body.Children.Add(inputRow);
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.Children.Add(FullBar(DemoFriend.Name, Heads.Mosaic(DemoFriend.Crowd, 4, 40), sub));
        body.Margin = new Thickness(0, 18, 0, 24);
        Grid.SetRow(body, 1);
        root.Children.Add(body);
        OpenFull(root);
        Render();
        input.Focus();
    }

    void OpenChat(string uuid)
    {
        var f = Online.Friends.FirstOrDefault(x => x.Uuid == uuid);
        if (f == null) return;
        chatWith = uuid;
        Online.MarkRead(uuid);
        var sub = Ui.T(Online.Doing(f), 12, "Faint", trim: true);

        var share = Ui.Btn("Share a profile", "Secondary", null, "\uE72D");
        Ui.Menu(share, () => ProfileStore.Load().Profiles.Select(p => ("Share " + p.Name, (Action)(async () =>
        {
            if (p.Name != ProfileStore.Active.Name) { Toast("Use that profile first, then share it.", "Warn"); return; }
            var err = await Online.ShareProfile(uuid, p);
            Toast(err ?? "Shared " + p.Name + " with " + f.Name + ".", err == null ? "Ok" : "Warn");
        }), false)));
        var joinBtn = Ui.Btn("Copy server address", "Secondary", (_, _) =>
        {
            var server = Online.Friends.FirstOrDefault(x => x.Uuid == uuid)?.Status?["server"]?.ToString();
            if (server == null) { Toast("They are not on a server they share.", "Warn"); return; }
            try { Clipboard.SetText(server); Toast("Copied " + server + ". In the game: Multiplayer, Direct connection, paste."); } catch { }
        }, "\uE71B");
        var remove = Ui.Btn("Remove", "Secondary", (_, _) => Confirm("Remove " + f.Name + "?",
            "You stop seeing each other online and can't message. You can add each other again with a code.", "Remove", async () =>
            {
                var err = await Online.Simple("remove", uuid);
                if (err != null) Toast(err, "Warn"); else { Toast(f.Name + " was removed."); CloseFull(); }
            }, true));
        remove.Foreground = Ui.Br("Bad");

        var lines = new StackPanel();
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = lines, Padding = new Thickness(0, 0, 10, 0) };
        var input = new TextBox { Height = 40, VerticalContentAlignment = VerticalAlignment.Center, Padding = new Thickness(12, 0, 12, 0), MaxLength = 500 };
        var send = Ui.Btn("Send", "Primary");
        async Task SendNow()
        {
            var text = input.Text.Trim();
            if (text.Length == 0) return;
            send.IsEnabled = false;
            var err = await Online.Send(uuid, text);
            send.IsEnabled = true;
            if (err != null) Toast(err, "Warn"); else input.Clear();
            input.Focus();
        }
        send.Click += (_, _) => _ = SendNow();
        input.KeyDown += (_, e) => { if (e.Key == Key.Enter) _ = SendNow(); };
        var inputRow = new Grid { Margin = new Thickness(0, 12, 0, 0) };
        inputRow.ColumnDefinitions.Add(new ColumnDefinition());
        inputRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        inputRow.Children.Add(input);
        Grid.SetColumn(send, 1);
        send.Margin = new Thickness(8, 0, 0, 0);
        inputRow.Children.Add(send);

        void Render()
        {
            if (!inFull || chatWith != uuid) { chatRender = null; return; }
            var now = Online.Friends.FirstOrDefault(x => x.Uuid == uuid);
            if (now == null) { chatRender = null; CloseFull(); return; }
            sub.Text = Online.Doing(now);
            joinBtn.IsEnabled = now.Status?["server"] != null;
            Online.MarkRead(uuid);
            lines.Children.Clear();
            var chat = Online.Chat(uuid);
            if (chat.Count == 0)
                lines.Children.Add(Ui.T("No messages yet.", 12.5, "Faint"));
            foreach (var l in chat)
            {
                var bubble = new PixelBox
                {
                    Background = Ui.Br(l.Mine ? "Panel2" : "Panel"), BorderBrush = Ui.Br(l.Mine ? "Edge" : "Line"), BorderThickness = new Thickness(1), Notch = 2,
                    Padding = new Thickness(12, 8, 12, 8), MaxWidth = 560, HorizontalAlignment = l.Mine ? HorizontalAlignment.Right : HorizontalAlignment.Left,
                    Margin = new Thickness(0, 0, 0, 8),
                    Child = Ui.V(Ui.T(l.Text, 13, "Text", wrap: true), Ui.T((l.Mine ? "You" : now.Name) + ", " + l.Time.ToString("HH:mm"), 10.5, "Faint").With(new Thickness(0, 4, 0, 0))),
                };
                lines.Children.Add(bubble);
            }
            scroll.ScrollToEnd();
        }

        var body = new Grid();
        body.RowDefinitions.Add(new RowDefinition());
        body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var chatCard = Ui.Card(scroll, 16);
        body.Children.Add(chatCard);
        Grid.SetRow(inputRow, 1);
        body.Children.Add(inputRow);

        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.Children.Add(FullBar(f.Name, null, sub, share.With(new Thickness(0, 0, 8, 0)), joinBtn.With(new Thickness(0, 0, 8, 0)), remove));
        body.Margin = new Thickness(0, 18, 0, 24);
        Grid.SetRow(body, 1);
        root.Children.Add(body);
        OpenFull(root);
        chatRender = Render;
        Render();
        input.Focus();
    }
}
