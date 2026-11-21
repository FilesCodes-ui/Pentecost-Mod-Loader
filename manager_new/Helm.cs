using System.Globalization;

namespace Pentecost.Manager;

public sealed partial class Helm : PixelArt
{
    static readonly int[,] Dith = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };
    const double Lx = -0.48, Ly = -0.62, Lz = 0.62;

    readonly Dictionary<string, FormattedText> words = new();
    readonly Random rng = new();
    readonly List<string> names = new();

    public event Action<int>? Turned;

    int selected;
    double rot = -Math.PI / 2, vel, lastAng, lastMove, flash, nextFlash = 5;
    bool drag;
    double cx, cy, R, peg;

    public Helm() : base(3)
    {
        MouseLeftButtonDown += (_, e) =>
        {
            var p = e.GetPosition(this);
            if (!Over(p)) return;
            drag = true;
            vel = 0;
            lastAng = Angle(p);
            lastMove = Now;
            CaptureMouse();
            e.Handled = true;
        };
        MouseMove += (_, e) =>
        {
            var p = e.GetPosition(this);
            if (!drag) { Cursor = Over(p) ? Cursors.Hand : null; return; }
            var a = Angle(p);
            var d = Wrap(a - lastAng);
            var now = Now;
            rot += d;
            vel = vel * 0.5 + d / Math.Max(0.008, now - lastMove) * 0.5;
            lastAng = a;
            lastMove = now;
            Track();
        };
        MouseLeftButtonUp += (_, _) => { drag = false; ReleaseMouseCapture(); if (Now - lastMove > 0.08) vel = 0; };
        LostMouseCapture += (_, _) => drag = false;
    }

    protected override string BgKey => "Bg";

    public void SetStations(IEnumerable<string> list, int pick)
    {
        names.Clear();
        names.AddRange(list);
        selected = Math.Clamp(pick, 0, Math.Max(0, names.Count - 1));
        rot = RotFor(selected);
    }

    public void Select(int i)
    {
        if (i < 0 || i >= names.Count) return;
        selected = i;
        vel = 0;
    }

    int N => Math.Max(1, names.Count);
    double Step => 2 * Math.PI / N;
    double RotFor(int i) => -Math.PI / 2 - i * Step;
    static double Wrap(double a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }
    double Angle(Point p) => Math.Atan2(p.Y / P - cy, p.X / P - cx);
    bool Over(Point p) { var dx = p.X / P - cx; var dy = p.Y / P - cy; return R > 0 && Math.Sqrt(dx * dx + dy * dy) <= R + peg + 3; }

    void Track()
    {
        var top = (int)Math.Round((-Math.PI / 2 - rot) / Step);
        top = ((top % N) + N) % N;
        if (top == selected) return;
        selected = top;
        var pick = top;
        Dispatcher.BeginInvoke(() => Turned?.Invoke(pick));
    }

    static int Solid(double light, int x, int y)
    {
        var lv = Math.Floor(Math.Clamp(light, 0, 1) * 6 + Dith[y & 3, x & 3] / 16.0) / 6.0;
        var g = (int)(28 + 214 * Math.Clamp(lv, 0, 1));
        return unchecked((int)(0xFF000000u | (uint)(g << 16) | (uint)(g << 8) | (uint)g));
    }

    static double Lit(double nx, double ny, double nz) => 0.12 + 0.88 * Math.Max(0, nx * Lx + ny * Ly + nz * Lz) + 0.1 * Math.Pow(Math.Max(0, nx * Lx + ny * Ly + nz * Lz), 12);

    protected override void Paint(double t, double dt)
    {
        var sea = (int)(rows * 0.70);
        Sky(t, dt, sea);
        if (names.Count == 0) return;

        if (!drag)
        {
            if (Math.Abs(vel) > 0.35) { rot += vel * dt; vel *= Math.Exp(-dt * 2.4); Track(); }
            else
            {
                vel = 0;
                var target = rot + Wrap(RotFor(selected) - rot);
                rot += (target - rot) * Math.Min(1, dt * 5.5);
            }
        }

        cx = cols * 0.765;
        cy = rows * 0.53;
        R = Math.Min(rows * 0.30, cols * 0.15);
        peg = R * 0.44;
        var rim = Math.Max(2.2, R * 0.075);
        var hubR = Math.Max(4.0, R * 0.2);
        var inner = R * 0.6;
        var a0 = rot + 0.012 * Math.Sin(t * 0.8);
        var reach = (int)Math.Ceiling(R + peg + 4);

        for (var y = (int)cy - reach; y <= (int)cy + reach; y++)
        {
            if (y < 0 || y >= rows) continue;
            for (var x = (int)cx - reach; x <= (int)cx + reach; x++)
            {
                if (x < 0 || x >= cols) continue;
                var dx = x + 0.5 - cx;
                var dy = y + 0.5 - cy;
                var d = Math.Sqrt(dx * dx + dy * dy);
                if (d > R + peg + 3.5) continue;
                var rx = d > 0 ? dx / d : 0;
                var ry = d > 0 ? dy / d : 0;
                double light = -1;

                if (d <= hubR)
                {
                    var u = d / hubR;
                    var nz = Math.Sqrt(Math.Max(0, 1 - u * u));
                    light = Lit(dx / hubR, dy / hubR, nz);
                    if (Math.Abs(u - 0.68) < 0.09) light *= 0.45;
                    if (u < 0.2) light = Math.Min(1, light + 0.25);
                }
                else if (Math.Abs(d - R) <= rim)
                {
                    var u = (d - R) / rim;
                    var nz = Math.Sqrt(Math.Max(0, 1 - u * u));
                    light = Lit(u * rx, u * ry, nz);
                    var s = (Math.Atan2(dy, dx) - a0) / Step + 0.5;
                    s -= Math.Floor(s);
                    if (s < 0.025 || s > 0.975) light *= 0.35;
                    light *= 0.92 + 0.08 * Math.Sin(d * 2.1 + Math.Atan2(dy, dx) * 40);
                }
                else if (Math.Abs(d - inner) <= rim * 0.55)
                {
                    var u = (d - inner) / (rim * 0.55);
                    light = Lit(u * rx, u * ry, Math.Sqrt(Math.Max(0, 1 - u * u))) * 0.8;
                }

                if (light < 0)
                    for (var k = 0; k < names.Count; k++)
                    {
                        var a = a0 + k * Step;
                        var ca = Math.Cos(a);
                        var sa = Math.Sin(a);
                        var along = dx * ca + dy * sa;
                        var perp = -dx * sa + dy * ca;
                        double w;
                        if (along > hubR && along < R)
                        {
                            var f = (along - hubR) / (R - hubR);
                            w = R * 0.034 + R * 0.03 * Math.Pow(Math.Sin(Math.PI * f), 2) + (f > 0.78 ? R * 0.02 : 0);
                        }
                        else if (along >= R && along <= R + peg)
                        {
                            var f = (along - R) / peg;
                            w = R * 0.04 + R * 0.035 * Math.Sin(Math.PI * Math.Min(1, f * 1.15));
                            var ex = along - (R + peg - R * 0.05);
                            var knob = R * 0.085;
                            if (ex * ex + perp * perp <= knob * knob)
                            {
                                var nzk = Math.Sqrt(Math.Max(0, 1 - (ex * ex + perp * perp) / (knob * knob)));
                                light = Lit((ex * ca - perp * sa) / knob, (ex * sa + perp * ca) / knob, nzk);
                                if (k == selected) light = Math.Min(1.15, light + 0.35);
                                break;
                            }
                        }
                        else continue;
                        if (Math.Abs(perp) > w) continue;
                        var v = perp / w;
                        var nz = Math.Sqrt(Math.Max(0, 1 - v * v));
                        light = Lit(-v * sa, v * ca, nz) * (along > R ? 1 : 0.86);
                        if (k == selected && along > R) light = Math.Min(1.1, light + 0.25);
                        break;
                    }

                if (light >= 0) buf[y * cols + x] = Solid(light, x, y);
            }
        }

        var top = a0 + selected * Step;
        var kx = cx + Math.Cos(top) * (R + peg - R * 0.05);
        var ky = cy + Math.Sin(top) * (R + peg - R * 0.05);
        var halo = R * 0.2;
        for (var y = (int)(ky - halo); y <= (int)(ky + halo); y++)
            for (var x = (int)(kx - halo); x <= (int)(kx + halo); x++)
            {
                var dd = Math.Sqrt((x - kx) * (x - kx) + (y - ky) * (y - ky)) / halo;
                if (dd > 1 || dd < 0.45) continue;
                if ((1 - dd) * 20 * (0.7 + 0.3 * Math.Sin(t * 3)) > Dith[y & 3, x & 3] + 2) Max(x, y, 120);
            }
    }

    FormattedText Word(string s, double size, Brush brush)
    {
        var key = size.ToString(CultureInfo.InvariantCulture) + (ReferenceEquals(brush, text) ? "t" : "f") + s;
        if (words.TryGetValue(key, out var hit)) return hit;
        if (words.Count > 60) words.Clear();
        var f = new FormattedText(s, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, size, brush, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        words[key] = f;
        return f;
    }

    protected override void Overlay(DrawingContext dc, double t)
    {
        if (names.Count == 0 || R <= 0) return;
        var top = rot + selected * Step;
        var kx = (cx + Math.Cos(top) * (R + peg)) * P;
        var ky = (cy + Math.Sin(top) * (R + peg)) * P;
        var name = Word(names[selected].ToUpperInvariant(), 11, text);
        var sub = Word((selected + 1).ToString("00") + " / " + names.Count.ToString("00"), 10, faint);
        var x = Math.Min(ActualWidth - Math.Max(name.Width, sub.Width) - 4, kx + R * P * 0.16 + 10);
        var y = Math.Max(4, ky - 12);
        dc.DrawRectangle(text, null, new Rect(x - 8, y + 1, 1, name.Height + sub.Height));
        dc.DrawText(name, new Point(x, y));
        dc.DrawText(sub, new Point(x, y + name.Height + 1));
    }
}
