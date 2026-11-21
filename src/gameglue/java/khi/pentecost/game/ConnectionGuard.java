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

final class ConnectionGuard {
    private static final String NAME = "pentecost-guard";

    private ConnectionGuard() {}

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
                    continue;
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
            }
        }
        throw new IllegalStateException("The world's network listener has an unexpected shape.");
    }

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
