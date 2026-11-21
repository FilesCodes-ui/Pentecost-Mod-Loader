namespace Pentecost.Manager;

public sealed partial class Helm
{
    int[] skyCache = Array.Empty<int>();
    int skyCols, skyRows;
    double shotAt = -99, shotX, shotY, boltAt = -99, boltX, boltY;

    void BuildSky(int sea)
    {
        Array.Clear(buf);
        for (var y = 0; y < sea; y++)
            for (var x = 0; x < cols; x++)
            {
                var wx = x + 11 * Math.Sin(y * 0.041 + 0.6);
                var wy = y + 8 * Math.Sin(x * 0.052 + 1.7);
                var a = Math.Sin(wx * 0.052 + wy * 0.021 + 0.4) * Math.Sin(wy * 0.037 - wx * 0.017 + 1.9);
                var b = Math.Sin(wx * 0.17 + wy * 0.12 + 2.3) * Math.Sin(wy * 0.14 - wx * 0.08);
                var cloud = Math.Max(0, a - 0.18) * (0.8 + 0.4 * b);
                var fil = Math.Abs(Math.Sin(wx * 0.09 + wy * 0.07 + a * 3)) < 0.07 && a > 0.12 ? 0.55 : 0;
                var fade = 1 - Math.Max(0, (y / (double)sea - 0.55) * 2);
                var v = (cloud * 1.25 + fil) * Math.Max(0, fade);
                if (v * 16 > Dith[y & 3, x & 3] + 1) Put(x, y, W((int)(9 + v * 17)));
            }

        for (var i = 0; i < cols * sea / 20; i++)
        {
            var u = Frac(i, 13.1);
            var bx = u * cols;
            var by = sea * (0.08 + 0.6 * (1 - u)) + (Frac(i, 17.3) - 0.5) * (Frac(i, 19.7) * 20 + 4);
            Max((int)bx, (int)by, (int)(8 + 22 * Frac(i, 23.9)));
        }
        skyCache = (int[])buf.Clone();
        skyCols = cols;
        skyRows = rows;
    }

    void Sky(double t, double dt, int sea)
    {
        if (skyCols != cols || skyRows != rows || skyCache.Length != buf.Length) BuildSky(sea);
        Array.Copy(skyCache, buf, buf.Length);

        var count = cols * sea / 55;
        for (var i = 0; i < count; i++)
        {
            var sx = (int)(Frac(i, 2.3) * cols);
            var sy = (int)(Frac(i, 4.7) * sea);
            var tw = 0.5 + 0.5 * Math.Sin(t * (0.4 + Frac(i, 6.1) * 1.8) + i * 1.3);
            var mag = Frac(i, 8.3);
            var a = (int)((18 + 90 * mag * mag) * (0.4 + 0.6 * tw));
            Max(sx, sy, a);
            if (mag > 0.94)
            {
                var spike = 1 + (int)(tw * 2.5);
                for (var k = 1; k <= spike; k++)
                {
                    var s2 = a * (spike - k + 1) / (spike + 2);
                    Max(sx - k, sy, s2); Max(sx + k, sy, s2); Max(sx, sy - k, s2); Max(sx, sy + k, s2);
                }
                Max(sx, sy, 160);
            }
        }

        var band = rows * 0.2;
        if (Settings.Storm && t >= nextFlash) { flash = 1; nextFlash = t + 7 + rng.NextDouble() * 11; }
        flash *= Math.Exp(-dt * 6);
        var flashX = cols * (0.55 + 0.4 * Frac(Math.Floor(nextFlash), 2.1));
        for (var y = 0; y < band; y++)
            for (var x = 0; x < cols; x++)
            {
                var n = 0.5 + 0.25 * Math.Sin(x * 0.045 + t * 0.05 + Math.Sin(y * 0.2 + x * 0.013) * 1.4) + 0.25 * Math.Sin(x * 0.11 - t * 0.08 + y * 0.31);
                var f = n * (1 - y / band);
                if (f * 15 <= Dith[y & 3, x & 3] + 4) continue;
                var near = flash > 0.03 ? flash * Math.Max(0, 1 - Math.Abs(x - flashX) / (cols * 0.3)) : 0;
                Max(x, y, (int)(16 + 26 * f + 150 * near));
            }

        var cp = (t % 64) / 64;
        if (cp < 0.55)
        {
            var f = cp / 0.55;
            var hx = cols * (1.05 - f * 0.7);
            var hy = sea * (0.10 + f * 0.16);
            var fade = Math.Sin(f * Math.PI);
            for (var k = 0; k < 34; k++)
                for (var s2 = -1; s2 <= 1; s2++)
                {
                    var x = (int)Math.Round(hx + k * 1.1);
                    var y = (int)Math.Round(hy - k * 0.24 + s2 * k * 0.09);
                    if (s2 != 0 && Dith[y & 3, x & 3] > 8 - k / 5) continue;
                    Max(x, y, (int)(fade * (s2 == 0 ? 120 : 55) * (1 - k / 34.0)));
                }
            Max((int)hx, (int)hy, (int)(220 * fade));
            Max((int)hx - 1, (int)hy, (int)(90 * fade));
            Max((int)hx, (int)hy - 1, (int)(70 * fade));
        }

        var sat = (t % 31) / 31;
        if (sat < 0.75)
        {
            var x = (int)(cols * (sat / 0.75));
            var y = (int)(sea * 0.62 - sat * sea * 0.12);
            Max(x, y, Math.Sin(t * 9) > 0.6 ? 160 : 45);
        }

        if (t - shotAt > 8 + Frac(Math.Floor(t / 8), 3.3) * 12) { shotAt = t; shotX = cols * (0.5 + Frac(t, 1.1) * 0.45); shotY = sea * Frac(t, 2.2) * 0.45; }
        var age = t - shotAt;
        if (age < 0.9)
            for (var k = 0; k < 14; k++)
            {
                var p = age * 64 - k * 1.1;
                if (p < 0) continue;
                Max((int)(shotX - p), (int)(shotY + p * 0.42), (int)(160 * (1 - age / 0.9) * (1 - k / 14.0)));
            }

        Giant(cols * 0.37, rows * 0.41, Math.Max(10, Math.Min(rows * 0.27, cols * 0.12)), t, dt);
    }

    static int Gray(double v) { var g = Math.Clamp((int)v, 0, 255); return unchecked((int)(0xFF000000u | (uint)(g << 16) | (uint)(g << 8) | (uint)g)); }

    void Giant(double gx, double gy, double r, double t, double dt)
    {
        const double tilt = 0.30;
        double ct = Math.Cos(tilt), st = Math.Sin(tilt);
        const double gLx = -0.58, gLy = -0.40, gLz = 0.71;

        var m1 = t * Math.PI * 2 / 58;
        var m2 = t * Math.PI * 2 / 37 + 2.0;
        (double X, double Y, bool Front, double R) Moon(double a, double orbit, double flat, double size)
        {
            double ex = Math.Cos(a) * r * orbit, ey = Math.Sin(a) * r * orbit * flat;
            return (gx + ex * ct - ey * st, gy + ex * st + ey * ct, Math.Sin(a) > 0, size);
        }
        var moons = new[] { Moon(m1, 1.75, 0.30, Math.Max(1.6, r * 0.075)), Moon(m2, 2.45, 0.22, Math.Max(1.2, r * 0.05)) };

        for (var dy = -(int)(r + 5); dy <= r + 5; dy++)
            for (var dx = -(int)(r + 5); dx <= r + 5; dx++)
            {
                var d = Math.Sqrt(dx * dx + dy * dy);
                if (d <= r || d > r + 4.5) continue;
                var ux = dx / d;
                var uy = dy / d;
                var lit = Math.Max(0, -(ux * gLx + uy * gLy) / Math.Sqrt(gLx * gLx + gLy * gLy));
                var g = (1 - (d - r) / 4.5) * (0.04 + 0.96 * lit * lit);
                int x = (int)gx + dx, y = (int)gy + dy;
                if (g * 16 > Dith[y & 3, x & 3] + 3) Max(x, y, (int)(20 + 70 * g * g));
            }

        void DrawMoon((double X, double Y, bool Front, double R) m, bool front)
        {
            if (m.Front != front) return;
            var ir = (int)Math.Ceiling(m.R);
            for (var dy = -ir; dy <= ir; dy++)
                for (var dx = -ir; dx <= ir; dx++)
                {
                    if (dx * dx + dy * dy > m.R * m.R) continue;
                    int x = (int)Math.Round(m.X) + dx, y = (int)Math.Round(m.Y) + dy;
                    if (!front && (x - gx) * (x - gx) + (y - gy) * (y - gy) <= r * r) continue;
                    double nx = dx / m.R, ny = dy / m.R, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - ny * ny));
                    var lam = Math.Max(0, nx * gLx + ny * gLy + nz * gLz);
                    var q = Math.Floor(lam * 4 + Dith[y & 3, x & 3] / 16.0) / 4;
                    Put(x, y, Gray(14 + 190 * q));
                }
        }
        foreach (var m in moons) DrawMoon(m, false);

        var ir2 = (int)Math.Ceiling(r);
        var since = t - boltAt;
        if (since > 2.5 + Frac(Math.Floor(t), 4.4) * 5)
        {
            boltAt = t;
            var ang = 0.2 + Frac(t, 5.5) * 1.2;
            var rad = r * (0.35 + 0.45 * Frac(t, 6.6));
            boltX = Math.Cos(ang) * rad;
            boltY = Math.Sin(ang) * rad * 0.9;
            since = 0;
        }
        var boltGlow = since < 0.5 ? (since < 0.08 || (since > 0.16 && since < 0.24) ? 1.0 : 0.35) * (1 - since / 0.5) : 0;

        for (var dy = -ir2; dy <= ir2; dy++)
            for (var dx = -ir2; dx <= ir2; dx++)
            {
                var dd = dx * dx + dy * dy;
                if (dd > r * r) continue;
                int x = (int)gx + dx, y = (int)gy + dy;
                double nx = dx / r, ny = dy / r, nz = Math.Sqrt(Math.Max(0, 1 - nx * nx - ny * ny));
                var px = nx * ct + ny * st;
                var py = -nx * st + ny * ct;
                var lat = Math.Asin(Math.Clamp(py, -1, 1));
                var lon = Math.Atan2(px, nz) + t * (0.018 + 0.012 * Math.Cos(lat * 3));

                var warp = 0.12 * Math.Sin(lon * 3 + lat * 9) + 0.06 * Math.Sin(lon * 7 - lat * 21 + t * 0.03);
                var b = 0.48 + 0.28 * Math.Sin(lat * 11 + warp * 4) + 0.16 * Math.Sin(lat * 23 + 1.3 + warp * 7) + 0.07 * Math.Sin(lat * 47 + warp * 12);
                b += 0.16 * Math.Exp(-lat * lat * 28);
                b += 0.07 * Math.Sin(lon * 14 + Math.Sin(lat * 30) * 2) * Math.Sin(lat * 40);
                b -= 0.12 * Math.Exp(-(lat - 0.62) * (lat - 0.62) * 60);
                b += 0.05 * Math.Sin(lon * 38 + lat * 140 + Math.Sin(lat * 60) * 3) * (0.5 + 0.5 * Math.Sin(lat * 19));
                b += 0.04 * Math.Sin(lon * 63 - lat * 210 + t * 0.05);
                var edge = Math.Abs(Math.Cos(lat * 11 + warp * 4));
                if (edge < 0.18) b += 0.09 * Math.Sin(lon * 22 + lat * 9 + t * 0.06) * (1 - edge / 0.18);
                b -= 0.22 * Math.Pow(Math.Abs(py), 6);

                var slon = Math.Atan2(px, nz) - (-0.28 + 0.30 * Math.Sin(t * 0.012));
                slon -= Math.Round(slon / (Math.PI * 2)) * Math.PI * 2;
                var ox = slon / 0.40;
                var oy = (lat - 0.30) / 0.12;
                var od = Math.Sqrt(ox * ox + oy * oy);
                if (od < 1.7)
                {
                    var spin = Math.Atan2(oy, ox) + (1.7 - od) * 2.6 + t * 0.25;
                    var swirl = 0.5 + 0.5 * Math.Sin(od * 10 - spin * 2);
                    var core = od < 1.0 ? 0.78 + 0.18 * swirl : od < 1.2 ? 0.30 : b;
                    var mix = Math.Clamp(1.7 - od, 0, 1);
                    b = b * (1 - mix) + core * mix;
                }
                for (var k = 0; k < 5; k++)
                {
                    var wl = lon - k * 1.25 - t * 0.009;
                    wl -= Math.Round(wl / (Math.PI * 2)) * Math.PI * 2;
                    var wd = Math.Sqrt(wl * wl / 0.012 + (lat - 0.42) * (lat - 0.42) / 0.0025);
                    if (wd < 1) b = Math.Max(b, 0.92 - 0.3 * wd);
                }

                var lam = nx * gLx + ny * gLy + nz * gLz;
                var limb = 0.35 + 0.65 * Math.Pow(nz, 0.55);
                var val = Math.Clamp(b, 0, 1.1) * Math.Max(0, lam) * limb;
                var q = Math.Floor(val * 7 + Dith[y & 3, x & 3] / 16.0) / 7;
                var v = 9 + 215 * q;
                if (lam < 0.06)
                {
                    v = Math.Max(v, 9 + 18 * b * (lam > -0.15 ? 1 : 0.4));
                    if (boltGlow > 0)
                    {
                        var bd = Math.Sqrt((dx - boltX) * (dx - boltX) + (dy - boltY) * (dy - boltY));
                        var g = boltGlow * Math.Max(0, 1 - bd / (r * 0.16));
                        if (g * 16 > Dith[y & 3, x & 3]) v = Math.Max(v, 40 + 160 * g);
                    }
                }
                if (dd > (r - 1.2) * (r - 1.2) && lam > 0.25) v = Math.Max(v, 150 + 70 * lam);

                foreach (var m in moons)
                {
                    if (!m.Front) continue;
                    var sx = m.X - gx + gLx * -r * 0.16;
                    var sy = m.Y - gy + gLy * -r * 0.16;
                    if ((dx - sx) * (dx - sx) + (dy - sy) * (dy - sy) <= m.R * m.R * 1.1) v = Math.Min(v, 12);
                }
                Put(x, y, Gray(v));
            }

        foreach (var m in moons) DrawMoon(m, true);
    }
}
