using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace Pentecost.Manager;

/// <summary>
/// A vector-monitor view of Pentecost's pixel boat: the boat's outline extruded into a block, drawn as white lines that
/// fade with distance, turning slowly above a perspective floor grid that drifts toward you. The mouse leans it.
/// Everything is drawn with plain lines (no 3D engine), so it stays crisp and cheap.
/// </summary>
public class Wire3D : FrameworkElement
{
    readonly record struct V(double X, double Y, double Z);

    readonly List<(V A, V B, bool Rib)> boat = new();
    readonly Pen[] pens = new Pen[16];
    double time, yawBias, yawTarget, pitchBias, pitchTarget;
    TimeSpan last;
    bool running;
    // set per frame by OnRender
    double focal, midX, midY;
    const double Dist = 30;

    public Wire3D()
    {
        for (int i = 0; i < pens.Length; i++)
        {
            var brush = new SolidColorBrush(Color.FromArgb((byte)(25 + i * 15), 242, 242, 242));
            brush.Freeze();
            var pen = new Pen(brush, 1.4) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
            pen.Freeze();
            pens[i] = pen;
        }
        Build();
        ClipToBounds = true;
        Effect = new System.Windows.Media.Effects.DropShadowEffect { Color = Colors.White, BlurRadius = 9, ShadowDepth = 0, Opacity = 0.4 };
        IsVisibleChanged += (_, _) => Run(IsVisible);
        Unloaded += (_, _) => Run(false);
    }

    void Build()
    {
        var cells = Pixels.BoatCells().ToHashSet();
        const int w = 12, h = 11;
        const double d = 3.2;
        var segs = new List<((int X, int Y) A, (int X, int Y) B)>();
        foreach (var (r, c) in cells)
        {
            if (!cells.Contains((r - 1, c))) segs.Add(((c, r), (c + 1, r)));
            if (!cells.Contains((r + 1, c))) segs.Add(((c, r + 1), (c + 1, r + 1)));
            if (!cells.Contains((r, c - 1))) segs.Add(((c, r), (c, r + 1)));
            if (!cells.Contains((r, c + 1))) segs.Add(((c + 1, r), (c + 1, r + 1)));
        }
        static V P(int gx, int gy, double z) => new(gx - w / 2.0, h / 2.0 - gy, z);
        var corners = new HashSet<(int, int)>();
        foreach (var (a, b) in segs)
        {
            boat.Add((P(a.X, a.Y, -d / 2), P(b.X, b.Y, -d / 2), false));
            boat.Add((P(a.X, a.Y, d / 2), P(b.X, b.Y, d / 2), false));
            corners.Add((a.X, a.Y));
            corners.Add((b.X, b.Y));
        }
        foreach (var (x, y) in corners) boat.Add((P(x, y, -d / 2), P(x, y, d / 2), true));
    }

    void Run(bool on)
    {
        if (on == running) return;
        running = on;
        if (on) { last = TimeSpan.Zero; CompositionTarget.Rendering += Frame; }
        else CompositionTarget.Rendering -= Frame;
    }

    void Frame(object? sender, EventArgs e)
    {
        var now = ((RenderingEventArgs)e).RenderingTime;
        double dt = last == TimeSpan.Zero ? 0.03 : (now - last).TotalSeconds;
        if (dt < 0.028) return; // about 35 frames a second is plenty for slow lines
        last = now;
        time += Math.Min(dt, 0.1);
        yawBias += (yawTarget - yawBias) * 0.1;
        pitchBias += (pitchTarget - pitchBias) * 0.1;
        InvalidateVisual();
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        var p = e.GetPosition(this);
        if (ActualWidth > 0 && ActualHeight > 0)
        {
            yawTarget = (p.X / ActualWidth - 0.5) * 1.0;
            pitchTarget = (p.Y / ActualHeight - 0.5) * 0.3;
        }
    }

    protected override void OnMouseLeave(MouseEventArgs e) { yawTarget = 0; pitchTarget = 0; }

    (double X, double Y, double Z) Project(V p, double yaw, double pitch)
    {
        double cy = Math.Cos(yaw), sy = Math.Sin(yaw);
        double x1 = p.X * cy + p.Z * sy, z1 = -p.X * sy + p.Z * cy;
        double cp = Math.Cos(pitch), sp = Math.Sin(pitch);
        double y2 = p.Y * cp - z1 * sp, z2 = p.Y * sp + z1 * cp;
        double k = focal / (Dist + z2);
        return (midX + x1 * k, midY - y2 * k, z2);
    }

    /// <summary>Nearer lines are brighter. Depth runs about -9 (near) to 9 (far) for the boat.</summary>
    static int Level(double z, int dim = 0) => Math.Clamp((int)((1 - (z + 9) / 18) * 15) - dim, 0, 15);

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h)); // so the mouse is seen over empty parts too
        if (w < 40 || h < 40) return;
        focal = Math.Min(h * 1.7, w * 1.1);
        midX = w / 2;
        midY = h / 2 - h * 0.05;
        double pitch = -0.22 + 0.04 * Math.Sin(time * 0.7) + pitchBias;

        // The floor: a grid that drifts toward the viewer. It only follows the pitch, so it does not turn with the boat.
        const double floorY = -7.2;
        double drift = (time * 1.6) % 2.0;
        for (int i = -7; i <= 7; i++)
        {
            FadeLine(dc, new V(i * 2, floorY, -14), new V(i * 2, floorY, 14), pitch);
            double z = i * 2 - drift;
            FadeLine(dc, new V(-14, floorY, z), new V(14, floorY, z), pitch);
        }

        // The boat.
        double yaw = time * 0.42 + yawBias;
        foreach (var (a, b, rib) in boat)
        {
            var pa = Project(a, yaw, pitch);
            var pb = Project(b, yaw, pitch);
            int level = Level((pa.Z + pb.Z) / 2, rib ? 6 : 0);
            dc.DrawLine(pens[level], new Point(pa.X, pa.Y), new Point(pb.X, pb.Y));
        }
    }

    /// <summary>A floor line drawn in pieces, each as bright as it is close to the middle of the grid.</summary>
    void FadeLine(DrawingContext dc, V a, V b, double pitch)
    {
        const int parts = 8;
        for (int i = 0; i < parts; i++)
        {
            var p = new V(a.X + (b.X - a.X) * i / parts, a.Y, a.Z + (b.Z - a.Z) * i / parts);
            var q = new V(a.X + (b.X - a.X) * (i + 1) / parts, a.Y, a.Z + (b.Z - a.Z) * (i + 1) / parts);
            double mx = (p.X + q.X) / 2, mz = (p.Z + q.Z) / 2;
            int level = (int)(Math.Max(0, 1 - Math.Sqrt(mx * mx + mz * mz) / 16) * 6);
            if (level <= 0) continue;
            var pp = Project(p, 0, pitch);
            var qq = Project(q, 0, pitch);
            dc.DrawLine(pens[level], new Point(pp.X, pp.Y), new Point(qq.X, qq.Y));
        }
    }
}
