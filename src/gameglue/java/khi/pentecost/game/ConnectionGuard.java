package khi.pentecost.game;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.local.LocalServerChannel;
import khi.pentecost.boot.Log;
import khi.pentecost.core.ConnectionLimiter;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;

/**
 * Puts a ConnectionLimiter in front of the world's network listener, so a connection that the limiter refuses
 * is closed before the game spends anything on it (no login, no ping answer, no player slot).
 * It sees each new connection the moment the listener accepts it.
 *
 * It also does the opposite job: stopListening closes that listener when sharing ends, so nobody new can
 * connect, from the internet or from the home network. Players already inside stay until they leave.
 * The game's own in-memory connection for the host is never touched.
 */
final class ConnectionGuard {
    private static final String NAME = "pentecost-guard";

    private ConnectionGuard() {}

    /** Throws when the listener cannot be found, so a world is never opened to the internet without the guard. */
    static void install(MinecraftServer server, ConnectionLimiter limiter) {
        ServerConnectionListener listener = server.getConnection();
        if (listener == null) {
            throw new IllegalStateException("The world has no network listener to protect.");
        }
        List<?> channels = findChannels(listener);
        int guarded = 0;
        synchronized (channels) {
            for (Object o : channels) {
                Channel serverChannel = ((ChannelFuture) o).channel();
                if (serverChannel instanceof LocalServerChannel) {
                    continue; // the host's own connection inside the game
                }
                if (serverChannel.pipeline().get(NAME) == null) {
                    serverChannel.pipeline().addFirst(NAME, new Gate(limiter));
                }
                guarded++;
            }
        }
        if (guarded == 0) {
            throw new IllegalStateException("The world has no open listener to protect.");
        }
        Log.info("WWLS connection guard on " + guarded + " listener(s)");
    }

    /**
     * Closes every network listener of the world except the host's own, and makes the game treat the world as
     * not open to others again. Safe to call more than once and when the world has already stopped.
     */
    static void stopListening(IntegratedServer server, int port) {
        try {
            if (!server.isRunning()) {
                return;
            }
            ServerConnectionListener listener = server.getConnection();
            if (listener != null) {
                List<?> channels = findChannels(listener);
                synchronized (channels) {
                    Iterator<?> it = channels.iterator();
                    while (it.hasNext()) {
                        Channel c = ((ChannelFuture) it.next()).channel();
                        if (c instanceof LocalServerChannel) {
                            continue;
                        }
                        c.close().awaitUninterruptibly(1500);
                        it.remove();
                    }
                }
            }
            forgetPublished(server, port);
            Log.info("WWLS stopped listening on port " + port);
        } catch (RuntimeException e) {
            Log.warn("WWLS could not stop the listener: " + e);
        }
    }

    /**
     * The integrated server remembers the port it was opened on and keeps announcing the world on the home network.
     * Clear both so the world looks closed and can be opened again. Fields are found by type and value, since
     * their names depend on how the game is mapped. Anything unexpected is left alone.
     */
    private static void forgetPublished(IntegratedServer server, int port) {
        try {
            Field only = null;
            int matches = 0;
            for (Field f : IntegratedServer.class.getDeclaredFields()) {
                if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    if (f.getInt(server) == port) {
                        only = f;
                        matches++;
                    }
                }
            }
            if (matches == 1) {
                only.setInt(server, -1);
            } else {
                Log.warn("WWLS could not reset the published port (" + matches + " candidates)");
            }
            for (Field f : IntegratedServer.class.getDeclaredFields()) {
                if (f.getType() == LanServerPinger.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    Object pinger = f.get(server);
                    if (pinger instanceof LanServerPinger p) {
                        p.interrupt();
                        f.set(server, null);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("WWLS could not reset the world's open state: " + e);
        }
    }

    /** The listener keeps its open server channels in a list of ChannelFuture. Found by its content, not by a field name. */
    private static List<?> findChannels(ServerConnectionListener listener) {
        for (Field f : ServerConnectionListener.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(listener);
                if (value instanceof List<?> list && !list.isEmpty()) {
                    boolean all = true;
                    synchronized (list) {
                        for (Object o : list) {
                            all &= o instanceof ChannelFuture;
                        }
                    }
                    if (all) {
                        return list;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // try the next field
            }
        }
        throw new IllegalStateException("The world's network listener has an unexpected shape.");
    }

    /** Sees every accepted connection before the game does. */
    @ChannelHandler.Sharable
    static final class Gate extends ChannelInboundHandlerAdapter {
        private final ConnectionLimiter limiter;

        Gate(ConnectionLimiter limiter) {
            this.limiter = limiter;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel child) {
                String ip = ConnectionLimiter.key(child.remoteAddress());
                if (limiter.admit(ip) != ConnectionLimiter.Verdict.ALLOW) {
                    child.unsafe().closeForcibly();
                    return;
                }
                child.closeFuture().addListener(f -> limiter.released(ip));
            }
            ctx.fireChannelRead(msg);
        }
    }
}
