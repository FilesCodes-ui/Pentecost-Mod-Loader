namespace Pentecost.Manager;

public sealed class Signal : PixelArt
{
    static readonly int[,] Dith = { { 0, 8, 2, 10 }, { 12, 4, 14, 6 }, { 3, 11, 1, 9 }, { 15, 7, 13, 5 } };
    static DateTime lastSent = DateTime.MinValue;
    static bool hooked;

    readonly List<double> rings = new();
    double nextIdle = 1.2, lastSeenSend;
    int[] skyCache = Array.Empty<int>();
    int skyCols, skyRows;

    public bool Warning { get; set; }

    public Signal() : base(3)
    {
        IsHitTestVisible = false;
        if (!hooked)
        {
            hooked = true;
            Feed.Changed += () => lastSent = DateTime.UtcNow;
        }
    }

    protected override string BgKey => "Bg";

    static int Gray(double v) { var g = Math.Clamp((int)v, 0, 255); return unchecked((int)(0xFF000000u | (uint)(g << 16) | (uint)(g << 8) | (uint)g)); }

    void Solid(int x, int y, double v) { if (x >= 0 && y >= 0 && x < cols && y < rows) buf[y * cols + x] = Gray(v); }

    void Line(double x0, double y0, double x1, double y1, double v)
    {
        var n = (int)Math.Ceiling(Math.Max(Math.Abs(x1 - x0), Math.Abs(y1 - y0)) * 1.5) + 1;
        for (var i = 0; i <= n; i++)
        {
            var f = i / (double)n;
            Solid((int)Math.Round(x0 + (x1 - x0) * f), (int)Math.Round(y0 + (y1 - y0) * f), v);
        }
    }

    void BuildSky(int sea)
    {
        Array.Clear(buf);
        for (var y = 0; y < sea; y++)
            for (var x = 0; x < cols; x++)
            {
                var wx = x + 10 * Math.Sin(y * 0.043 + 2.1);
                var wy = y + 7 * Math.Sin(x * 0.05 + 0.4);
                var a = Math.Sin(wx * 0.047 + wy * 0.024 + 1.3) * Math.Sin(wy * 0.035 - wx * 0.019 + 0.6);
                var v = Math.Max(0, a - 0.2) * 1.2 * Math.Max(0, 1 - Math.Max(0, (y / (double)sea - 0.5) * 2));
                if (v * 16 > Dith[y & 3, x & 3] + 1) Put(x, y, W((int)(9 + v * 16)));
            }
        skyCache = (int[])buf.Clone();
        skyCols = cols;
        skyRows = rows;
    }

    protected override void Paint(double t, double dt)
    {
        var sea = (int)(rows * 0.70);
        if (skyCols != cols || skyRows != rows || skyCache.Length != buf.Length) BuildSky(sea);
        Array.Copy(skyCache, buf, buf.Length);

        for (var i = 0; i < cols * sea / 55; i++)
        {
            var sx = (int)(Frac(i, 2.3) * cols);
            var sy = (int)(Frac(i, 4.7) * sea);
            var tw = 0.5 + 0.5 * Math.Sin(t * (0.4 + Frac(i, 6.1) * 1.8) + i * 1.3);
            var mag = Frac(i, 8.3);
            Max(sx, sy, (int)((18 + 90 * mag * mag) * (0.4 + 0.6 * tw)));
            if (mag > 0.95)
                for (var k = 1; k <= 2; k++) { var s = 60 / k; Max(sx - k, sy, s); Max(sx + k, sy, s); Max(sx, sy - k, s); Max(sx, sy + k, s); }
        }

        var mx = cols * 0.78;
        var baseY = sea + 2.0;
        var topY = Math.Max(8, rows * 0.12);
        var h = baseY - topY;
        var beaconY = topY - 4;

        var sent = (DateTime.UtcNow - lastSent).TotalSeconds;
        if (sent < 1.5 && Math.Abs(sent - lastSeenSend) > 0.5) { rings.Add(t); rings.Add(t + 0.25); rings.Add(t + 0.5); lastSeenSend = sent; }
        if (t > nextIdle) { rings.Add(t); nextIdle = t + 3.6; }
        rings.RemoveAll(r => t - r > 4.5);

        var maxR = cols * 0.62;
        var shipXs = new[] { 0.06, 0.17, 0.31, 0.45, 0.58, 0.93 };
        var lit = new double[shipXs.Length];
        foreach (var r0 in rings)
        {
            var age = t - r0;
            if (age < 0) continue;
            var rad = age / 4.5 * maxR;
            var fade = 1 - age / 4.5;
            var steps = (int)(rad * 7) + 16;
            for (var k = 0; k < steps; k++)
            {
                var a = Math.PI + k * Math.PI / steps * 1.0;
                var x = mx + Math.Cos(a) * rad;
                var y = beaconY + Math.Sin(a) * rad * 0.42;
                if (y >= sea - 1) continue;
                if (fade * 16 > Dith[((int)y) & 3, ((int)x) & 3] * 0.8) Max((int)x, (int)y, (int)(30 + 140 * fade));
            }
            for (var k = 0; k < steps; k++)
            {
                var a = k * Math.PI / steps;
                var x = mx + Math.Cos(a) * rad;
                var y = beaconY + Math.Sin(a) * rad * 0.42;
                if (y >= sea - 1) continue;
                if (fade * 12 > Dith[((int)y) & 3, ((int)x) & 3]) Max((int)x, (int)y, (int)(20 + 90 * fade));
            }
            for (var i = 0; i < shipXs.Length; i++)
            {
                var d = Math.Abs(shipXs[i] * cols - mx);
                var hit = 1 - Math.Abs(rad - d) / 10;
                if (hit > 0) lit[i] = Math.Max(lit[i], hit * fade + 0.15);
            }
        }

        for (var i = 0; i < shipXs.Length; i++)
        {
            var sx = (int)(shipXs[i] * cols);
            var sy = sea - 5 + (int)Math.Round(Math.Sin(t * 1.1 + i * 1.7) * 0.7);
            var v = 80 + 175 * Math.Min(1, lit[i]);
            var len = 12 + (i * 5) % 7;
            var mastH = 8 + (i * 3) % 5;
            for (var x = -len / 2; x <= len / 2; x++) Solid(sx + x, sy, v);
            for (var x = -len / 2 + 1; x <= len / 2 - 1; x++) Solid(sx + x, sy + 1, v * 0.75);
            for (var x = -len / 2 + 3; x <= len / 2 - 2; x++) Solid(sx + x, sy + 2, v * 0.5);
            for (var y = 1; y <= mastH; y++) Solid(sx, sy - y, v);
            for (var y = 2; y <= mastH - 1; y++)
            {
                var wdt = (int)((y - 1) * 0.55);
                for (var x = 1; x <= wdt; x++)
                    if ((x + y) % 2 == 0 || x == wdt) Solid(sx + x, sy - mastH + y - 1, v * (x == wdt ? 0.95 : 0.6));
            }
            for (var y = 3; y <= mastH - 2; y++)
            {
                var wdt = (int)((y - 2) * 0.4);
                for (var x = 1; x <= wdt; x++) if ((x + y) % 2 == 1 || x == wdt) Solid(sx - x, sy - mastH + y, v * 0.5);
            }
            if (lit[i] > 0.6) for (var y = -1; y <= 1; y++) Max(sx, sy - mastH - 2 + y, 255);
        }

        var rockW = cols * 0.15;
        for (var y = (int)baseY - 6; y < rows; y++)
        {
            var f = (y - (baseY - 6)) / 14.0;
            var half = rockW * (0.35 + 0.65 * Math.Min(1, f)) + 2 * Math.Sin(y * 0.9);
            for (var x = (int)(mx - half); x <= (int)(mx + half); x++)
            {
                var edge = Math.Abs(x - mx) / half;
                var light = 0.25 + 0.55 * (1 - (x - (mx - half)) / (2 * half)) - f * 0.15;
                var q = Math.Floor(Math.Clamp(light, 0, 1) * 5 + Dith[y & 3, x & 3] / 16.0) / 5;
                Solid(x, y, 20 + 140 * q + (edge > 0.93 ? 40 : 0));
            }
        }

        var legBase = 14.0;
        var legTop = 2.2;
        double Half(double y) => legTop + (legBase - legTop) * Math.Pow((y - topY) / h, 1.35);
        Line(mx - legTop, topY, mx - legBase, baseY - 6, 225);
        Line(mx + legTop, topY, mx + legBase, baseY - 6, 150);
        Line(mx - legTop + 0.6, topY, mx - legBase + 0.8, baseY - 6, 190);
        foreach (var side in new[] { -1, 1 })
        {
            Line(mx, topY + h * 0.22, mx + side * rockW * 0.95, baseY - 5, 70);
            Line(mx, topY + h * 0.5, mx + side * rockW * 0.75, baseY - 5, 60);
        }
        Line(mx - legTop - 1, topY, mx - legBase - 1, baseY - 6, 160);
        Line(mx + legTop + 1, topY, mx + legBase + 1, baseY - 6, 110);
        var bays = 8;
        for (var b = 0; b < bays; b++)
        {
            var y0 = topY + h * Math.Pow(b / (double)bays, 1.15);
            var y1 = topY + h * Math.Pow((b + 1) / (double)bays, 1.15);
            if (y1 > baseY - 6) y1 = baseY - 6;
            var h0 = Half(y0);
            var h1 = Half(y1);
            Line(mx - h0, y0, mx + h0, y0, 200);
            Line(mx - h0, y0, mx + h1, y1, 120);
            Line(mx + h0, y0, mx - h1, y1, 95);
        }
        var platY = topY + h * 0.38;
        var ph = Half(platY) + 3;
        Line(mx - ph, platY, mx + ph, platY, 235);
        Line(mx - ph, platY + 1, mx + ph, platY + 1, 120);
        for (var x = (int)(mx - ph); x <= (int)(mx + ph); x += 2) Solid(x, (int)platY - 1, 160);

        var dx0 = mx - ph - 4;
        var dy0 = platY - 4;
        for (var y = -5; y <= 5; y++)
            for (var x = -3; x <= 2; x++)
            {
                var e = (x * x) / 9.0 + (y * y) / 25.0;
                if (e > 1) continue;
                var light = 0.35 + 0.6 * (-(x + 3) / 6.0 + 1) * (1 - y / 10.0);
                var q = Math.Floor(Math.Clamp(light, 0, 1) * 5 + Dith[(int)(dy0 + y) & 3, (int)(dx0 + x) & 3] / 16.0) / 5;
                Solid((int)(dx0 + x), (int)(dy0 + y), 40 + 200 * q);
            }
        Line(dx0 + 2, dy0, mx - ph, platY, 140);

        Line(mx, topY, mx, beaconY - 6, 210);
        Line(mx - 2, beaconY - 2, mx + 2, beaconY - 2, 170);

        var blink = Warning
            ? (Math.Sin(t * 2.2) > 0 ? 1.0 : 0.15)
            : 0.55 + 0.45 * Math.Pow(Math.Max(0, Math.Sin(t * 1.4)), 6) + (sent < 1.2 ? 0.5 : 0);
        var glowR = 6 + 5 * Math.Min(1, blink);
        for (var y = (int)(beaconY - glowR); y <= beaconY + glowR; y++)
            for (var x = (int)(mx - glowR); x <= mx + glowR; x++)
            {
                var dd = Math.Sqrt((x - mx) * (x - mx) + (y - beaconY) * (y - beaconY)) / glowR;
                if (dd > 1) continue;
                if ((1 - dd) * 16 * Math.Min(1, blink) > Dith[y & 3, x & 3]) Max(x, y, (int)(40 + 120 * (1 - dd) * Math.Min(1, blink)));
            }
        for (var y = -1; y <= 1; y++)
            for (var x = -1; x <= 1; x++)
                Solid((int)mx + x, (int)beaconY + y, Math.Abs(x) + Math.Abs(y) == 2 ? 120 : 140 + 115 * Math.Min(1, blink));
    }
}
