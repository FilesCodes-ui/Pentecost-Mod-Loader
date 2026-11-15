package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.core.JoinApprovals;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps out anyone the host has not said yes to.
 *
 * Every fifth of a second it looks at who is in the world. The host is always welcome. Anyone else goes to
 * JoinApprovals: approved accounts stay, everyone else is disconnected with a short reason and, the first time,
 * the host is asked. By then Mojang has verified the account, so the name the host sees belongs to a real
 * account and a friend's name cannot be borrowed. A turned-away player is in the world for a fraction of a
 * second, long enough to be sent home and not long enough to do anything.
 */
final class JoinGate {
    private static final String WAIT = "The host has to say yes first, and was just asked. Try again in a minute.";
    private static final String NO = "The host did not let you in.";
    private static final String FULL = "Too many people are asking to join this world right now. Try again later.";

    private final IntegratedServer server;
    private final JoinApprovals approvals;
    private final ScheduledExecutorService timer;
    private volatile boolean stopped;

    private JoinGate(IntegratedServer server, JoinApprovals approvals) {
        this.server = server;
        this.approvals = approvals;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Pentecost WWLS join gate");
            t.setDaemon(true);
            return t;
        });
    }

    static JoinGate start(IntegratedServer server, JoinApprovals approvals) {
        JoinGate gate = new JoinGate(server, approvals);
        gate.timer.scheduleWithFixedDelay(gate::tick, 200, 200, TimeUnit.MILLISECONDS);
        return gate;
    }

    void stop() {
        stopped = true;
        timer.shutdownNow();
    }

    private void tick() {
        if (stopped) {
            return;
        }
        try {
            if (!server.isRunning()) {
                stop();
                return;
            }
            server.execute(this::scan);
        } catch (RuntimeException e) {
            Log.warn("WWLS join gate: " + e);
        }
    }

    /** Runs on the server's own thread, where the player list may be read. */
    private void scan() {
        if (stopped) {
            return;
        }
        try {
            for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
                if (server.isSingleplayerOwner(p.getGameProfile())) {
                    continue;
                }
                JoinApprovals.Decision d = approvals.check(p.getUUID(), p.getGameProfile().getName());
                if (d == JoinApprovals.Decision.ALLOW) {
                    continue;
                }
                String why = switch (d) {
                    case ASK -> WAIT;
                    case DENY -> NO;
                    default -> FULL;
                };
                p.connection.disconnect(Component.literal(why));
            }
        } catch (RuntimeException e) {
            Log.warn("WWLS join gate: " + e);
        }
    }
}
