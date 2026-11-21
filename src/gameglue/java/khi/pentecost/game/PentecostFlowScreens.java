package khi.pentecost.game;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;

public final class PentecostFlowScreens {
    private PentecostFlowScreens() {}

    static void backdrop(GuiGraphics g, Minecraft mc) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), Ui.back(mc));
        Ui.dither(g, g.guiWidth(), g.guiHeight());
    }

    static void header(GuiGraphics g, Minecraft mc, String word) {
        float ui = Ui.scale(mc);
        int vw = (int) (g.guiWidth() / ui);
        int vh = (int) (g.guiHeight() / ui);
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);
        int px = 2;
        int total = (Ui.LOGO_W + 5) * px + Ui.wordmarkWidth(word, px);
        int x = vw / 2 - total / 2;
        int y = Math.max(14, vh / 14);
        Ui.logo(g, x, y - px, px, Ui.TEXT);
        Ui.wordmark(g, word, x + (Ui.LOGO_W + 5) * px, y, px, Ui.TEXT);
        g.pose().popPose();
    }

    private static Object read(Class<?> owner, Object target, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static final class Connecting extends Screen {
        private final ConnectScreen inner;
        private int cancelX, cancelY, cancelW;

        Connecting(ConnectScreen inner) {
            super(inner.getTitle());
            this.inner = inner;
        }

        @Override
        protected void init() {
            super.init();
            inner.init(minecraft, width, height);
        }

        @Override
        public void tick() {
            inner.tick();
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

        private void cancel() {
            for (GuiEventListener c : inner.children()) {
                if (c instanceof Button b) {
                    b.onPress();
                    return;
                }
            }
            minecraft.setScreen(null);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Object status = read(ConnectScreen.class, inner, "status");
            String text = status instanceof Component c ? c.getString() : "Connecting";
            PentecostLevelLoadingScreen.draw(g, minecraft, "CONNECTING", text, -1f, null);
            float ui = Ui.scale(minecraft);
            int vw = (int) (width / ui);
            int vh = (int) (height / ui);
            cancelW = 120;
            cancelX = vw / 2 - cancelW / 2;
            cancelY = vh - 70;
            int mx = (int) (mouseX / ui);
            int my = (int) (mouseY / ui);
            g.pose().pushPose();
            g.pose().scale(ui, ui, 1);
            boolean hover = Ui.over(mx, my, cancelX, cancelY, cancelW, 20);
            if (hover) {
                g.fill(cancelX, cancelY, cancelX + cancelW, cancelY + 20, Ui.TEXT);
            } else {
                Ui.outline(g, cancelX, cancelY, cancelX + cancelW, cancelY + 20, 0x66FFFFFF);
            }
            g.drawString(font, "Cancel", cancelX + (cancelW - font.width("Cancel")) / 2, cancelY + 6,
                    hover ? Ui.BLACK : Ui.TEXT, false);
            g.pose().popPose();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            float ui = Ui.scale(minecraft);
            double mx = mouseX / ui;
            double my = mouseY / ui;
            if (button == 0 && mx >= cancelX && mx < cancelX + cancelW && my >= cancelY && my < cancelY + 20) {
                cancel();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == 256) {
                cancel();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
    }

    static Screen connecting(Screen incoming) {
        return incoming instanceof ConnectScreen c ? new Connecting(c) : null;
    }

    public static final class Disconnected extends DisconnectedScreen {
        Disconnected(Screen parent, Component title, Component reason, Component buttonText) {
            super(parent, title, reason, buttonText);
        }

        @Override
        public void renderBackground(GuiGraphics g) {
            backdrop(g, minecraft);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            header(g, minecraft, "DISCONNECTED");
        }
    }

    static Screen disconnected(Screen incoming) {
        if (incoming.getClass() != DisconnectedScreen.class) {
            return null;
        }
        Object parent = read(DisconnectedScreen.class, incoming, "parent");
        Object reason = read(DisconnectedScreen.class, incoming, "reason");
        Object button = read(DisconnectedScreen.class, incoming, "buttonText");
        if (!(reason instanceof Component r) || !(button instanceof Component b)) {
            return null;
        }
        return new Disconnected(parent instanceof Screen p ? p : null, incoming.getTitle(), r, b);
    }

    public static final class Confirm extends ConfirmScreen {
        Confirm(BooleanConsumer callback, Component title, Component message, Component yes, Component no) {
            super(callback, title, message, yes, no);
        }

        @Override
        public void renderBackground(GuiGraphics g) {
            backdrop(g, minecraft);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            header(g, minecraft, "CONFIRM");
        }
    }

    static Screen confirm(Screen incoming) {
        if (incoming.getClass() != ConfirmScreen.class) {
            return null;
        }
        Object callback = read(ConfirmScreen.class, incoming, "callback");
        Object message = read(ConfirmScreen.class, incoming, "message");
        Object yes = read(ConfirmScreen.class, incoming, "yesButton");
        Object no = read(ConfirmScreen.class, incoming, "noButton");
        if (!(callback instanceof BooleanConsumer cb) || !(message instanceof Component m)
                || !(yes instanceof Component y) || !(no instanceof Component n)) {
            return null;
        }
        Confirm made = new Confirm(cb, incoming.getTitle(), m, y, n);
        Object delay = read(ConfirmScreen.class, incoming, "delayTicker");
        if (delay instanceof Integer d && d > 0) {
            made.setDelay(d);
        }
        return made;
    }
}
