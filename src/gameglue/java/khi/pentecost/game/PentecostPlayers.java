package khi.pentecost.game;

import io.netty.buffer.Unpooled;
import khi.pentecost.core.BadgeHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PentecostPlayers implements BadgeHooks.Impl {
    private static final ResourceLocation HI = new ResourceLocation("pentecost", "hi");
    private static final ResourceLocation HELLO = new ResourceLocation("pentecost", "hello");
    private static final ResourceLocation LIST = new ResourceLocation("pentecost", "players");
    private static final ResourceLocation DEV_ASK = new ResourceLocation("pentecost", "devask");
    private static final ResourceLocation DEV_PROOF = new ResourceLocation("pentecost", "devproof");
    private static final int MAX = 256;
    private static final String PURPOSE = "badge";

    private static final Set<UUID> PROVEN = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<UUID, byte[]> PENDING = new ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Integer> RELAYED = new ConcurrentHashMap<>();
    private static long relayWindow;

    private static volatile Set<UUID> known = Set.of();
    private static final Set<UUID> ANSWERED = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> GREETED = ConcurrentHashMap.newKeySet();
    private static volatile ClientPacketListener answeredOn;
    private static long nextGreet;

    static void install() {
        BadgeHooks.impl = new PentecostPlayers();
        Thread t = new Thread(khi.pentecost.auth.DevKey::held, "pentecost-devkey");
        t.setDaemon(true);
        t.start();
    }

    static boolean provenDev(UUID id) {
        return id != null && answeredOn != null && answeredOn == Minecraft.getInstance().getConnection() && PROVEN.contains(id);
    }

    static boolean has(UUID id) {
        if (answeredOn == null || answeredOn != Minecraft.getInstance().getConnection()) {
            return false;
        }
        return id != null && known.contains(id);
    }

    @Override
    public boolean server(Object listener, Object packet) {
        if (!(packet instanceof ServerboundCustomPayloadPacket p) || !(listener instanceof ServerGamePacketListenerImpl l)) {
            return false;
        }
        if (DEV_ASK.equals(p.getIdentifier()) || DEV_PROOF.equals(p.getIdentifier())) {
            relay(l.player, p);
            return true;
        }
        if (!HELLO.equals(p.getIdentifier())) {
            return false;
        }
        ServerPlayer player = l.player;
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.execute(() -> {
                if (ANSWERED.size() < MAX) {
                    ANSWERED.add(player.getUUID());
                }
                sendList(server);
            });
        }
        return true;
    }

    @Override
    public boolean client(Object listener, Object packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket p)) {
            return false;
        }
        ResourceLocation id = p.getIdentifier();
        if (HI.equals(id)) {
            if (listener instanceof ClientPacketListener c && answeredOn != c) {
                answeredOn = c;
                Minecraft.getInstance().execute(() -> c.send(new ServerboundCustomPayloadPacket(HELLO, new FriendlyByteBuf(Unpooled.buffer()))));
            }
            release(p);
            return true;
        }
        if (LIST.equals(id)) {
            FriendlyByteBuf buf = p.getData();
            Set<UUID> next = new HashSet<>();
            try {
                int n = Math.min(MAX, buf.readVarInt());
                for (int i = 0; i < n; i++) {
                    next.add(buf.readUUID());
                }
                known = Set.copyOf(next);
            } catch (RuntimeException e) {
            }
            release(p);
            if (listener instanceof ClientPacketListener c) {
                askDevs(c);
            }
            return true;
        }
        if (DEV_ASK.equals(id)) {
            FriendlyByteBuf buf = p.getData();
            try {
                UUID asker = buf.readUUID();
                byte[] challenge = buf.readByteArray(64);
                if (listener instanceof ClientPacketListener c && PentecostDev.ownShowsTag()) {
                    UUID self = Minecraft.getInstance().getUser().getProfileId();
                    byte[] sig = khi.pentecost.auth.DevKey.prove(challenge, self, PURPOSE);
                    if (sig != null) {
                        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
                        out.writeUUID(asker);
                        out.writeByteArray(challenge);
                        out.writeByteArray(sig);
                        Minecraft.getInstance().execute(() -> c.send(new ServerboundCustomPayloadPacket(DEV_PROOF, out)));
                    }
                }
            } catch (RuntimeException ignored) {
            }
            release(p);
            return true;
        }
        if (DEV_PROOF.equals(id)) {
            FriendlyByteBuf buf = p.getData();
            try {
                UUID from = buf.readUUID();
                byte[] challenge = buf.readByteArray(64);
                byte[] sig = buf.readByteArray(1024);
                byte[] asked = PENDING.get(from);
                if (asked != null && java.util.Arrays.equals(asked, challenge)
                        && khi.pentecost.auth.DevKey.verify(khi.pentecost.auth.DevKey.proofMessage(challenge, from, PURPOSE), sig)) {
                    PENDING.remove(from);
                    PROVEN.add(from);
                }
            } catch (RuntimeException ignored) {
            }
            release(p);
            return true;
        }
        return false;
    }

    private static void askDevs(ClientPacketListener c) {
        UUID self = Minecraft.getInstance().getUser().getProfileId();
        for (UUID id : known) {
            if (!PentecostDev.isOwnerId(id) || id.equals(self) || PROVEN.contains(id) || PENDING.containsKey(id)) {
                continue;
            }
            byte[] challenge = khi.pentecost.auth.DevKey.challenge();
            PENDING.put(id, challenge);
            FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
            out.writeUUID(id);
            out.writeByteArray(challenge);
            Minecraft.getInstance().execute(() -> c.send(new ServerboundCustomPayloadPacket(DEV_ASK, out)));
        }
    }

    private static void relay(ServerPlayer sender, ServerboundCustomPayloadPacket p) {
        MinecraftServer server = sender.getServer();
        FriendlyByteBuf in = p.getData();
        UUID to;
        byte[] challenge;
        byte[] sig = null;
        boolean ask = DEV_ASK.equals(p.getIdentifier());
        try {
            to = in.readUUID();
            challenge = in.readByteArray(64);
            if (!ask) {
                sig = in.readByteArray(1024);
            }
        } catch (RuntimeException e) {
            return;
        } finally {
            try {
                if (in.refCnt() > 0) {
                    in.release();
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (server == null) {
            return;
        }
        byte[] s = sig;
        server.execute(() -> {
            long now = System.currentTimeMillis();
            if (now - relayWindow > 10_000) {
                relayWindow = now;
                RELAYED.clear();
            }
            if (RELAYED.merge(sender.getUUID(), 1, Integer::sum) > 8) {
                return;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(to);
            if (target == null) {
                return;
            }
            FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
            out.writeUUID(sender.getUUID());
            out.writeByteArray(challenge);
            if (!ask) {
                out.writeByteArray(s);
            }
            target.connection.send(new ClientboundCustomPayloadPacket(ask ? DEV_ASK : DEV_PROOF, out));
        });
    }

    private static void release(ClientboundCustomPayloadPacket p) {
        try {
            FriendlyByteBuf data = p.getData();
            if (data.refCnt() > 0) {
                data.release();
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void serverTick(Object serverObject) {
        if (!(serverObject instanceof MinecraftServer server)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextGreet) {
            return;
        }
        nextGreet = now + 1000;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.add(p.getUUID());
            if (GREETED.add(p.getUUID())) {
                p.connection.send(new ClientboundCustomPayloadPacket(HI, new FriendlyByteBuf(Unpooled.buffer())));
            }
        }
        GREETED.retainAll(online);
        if (ANSWERED.retainAll(online)) {
            sendList(server);
        }
    }

    private static void sendList(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!ANSWERED.contains(p.getUUID())) {
                continue;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeVarInt(ANSWERED.size());
            for (UUID id : ANSWERED) {
                buf.writeUUID(id);
            }
            p.connection.send(new ClientboundCustomPayloadPacket(LIST, buf));
        }
    }

    static void clear() {
        known = Set.of();
        answeredOn = null;
        PROVEN.clear();
        PENDING.clear();
    }
}
