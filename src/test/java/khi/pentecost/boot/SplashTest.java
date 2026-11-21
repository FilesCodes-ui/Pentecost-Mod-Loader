package khi.pentecost.boot;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SplashTest {
    @Test
    void drawsAndSavesAPreview() throws Exception {
        java.awt.image.BufferedImage img = Splash.preview();
        Path out = Path.of("build", "splash-preview.png");
        Files.createDirectories(out.getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());
        assertTrue(Files.size(out) > 0);
        boolean bright = false;
        for (int x = 0; x < img.getWidth() && !bright; x += 2) {
            for (int y = 0; y < img.getHeight(); y += 2) {
                if ((img.getRGB(x, y) & 0xFF) > 0xE0) {
                    bright = true;
                    break;
                }
            }
        }
        assertTrue(bright);
    }
}
