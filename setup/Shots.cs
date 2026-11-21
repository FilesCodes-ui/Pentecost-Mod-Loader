using System.Threading.Tasks;
using System.Windows.Media.Imaging;

namespace Pentecost.Setup;

public partial class SetupWindow
{
    public async Task Shoot(string dir)
    {
        Directory.CreateDirectory(dir);
        await Task.Delay(900);
        Save(dir, "1-welcome.png");
        ShowMods();
        await Task.Delay(3500);
        Save(dir, "2-mods.png");
        var run = Install();
        await Task.Delay(500);
        await Task.Delay(2600);
        Save(dir, "3-installing.png");
        await run;
        await Task.Delay(2600);
        Save(dir, "4-done.png");
        ShowError("Could not reach GitHub. Check your internet connection.");
        await Task.Delay(700);
        Save(dir, "5-error.png");
    }

    void Save(string dir, string name)
    {
        var root = (FrameworkElement)Content;
        var bmp = new RenderTargetBitmap((int)root.ActualWidth, (int)root.ActualHeight, 96, 96, PixelFormats.Pbgra32);
        bmp.Render(root);
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(bmp));
        using var f = File.Create(Path.Combine(dir, name));
        enc.Save(f);
    }
}
