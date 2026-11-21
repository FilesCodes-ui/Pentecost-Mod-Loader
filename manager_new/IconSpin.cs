using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace Pentecost.Manager;

static class IconSpin
{
    const int WM_SETICON = 0x0080, ICON_SMALL = 0, ICON_BIG = 1, SM_CXICON = 11, SM_CXSMICON = 49;

    [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] static extern IntPtr CreateIconIndirect(ref IconInfo info);
    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr icon);
    [DllImport("gdi32.dll")] static extern IntPtr CreateBitmap(int w, int h, uint planes, uint bits, byte[]? data);
    [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr obj);

    [StructLayout(LayoutKind.Sequential)]
    struct IconInfo
    {
        public bool Icon;
        public int HotX, HotY;
        public IntPtr Mask, Color;
    }

    static IntPtr lastSmall, lastBig;

    public static void Show(Window window, double seconds)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero) return;
        var dpi = VisualTreeHelper.GetDpi(window).DpiScaleX;
        var big = Make(IconMaker.FrameAt(Math.Max(16, (int)Math.Round(GetSystemMetrics(SM_CXICON) * Math.Max(1, dpi) / Math.Max(1, dpi))), seconds));
        var small = Make(IconMaker.FrameAt(Math.Max(16, GetSystemMetrics(SM_CXSMICON)), seconds));
        SendMessage(hwnd, WM_SETICON, (IntPtr)ICON_BIG, big);
        SendMessage(hwnd, WM_SETICON, (IntPtr)ICON_SMALL, small);
        if (lastBig != IntPtr.Zero) DestroyIcon(lastBig);
        if (lastSmall != IntPtr.Zero) DestroyIcon(lastSmall);
        lastBig = big;
        lastSmall = small;
    }

    static IntPtr Make(BitmapSource frame)
    {
        var src = frame.Format == PixelFormats.Bgra32 ? frame : new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
        int w = src.PixelWidth, h = src.PixelHeight;
        var px = new byte[w * h * 4];
        src.CopyPixels(px, w * 4, 0);
        var color = CreateBitmap(w, h, 1, 32, px);
        var mask = CreateBitmap(w, h, 1, 1, new byte[((w + 15) / 16) * 2 * h]);
        var info = new IconInfo { Icon = true, Mask = mask, Color = color };
        var icon = CreateIconIndirect(ref info);
        DeleteObject(color);
        DeleteObject(mask);
        return icon;
    }
}
