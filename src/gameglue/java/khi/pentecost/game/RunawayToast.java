package khi.pentecost.game;

import khi.pentecost.core.Runaways;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class RunawayToast {
    private RunawayToast() {}

    static void install() {
        if (Runaways.notice != null) return;
        Runaways.notice = text -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> khi.pentecost.game.PentecostToast.show(Component.literal("A mod got stuck"), Component.literal(text)));
        };
    }
}
