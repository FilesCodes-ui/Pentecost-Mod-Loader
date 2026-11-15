package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;

/** The signed in account's skin, downloaded once and kept as a texture for the menus. */
public final class AccountSkin {
    private static final ResourceLocation ID = new ResourceLocation("pentecost", "account_skin");
    private static boolean ready;
    private static int height = 64;

    private AccountSkin() {}

    /** The skin texture, or null when nobody is signed in or it has not loaded yet. */
    public static ResourceLocation location() {
        return ready ? ID : null;
    }

    /** The skin image height: 64, or 32 for old skins. The width is always 64. */
    public static int height() {
        return height;
    }

    /** Called with the PNG bytes of the skin, from any thread. */
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
