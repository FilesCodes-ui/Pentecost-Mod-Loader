package khi.pentecost.game;

import khi.pentecost.auth.DevFeed;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

final class FeedHooks {
    private FeedHooks() {}

    private static volatile boolean started;

    static void install() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(40_000);
                while (true) {
                    pull();
                    Thread.sleep(30 * 60_000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pentecost-feed");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static void ping() {
        Thread t = new Thread(FeedHooks::pull, "pentecost-feed-ping");
        t.setDaemon(true);
        t.start();
    }

    private static void pull() {
        DevFeed.refresh();
        List<DevFeed.Post> fresh = DevFeed.unseenPosts();
        if (fresh.isEmpty()) {
            return;
        }
        List<DevFeed.Post> shown = fresh.subList(0, Math.min(3, fresh.size()));
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            for (DevFeed.Post p : shown) {
                String title = p.title().isEmpty() ? "Pentecost" : p.title();
                String body = p.body().length() > 140 ? p.body().substring(0, 137) + "..." : p.body();
                PentecostToast.show(Component.literal(title), Component.literal(body));
            }
            DevFeed.markSeen(fresh);
        });
    }
}
