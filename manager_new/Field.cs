using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

/// <summary>
/// One big sheet of pixels for the whole window. Every panel shows the part of the sheet that lies under it, so the
/// panels read as pieces cut from one thing, and the empty space between them stays empty.
/// The sheet is a lattice of large triangles, the shape of the Pentecost mark, broken up by an ordered dither so it
/// thins out and comes back in broad waves.
/// </summary>
public static class Field
{
    /// <summary>Screen size of one pixel of the sheet.</summary>
    public const double Cell = 4;

    const int Wide = 1100, High = 640;
    static BitmapSource? image;

    public static BitmapSource Image => image ??= Make();

    static readonly int[] Bayer = { 0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5 };

    static BitmapSource Make()
    {
        var px = new byte[Wide * High * 4];
        const int period = 48;
        for (var y = 0; y < High; y++)
            for (var x = 0; x < Wide; x++)
            {
                var u = (x + 2 * y) % period;
                var v = ((x - 2 * y) % period + period) % period;
                var flat = y % (period / 4) == 0;
                var slant = u < 2 || v < 2;
                if (!flat && !slant) continue;

                // Broad waves decide where the lattice is thick and where it is gone.
                var wave = (0.5 + 0.5 * Math.Sin(x * 0.011 + y * 0.008)) * (0.5 + 0.5 * Math.Cos(y * 0.013 - x * 0.004));
                var gate = Bayer[(y & 3) * 4 + (x & 3)] / 16.0;
                if (wave * 1.05 <= gate) continue;

                var corner = u < 2 && v < 2;
                var a = corner ? 0.26 : flat ? 0.04 : 0.10;
                var i = (y * Wide + x) * 4;
                var c = (byte)(255 * a);
                px[i] = px[i + 1] = px[i + 2] = c;   // premultiplied white
                px[i + 3] = c;
            }
        var bmp = BitmapSource.Create(Wide, High, 96, 96, PixelFormats.Pbgra32, null, px, Wide * 4);
        bmp.Freeze();
        return bmp;
    }
}
