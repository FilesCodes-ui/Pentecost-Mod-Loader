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

final class JoinGate {
    private static final String WAIT = "The host was just asked to let you in. When they say yes, press Join again.";
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
