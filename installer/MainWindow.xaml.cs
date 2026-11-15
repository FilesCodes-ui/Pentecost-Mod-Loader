using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Microsoft.Win32;

namespace Pentecost.Installer;

public partial class MainWindow : Window
{
    string mc;
    Panel current;
    bool lastWasUninstall;
    bool removeData;
    CancellationTokenSource? cts;

    public MainWindow(string minecraftDir)
    {
        InitializeComponent();
        mc = minecraftDir;
        current = HomePanel;
        string version;
        try { version = Setup.PayloadVersion(); } catch (SetupException) { version = "?"; }
        VersionLabel.Text = version;
        RefreshHome();
        Closing += (_, _) => cts?.Cancel();
    }

    // ------------------------------------------------------------------ pages

    void RefreshHome()
    {
        var installed = Setup.InstalledVersion(mc);
        var version = VersionLabel.Text;
        HomeTitle.Text = installed == null ? "Install Pentecost" : installed == version ? "Reinstall Pentecost" : "Update Pentecost";
        HomeSub.Text = installed == null
            ? $"Version {version} for Minecraft 1.20.1"
            : $"Installed: {installed}. This installer has version {version}.";
        InstallBtn.Content = installed == null ? "Install" : installed == version ? "Reinstall" : "Update";
        UninstallBtn.Visibility = installed == null ? Visibility.Collapsed : Visibility.Visible;
        FolderText.Text = mc;
        FolderText.ToolTip = mc;
        var bytes = Worlds.TotalBytes();
        WorldsBox.Visibility = bytes > 0 ? Visibility.Visible : Visibility.Collapsed;
        WorldsTitle.Text = $"Download the main menu worlds (about {bytes / 1048576} MB)";
    }

    void Show(Panel next)
    {
        if (next == current) return;
        current.Visibility = Visibility.Collapsed;
        next.Visibility = Visibility.Visible;
        next.Opacity = 0;
        next.RenderTransform = new TranslateTransform(0, 10);
        next.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = new QuadraticEase() });
        next.RenderTransform.BeginAnimation(TranslateTransform.YProperty,
            new DoubleAnimation(10, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } });
        current = next;
    }

    void SetBar(double fraction)
    {
        Bar.BeginAnimation(System.Windows.Controls.Primitives.RangeBase.ValueProperty,
            new DoubleAnimation(Math.Clamp(fraction, 0, 1), TimeSpan.FromMilliseconds(220)));
        ProgPercent.Text = $"{(int)Math.Round(Math.Clamp(fraction, 0, 1) * 100)} %";
    }

    // ---------------------------------------------------------------- actions

    async Task RunAsync(bool uninstall)
    {
        lastWasUninstall = uninstall;
        cts = new CancellationTokenSource();
        ProgTitle.Text = uninstall ? "Removing Pentecost" : "Installing Pentecost";
        ProgDetail.Text = "";
        Bar.BeginAnimation(System.Windows.Controls.Primitives.RangeBase.ValueProperty, null);
        Bar.Value = 0;
        ProgPercent.Text = "0 %";
        Show(ProgressPanel);

        var progress = new Progress<Progress>(p =>
        {
            ProgTitle.Text = p.Step == "Done" ? ProgTitle.Text : p.Step;
            ProgDetail.Text = p.Detail;
            SetBar(p.Fraction);
        });
        try
        {
            if (uninstall)
            {
                var drop = removeData;
                await Task.Run(() => Setup.Uninstall(mc, drop, progress));
                ShowDone(uninstall: true, result: null);
            }
            else
            {
                var result = await Setup.InstallAsync(mc, WorldsBox.IsChecked == true && WorldsBox.Visibility == Visibility.Visible, progress, cts.Token);
                ShowDone(uninstall: false, result);
            }
        }
        catch (OperationCanceledException) { /* the window is closing */ }
        catch (SetupException ex) { Log.Write("error: " + ex.Message); ShowError(ex.Message); }
        catch (Exception ex)
        {
            Log.Write("error: " + ex);
            ShowError($"Something unexpected went wrong:\n{ex.Message}");
        }
    }

    void ShowDone(bool uninstall, InstallResult? result)
    {
        SetBar(1);
        if (uninstall)
        {
            DoneTitle.Text = "Pentecost is removed";
            DoneBody.Text = removeData
                ? "The program files, the launcher profile and your Pentecost game folder are gone."
                : "Your worlds, mods and settings are still in the Pentecost game folder, in case you install again.";
            ModsBtn.Visibility = Visibility.Collapsed;
            DoneWarn.Visibility = Visibility.Collapsed;
        }
        else
        {
            DoneTitle.Text = "Pentecost is installed";
            DoneBody.Text = "1.  Open the Minecraft Launcher.\n2.  Pick Pentecost next to the Play button.\n3.  Press Play. The first start downloads Minecraft and Forge and takes a few minutes.";
            ModsBtn.Visibility = Visibility.Visible;
            var failed = result?.WorldsFailed ?? 0;
            DoneWarn.Visibility = failed > 0 ? Visibility.Visible : Visibility.Collapsed;
            DoneWarn.Text = failed > 0
                ? $"{failed} of the main menu worlds could not be downloaded. Pentecost works without them. Run this installer again with a working connection to get them."
                : "";
        }
        Show(DonePanel);
    }

    void ShowError(string message)
    {
        ErrBody.Text = message;
        ErrLog.Text = "Details are in " + Log.Path_;
        Show(ErrorPanel);
    }

    // --------------------------------------------------------------- handlers

    void TitleBar_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    void Min_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    void Close_Click(object sender, RoutedEventArgs e) => Close();

    void Change_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "Choose the .minecraft folder", InitialDirectory = Directory.Exists(mc) ? mc : null };
        if (dialog.ShowDialog(this) == true)
        {
            mc = dialog.FolderName;
            RefreshHome();
        }
    }

    async void Install_Click(object sender, RoutedEventArgs e) => await RunAsync(false);

    void Uninstall_Click(object sender, RoutedEventArgs e)
    {
        RemoveDataBox.IsChecked = false;
        Show(ConfirmPanel);
    }

    async void ConfirmRemove_Click(object sender, RoutedEventArgs e)
    {
        removeData = RemoveDataBox.IsChecked == true;
        await RunAsync(true);
    }

    void Back_Click(object sender, RoutedEventArgs e)
    {
        RefreshHome();
        Show(HomePanel);
    }

    async void Retry_Click(object sender, RoutedEventArgs e) => await RunAsync(lastWasUninstall);

    void OpenMods_Click(object sender, RoutedEventArgs e)
    {
        var mods = Path.Combine(Setup.HomeOf(mc), "run", "mods");
        Directory.CreateDirectory(mods);
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(mods) { UseShellExecute = true });
    }
}
