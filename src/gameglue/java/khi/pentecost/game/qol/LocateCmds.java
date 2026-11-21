package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.StringArgumentType;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LocateCmds {
    private static final String C = "Locate";

    private LocateCmds() {}

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    private static List<ServerPlayer> others(ServerPlayer me) {
        List<ServerPlayer> list = new ArrayList<>();
        for (ServerPlayer p : me.getServer().getPlayerList().getPlayers()) {
            if (p != me && p.level() == me.level()) {
                list.add(p);
            }
        }
        list.sort(Comparator.comparingDouble(me::distanceToSqr));
        return list;
    }

    static void add() {
        Qol.none(C, "coords", 0, "Show where you are", c -> {
            ServerPlayer p = Qol.me(c);
            return Qol.ok(c, "You are at " + Where.describe(Where.of(p)) + ", facing " + p.getDirection().getName() + ".");
        });

        Qol.playerReq(C, "where", 2, "Show where a player is", (c, t) ->
                Qol.ok(c, name(t) + " is at " + Where.describe(Where.of(t)) + "."));

        Qol.where(C, "distance", 2, "How far you are from a player, a place or x y z", (c, s) -> {
            Where.Spot me = Where.of(c.getSource());
            if (me.level() != s.level()) {
                throw new Bad("That is in " + Where.dimension(s.level()) + ", not here.");
            }
            double d = me.pos().distanceTo(s.pos());
            double flat = Math.sqrt(Math.pow(me.pos().x - s.pos().x, 2) + Math.pow(me.pos().z - s.pos().z, 2));
            return Qol.ok(c, Math.round(d) + " blocks away (" + Math.round(flat) + " sideways, "
                    + Math.round(Math.abs(me.pos().y - s.pos().y)) + " up or down).");
        });

        Qol.where(C, "bearing", 0, "Which way a player, a place or x y z is from where you face", (c, s) ->
                Qol.ok(c, Where.describe(s) + ": " + Tracker.relative(Qol.me(c), s)));

        Qol.none(C, "biome", 0, "Show the biome you are in", c -> {
            ServerPlayer p = Qol.me(c);
            String b = p.serverLevel().getBiome(p.blockPosition()).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
            return Qol.ok(c, "You are in " + b + ".");
        });

        Qol.none(C, "light", 0, "Show the light level where you stand", c -> {
            ServerPlayer p = Qol.me(c);
            ServerLevel l = p.serverLevel();
            BlockPos bp = p.blockPosition();
            return Qol.ok(c, "Block light " + l.getBrightness(LightLayer.BLOCK, bp) + ", sky light " + l.getBrightness(LightLayer.SKY, bp)
                    + ". Hostile mobs can spawn on dark blocks at block light 0.");
        });

        Qol.none(C, "here", 0, "Tell everyone where you are", c -> {
            ServerPlayer p = Qol.me(c);
            Qol.say(c.getSource().getServer(), name(p) + " is at " + Where.describe(Where.of(p)) + ".");
            return 1;
        });

        Qol.playerReq(C, "share", 0, "Send your position to a player", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            t.sendSystemMessage(Component.literal(name(me) + " shared their position: " + Where.describe(Where.of(me)) + "."));
            return Qol.ok(c, "Sent your position to " + name(t) + ".");
        });

        Qol.integer(C, "nearby", 0, "[radius]", "List players near you (default 100 blocks)", 1, 5000, true, (c, n) -> {
            ServerPlayer me = Qol.me(c);
            double r = n == null ? 100 : n;
            List<String> out = new ArrayList<>();
            for (ServerPlayer p : others(me)) {
                double d = Math.sqrt(me.distanceToSqr(p));
                if (d <= r) {
                    out.add(name(p) + " " + Math.round(d));
                }
            }
            return Qol.ok(c, out.isEmpty() ? "Nobody within " + Math.round(r) + " blocks." : "Nearby (blocks): " + String.join(", ", out));
        });

        Qol.none(C, "nearest", 0, "Find the nearest other player", c -> {
            ServerPlayer me = Qol.me(c);
            List<ServerPlayer> list = others(me);
            if (list.isEmpty()) {
                throw new Bad("Nobody else is in this dimension.");
            }
            ServerPlayer p = list.get(0);
            return Qol.ok(c, name(p) + " is nearest: " + Tracker.relative(me, Where.of(p)) + ".");
        });

        Qol.none(C, "farthest", 0, "Find the farthest other player in this dimension", c -> {
            ServerPlayer me = Qol.me(c);
            List<ServerPlayer> list = others(me);
            if (list.isEmpty()) {
                throw new Bad("Nobody else is in this dimension.");
            }
            ServerPlayer p = list.get(list.size() - 1);
            return Qol.ok(c, name(p) + " is farthest: " + Tracker.relative(me, Where.of(p)) + ".");
        });

        Qol.custom(C, "track", 0, "<where>", "Keep pointing at a player or place in your action bar (/untrack stops)",
                Qol.lit("track", 0).then(Commands.argument("where", StringArgumentType.greedyString()).suggests(Where::suggest)
                        .executes(Qol.guard(c -> {
                            String text = StringArgumentType.getString(c, "where").trim();
                            Where.resolve(c.getSource(), text);
                            Tracker.set(Qol.me(c).getUUID(), text);
                            return Qol.ok(c, "Tracking " + text + ". /untrack to stop.");
                        }))));

        Qol.none(C, "untrack", 0, "Stop tracking", c -> {
            if (!Tracker.stop(Qol.me(c).getUUID())) {
                throw new Bad("You are not tracking anything.");
            }
            return Qol.ok(c, "Stopped tracking.");
        });

        Qol.none(C, "slimechunk", 0, "Is this a slime chunk?", c -> {
            ServerPlayer p = Qol.me(c);
            ServerLevel l = p.serverLevel();
            if (l.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
                throw new Bad("Slime chunks only matter in the Overworld.");
            }
            int cx = p.chunkPosition().x;
            int cz = p.chunkPosition().z;
            boolean slime = WorldgenRandom.seedSlimeChunk(cx, cz, l.getSeed(), 987234911L).nextInt(10) == 0;
            return Qol.ok(c, "Chunk " + cx + " " + cz + (slime ? " is a slime chunk." : " is not a slime chunk."));
        });

        Qol.none(C, "chunk", 0, "Show your chunk and region file", c -> {
            ServerPlayer p = Qol.me(c);
            int cx = p.chunkPosition().x;
            int cz = p.chunkPosition().z;
            return Qol.ok(c, "Chunk " + cx + " " + cz + ", covering blocks " + (cx * 16) + " " + (cz * 16) + " to "
                    + (cx * 16 + 15) + " " + (cz * 16 + 15) + ". Region file r." + (cx >> 5) + "." + (cz >> 5) + ".mca.");
        });
    }
}
