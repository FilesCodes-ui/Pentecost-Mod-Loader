package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Turns skin and cape PNG files into game textures for the wardrobe and the settings preview.
 * Old 64 by 32 skins are converted to the 64 by 64 layout the player model reads, the way the game does it.
 * Only the render thread may call these. A texture is made once per image and dropped when many others were made after it.
 */
final class SkinTextures {
    private static final int KEEP = 64;
    private static final ResourceLocation BAD = new ResourceLocation("pentecost", "skins/bad");

    private static final Map<String, ResourceLocation> LOADED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
            if (size() <= KEEP) {
                return false;
            }
            if (eldest.getValue() != BAD) {
                Minecraft.getInstance().getTextureManager().release(eldest.getValue());
            }
            return true;
        }
    };
    /** The key of an array already seen, so the same bytes are not hashed every frame. */
    private static final Map<byte[], String> KEYS = new WeakHashMap<>();

    private SkinTextures() {}

    /** The texture for a skin image, or null when the bytes are not a readable skin. */
    static ResourceLocation skin(byte[] png) {
        return get(png, true);
    }

    /** The texture for a cape image, or null when the bytes are not a readable cape. */
    static ResourceLocation cape(byte[] png) {
        return get(png, false);
    }

    private static ResourceLocation get(byte[] png, boolean skin) {
        if (png == null || png.length == 0) {
            return null;
        }
        String key = KEYS.get(png);
        if (key == null) {
            key = (skin ? "s" : "c") + hash(png);
            KEYS.put(png, key);
        }
        ResourceLocation id = LOADED.get(key);
        if (id != null) {
            return id == BAD ? null : id;
        }
        id = new ResourceLocation("pentecost", "skins/" + key);
        try {
            NativeImage image = skin ? skinImage(png) : capeImage(png);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        } catch (IOException | RuntimeException e) {
            Log.error("Cannot read a " + (skin ? "skin" : "cape") + " image: " + e);
            id = BAD;
        }
        LOADED.put(key, id);
        return id == BAD ? null : id;
    }

    private static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    // ------------------------------------------------------------------ images

    private static NativeImage skinImage(byte[] png) throws IOException {
        NativeImage in = NativeImage.read(new ByteArrayInputStream(png));
        NativeImage image = in;
        boolean legacy = in.getHeight() == 32;
        if (legacy) {
            image = new NativeImage(64, 64, true);
            image.copyFrom(in);
            in.close();
            image.fillRect(0, 32, 64, 32, 0);
            image.copyRect(4, 16, 16, 32, 4, 4, true, false);
            image.copyRect(8, 16, 16, 32, 4, 4, true, false);
            image.copyRect(0, 20, 24, 32, 4, 12, true, false);
            image.copyRect(4, 20, 16, 32, 4, 12, true, false);
            image.copyRect(8, 20, 8, 32, 4, 12, true, false);
            image.copyRect(12, 20, 16, 32, 4, 12, true, false);
            image.copyRect(44, 16, -8, 32, 4, 4, true, false);
            image.copyRect(48, 16, -8, 32, 4, 4, true, false);
            image.copyRect(40, 20, 0, 32, 4, 12, true, false);
            image.copyRect(44, 20, -8, 32, 4, 12, true, false);
            image.copyRect(48, 20, -16, 32, 4, 12, true, false);
            image.copyRect(52, 20, -8, 32, 4, 12, true, false);
        }
        opaque(image, 0, 0, 32, 16);
        if (legacy) {
            keepOuterLayerIfSolid(image);
        }
        opaque(image, 0, 16, 64, 32);
        opaque(image, 16, 48, 48, 64);
        return image;
    }

    private static NativeImage capeImage(byte[] png) throws IOException {
        NativeImage in = NativeImage.read(new ByteArrayInputStream(png));
        if (in.getWidth() >= 64 && in.getHeight() * 2 == in.getWidth()) {
            return in;
        }
        // The old 22 by 17 cape: put it in the top left corner of the 64 by 32 sheet the model reads.
        NativeImage sheet = new NativeImage(64, 32, true);
        sheet.copyFrom(in);
        in.close();
        return sheet;
    }

    /** The base layers have no see-through parts. */
    private static void opaque(NativeImage image, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) {
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0xFF000000);
            }
        }
    }

    /** Old skins with a fully solid hat area meant "no hat". New skins would show a solid block, so make that area clear. */
    private static void keepOuterLayerIfSolid(NativeImage image) {
        for (int x = 32; x < 64; x++) {
            for (int y = 0; y < 32; y++) {
                if (((image.getPixelRGBA(x, y) >> 24) & 255) < 128) {
                    return;
                }
            }
        }
        for (int x = 32; x < 64; x++) {
            for (int y = 0; y < 32; y++) {
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) & 0x00FFFFFF);
            }
        }
    }
}
