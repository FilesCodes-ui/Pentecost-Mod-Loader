package khi.pentecost.game;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

final class GrayButtons {
    private static final class Slot {
        TextureTarget target;
        DynamicTexture texture;
        ResourceLocation id;
        int width;
        int height;
        String key;
    }

    private static final Map<AbstractButton, Slot> SLOTS = new HashMap<>();

    private GrayButtons() {}

    static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Slot s : SLOTS.values()) {
            free(mc, s);
        }
        SLOTS.clear();
    }

    private static void free(Minecraft mc, Slot s) {
        if (s.target != null) {
            s.target.destroyBuffers();
        }
        if (s.id != null) {
            mc.getTextureManager().release(s.id);
        }
    }

    static void draw(GuiGraphics g, AbstractButton b, int x, int y, int mx, int my, float partialTick, float scale) {
        Minecraft mc = Minecraft.getInstance();
        int bw = b.getWidth();
        int bh = b.getHeight();
        double pixels = mc.getWindow().getGuiScale() * scale;
        int tw = Math.max(1, (int) Math.ceil(bw * pixels));
        int th = Math.max(1, (int) Math.ceil(bh * pixels));
        Slot slot = SLOTS.computeIfAbsent(b, k -> new Slot());
        if (slot.target == null || slot.width != tw || slot.height != th) {
            free(mc, slot);
            slot.target = new TextureTarget(tw, th, true, Minecraft.ON_OSX);
            slot.texture = new DynamicTexture(tw, th, true);
            slot.id = mc.getTextureManager().register("pentecost_button", slot.texture);
            slot.width = tw;
            slot.height = th;
            slot.key = null;
        }

        boolean hovered = mx >= x && mx < x + bw && my >= y && my < y + bh;
        String key = hovered + "|" + b.active + "|" + b.isFocused() + "|" + b.getMessage().getString() + "|" + tw + "x" + th;
        if (key.equals(slot.key)) {
            blitSlot(g, slot, x, y, bw, bh, tw, th);
            return;
        }

        g.flush();
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        slot.target.setClearColor(0f, 0f, 0f, 0f);
        slot.target.clear(Minecraft.ON_OSX);
        slot.target.bindWrite(true);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, bw, bh, 0f, 1000f, 21000f),
                VertexSorting.ORTHOGRAPHIC_Z);
        int oldX = b.getX();
        int oldY = b.getY();
        boolean drawn = true;
        try {
            b.setX(0);
            b.setY(0);
            b.visible = true;
            GuiGraphics inner = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            b.render(inner, mx - x, my - y, partialTick);
            inner.flush();
        } catch (RuntimeException e) {
            drawn = false;
        } finally {
            b.visible = false;
            b.setX(oldX);
            b.setY(oldY);
            mc.getMainRenderTarget().bindWrite(true);
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
        }
        if (!drawn) {
            Ui.outline(g, x, y, x + bw, y + bh, Ui.FAINT);
            return;
        }

        NativeImage image = slot.texture.getPixels();
        if (image == null) {
            return;
        }
        RenderSystem.bindTexture(slot.target.getColorTextureId());
        image.downloadTexture(0, false);
        for (int row = 0; row < th / 2; row++) {
            int other = th - 1 - row;
            for (int col = 0; col < tw; col++) {
                int a = gray(image.getPixelRGBA(col, row));
                int c = gray(image.getPixelRGBA(col, other));
                image.setPixelRGBA(col, row, c);
                image.setPixelRGBA(col, other, a);
            }
        }
        if (th % 2 == 1) {
            int row = th / 2;
            for (int col = 0; col < tw; col++) {
                image.setPixelRGBA(col, row, gray(image.getPixelRGBA(col, row)));
            }
        }
        slot.texture.upload();
        slot.key = key;
        blitSlot(g, slot, x, y, bw, bh, tw, th);
    }

    private static void blitSlot(GuiGraphics g, Slot slot, int x, int y, int bw, int bh, int tw, int th) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(slot.id, x, y, bw, bh, 0f, 0f, tw, th, tw, th);
        RenderSystem.disableBlend();
    }

    private static int gray(int p) {
        int a = p >>> 24;
        int b = (p >> 16) & 0xFF;
        int gr = (p >> 8) & 0xFF;
        int r = p & 0xFF;
        int l = Math.min(255, (int) (r * 0.30 + gr * 0.59 + b * 0.11));
        return (a << 24) | (l << 16) | (l << 8) | l;
    }
}
