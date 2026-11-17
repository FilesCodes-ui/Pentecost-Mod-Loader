using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

public partial class MainWindow
{
    /// <summary>Draws each page into a PNG without needing the screen. Used to check the look.</summary>
    public async Task Shoot(string dir, string[] pages)
    {
        Directory.CreateDirectory(dir);
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
            Go(page);
            await Task.Delay(page is "Browse" or "Mods" ? 4500 : 1800);
            await Snap(page);
        }

        // The boxes that open over a page.
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
