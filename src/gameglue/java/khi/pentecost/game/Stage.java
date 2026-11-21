package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

final class Stage {
    private static final int PX = 2;
    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    private final ResourceLocation id;
    private DynamicTexture tex;
    private int texW = -1;
    private int texH = -1;
    private int builtCx = -1;
    private int builtFeet = -1;

    Stage(String name) {
        id = new ResourceLocation("pentecost", name);
    }

    private static int hash(int n) {
        int h = n * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private static double rnd(int x, int y, int seed) {
        return hash(x * 73856093 ^ y * 19349663 ^ seed * 83492791) / (double) 0x7FFFFFFF;
    }

    private static int dither(double v, int x, int y) {
        double c = Math.max(0, Math.min(255, v));
        int base = (int) (c / 6) * 6;
        double frac = (c - base) / 6.0;
        return Math.min(255, frac * 16 > BAYER[y & 3][x & 3] ? base + 6 : base);
    }

    private static int tint(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
    }

    void release() {
        if (tex != null) {
            Minecraft.getInstance().getTextureManager().release(id);
            tex = null;
            texW = -1;
        }
    }

    void draw(GuiGraphics g, int vw, int vh, int cx, int feet, int platform) {
        int tw = (vw + PX - 1) / PX;
        int th = (vh + PX - 1) / PX;
        if (tw != texW || th != texH || cx != builtCx || feet != builtFeet || tex == null) {
            build(tw, th, cx, feet, platform);
        }
        g.blit(id, 0, 0, texW * PX, texH * PX, 0f, 0f, texW, texH, texW, texH);
    }

    private void build(int tw, int th, int cxV, int feetV, int platformV) {
        Minecraft mc = Minecraft.getInstance();
        if (tex != null) {
            mc.getTextureManager().release(id);
        }
        texW = tw;
        texH = th;
        builtCx = cxV;
        builtFeet = feetV;
        double sx = cxV / (double) PX;
        double feet = feetV / (double) PX;
        double rx = Math.max(20, platformV / (double) PX);
        double ry = rx * 0.22;
        NativeImage img = new NativeImage(tw, th, false);
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                double v = 6 + 9 * (y / (double) th);
                double half = 6 + y * 0.34;
                double dx = Math.abs(x - sx);
                if (y < feet + ry && dx < half) {
                    double k = 1 - dx / half;
                    double fall = Math.min(1, y / (feet * 0.9));
                    v += 26 * Math.pow(k, 1.6) * (0.4 + 0.6 * fall);
                }
                if (y > feet) {
                    double depth = (y - feet) / Math.max(1, th - feet);
                    v = 8 - depth * 3;
                    double vx = (x - sx) / (y - feet + 6);
                    double lane = Math.abs(vx * 3 - Math.round(vx * 3));
                    if (lane < 0.06 && Math.abs(x - sx) < tw * 0.6) {
                        v += 7 * (1 - depth);
                    }
                    double row = Math.log(y - feet + 1) * 4.0;
                    if (Math.abs(row - Math.round(row)) < 0.08) {
                        v += 5 * (1 - depth);
                    }
                }
                double hcy = feet * 0.52;
                double hr = feet * 0.40;
                double hd = Math.sqrt((x - sx) * (x - sx) + (y - hcy) * (y - hcy));
                if (y < feet && Math.abs(hd - hr) < 0.55) {
                    v += 16;
                }
                if (y < feet && Math.abs(hd - hr * 0.84) < 0.5 && ((int) (Math.atan2(y - hcy, x - sx) * 40) & 1) == 0) {
                    v += 9;
                }
                if (y < feet && Math.abs(hd - hr * 1.18) < 0.5 && ((int) (Math.atan2(y - hcy, x - sx) * 14) % 3) == 0) {
                    v += 6;
                }
                double horizon = feet - 4;
                if (y < horizon && y > horizon - 26) {
                    double k = 1 - (horizon - y) / 26.0;
                    v += 6 * k * k;
                }
                double ex = (x - sx) / rx;
                double ey = (y - feet) / ry;
                double e = ex * ex + ey * ey;
                if (e < 1) {
                    v = 24 + 30 * (1 - e) + (y < feet ? 10 : 0);
                }
                if (Math.abs(e - 1) < 0.07) {
                    v = 120;
                }
                if (Math.abs(e - 2.6) < 0.05 && rnd(x / 2, 0, 3) > 0.3) {
                    v = Math.max(v, 40);
                }
                double vx = Math.min(x, tw - x) / (tw * 0.18);
                double vy = Math.min(y, th - y) / (th * 0.2);
                v *= 0.55 + 0.45 * Math.min(1, Math.min(vx, vy));
                int g = dither(v, x, y);
                if (y < feet - 20 && rnd(x, y, 91) > 0.9975 && dx > half) {
                    g = Math.max(g, 40 + (int) (rnd(x, y, 5) * 70));
                }
                img.setPixelRGBA(x, y, 0xFF000000 | (g << 16) | (g << 8) | g);
            }
        }
        tex = new DynamicTexture(img);
        mc.getTextureManager().register(id, tex);
    }

    static void life(GuiGraphics g, int cx, int feet, int platform, double t) {
        int lamp = 6;
        g.fill(cx - 9, lamp, cx + 9, lamp + 3, 0xFF2A2A2C);
        g.fill(cx - 6, lamp + 3, cx + 6, lamp + 4, 0xFFD8D8D8);
        double flicker = 0.85 + 0.15 * Math.sin(t * 9.0) * Math.sin(t * 2.3);
        g.fill(cx - 4, lamp + 4, cx + 4, lamp + 5, tint((int) (180 * flicker)));

        for (int k = 0; k < 3; k++) {
            double drift = Math.sin(t * 0.21 + k * 2.1) * 8;
            int a = (int) (10 + 8 * Math.sin(t * 0.6 + k));
            for (int y = lamp + 6; y < feet - 4; y += 2) {
                double spread = (y - lamp) * (0.12 + k * 0.05);
                int x = (int) (cx + drift + (k - 1) * spread);
                g.fill(x, y, x + 1, y + 1, tint(a));
            }
        }

        double rx = platform * 1.15;
        double ry = rx * 0.22;
        int n = 64;
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n + t * 0.35;
            double front = Math.sin(ang);
            if (i % 4 == 3) {
                continue;
            }
            int x = (int) (cx + Math.cos(ang) * rx);
            int y = (int) (feet + Math.sin(ang) * ry);
            int a = (int) (40 + 120 * (0.5 + 0.5 * front));
            g.fill(x, y, x + 2, y + 1, tint(a));
        }
        double rx2 = platform * 1.55;
        double ry2 = rx2 * 0.22;
        for (int i = 0; i < 24; i++) {
            double ang = i * Math.PI * 2 / 24 - t * 0.18;
            int x = (int) (cx + Math.cos(ang) * rx2);
            int y = (int) (feet + Math.sin(ang) * ry2);
            int a = (int) (20 + 60 * (0.5 + 0.5 * Math.sin(ang)));
            g.fill(x, y, x + 1, y + 1, tint(a));
        }

        double cycle = (t % 3.2) / 3.2;
        double pr = platform * (0.6 + cycle * 1.1);
        double pry = pr * 0.22;
        int pa = (int) (90 * (1 - cycle));
        for (int i = 0; i < 48; i++) {
            double ang = i * Math.PI * 2 / 48;
            int x = (int) (cx + Math.cos(ang) * pr);
            int y = (int) (feet + Math.sin(ang) * pry);
            g.fill(x, y, x + 1, y + 1, tint(pa));
        }
    }

    static void dust(GuiGraphics g, int cx, int feet, double t) {
        for (int i = 0; i < 26; i++) {
            double speed = 4 + (i % 5) * 2;
            double y = feet - ((t * speed + hash(i * 3 + 1) % 400) % Math.max(1, feet - 30));
            double spread = 10 + y * 0.3;
            double x = cx + Math.sin(t * 0.3 + i * 1.7) * spread * ((hash(i * 7) % 100) / 100.0);
            int a = (int) (60 + 60 * Math.sin(t * 1.3 + i));
            g.fill((int) x, (int) y, (int) x + 1, (int) y + 1, tint(a));
        }
    }
}
