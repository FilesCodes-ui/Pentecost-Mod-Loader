using System.Globalization;

namespace Pentecost.Manager;

public sealed class Wrecks : PixelArt
{
    sealed class Ghost
    {
        public string Name = "";
        public int Count;
        public double X, Seed, List;
        public int X0, X1, Y0, Y1;
    }

    sealed class Sprite
    {
        public int W, H;
        public int[] Px = Array.Empty<int>();
        public int Ox, Oy;
        public void Put(int x, int y, int v) { if (x >= 0 && y >= 0 && x < W && y < H) Px[y * W + x] = v; }
        public int Get(int x, int y) => x >= 0 && y >= 0 && x < W && y < H ? Px[y * W + x] : 0;
    }

    public event Action<string?>? Picked;
    public double SeaLine { get; set; } = 0.72;
    public string Hero { get; private set; } = "";

    protected override string BgKey => "Bg";

    readonly List<Ghost> ghosts = new();
    readonly List<(double X, double Y, double Vx, double Vy, double Life, bool Ember)> parts = new();
    readonly Random rng = new();
    Ghost? hover;
    string? picked;
    bool heroHover;
    double flashAt = -99, nextFlash = 3, boltX;
    int boltSeed;
    Sprite? ship, small;
    int builtCols, builtRows;
    double heroX, heroW, heroY0, heroY1;
    double breakX, breakY;
    double moonX, moonY, moonR;
    public int HeroCount { get; set; }

    public Wrecks() : base(3)
    {
        MouseMove += (_, e) =>
        {
            var p = e.GetPosition(this);
            double cx = p.X / P, cy = p.Y / P;
            hover = ghosts.FirstOrDefault(g => cx >= g.X0 - 4 && cx <= g.X1 + 4 && cy >= g.Y0 - 10 && cy <= g.Y1 + 2);
            heroHover = hover == null && Hero.Length > 0 && Math.Abs(cx - heroX) < heroW && cy > heroY0 && cy < heroY1;
            Cursor = hover != null || heroHover ? Cursors.Hand : Cursors.Arrow;
        };
        MouseLeave += (_, _) => { hover = null; heroHover = false; Cursor = Cursors.Arrow; };
        MouseLeftButtonUp += (_, _) =>
        {
            var name = hover?.Name ?? (heroHover ? Hero : null);
            if (name == null) return;
            picked = picked == name ? null : name;
            Picked?.Invoke(picked);
        };
    }

    public void Clear() => picked = null;

    public void SetWrecks(IEnumerable<(string Mod, DateTime Time)> list)
    {
        var items = list.ToList();
        ghosts.Clear();
        Hero = items.Count > 0 ? items.OrderByDescending(i => i.Time).First().Mod : "";
        var groups = items.Where(i => i.Mod != Hero).GroupBy(i => i.Mod).Select(g => (g.Key, g.Count())).OrderByDescending(g => g.Item2).Take(5).ToList();
        double[] slots = { 0.2, 0.92, 0.33, 0.83, 0.1 };
        for (var i = 0; i < groups.Count; i++)
            ghosts.Add(new Ghost { Name = groups[i].Key, Count = groups[i].Item2, X = slots[i], Seed = i * 1.7 + 0.4, List = (i % 2 == 0 ? 1 : -1) * (0.08 + i * 0.03) });
    }

    public int CountFor(string mod) => mod == Hero ? -1 : ghosts.FirstOrDefault(g => g.Name == mod)?.Count ?? 0;

    static void Line(Sprite s, int x0, int y0, int x1, int y1, int v)
    {
        int dx = Math.Abs(x1 - x0), dy = -Math.Abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        while (true)
        {
            s.Put(x0, y0, v);
            if (x0 == x1 && y0 == y1) break;
            var e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    Sprite Galleon(double s, int rim, double seed, bool broken)
    {
        var half = (int)(36 * s);
        var hullH = Math.Max(3, (int)(9 * s));
        var castleH = Math.Max(2, (int)(7 * s));
        var mastMax = (int)(44 * s);
        var sp = new Sprite { W = half * 2 + (int)(20 * s) + 8, H = mastMax + hullH + castleH + 12 };
        sp.Px = new int[sp.W * sp.H];
        var cx = (int)(14 * s) + half + 4;
        var wy = sp.H - 5;
        sp.Ox = cx; sp.Oy = wy;
        var fill = bgPix;
        int rimC = W(rim), dimC = W((int)(rim * 0.55));
        for (var dx = -half; dx <= half; dx++)
        {
            var x = cx + dx;
            var castle = dx > half * 0.45 ? (int)((dx - half * 0.45) / (half * 0.55) * castleH) : 0;
            var bowRise = dx < -half * 0.7 ? (int)((-half * 0.7 - dx) / (half * 0.3) * 2 * s) : 0;
            var top = wy - hullH - castle - bowRise;
            var curve = Math.Abs(dx) > half * 0.75 ? (int)((Math.Abs(dx) - half * 0.75) / (half * 0.25) * hullH * 0.8) : 0;
            var bottom = wy + 3 - curve;
            for (var y = top; y <= bottom; y++)
            {
                sp.Put(x, y, fill);
                var row = y - (wy - hullH);
                if (row > 0 && row % 3 == 0 && ((x + row) & 1) == 0) sp.Put(x, y, W((int)(rim * 0.22)));
                else if (row > 0 && Bayer[y & 3, x & 3] < 2) sp.Put(x, y, W((int)(rim * 0.12)));
            }
            sp.Put(x, top, rimC);
            sp.Put(x, bottom, dimC);
            if (Math.Abs(dx) < half * 0.8) sp.Put(x, wy - hullH + 2, W((int)(rim * 0.55)));
            if (Math.Abs(dx) < half * 0.62 && dx % 5 == 0)
            {
                var gy = wy - hullH + 4;
                sp.Put(x, gy, W((int)(rim * 0.7)));
                sp.Put(x + 1, gy, W((int)(rim * 0.7)));
            }
            if (broken && Frac(seed + dx * 0.61, 3.3) > 0.82)
                for (var y = wy - hullH + 3; y < bottom - 1; y++) if (Frac(seed + dx, y * 0.7) > 0.45) sp.Put(x, y, W(6));
        }
        var spritX = cx - half - (int)(12 * s);
        var spritY = wy - hullH - (int)(7 * s);
        Line(sp, cx - half, wy - hullH - (int)(2 * s), broken ? cx - half - (int)(6 * s) : spritX, broken ? spritY + (int)(3 * s) : spritY, dimC);
        var masts = broken ? new[] { (-half * 0.4, 30.0, 1.0), (0.0, 42.0, 0.48), (half * 0.38, 28.0, 0.82) } : new[] { (-half * 0.4, 34.0, 1.0), (0.0, 42.0, 1.0), (half * 0.38, 30.0, 1.0) };
        foreach (var (mx, mh, keep) in masts)
        {
            var x = cx + (int)mx;
            var full = (int)(mh * s);
            var top = wy - hullH - full;
            var cut = wy - hullH - (int)(full * keep);
            for (var y = cut; y < wy - hullH; y++) sp.Put(x, y, dimC);
            if (keep < 1)
            {
                for (var k = 0; k < 3; k++) sp.Put(x + (k % 2 == 0 ? -1 : 1), cut + k, dimC);
                var len = full - (int)(full * keep);
                Line(sp, x - 2, cut + 2, x - 2 - (int)(len * 0.85), wy - hullH - 1, dimC);
                Line(sp, x - 2 - (int)(len * 0.85), wy - hullH - 1, x - 2 - (int)(len * 0.85) - 4, wy - hullH + 2, W((int)(rim * 0.35)));
            }
            for (var k = 0; k < 3; k++)
            {
                var yTop = top + 2 + k * (int)(mh * s * 0.28);
                if (yTop < cut) continue;
                var h = Math.Max(2, (int)(mh * s * 0.25));
                var w = Math.Max(2, (int)((6 + k * 2.2) * s));
                var tilt = broken ? (int)(w * 0.25) : 0;
                for (var dx = -w - 1; dx <= w + 1; dx++) sp.Put(x + dx, yTop + (dx * tilt) / Math.Max(1, w), dimC);
                for (var y = yTop + 1; y <= yTop + h; y++)
                {
                    var v = (y - yTop) / (double)h;
                    for (var dx = -w + 1; dx < w; dx++)
                    {
                        var torn = Frac(seed + dx * 1.3, k + mx) * (broken ? 0.95 : 0.6);
                        if (v > 1 - torn || Frac(seed + dx * 0.7, (y - yTop) * 1.1 + k) > (broken ? 0.8 : 0.9)) continue;
                        sp.Put(x + dx, y + (dx * tilt) / Math.Max(1, w), fill);
                    }
                    if (v < 0.6) { sp.Put(x - w, y, dimC); sp.Put(x + w, y, dimC); }
                }
            }
            Line(sp, x, Math.Max(top, cut), spritX, spritY, W((int)(rim * 0.22)));
        }
        return sp;
    }

    void Blit(Sprite sp, double ox, double oy, double ang, double alpha, Func<int, int, bool>? keep = null, double pivotX = double.NaN, double pivotY = double.NaN)
    {
        double ca = Math.Cos(ang), sa = Math.Sin(ang);
        var px = double.IsNaN(pivotX) ? sp.Ox : pivotX;
        var py = double.IsNaN(pivotY) ? sp.Oy : pivotY;
        var r = Math.Sqrt(sp.W * sp.W + sp.H * sp.H);
        int x0 = (int)(ox - r), x1 = (int)(ox + r), y0 = (int)(oy - r), y1 = (int)(oy + r);
        for (var y = Math.Max(0, y0); y < Math.Min(rows, y1); y++)
            for (var x = Math.Max(0, x0); x < Math.Min(cols, x1); x++)
            {
                double dx = x - ox, dy = y - oy;
                var sx = (int)Math.Round(dx * ca + dy * sa + px);
                var sy = (int)Math.Round(-dx * sa + dy * ca + py);
                if (keep != null && !keep(sx, sy)) continue;
                var v = sp.Get(sx, sy);
                if (v == 0) continue;
                if (alpha < 1)
                {
                    if (v == bgPix) { if (Bayer[y & 3, x & 3] < alpha * 16) Put(x, y, bgPix); continue; }
                    var a = (v >> 24) & 0xFF;
                    Max(x, y, (int)(a * alpha));
                    continue;
                }
                Put(x, y, v);
            }
    }

    void Build()
    {
        builtCols = cols; builtRows = rows;
        var sea = rows * SeaLine;
        var s = Math.Min(cols * 0.22 / 36, sea * 0.84 / 60);
        ship = Galleon(s, 235, 4.2, true);
        small = Galleon(Math.Max(0.28, s * 0.3), 150, 1.9, true);
        heroW = 36 * s;
    }

    protected override void Paint(double t, double dt)
    {
        if (builtCols != cols || builtRows != rows || ship == null) Build();
        var sea = (int)(rows * SeaLine);
        if (t > nextFlash)
        {
            flashAt = t;
            nextFlash = t + 5 + rng.NextDouble() * 8;
            boltSeed = rng.Next();
            boltX = rng.NextDouble() < 0.5 && Hero.Length > 0 ? heroX - heroW * 0.1 : rng.NextDouble() * cols;
        }
        var fa = t - flashAt;
        var flash = fa < 0 ? 0 : fa < 0.07 ? 1 : fa < 0.14 ? 0.2 : fa < 0.24 ? 0.8 : fa < 1.0 ? 0.8 * (1 - (fa - 0.24) / 0.76) : 0;

        for (var y = 0; y < rows; y++)
        {
            var g = y / (double)sea;
            var v = 0.08 + 0.1 * g + flash * 0.25;
            for (var x = 0; x < cols; x++)
                if (v * 16 > Bayer[y & 3, x & 3] + 0.5) Put(x, y, W((int)(13 + flash * 30)));
        }

        var glowX = heroX - heroW * 0.05;
        for (var y = Math.Max(0, sea - 40); y < sea + 4; y++)
            for (var x = 0; x < cols; x++)
            {
                var d = Math.Sqrt(Math.Pow((x - glowX) / 70.0, 2) + Math.Pow((y - sea) / 26.0, 2));
                var g = Math.Max(0, 1 - d) * (0.55 + 0.15 * Math.Sin(t * 5) * Math.Sin(t * 2.3));
                if (g * 14 > Bayer[y & 3, x & 3] + 1) Max(x, y, (int)(16 + g * 30));
            }

        for (var y = sea - 12; y < sea + 2; y++)
            for (var x = 0; x < cols; x++)
            {
                var f = 1 - Math.Abs(y - (sea - 4)) / 8.0;
                var n = 0.5 + 0.5 * Math.Sin(x * 0.05 + t * 0.2 + y * 0.3);
                if (f > 0 && f * n * 10 > Bayer[y & 3, x & 3] + 2) Max(x, y, 26);
            }

        if (ship != null)
        {
            heroX = cols * 0.6;
            var mr = ship.H * 0.5;
            var mcx = heroX - heroW * 0.08;
            var mcy = sea - ship.H * 0.38;
            moonX = mcx; moonY = mcy; moonR = mr;
            for (var y = (int)(mcy - mr * 1.9); y < sea + 2; y++)
                for (var x = (int)(mcx - mr * 2.2); x < mcx + mr * 2.2; x++)
                {
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                    var d = Math.Sqrt((x - mcx) * (x - mcx) + (y - mcy) * (y - mcy)) / mr;
                    if (d <= 1)
                    {
                        var nx = (x - mcx) / mr; var ny = (y - mcy) / mr;
                        var shade = 0.75 + 0.25 * Math.Clamp(-nx * 0.6 - ny * 0.4 + 0.5, 0, 1);
                        var mare = Math.Sin(nx * 3.1 + 1.3 + Math.Sin(ny * 4.2) * 0.8) * Math.Sin(ny * 2.7 - 0.6 + nx * 1.4); var crater = mare > 0.35 && Bayer[y & 3, x & 3] < (mare - 0.35) * 30 ? 0.78 : 1;
                        var v = (150 + 70 * shade) * crater;
                        if (d > 0.94) v = 235;
                        Put(x, y, W((int)v));
                    }
                    else
                    {
                        var h = Math.Pow(Math.Max(0, 1 - (d - 1) / 1.1), 2);
                        if (h * 14 > Bayer[y & 3, x & 3] + 0.5) Max(x, y, (int)(20 + h * 70));
                    }
                }
        }

        foreach (var g in ghosts)
        {
            var gx = g.X * cols;
            var gy = sea - 3 + Math.Sin(t * 0.5 + g.Seed) * 0.6;
            var on = g == hover || g.Name == picked;
            if (small != null)
            {
                Blit(small, gx, gy, g.List + Math.Sin(t * 0.4 + g.Seed) * 0.02, on ? 1 : 0.7);
                g.X0 = (int)(gx - small.Ox); g.X1 = (int)(gx + small.W - small.Ox); g.Y0 = (int)(gy - small.Oy); g.Y1 = (int)gy;
            }
        }

        DrawStorm(t, flash);

        for (var i = 0; i < cols * rows / 55; i++)
        {
            var sx = (int)(Frac(i, 6.1) * cols);
            var sy = (int)(Frac(i, 8.7) * sea);
            if (sy >= sea - 2) continue;
            var dm = Math.Sqrt((sx - moonX) * (sx - moonX) + (sy - moonY) * (sy - moonY));
            if (dm < moonR * 1.5) continue;
            var cur = (buf[sy * cols + sx] >> 24) & 0xFF;
            if (buf[sy * cols + sx] == bgPix || cur > 30) continue;
            var tw = 0.5 + 0.5 * Math.Sin(t * (0.5 + Frac(i, 9.3) * 2.2) + i * 1.7);
            var mag = Frac(i, 11.9);
            var a = (int)((30 + 110 * mag * mag) * (0.35 + 0.65 * tw) + flash * 40);
            Max(sx, sy, a);
            if (mag > 0.94 && tw > 0.6) { Max(sx - 1, sy, a / 3); Max(sx + 1, sy, a / 3); Max(sx, sy - 1, a / 3); Max(sx, sy + 1, a / 3); }
        }

        if (Hero.Length > 0 && ship != null)
        {
            var bob = Math.Sin(t * 0.55) * 1.2;
            var wy = sea - 3 + bob;
            var roll = Math.Sin(t * 0.4) * 0.015;
            var on = heroHover || picked == Hero;
            breakX = ship.Ox + 2;
            breakY = ship.Oy;
            int Jag(int y) => (int)Math.Round(Frac(y * 0.37, 2.1) * 3 - 1);
            Blit(ship, heroX + 3, wy, 0.1 + roll, 1, (sx, sy) => sx > breakX + Jag(sy), breakX, breakY);
            Blit(ship, heroX - 3, wy - 1, -0.2 + roll, 1, (sx, sy) => sx < breakX - 2 + Jag(sy), breakX, breakY);
            heroY0 = wy - ship.Oy;
            heroY1 = wy + 4;
            if (on)
                for (var x = (int)(heroX - heroW); x < heroX + heroW; x++) if ((x & 1) == 0) Max(x, (int)wy + 6, 160);

            var fx = heroX;
            var fy = wy - ship.Oy + ship.H - 14;
            for (var k = 0; k < 18; k++)
            {
                var a = Frac(k, Math.Floor(t * 12)) ;
                var x = (int)Math.Round(fx + (Frac(k, 1.3) - 0.5) * 8);
                var h = (int)(3 + a * 7);
                for (var j = 0; j < h; j++) Max(x + (int)Math.Round(Math.Sin(t * 9 + j + k) * 0.6), (int)fy - j, (int)(240 - j * 22));
            }
            if (rng.NextDouble() < 0.9) parts.Add((fx + (rng.NextDouble() - 0.5) * 8, fy - 6, (rng.NextDouble() - 0.5) * 3, -(6 + rng.NextDouble() * 6), 1, false));
            if (rng.NextDouble() < 0.5) parts.Add((fx + (rng.NextDouble() - 0.5) * 6, fy - 4, (rng.NextDouble() - 0.5) * 14, -(10 + rng.NextDouble() * 14), 1, true));
        }

        for (var i = parts.Count - 1; i >= 0; i--)
        {
            var (x, y, vx, vy, life, ember) = parts[i];
            x += (vx + (ember ? 0 : 5 + Math.Sin(t * 0.7 + i) * 2)) * dt;
            y += vy * dt;
            if (ember) vy += 6 * dt;
            life -= dt * (ember ? 0.7 : 0.16);
            if (life <= 0 || y < -10) { parts.RemoveAt(i); continue; }
            parts[i] = (x, y, vx, vy, life, ember);
            if (ember) { Max((int)x, (int)y, (int)(250 * life)); continue; }
            var rad = 1.2 + (1 - life) * 5;
            for (var dy = -(int)rad; dy <= (int)rad; dy++)
                for (var dx = -(int)rad; dx <= (int)rad; dx++)
                {
                    if (dx * dx + dy * dy > rad * rad) continue;
                    int px = (int)x + dx, py = (int)y + dy;
                    if (px < 0 || py < 0 || px >= cols || py >= rows) continue;
                    if (life * 10 > Bayer[py & 3, px & 3] + 1) Max(px, py, (int)(20 + 50 * life + flash * 40));
                }
        }

        if (flash > 0.5 && fa < 0.28)
        {
            var br = new Random(boltSeed);
            double x = boltX + (br.NextDouble() - 0.5) * 24;
            var end = Hero.Length > 0 && Math.Abs(boltX - (heroX - heroW * 0.1)) < 1 ? (int)(heroY0 + 6) : sea - 2;
            for (var y = 0; y < end; y++)
            {
                x += (br.NextDouble() - 0.5) * 2.6 + (boltX - x) * 0.05;
                Put((int)x, y, W(255));
                Max((int)x + 1, y, 120);
                if (br.NextDouble() < 0.035)
                {
                    var bx = x;
                    var dir = br.NextDouble() < 0.5 ? -1 : 1;
                    for (var k = 0; k < 6 + br.Next(12); k++) { bx += dir * br.NextDouble() * 1.8; Max((int)bx, y + k, 170 - k * 8); }
                }
            }
        }

        for (var i = 0; i < 110; i++)
        {
            var sp = 90 + Frac(i, 2.7) * 50;
            var x0 = Frac(i, 1.9) * (cols + 60);
            var y0 = (Frac(i, 4.3) * rows + t * sp) % (rows + 10);
            var x = x0 - y0 * 0.3;
            if (x < 0) x += cols;
            for (var k = 0; k < 4; k++) Max((int)Math.Round(x - k * 0.3), (int)y0 - k, (int)((26 + flash * 60) * (1 - k / 4.0)));
        }
    }

    (double X, double R, double B)[][] storm = Array.Empty<(double, double, double)[]>();
    double[] stormPeriod = Array.Empty<double>();
    int stormCols;

    void DrawStorm(double t, double flash)
    {
        if (stormCols != cols)
        {
            stormCols = cols;
            var r = new Random(11);
            storm = new (double, double, double)[2][];
            stormPeriod = new double[2];
            for (var l = 0; l < 2; l++)
            {
                var arcs = new List<(double, double, double)>();
                var x = 0.0;
                var rmin = l == 0 ? 7 : 11;
                var rmax = l == 0 ? 14 : 22;
                while (x < cols + rmax * 2)
                {
                    var rad = rmin + r.NextDouble() * (rmax - rmin);
                    arcs.Add((x, rad, (r.NextDouble() - 0.5) * rmin * 0.7));
                    x += rad * (0.85 + r.NextDouble() * 0.45);
                }
                storm[l] = arcs.ToArray();
                stormPeriod[l] = x;
            }
        }
        var edge = new int[cols];
        for (var l = 0; l < 2; l++)
        {
            var baseY = rows * (l == 0 ? 0.11 : 0.04);
            var off = (t * (l == 0 ? 1.4 : 2.6)) % stormPeriod[l];
            var line = l == 0 ? 55 : 95;
            for (var x = 0; x < cols; x++)
            {
                var best = baseY;
                foreach (var (ax, rad, by) in storm[l])
                {
                    var px = ax - off;
                    if (px < -rad * 2) px += stormPeriod[l];
                    var d = x - px;
                    if (Math.Abs(d) >= rad) continue;
                    var bottom = baseY + by + Math.Sqrt(rad * rad - d * d) * 0.55;
                    if (bottom > best) best = bottom;
                }
                edge[x] = (int)Math.Round(best);
            }
            for (var x = 0; x < cols; x++)
            {
                var lit = Math.Max(0, 1 - Math.Abs(x - boltX) / 50.0) * flash;
                for (var y = 0; y < edge[x]; y++)
                {
                    var k = edge[x] - y;
                    var v = k <= 8 && (1 - k / 8.0) * 9 > Bayer[y & 3, x & 3] ? 22 + l * 4 + flash * 30 + lit * 60 : 0;
                    buf[y * cols + x] = v > 0 ? W((int)v) : bgPix;
                }
                Put(x, edge[x], W((int)(line + flash * 90 + lit * 100)));
                if (x > 0)
                {
                    int a = edge[x - 1], c = edge[x];
                    for (var y = Math.Min(a, c) + 1; y < Math.Max(a, c); y++) Put(a < c ? x : x - 1, y, W((int)(line + flash * 90 + lit * 100)));
                }
            }
            var off2 = (t * (l == 0 ? 1.4 : 2.6)) % stormPeriod[l];
            foreach (var (ax, rad, by) in storm[l])
            {
                if (((int)(ax * 5)) % 3 == 0) continue;
                var px = ax - off2;
                if (px < -rad * 2) px += stormPeriod[l];
                var fy = baseY + by - rad * 0.15;
                for (var x = (int)(px - rad * 0.5); x < px + rad * 0.5; x++)
                {
                    if (x < 0 || x >= cols) continue;
                    var y = (int)Math.Round(fy + Math.Sqrt(Math.Max(0, rad * rad * 0.25 - (x - px) * (x - px))) * 0.4);
                    if (y < edge[x] - 2 && y >= 0) Put(x, y, W((int)(line * 0.5 + flash * 50)));
                }
            }
        }
    }

    protected override void Overlay(DrawingContext dc, double t)
    {
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        if (Hero.Length > 0 && moonR > 0)
        {
            var on = heroHover || picked == Hero;
            var hn = new FormattedText(Hero.ToUpperInvariant(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 11, text, dpi);
            var hc = new FormattedText(HeroCount <= 1 ? "1 WRECK" : HeroCount + " WRECKS", CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 9, on ? text : faint, dpi);
            var w = Math.Max(hn.Width, hc.Width) + 16;
            var hx = (moonX + moonR * 1.05) * P + 14;
            var hy = (moonY - moonR * 0.75) * P;
            if (hx + w > ActualWidth - 6) hx = (moonX - moonR * 1.05) * P - 14 - w;
            var plate = new SolidColorBrush(Color.FromArgb(220, (byte)((bgPix >> 16) & 0xFF), (byte)((bgPix >> 8) & 0xFF), (byte)(bgPix & 0xFF)));
            dc.DrawRectangle(plate, null, new Rect(Math.Round(hx), Math.Round(hy), w, hn.Height + hc.Height + 10));
            dc.DrawRectangle(text, null, new Rect(Math.Round(hx), Math.Round(hy), 2, hn.Height + hc.Height + 10));
            dc.DrawText(hn, new Point(Math.Round(hx + 10), Math.Round(hy + 5)));
            dc.DrawText(hc, new Point(Math.Round(hx + 10), Math.Round(hy + 5 + hn.Height)));
        }
        foreach (var g in ghosts)
        {
            var on = g == hover || g.Name == picked;
            var name = new FormattedText(g.Name.ToUpperInvariant(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 10, on ? text : faint, dpi);
            var count = new FormattedText(g.Count == 1 ? "1 WRECK" : g.Count + " WRECKS", CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, 9, faint, dpi);
            var cx = (g.X0 + g.X1) / 2.0 * P;
            double y = g.Y0 * P - 30;
            dc.DrawText(name, new Point(Math.Round(Math.Clamp(cx - name.Width / 2, 4.0, ActualWidth - name.Width - 4)), Math.Round(y)));
            dc.DrawText(count, new Point(Math.Round(Math.Clamp(cx - count.Width / 2, 4.0, ActualWidth - count.Width - 4)), Math.Round(y + name.Height)));
        }
    }
}
