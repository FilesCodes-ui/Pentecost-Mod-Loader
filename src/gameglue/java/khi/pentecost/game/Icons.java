package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small square pictures for lists (pack icons), loaded off the game thread. The first frames draw nothing for an icon,
 * then it appears. A loader returns a 64 by 64 PNG, or null for "no icon" (asked once, not again). The newest 200
 * textures are kept, older ones are released. Draw calls happen on the render thread only.
 */
final class Icons {
    private static final byte[] NONE = new byte[0];
    private static final ResourceLocation BAD = new ResourceLocation("pentecost", "icons/none");
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService LOAD = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "pentecost-icons-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Map<String, byte[]> READY = new ConcurrentHashMap<>();
    private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();
    private static final Map<String, ResourceLocation> TEXTURES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
            if (size() <= 200) {
                return false;
            }
            if (eldest.getValue() != BAD) {
                Minecraft.getInstance().getTextureManager().release(eldest.getValue());
            }
            ASKED.remove(eldest.getKey());
            return true;
        }
    };

    private Icons() {}

    /** The texture for this key, starting a load when needed. Null while loading or when there is no icon. */
    static ResourceLocation get(String key, Callable<byte[]> loader) {
        ResourceLocation t = TEXTURES.get(key);
        if (t != null) {
            return t == BAD ? null : t;
        }
        byte[] b = READY.remove(key);
        if (b == null) {
            if (ASKED.add(key)) {
                LOAD.execute(() -> {
                    byte[] got;
                    try {
                        got = loader.call();
                    } catch (Exception | LinkageError e) {
                        got = null;
                    }
                    READY.put(key, got == null ? NONE : got);
                });
            }
            return null;
        }
        if (b.length == 0) {
            TEXTURES.put(key, BAD);
            return null;
        }
        ResourceLocation id = new ResourceLocation("pentecost", "icons/" + Integer.toHexString(key.hashCode()) + "_" + Integer.toHexString(b.length));
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(b));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        } catch (java.io.IOException | RuntimeException e) {
            Log.warn("An icon could not be read: " + e);
            id = BAD;
        }
        TEXTURES.put(key, id);
        return id == BAD ? null : id;
    }

    /** Draws the icon at x, y, size by size. False when there is none (yet), so the caller draws its own tile. */
    static boolean draw(GuiGraphics g, String key, Callable<byte[]> loader, int x, int y, int size) {
        ResourceLocation t = get(key, loader);
        if (t == null) {
            return false;
        }
        RenderSystem.enableBlend();
        g.blit(t, x, y, size, size, 0f, 0f, 64, 64, 64, 64);
        return true;
    }
}
