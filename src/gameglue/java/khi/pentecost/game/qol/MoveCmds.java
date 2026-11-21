package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

final class MoveCmds {
    private static final String C = "Move";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");
    private static final Random RANDOM = new Random();

    private record Request(UUID from, long at, boolean here) {}

    private static final Map<UUID, Request> REQUESTS = new HashMap<>();
    private static final java.util.Set<UUID> NOTP = new java.util.HashSet<>();

    private static final SuggestionProvider<CommandSourceStack> HOMES = (c, b) -> {
        try {
            return SharedSuggestionProvider.suggest(Store.homeNames(Qol.self(c.getSource())), b);
        } catch (RuntimeException e) {
            return b.buildFuture();
        }
    };
    private static final SuggestionProvider<CommandSourceStack> WARPS = (c, b) -> SharedSuggestionProvider.suggest(Store.warpNames(), b);

    private MoveCmds() {}

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    private static String checked(String n, String fallback) {
        String v = n == null ? fallback : n;
        if (!NAME.matcher(v).matches()) {
            throw new Bad("Names use letters, numbers, _ and -, up to 24 characters.");
        }
        return v;
    }

    private static final SuggestionProvider<CommandSourceStack> BIOMES = (c, b) -> SharedSuggestionProvider.suggest(
            c.getSource().registryAccess().registryOrThrow(Registries.BIOME).keySet().stream()
                    .map(ResourceLocation::getPath).distinct().sorted(), b);

    private static int rtp(CommandContext<CommandSourceStack> c, int radius, int min, String biomeName) throws CommandSyntaxException {
        ServerPlayer p = Qol.me(c);
        ServerLevel l = p.serverLevel();
        if (min >= radius) {
            throw new Bad("The minimum distance has to be smaller than the radius.");
        }
        Registry<Biome> biomes = l.registryAccess().registryOrThrow(Registries.BIOME);
        ResourceKey<Biome> wanted = null;
        if (biomeName != null) {
            for (ResourceLocation id : biomes.keySet()) {
                if (id.getPath().equalsIgnoreCase(biomeName) || id.toString().equalsIgnoreCase(biomeName)) {
                    wanted = ResourceKey.create(Registries.BIOME, id);
                    break;
                }
            }
            if (wanted == null) {
                throw new Bad("There is no biome called " + biomeName + ". Press Tab to see them.");
            }
            Holder<Biome> h = biomes.getHolderOrThrow(wanted);
            if (h.is(BiomeTags.IS_OCEAN) || h.is(BiomeTags.IS_RIVER)) {
                throw new Bad("That biome is water, so there is no ground to stand on.");
            }
        }
        BiomeSource source = l.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = l.getChunkSource().randomState().sampler();
        int sx = p.getBlockX();
        int sz = p.getBlockZ();
        int loads = 0;
        for (int i = 0; i < 1500 && loads < 24; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt((double) min * min + RANDOM.nextDouble() * ((double) radius * radius - (double) min * min));
            int x = sx + (int) Math.round(Math.cos(angle) * dist);
            int z = sz + (int) Math.round(Math.sin(angle) * dist);
            if (!l.getWorldBorder().isWithinBounds(new BlockPos(x, 64, z))) {
                continue;
            }
            Holder<Biome> here = source.getNoiseBiome(x >> 2, 16, z >> 2, sampler);
            boolean fits = wanted != null ? here.is(wanted) : !(here.is(BiomeTags.IS_OCEAN) || here.is(BiomeTags.IS_RIVER));
            if (!fits) {
                continue;
            }
            loads++;
            int y = groundY(l, x, z);
            if (!standable(l, x, y, z)) {
                continue;
            }
            Where.Spot s = new Where.Spot(l, new Vec3(x + 0.5, y, z + 0.5), p.getYRot(), 0.0F);
            Qol.go(p, s);
            long far = Math.round(Math.hypot(x - sx, z - sz));
            String place = prettyBiome(l.getBiome(new BlockPos(x, y, z)));
            return Qol.ok(c, "Teleported " + String.format("%,d", far) + " blocks " + compass(x - sx, z - sz) + " to "
                    + Where.describe(s) + (place.isEmpty() ? "" : ", in " + place)
                    + ". /back returns you, /setwarp <n> saves the spot.");
        }
        throw new Bad(wanted != null
                ? "No " + biomeName + " turned up within " + radius + " blocks. Try a bigger radius."
                : "No dry land turned up. Try again, or a different radius.");
    }

    private static int groundY(ServerLevel l, int x, int z) {
        return l.dimensionType().hasCeiling() ? safeY(l, x, z) : l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static boolean standable(ServerLevel l, int x, int y, int z) {
        if (y <= l.getMinBuildHeight() + 1 || y >= l.getMaxBuildHeight() - 2) {
            return false;
        }
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos ground = feet.below();
        BlockState g = l.getBlockState(ground);
        BlockState f = l.getBlockState(feet);
        BlockState h = l.getBlockState(feet.above());
        if (g.isAir() || !g.getFluidState().isEmpty() || !f.getFluidState().isEmpty() || !h.getFluidState().isEmpty()) {
            return false;
        }
        if (g.is(Blocks.MAGMA_BLOCK) || g.is(Blocks.CACTUS) || g.is(Blocks.CAMPFIRE) || g.is(Blocks.SOUL_CAMPFIRE)
                || g.is(Blocks.POWDER_SNOW) || g.is(Blocks.SWEET_BERRY_BUSH) || g.is(Blocks.POINTED_DRIPSTONE)
                || g.is(Blocks.WITHER_ROSE) || g.is(BlockTags.FIRE)) {
            return false;
        }
        if (f.is(BlockTags.FIRE) || f.is(Blocks.CACTUS) || f.is(Blocks.SWEET_BERRY_BUSH) || f.is(Blocks.COBWEB)) {
            return false;
        }
        return f.getCollisionShape(l, feet).isEmpty() && h.getCollisionShape(l, feet.above()).isEmpty();
    }

    private static String prettyBiome(Holder<Biome> h) {
        return h.unwrapKey().map(k -> {
            StringBuilder out = new StringBuilder();
            for (String w : k.location().getPath().split("_")) {
                if (!w.isEmpty()) {
                    out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                }
            }
            return out.toString();
        }).orElse("");
    }

    private static String compass(int dx, int dz) {
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        double deg = (Math.toDegrees(Math.atan2(dx, -dz)) + 360.0) % 360.0;
        return names[(int) Math.round(deg / 45.0) % 8];
    }
    static int safeY(ServerLevel l, int x, int z) {
        if (!l.dimensionType().hasCeiling()) {
            return l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }
        int top = Math.min(l.getMaxBuildHeight() - 2, l.getMinBuildHeight() + l.getLogicalHeight() - 2);
        for (int y = top; y > l.getMinBuildHeight() + 1; y--) {
            BlockPos bp = new BlockPos(x, y, z);
            if (!l.getBlockState(bp.below()).isAir() && l.getFluidState(bp.below()).isEmpty()
                    && l.getBlockState(bp).isAir() && l.getBlockState(bp.above()).isAir()) {
                return y;
            }
        }
        return l.getSeaLevel();
    }

    private static void shortcut(String name, String key, String desc, int perm) {
        Qol.none(C, name, perm, desc, c -> {
            Where.Spot s = Where.resolve(c.getSource(), key);
            Qol.go(Qol.me(c), s);
            return Qol.ok(c, "Teleported to " + Where.describe(s) + ".");
        });
    }

    static void add() {
        Qol.where(C, "goto", 2, "Teleport to a player, a place or x y z (players work in any coordinate)", (c, s) -> {
            Qol.go(Qol.me(c), s);
            return Qol.ok(c, "Teleported to " + Where.describe(s) + ".");
        });

        Qol.playerReq(C, "bring", 2, "Bring a player to you (@a for everyone)", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (t == me) {
                return 0;
            }
            Qol.go(t, Where.of(me));
            return Qol.ok(c, "Brought " + name(t) + " to you.");
        });

        Qol.playerReq(C, "swap", 2, "Swap places with a player", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (t == me) {
                throw new Bad("That is you.");
            }
            Where.Spot mine = Where.of(me);
            Where.Spot theirs = Where.of(t);
            Qol.go(me, theirs);
            Qol.go(t, mine);
            return Qol.ok(c, "Swapped places with " + name(t) + ".");
        });

        Qol.custom(C, "sendto", 2, "<player> <where>", "Send a player (or @a) to a place",
                Qol.lit("sendto", 2).then(Commands.argument("player", EntityArgument.players())
                        .then(Commands.argument("where", StringArgumentType.greedyString()).suggests(Where::suggest)
                                .executes(Qol.guard(c -> {
                                    Where.Spot s = Where.resolve(c.getSource(), StringArgumentType.getString(c, "where"));
                                    int n = 0;
                                    for (ServerPlayer t : new java.util.ArrayList<>(EntityArgument.getPlayers(c, "player"))) {
                                        Qol.go(t, s);
                                        n++;
                                    }
                                    return Qol.ok(c, "Sent " + n + " player" + (n == 1 ? "" : "s") + " to " + Where.describe(s) + ".");
                                })))));

        Qol.none(C, "back", 0, "Go back to where you were before your last teleport", c -> {
            ServerPlayer p = Qol.me(c);
            Where.Spot s = Store.back(p);
            if (s == null) {
                throw new Bad("There is no earlier place to go back to.");
            }
            Qol.go(p, s);
            return Qol.ok(c, "Back at " + Where.describe(s) + ".");
        });

        Qol.none(C, "top", 2, "Go to the highest open spot above you", c -> {
            ServerPlayer p = Qol.me(c);
            ServerLevel l = p.serverLevel();
            BlockPos bp = p.blockPosition();
            int y = safeY(l, bp.getX(), bp.getZ());
            Qol.go(p, new Where.Spot(l, new Vec3(p.getX(), y, p.getZ()), p.getYRot(), p.getXRot()));
            return Qol.ok(c, "Moved to the top, y " + y + ".");
        });

        Qol.integer(C, "up", 2, "<blocks>", "Go up some blocks and stand on a glass block", 1, 256, false, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            ServerLevel l = p.serverLevel();
            BlockPos target = p.blockPosition().above(n);
            if (target.getY() >= l.getMaxBuildHeight() - 2) {
                throw new Bad("That is above the top of the world.");
            }
            if (l.getBlockState(target.below()).isAir()) {
                l.setBlockAndUpdate(target.below(), Blocks.GLASS.defaultBlockState());
            }
            Qol.go(p, new Where.Spot(l, new Vec3(p.getX(), target.getY(), p.getZ()), p.getYRot(), p.getXRot()));
            return Qol.ok(c, "Went up " + n + ".");
        });

        Qol.integer(C, "down", 2, "<blocks>", "Go down some blocks", 1, 256, false, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            ServerLevel l = p.serverLevel();
            double y = p.getY() - n;
            if (y < l.getMinBuildHeight() + 1) {
                throw new Bad("That is below the bottom of the world.");
            }
            Qol.go(p, new Where.Spot(l, new Vec3(p.getX(), y, p.getZ()), p.getYRot(), p.getXRot()));
            return Qol.ok(c, "Went down " + n + ".");
        });

        Qol.none(C, "jump", 2, "Teleport to the block you are looking at", c -> {
            Where.Spot s = Where.look(c.getSource());
            Qol.go(Qol.me(c), s);
            return Qol.ok(c, "Jumped to " + Where.describe(s) + ".");
        });

        shortcut("spawn", "spawn", "Go to the world spawn", 2);
        shortcut("bed", "bed", "Go to your bed or respawn anchor", 2);
        shortcut("death", "death", "Go to where you last died", 2);

        Qol.word(C, "home", 0, "[name]", "Go to a home (the one called home when no name is given)", true, HOMES, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            String key = checked(n, "home");
            Where.Spot s = Store.home(p, key);
            if (s == null) {
                throw new Bad("You have no home called " + key + ". /homes lists yours.");
            }
            Qol.go(p, s);
            return Qol.ok(c, "Welcome to " + key + ".");
        });
        Qol.word(C, "sethome", 0, "[name]", "Save your position as a home", true, HOMES, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            String key = checked(n, "home");
            if (!Store.setHome(p, key, Where.of(p))) {
                throw new Bad("You already have " + Store.MAX_HOMES + " homes. /delhome one first.");
            }
            return Qol.ok(c, "Home " + key + " saved at " + Where.describe(Where.of(p)) + ".");
        });
        Qol.word(C, "delhome", 0, "<name>", "Delete one of your homes", false, HOMES, (c, n) -> {
            if (!Store.delHome(Qol.me(c), n)) {
                throw new Bad("You have no home called " + n + ".");
            }
            return Qol.ok(c, "Home " + n + " deleted.");
        });
        Qol.none(C, "homes", 0, "List your homes", c -> {
            var names = Store.homeNames(Qol.me(c));
            if (names.isEmpty()) {
                return Qol.ok(c, "You have no homes. /sethome saves one.");
            }
            Qol.ok(c, "Your homes (" + names.size() + " of " + Store.MAX_HOMES + "):");
            for (String h : names) {
                Where.Spot hs = Store.home(Qol.me(c), h);
                Qol.ok(c, "  " + h + (hs == null ? " (its world is not loaded)" : ": " + Where.describe(hs)));
            }
            return names.size();
        });

        Qol.word(C, "warp", 0, "<name>", "Go to a warp the server has set", false, WARPS, (c, n) -> {
            Where.Spot s = Store.warp(n);
            if (s == null) {
                throw new Bad("There is no warp called " + n + ". /warps lists them.");
            }
            Qol.go(Qol.me(c), s);
            return Qol.ok(c, "Warped to " + n + ".");
        });
        Qol.word(C, "setwarp", 2, "<name>", "Save your position as a warp for everyone", false, WARPS, (c, n) -> {
            Store.setWarp(checked(n, null), Where.of(Qol.me(c)));
            return Qol.ok(c, "Warp " + n + " saved.");
        });
        Qol.word(C, "delwarp", 2, "<name>", "Delete a warp", false, WARPS, (c, n) -> {
            if (!Store.delWarp(n)) {
                throw new Bad("There is no warp called " + n + ".");
            }
            return Qol.ok(c, "Warp " + n + " deleted.");
        });
        Qol.none(C, "warps", 0, "List the warps", c -> {
            var names = Store.warpNames();
            if (names.isEmpty()) {
                return Qol.ok(c, "There are no warps yet.");
            }
            Qol.ok(c, "Warps (" + names.size() + "):");
            for (String w : names) {
                Where.Spot ws = Store.warp(w);
                Qol.ok(c, "  " + w + (ws == null ? " (its world is not loaded)" : ": " + Where.describe(ws)));
            }
            return names.size();
        });

        Qol.playerReq(C, "tpa", 0, "Ask a player if you may teleport to them", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (t == me) {
                throw new Bad("That is you.");
            }
            if (NOTP.contains(t.getUUID())) {
                throw new Bad(name(t) + " is not taking teleport requests.");
            }
            REQUESTS.put(t.getUUID(), new Request(me.getUUID(), System.currentTimeMillis(), false));
            t.sendSystemMessage(Component.literal(name(me) + " wants to teleport to you. /tpaccept to allow it, /tpdeny to refuse. It runs out in 60 seconds."));
            return Qol.ok(c, "Asked " + name(t) + ". They have 60 seconds to answer.");
        });
        Qol.none(C, "tpaccept", 0, "Accept the teleport request you were sent", c -> {
            ServerPlayer me = Qol.me(c);
            Request r = REQUESTS.remove(me.getUUID());
            if (r == null || System.currentTimeMillis() - r.at() > 60_000L) {
                throw new Bad("You have no teleport request waiting.");
            }
            ServerPlayer from = c.getSource().getServer().getPlayerList().getPlayer(r.from());
            if (from == null) {
                throw new Bad("That player has gone offline.");
            }
            if (r.here()) {
                Qol.go(me, Where.of(from));
            } else {
                Qol.go(from, Where.of(me));
            }
            from.sendSystemMessage(Component.literal(name(me) + " accepted. Teleporting."));
            return Qol.ok(c, "Accepted. " + name(from) + " is on the way.");
        });
        Qol.none(C, "tpdeny", 0, "Refuse the teleport request you were sent", c -> {
            ServerPlayer me = Qol.me(c);
            Request r = REQUESTS.remove(me.getUUID());
            if (r == null) {
                throw new Bad("You have no teleport request waiting.");
            }
            ServerPlayer from = c.getSource().getServer().getPlayerList().getPlayer(r.from());
            if (from != null) {
                from.sendSystemMessage(Component.literal(name(me) + " said no."));
            }
            return Qol.ok(c, "Refused.");
        });

        Qol.playerReq(C, "tpahere", 0, "Ask a player to teleport to you", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (t == me) {
                throw new Bad("That is you.");
            }
            if (NOTP.contains(t.getUUID())) {
                throw new Bad(name(t) + " is not taking teleport requests.");
            }
            REQUESTS.put(t.getUUID(), new Request(me.getUUID(), System.currentTimeMillis(), true));
            t.sendSystemMessage(Component.literal(name(me) + " wants you to teleport to them. /tpaccept to go, /tpdeny to refuse. It runs out in 60 seconds."));
            return Qol.ok(c, "Asked " + name(t) + " to come to you. They have 60 seconds to answer.");
        });
        Qol.none(C, "tpacancel", 0, "Cancel the teleport requests you sent", c -> {
            ServerPlayer me = Qol.me(c);
            int n = 0;
            for (var it = REQUESTS.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue().from().equals(me.getUUID())) {
                    it.remove();
                    n++;
                }
            }
            return Qol.ok(c, n == 0 ? "You have no requests out." : "Cancelled " + n + ".");
        });
        Qol.none(C, "tptoggle", 0, "Stop or start taking teleport requests", c -> {
            ServerPlayer me = Qol.me(c);
            boolean off = NOTP.add(me.getUUID());
            if (!off) {
                NOTP.remove(me.getUUID());
            }
            return Qol.ok(c, off ? "You are not taking teleport requests." : "You take teleport requests again.");
        });
        Qol.custom(C, "rtp", 2, "[radius] [min] | biome <name> [radius]",
                "Teleport to a random safe spot on dry land, at least a minimum distance away, or in a biome you pick",
                Qol.lit("rtp", 2)
                        .executes(Qol.guard(c -> rtp(c, 1000, 0, null)))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(50, 50000))
                                .executes(Qol.guard(c -> rtp(c, IntegerArgumentType.getInteger(c, "radius"), 0, null)))
                                .then(Commands.argument("min", IntegerArgumentType.integer(0, 49999))
                                        .executes(Qol.guard(c -> rtp(c, IntegerArgumentType.getInteger(c, "radius"),
                                                IntegerArgumentType.getInteger(c, "min"), null)))))
                        .then(Commands.literal("biome")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(BIOMES)
                                        .executes(Qol.guard(c -> rtp(c, 5000, 0, StringArgumentType.getString(c, "name"))))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(50, 50000))
                                                .executes(Qol.guard(c -> rtp(c, IntegerArgumentType.getInteger(c, "radius"), 0,
                                                        StringArgumentType.getString(c, "name")))))))
        );
        Qol.word(C, "dim", 2, "<overworld|nether|end>", "Go to the other dimension, at matching coordinates", false,
                Qol.options("overworld", "nether", "end"), (c, w) -> {
                    ServerPlayer p = Qol.me(c);
                    MinecraftServer server = c.getSource().getServer();
                    ServerLevel from = p.serverLevel();
                    ServerLevel to = switch (w.toLowerCase()) {
                        case "overworld" -> server.getLevel(Level.OVERWORLD);
                        case "nether" -> server.getLevel(Level.NETHER);
                        case "end" -> server.getLevel(Level.END);
                        default -> throw new Bad("Pick overworld, nether or end.");
                    };
                    if (to == null) {
                        throw new Bad("That dimension is not loaded.");
                    }
                    if (to == from) {
                        throw new Bad("You are already there.");
                    }
                    if (to.dimension() == Level.END) {
                        BlockPos at = ServerLevel.END_SPAWN_POINT;
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                BlockPos bp = at.offset(dx, -1, dz);
                                if (to.getBlockState(bp).isAir()) {
                                    to.setBlockAndUpdate(bp, Blocks.OBSIDIAN.defaultBlockState());
                                }
                            }
                        }
                        Qol.go(p, new Where.Spot(to, Vec3.atBottomCenterOf(at), p.getYRot(), 0.0F));
                        return Qol.ok(c, "Welcome to the End.");
                    }
                    double scale = from.dimension() == Level.NETHER ? 8.0 : to.dimension() == Level.NETHER ? 0.125 : 1.0;
                    int x = Mth.floor(p.getX() * scale);
                    int z = Mth.floor(p.getZ() * scale);
                    Where.Spot s = new Where.Spot(to, new Vec3(x + 0.5, safeY(to, x, z), z + 0.5), p.getYRot(), p.getXRot());
                    Qol.go(p, s);
                    return Qol.ok(c, "Moved to " + Where.describe(s) + ".");
                });

        Qol.integer(C, "launch", 2, "[power 1-20]", "Fling yourself upward", 1, 20, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            p.setDeltaMovement(p.getDeltaMovement().add(0.0, (n == null ? 5 : n) * 0.3, 0.0));
            p.hurtMarked = true;
            return Qol.ok(c, "Whoosh.");
        });
        Qol.integer(C, "dash", 2, "[power 1-20]", "Fling yourself the way you are looking", 1, 20, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            p.setDeltaMovement(p.getDeltaMovement().add(p.getLookAngle().scale((n == null ? 5 : n) * 0.4)));
            p.hurtMarked = true;
            return Qol.ok(c, "Dash.");
        });
        Qol.where(C, "lookat", 2, "Turn to face a player, a place or x y z", (c, s) -> {
            ServerPlayer p = Qol.me(c);
            Vec3 d = s.pos().subtract(p.getEyePosition());
            double flat = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Mth.atan2(d.z, d.x) * 57.29577951308232) - 90.0F;
            float pitch = (float) -(Mth.atan2(d.y, flat) * 57.29577951308232);
            p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), yaw, pitch);
            return Qol.ok(c, "Facing " + Where.describe(s) + ".");
        });
        Qol.word(C, "face", 2, "<north|south|east|west|up|down>", "Turn to face a direction", false,
                Qol.options("north", "south", "east", "west", "up", "down"), (c, w) -> {
                    ServerPlayer p = Qol.me(c);
                    float yaw = p.getYRot();
                    float pitch = 0.0F;
                    switch (w.toLowerCase()) {
                        case "south" -> yaw = 0.0F;
                        case "west" -> yaw = 90.0F;
                        case "north" -> yaw = 180.0F;
                        case "east" -> yaw = 270.0F;
                        case "up" -> pitch = -90.0F;
                        case "down" -> pitch = 90.0F;
                        default -> throw new Bad("Pick north, south, east, west, up or down.");
                    }
                    p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), yaw, pitch);
                    return Qol.ok(c, "Facing " + w + ".");
                });
    }
}
