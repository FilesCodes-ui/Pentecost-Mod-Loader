package khi.pentecost.cape;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeArtTest {
    @Test
    void rendersFramesAndPreview() throws Exception {
        CapeArt art = new CapeArt();
        CapeArt.State s = new CapeArt.State();
        CapeArt.Motion m = new CapeArt.Motion();
        int frames = 12;
        int zoom = 6;
        BufferedImage strip = new BufferedImage(frames * (CapeArt.W * zoom + 8), CapeArt.H * zoom, BufferedImage.TYPE_INT_ARGB);
        double t = 0;
        int shot = 0;
        for (int i = 0; i < 200; i++) {
            t += 1 / 20.0;
            m.t = t;
            m.onGround = true;
            m.vy = 0;
            m.strafe = 0;
            m.speed = 0;
            if (i >= 40 && i < 80) {
                m.strafe = 1;
                m.speed = 0.6;
            }
            if (i >= 100 && i < 104) {
                m.onGround = false;
                m.vy = 0.42;
            }
            if (i >= 104 && i < 112) {
                m.onGround = false;
                m.vy = 0.1 - (i - 104) * 0.08;
            }
            if (i >= 140 && i < 175) {
                m.onGround = false;
                m.vy = -1.2;
            }
            int[] f = art.frame(s, m);
            assertEquals(CapeArt.W * CapeArt.H, f.length);
            if (i % 17 == 5 && shot < frames) {
                for (int y = 0; y < CapeArt.H; y++) {
                    for (int x = 0; x < CapeArt.W; x++) {
                        for (int a = 0; a < zoom; a++) {
                            for (int b = 0; b < zoom; b++) {
                                strip.setRGB(shot * (CapeArt.W * zoom + 8) + x * zoom + a, y * zoom + b, f[y * CapeArt.W + x]);
                            }
                        }
                    }
                }
                shot++;
            }
        }
        File out = new File("build/cape-preview");
        assertTrue(out.isDirectory() || out.mkdirs());
        ImageIO.write(strip, "png", new File(out, "strip.png"));
        int big = 10;
        BufferedImage one = new BufferedImage(CapeArt.W * big * 2 + 10, CapeArt.H * big, BufferedImage.TYPE_INT_ARGB);
        CapeArt.State s2 = new CapeArt.State();
        CapeArt.Motion m2 = new CapeArt.Motion();
        int[] keep = null;
        for (int i = 0; i < 60; i++) {
            m2.t = 3 + i / 20.0;
            m2.onGround = true;
            int[] f = art.frame(s2, m2);
            if (i == 20) {
                keep = f.clone();
            }
            if (i == 59) {
                for (int k = 0; k < 2; k++) {
                    int[] src = k == 0 ? keep : f;
                    for (int y = 0; y < CapeArt.H; y++) {
                        for (int x = 0; x < CapeArt.W; x++) {
                            for (int a = 0; a < big; a++) {
                                for (int b = 0; b < big; b++) {
                                    one.setRGB(k * (CapeArt.W * big + 10) + x * big + a, y * big + b, src[y * CapeArt.W + x]);
                                }
                            }
                        }
                    }
                }
            }
        }
        ImageIO.write(one, "png", new File(out, "big.png"));
        int[] tex = new int[CapeArt.TEX_W * CapeArt.TEX_H];
        art.texture(tex, s, m);
        BufferedImage ti = new BufferedImage(CapeArt.TEX_W, CapeArt.TEX_H, BufferedImage.TYPE_INT_ARGB);
        ti.setRGB(0, 0, CapeArt.TEX_W, CapeArt.TEX_H, tex, 0, CapeArt.TEX_W);
        ImageIO.write(ti, "png", new File(out, "texture.png"));
    }
}
