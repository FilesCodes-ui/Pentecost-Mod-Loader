package khi.pentecost.messenger;

import khi.pentecost.control.Settings;

/**
 * Connects the messenger to the game's two threads and to the settings. The server calls serverTick at the start of
 * every tick and the game calls frame once a frame. Both read the switch once a second and pump their own lane, so
 * answers meant for the world are applied by the world's own thread, at a known point in the tick.
 */
public final class Threads {
    /** Most time the server spends a tick applying answers. The rest wait for the next tick. */
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

    /** True when mob path searches may run on the workers. */
    public static boolean paths() {
        return paths && Messenger.get().on();
    }

    /** Called by the server at the start of every tick, on the server thread. */
    public static void serverTick(Object server) {
        refresh();
        Messenger m = Messenger.get();
        if (server != lastServer) {
            // A new world: answers for the old one must not reach it.
            lastServer = server;
            m.drop(Lane.SERVER);
        }
        m.pump(Lane.SERVER, SERVER_BUDGET);
    }

    /** Called by the game once a frame, on the game thread. */
    public static void frame() {
        refresh();
        Messenger.get().pump(Lane.GAME, GAME_BUDGET);
    }
}
