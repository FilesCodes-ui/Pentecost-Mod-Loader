package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;

public final class AccountSkin {
    private static final ResourceLocation ID = new ResourceLocation("pentecost", "account_skin");
    private static boolean ready;
    private static int height = 64;

    private AccountSkin() {}

    public static ResourceLocation location() {
        return ready ? ID : null;
    }

    public static int height() {
        return height;
    }

    public static void set(byte[] png) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try (ByteArrayInputStream in = new ByteArrayInputStream(png)) {
                NativeImage image = NativeImage.read(in);
                int h = image.getHeight();
                mc.getTextureManager().register(ID, new DynamicTexture(image));
                height = h;
                ready = true;
            } catch (java.io.IOException | RuntimeException e) {
                Log.error("Cannot read the skin: " + e);
            }
        });
    }

    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (ready) {
                mc.getTextureManager().release(ID);
                ready = false;
            }
        });
    }
}
