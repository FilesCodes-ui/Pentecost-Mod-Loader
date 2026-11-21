package khi.pentecost.messenger;

import khi.pentecost.control.Settings;

public final class Threads {
    private static final long SERVER_BUDGET = 4_000_000;
    private static final long GAME_BUDGET = 2_000_000;

    private static volatile long checkedAt;
    private static volatile boolean paths = true;
    private static Object lastServer;

    private Threads() {}

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 1000) {
            return;
        }
        checkedAt = now;
        Messenger.get().setOn(Settings.on(Settings.MESSENGER));
        paths = Settings.on(Settings.ASYNC_PATHS);
    }

    public static boolean paths() {
        return paths && Messenger.get().on();
    }

    public static void serverTick(Object server) {
        refresh();
        Messenger m = Messenger.get();
        if (server != lastServer) {
            lastServer = server;
            m.drop(Lane.SERVER);
        }
        m.pump(Lane.SERVER, SERVER_BUDGET);
    }

    public static void frame() {
        refresh();
        Messenger.get().pump(Lane.GAME, GAME_BUDGET);
    }
}
