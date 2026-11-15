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
    bool updating;
    CancellationTokenSource? cts;

    public MainWindow(string minecraftDir, bool update = false, int waitPid = -1)
    {
        InitializeComponent();
        mc = minecraftDir;
        current = HomePanel;
        string version;
        try { version = Setup.PayloadVersion(); } catch (SetupException) { version = "?"; }
        VersionLabel.Text = version;
        RefreshHome();
        Closing += (_, _) => cts?.Cancel();
        Opacity = 0;
        Loaded += (_, _) => BeginAnimation(OpacityProperty,
            new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(320)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } });
        if (update)
        {
            updating = true;
            Loaded += async (_, _) => await RunUpdateAsync(waitPid);
        }
    }

    /// <summary>
    /// Started by the game's Update button: wait for the game to close so its files are free, then install over the old copy.
    /// The main menu worlds are not downloaded again, and the player's worlds, mods and settings stay.
    /// </summary>
    async Task RunUpdateAsync(int waitPid)
    {
        WorldsBox.IsChecked = false;
        ModsBox.IsChecked = false;
        ProgWord.Text = "UPDATING";
        ProgTitle.Text = "Waiting for Pentecost to close";
        ProgDetail.Text = "";
        ProgPercent.Text = "";
        Show(ProgressPanel);
        if (waitPid > 0)
        {
            await Task.Run(() =>
            {
                try { System.Diagnostics.Process.GetProcessById(waitPid).WaitForExit(60_000); }
                catch (ArgumentException) { /* already closed */ }
                catch (Exception ex) { Log.Write("wait failed: " + ex.Message); }
            });
        }
        await RunAsync(false);
    }

    // ------------------------------------------------------------------ pages

    void RefreshHome()
    {
        var installed = Setup.InstalledVersion(mc);
        var version = VersionLabel.Text;
        HomeWord.Text = installed == null ? "INSTALL" : installed == version ? "REINSTALL" : "UPDATE";
        HomeSub.Text = installed == null
            ? $"Version {version} for Minecraft 1.20.1"
            : $"Installed: {installed}. This installer has version {version}.";
        InstallBtn.Content = installed == null ? "Install" : installed == version ? "Reinstall" : "Update";
        UninstallBtn.Visibility = installed == null ? Visibility.Collapsed : Visibility.Visible;
        FolderText.Text = mc;
        FolderText.ToolTip = mc;
        // Look in run\menu_world first, so the box only offers the worlds that are actually missing.
        var allWorlds = Worlds.Load().Worlds.Count;
        var missing = Worlds.Missing(Setup.HomeOf(mc));
        var bytes = missing.Sum(w => w.Size);
        WorldsBox.Visibility = allWorlds > 0 ? Visibility.Visible : Visibility.Collapsed;
        if (allWorlds > 0 && missing.Count == 0)
        {
            WorldsBox.IsChecked = false;
            WorldsBox.IsEnabled = false;
            WorldsTitle.Text = "The main menu worlds are already installed";
            WorldsNote.Text = "Nothing to download. Your copies are left as they are.";
        }
        else
        {
            if (!WorldsBox.IsEnabled) WorldsBox.IsChecked = true;
            WorldsBox.IsEnabled = true;
            var some = missing.Count < allWorlds;
            WorldsTitle.Text = some
                ? $"Download the {missing.Count} missing main menu worlds (about {bytes / 1048576} MB)"
                : $"Download the main menu worlds (about {bytes / 1048576} MB)";
            WorldsNote.Text = some
                ? "The ones you already have are left as they are."
                : "Pentecost shows these behind the title screen. You can skip them and run this installer again later.";
        }

        // Look in run\mods first, so the box only offers what is actually missing.
        var have = VideoMods.Installed(Setup.HomeOf(mc));
        if (have.Embeddium && have.Oculus)
        {
            ModsBox.IsChecked = false;
            ModsBox.IsEnabled = false;
            ModsTitle.Text = "Embeddium and Oculus are already installed";
            ModsNote.Text = "Nothing to download. Your copies are left as they are.";
        }
        else
        {
            if (!ModsBox.IsEnabled) ModsBox.IsChecked = true;
            ModsBox.IsEnabled = true;
            ModsTitle.Text = have.Embeddium ? "Add Oculus (shader packs)"
                : have.Oculus ? "Add Embeddium (video options, borderless)"
                : "Add Embeddium and Oculus (video options, borderless, shader packs)";
            ModsNote.Text = have.Embeddium || have.Oculus
                ? "Small download from Modrinth. The one you already have is left as it is."
                : "Small downloads from Modrinth. You can also add them later from the Video page.";
        }
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
        ProgPercent.Text = $"{(int)Math.Round(Math.Clamp(fraction, 0, 1) * 100)}%";
    }

    // ---------------------------------------------------------------- actions

    async Task RunAsync(bool uninstall)
    {
        lastWasUninstall = uninstall;
        cts = new CancellationTokenSource();
        ProgWord.Text = uninstall ? "REMOVING" : updating ? "UPDATING" : "INSTALLING";
        ProgTitle.Text = "Getting ready";
        ProgDetail.Text = "";
        Bar.BeginAnimation(System.Windows.Controls.Primitives.RangeBase.ValueProperty, null);
        Bar.Value = 0;
        ProgPercent.Text = "0%";
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
                var result = await Setup.InstallAsync(mc, WorldsBox.IsChecked == true && WorldsBox.Visibility == Visibility.Visible, ModsBox.IsChecked == true, progress, cts.Token);
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
            DoneTitle.Text = updating ? "Pentecost is updated" : "Pentecost is installed";
            DoneBody.Text = updating
                ? "1.  Open the Minecraft Launcher.\n2.  Press Play with Pentecost picked. Your worlds, mods and settings are as you left them."
                : "1.  Open the Minecraft Launcher.\n2.  Pick Pentecost next to the Play button.\n3.  Press Play. The first start downloads Minecraft and Forge and takes a few minutes.";
            ModsBtn.Visibility = Visibility.Visible;
            var failed = result?.WorldsFailed ?? 0;
            var modsFailed = result?.ModsFailed ?? 0;
            var warn = new List<string>();
            if (failed > 0) warn.Add($"{failed} of the main menu worlds could not be downloaded. Pentecost works without them. Run this installer again with a working connection to get them.");
            if (modsFailed > 0) warn.Add("Embeddium or Oculus could not be downloaded. Pentecost works without them. You can add them later from its Video page.");
            DoneWarn.Visibility = warn.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            DoneWarn.Text = string.Join("\n\n", warn);
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
