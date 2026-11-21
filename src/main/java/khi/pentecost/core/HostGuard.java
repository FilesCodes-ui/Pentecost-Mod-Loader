package khi.pentecost.core;

import khi.pentecost.boot.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

public final class HostGuard {
    public static final long IDLE_MS = 30 * 60_000L;
    public static final int FLOOD_PAUSES = 3;
    public static final long FLOOD_WINDOW_MS = 10 * 60_000L;

    private final Wwls.Session session;
    private final IntSupplier friends;
    private final ConnectionLimiter limiter;
    private final LongSupplier clock = () -> System.nanoTime() / 1_000_000L;
    private final ScheduledExecutorService timer;
    private volatile String reason = "";
    private long lastActive;

    private HostGuard(Wwls.Session session, IntSupplier friends, ConnectionLimiter limiter) {
        this.session = session;
        this.friends = friends;
        this.limiter = limiter;
        this.lastActive = clock.getAsLong();
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Pentecost WWLS guard");
            t.setDaemon(true);
            return t;
        });
    }

    public static HostGuard start(Wwls.Session session, IntSupplier friends, ConnectionLimiter limiter) {
        HostGuard g = new HostGuard(session, friends, limiter);
        g.timer.scheduleWithFixedDelay(g::tick, 5, 5, TimeUnit.SECONDS);
        return g;
    }

    public ConnectionLimiter limiter() {
        return limiter;
    }

    public String reason() {
        return reason;
    }

    static String verdict(long quietMs, int friendsOnline, int recentPauses) {
        if (recentPauses >= FLOOD_PAUSES) {
            return "Sharing stopped: heavy traffic from the internet. Open the world again when it calms down, "
                    + "or use the playit.gg helper to hide your address.";
        }
        if (friendsOnline <= 0 && quietMs >= IDLE_MS) {
            return "Sharing stopped: nobody joined for " + (IDLE_MS / 60_000L) + " minutes.";
        }
        return null;
    }

    private void tick() {
        try {
            if (session.closed()) {
                timer.shutdown();
                return;
            }
            long now = clock.getAsLong();
            int online = friends.getAsInt();
            if (online > 0) {
                lastActive = now;
            }
            String why = verdict(now - lastActive, online, limiter.pausesWithin(FLOOD_WINDOW_MS));
            if (why != null) {
                reason = why;
                timer.shutdown();
                Log.info("WWLS: " + why);
                session.close();
            }
        } catch (RuntimeException e) {
            Log.warn("WWLS guard error: " + e);
        }
    }
}
