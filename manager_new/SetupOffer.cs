namespace Pentecost.Manager;

public partial class MainWindow
{
    /// <summary>The downloaded one-file Manager offers to install itself. It then restarts from the install folder and offers Pentecost.</summary>
    void OfferSelfInstall()
    {
        var no = Ui.Btn("Run once", "Secondary", (_, _) => HideModal());
        var go = Ui.Btn("Install", "Primary", async (_, _) =>
        {
            var err = await Task.Run(SelfInstall.Install);
            if (err != null) { HideModal(); Toast(err, "Bad"); return; }
            App.ReleaseSingle();
            SelfInstall.Launch("--welcome");
            Application.Current.Shutdown();
        });
        var buttons = Ui.H(no, go.With(new Thickness(10, 0, 0, 0)));
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        var box = Ui.V(Ui.T("Install Pentecost Manager", 17, bold: true),
            Ui.T("The Manager goes into " + SelfInstall.Target + ", with a shortcut on the desktop and in the Start menu. It installs and updates Pentecost for you.", 13, "Soft", wrap: true).With(new Thickness(0, 8, 0, 0)),
            buttons.With(new Thickness(0, 24, 0, 0)));
        box.Width = 470;
        ShowModal(Ui.Card(box, 26));
    }
}
