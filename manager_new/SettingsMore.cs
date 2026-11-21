using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Pentecost.Manager;

public static class Power
{
    [StructLayout(LayoutKind.Sequential)]
    struct Status { public byte AC, Battery, Percent, Saver; public int Life, Full; }

    [DllImport("kernel32.dll")]
    static extern bool GetSystemPowerStatus(out Status s);

    static bool last;
    static long checkedAt = -1;

    public static bool OnBattery
    {
        get
        {
            var now = Environment.TickCount64;
            if (checkedAt >= 0 && now - checkedAt < 5000) return last;
            checkedAt = now;
            try { last = GetSystemPowerStatus(out var s) && s.AC == 0; } catch { last = false; }
            return last;
        }
    }
}

public static class Autostart
{
    const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string Name = "Pentecost Manager";

    public static void Set(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(Key);
            var exe = Environment.ProcessPath;
            if (on && !string.IsNullOrEmpty(exe)) k.SetValue(Name, "\"" + exe + "\" --minimized");
            else k.DeleteValue(Name, false);
        }
        catch (Exception ex) { AppLog.Write("autostart: " + ex.Message); }
    }
}

public partial class MainWindow
{
    System.Windows.Forms.NotifyIcon? tray;
    bool quitting;

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!quitting && !testing && !managerHandedOver && Settings.CloseAction == "tray")
        {
            e.Cancel = true;
            HideToTray();
            return;
        }
        base.OnClosing(e);
    }

    protected override void OnClosed(EventArgs e)
    {
        if (tray != null) { tray.Visible = false; tray.Dispose(); tray = null; }
        base.OnClosed(e);
    }

    public void HideToTray()
    {
        tray ??= MakeTray();
        tray.Visible = true;
        Hide();
    }

    public void ShowFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        if (tray != null) tray.Visible = false;
    }

    void QuitFromTray()
    {
        quitting = true;
        if (tray != null) tray.Visible = false;
        Close();
    }

    System.Windows.Forms.NotifyIcon MakeTray()
    {
        var n = new System.Windows.Forms.NotifyIcon { Text = "Pentecost Manager" };
        try { n.Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }
        n.DoubleClick += (_, _) => Dispatcher.BeginInvoke(ShowFromTray);
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Open Pentecost Manager", null, (_, _) => Dispatcher.BeginInvoke(ShowFromTray));
        menu.Items.Add("Quit", null, (_, _) => Dispatcher.BeginInvoke(QuitFromTray));
        n.ContextMenuStrip = menu;
        return n;
    }

    Grid TextRow(string title, string sub, string value, Action<string> save)
    {
        var box = new TextBox
        {
            Text = value, Width = 300, Height = 32, Padding = new Thickness(10, 0, 10, 0), VerticalContentAlignment = VerticalAlignment.Center,
            FontFamily = (FontFamily)FindResource("Mono"), FontSize = 12, Background = Ui.Br("Panel"), BorderBrush = Ui.Br("Line"),
            BorderThickness = new Thickness(1), Foreground = Ui.Br("Text"), CaretBrush = Ui.Br("Text"),
        };
        var saved = value;
        void Commit()
        {
            var v = box.Text.Trim();
            if (v == saved) return;
            saved = v;
            save(v);
        }
        box.LostKeyboardFocus += (_, _) => Commit();
        box.KeyDown += (_, e) => { if (e.Key == Key.Enter) { Commit(); Keyboard.ClearFocus(); } };
        return Ui.Row(SettingText(title, sub), box);
    }

    Grid JoinToggle()
    {
        var row = Toggle("Let friends see my server", "When you host a world, friends see its address and can join. Off, it stays hidden.", Online.AllowJoin, _ => { });
        var sw = row.Children.OfType<CheckBox>().First();
        sw.IsEnabled = Online.State == "online";
        sw.Click += async (_, _) =>
        {
            var err = await Online.SetAllowJoin(sw.IsChecked == true);
            if (err != null) { Toast(err, "Warn"); sw.IsChecked = Online.AllowJoin; }
        };
        return row;
    }
}
