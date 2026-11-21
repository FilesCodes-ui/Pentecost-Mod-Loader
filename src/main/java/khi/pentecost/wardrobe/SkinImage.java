package khi.pentecost.wardrobe;

import khi.pentecost.account.SkinApi;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SkinImage {
    public record Report(int width, int height, boolean slim, boolean legacy, List<String> notes) {}

    private SkinImage() {}

    public static BufferedImage read(byte[] png) throws IOException {
        SkinApi.checkSkin(png);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("This file is not a readable PNG image");
        }
        return image;
    }

    public static Report check(byte[] png) throws IOException {
        BufferedImage img = read(png);
        boolean legacy = img.getHeight() == 32;
        boolean slim = !legacy && detectSlim(img);
        List<String> notes = new ArrayList<>();
        if (everyAlpha(img, 0, 0, img.getWidth(), img.getHeight(), 0)) {
            notes.add("The whole skin is see-through. Nothing would show.");
        }
        if (legacy) {
            notes.add("Old 64 by 32 layout. Fix converts it to the current one, so it can use the second layer.");
        }
        List<String> seeThrough = new ArrayList<>();
        if (anySeeThrough(img, 8, 8, 8, 8)) {
            seeThrough.add("face");
        }
        if (anySeeThrough(img, 20, 20, 8, 12)) {
            seeThrough.add("body");
        }
        if (anySeeThrough(img, 44, 20, slim ? 3 : 4, 12)) {
            seeThrough.add("right arm");
        }
        if (anySeeThrough(img, 4, 20, 4, 12)) {
            seeThrough.add("right leg");
        }
        if (!legacy) {
            if (anySeeThrough(img, 36, 52, slim ? 3 : 4, 12)) {
                seeThrough.add("left arm");
            }
            if (anySeeThrough(img, 20, 52, 4, 12)) {
                seeThrough.add("left leg");
            }
        }
        if (!seeThrough.isEmpty()) {
            notes.add("See-through pixels on the base layer of the " + String.join(", ", seeThrough)
                    + ". The game draws them black.");
        }
        if (everyAlpha(img, 32, 0, 32, 16, 255)) {
            notes.add("The hat layer is fully solid. It shows as a block over the head.");
        }
        return new Report(img.getWidth(), img.getHeight(), slim, legacy, List.copyOf(notes));
    }

    public static boolean detectSlim(BufferedImage img) {
        if (img.getHeight() < 64 || img.getWidth() < 64) {
            return false;
        }
        return everyAlpha(img, 54, 20, 2, 12, 0) && everyAlpha(img, 46, 52, 2, 12, 0);
    }

    public static byte[] upgradeLegacy(byte[] png) throws IOException {
        BufferedImage old = read(png);
        if (old.getHeight() != 32) {
            return png;
        }
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(old, 0, 0, null);
        int[][] copies = {
                {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
                {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12},
                {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4}, {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12},
                {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
        for (int[] c : copies) {
            for (int yy = 0; yy < c[5]; yy++) {
                for (int xx = 0; xx < c[4]; xx++) {
                    int pixel = out.getRGB(c[0] + xx, c[1] + yy);
                    out.setRGB(c[0] + c[2] + (c[4] - 1 - xx), c[1] + c[3] + yy, pixel);
                }
            }
        }
        return toPng(out);
    }

    public static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("Could not write the skin as a PNG");
        }
        return out.toByteArray();
    }

    private static boolean everyAlpha(BufferedImage img, int x, int y, int w, int h, int alpha) {
        for (int yy = y; yy < y + h && yy < img.getHeight(); yy++) {
            for (int xx = x; xx < x + w && xx < img.getWidth(); xx++) {
                if ((img.getRGB(xx, yy) >>> 24) != alpha) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean anySeeThrough(BufferedImage img, int x, int y, int w, int h) {
        for (int yy = y; yy < y + h && yy < img.getHeight(); yy++) {
            for (int xx = x; xx < x + w && xx < img.getWidth(); xx++) {
                if ((img.getRGB(xx, yy) >>> 24) < 255) {
                    return true;
                }
            }
        }
        return false;
    }
}
