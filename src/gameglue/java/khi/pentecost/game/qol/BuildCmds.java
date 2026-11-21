package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BuildCmds {
    private static final String C = "Build";
    private static final int LIMIT = 250_000;

    private static final class Sel {
        ServerLevel level;
        BlockPos a;
        BlockPos b;
    }

    private static final class Job {
        final ServerLevel level;
        final List<BlockPos> at = new ArrayList<>();
        final List<BlockState> old = new ArrayList<>();
        int changed;

        Job(ServerLevel level) {
            this.level = level;
        }

        void set(BlockPos bp, BlockState st) {
            if (level.isOutsideBuildHeight(bp)) {
                return;
            }
            BlockState cur = level.getBlockState(bp);
            if (cur == st) {
                return;
            }
            at.add(bp.immutable());
            old.add(cur);
            level.setBlock(bp, st, 2);
            changed++;
        }
    }

    private record Clip(List<BlockPos> offsets, List<BlockState> states) {}

    private static final Map<UUID, Sel> SELS = new HashMap<>();
    private static final Map<UUID, Deque<Job>> UNDO = new HashMap<>();
    private static final Map<UUID, Clip> CLIPS = new HashMap<>();
    private static final Map<UUID, Long> AWAY = new HashMap<>();
    private static final long KEEP_MS = 30L * 60_000L;
    private static int sinceSweep;

    static final SuggestionProvider<CommandSourceStack> BLOCKS = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.BLOCK.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);

    private BuildCmds() {}

    static void clear() {
        SELS.clear();
        UNDO.clear();
        CLIPS.clear();
        AWAY.clear();
        sinceSweep = 0;
    }

    static void tick(MinecraftServer server) {
        if (++sinceSweep < 1200) {
            return;
        }
        sinceSweep = 0;
        Set<UUID> holders = new HashSet<>(SELS.keySet());
        holders.addAll(UNDO.keySet());
        holders.addAll(CLIPS.keySet());
        long now = System.currentTimeMillis();
        for (UUID id : holders) {
            if (server.getPlayerList().getPlayer(id) != null) {
                AWAY.remove(id);
                continue;
            }
            Long since = AWAY.putIfAbsent(id, now);
            if (since != null && now - since > KEEP_MS) {
                SELS.remove(id);
                UNDO.remove(id);
                CLIPS.remove(id);
                AWAY.remove(id);
            }
        }
        AWAY.keySet().retainAll(holders);
    }

    private static BlockState block(String s) {
        ResourceLocation rl = ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s.toLowerCase(Locale.ROOT));
        if (rl == null) {
            throw new Bad("\"" + s + "\" is not a valid block name.");
        }
        return BuiltInRegistries.BLOCK.getOptional(rl).orElseThrow(() -> new Bad("There is no block called " + s + ".")).defaultBlockState();
    }

    private static Sel sel(ServerPlayer p) {
        Sel s = SELS.get(p.getUUID());
        if (s == null || s.a == null || s.b == null) {
            throw new Bad("Pick two corners first with /pos1 and /pos2.");
        }
        return s;
    }

    private static long volume(BlockPos a, BlockPos b) {
        return (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    private static Sel limited(ServerPlayer p) {
        Sel s = sel(p);
        if (volume(s.a, s.b) > LIMIT) {
            throw new Bad("That is " + volume(s.a, s.b) + " blocks. The limit is " + LIMIT + ".");
        }
        return s;
    }

    private static int finish(ServerPlayer p, Job j) {
        if (j.changed > 0) {
            Deque<Job> stack = UNDO.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
            stack.push(j);
            while (stack.size() > 10) {
                stack.removeLast();
            }
        }
        return j.changed;
    }

    private static int done(CommandContext<CommandSourceStack> c, int n) {
        return Qol.ok(c, n + " block" + (n == 1 ? "" : "s") + " changed. /undo puts them back.");
    }

    private static BlockPos looked(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = Qol.self(src);
        HitResult hit = p.pick(300.0, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            throw new Bad("You are not looking at a block (it has to be within 300 blocks).");
        }
        return ((BlockHitResult) hit).getBlockPos();
    }

    private static int setCorner(CommandContext<CommandSourceStack> c, int which, BlockPos bp) throws CommandSyntaxException {
        ServerPlayer p = Qol.me(c);
        Sel s = SELS.computeIfAbsent(p.getUUID(), k -> new Sel());
        if (s.level != p.serverLevel() && s.level != null) {
            s.a = null;
            s.b = null;
        }
        s.level = p.serverLevel();
        if (which == 1) {
            s.a = bp;
        } else {
            s.b = bp;
        }
        String vol = s.a != null && s.b != null ? " The selection is " + volume(s.a, s.b) + " blocks." : "";
        return Qol.ok(c, "Corner " + which + " is " + bp.getX() + " " + bp.getY() + " " + bp.getZ() + "." + vol);
    }

    private static void corner(String name, int which) {
        Qol.custom(C, name, 2, "[where]", "Set corner " + which + " of your selection (the block you look at when left out)",
                Qol.lit(name, 2).executes(Qol.guard(c -> setCorner(c, which, looked(c.getSource()))))
                        .then(Commands.argument("where", StringArgumentType.greedyString()).suggests(Where::suggest)
                                .executes(Qol.guard(c -> setCorner(c, which,
                                        BlockPos.containing(Where.resolve(c.getSource(), StringArgumentType.getString(c, "where")).pos()))))));
    }

    private static int[] bounds(Sel s) {
        return new int[]{Math.min(s.a.getX(), s.b.getX()), Math.min(s.a.getY(), s.b.getY()), Math.min(s.a.getZ(), s.b.getZ()),
                Math.max(s.a.getX(), s.b.getX()), Math.max(s.a.getY(), s.b.getY()), Math.max(s.a.getZ(), s.b.getZ())};
    }

    private interface Pick {
        boolean at(int x, int y, int z, int[] b);
    }

    private static void boxCommand(String name, String desc, Pick pick) {
        Qol.word(C, name, 2, "<block>", desc, false, BLOCKS, (c, w) -> {
            ServerPlayer p = Qol.me(c);
            Sel s = limited(p);
            BlockState st = block(w);
            int[] b = bounds(s);
            Job j = new Job(s.level);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        if (pick.at(x, y, z, b)) {
                            j.set(m.set(x, y, z), st);
                        }
                    }
                }
            }
            return done(c, finish(p, j));
        });
    }

    private static int sphere(CommandContext<CommandSourceStack> c, boolean hollow) throws CommandSyntaxException {
        ServerPlayer p = Qol.me(c);
        BlockState st = block(StringArgumentType.getString(c, "word"));
        int r = IntegerArgumentType.getInteger(c, "n");
        BlockPos mid = p.blockPosition();
        Job j = new Job(p.serverLevel());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double outer = r * r + r * 0.5;
        double inner = (r - 1.0) * (r - 1.0) + (r - 1.0) * 0.5;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    double d = x * x + y * y + z * z;
                    if (d <= outer && (!hollow || d > inner)) {
                        j.set(m.set(mid.getX() + x, mid.getY() + y, mid.getZ() + z), st);
                    }
                }
            }
        }
        return done(c, finish(p, j));
    }

    private static void sphereCommand(String name, String desc, boolean hollow) {
        Qol.custom(C, name, 2, "<block> <radius>", desc, Qol.lit(name, 2)
                .then(Commands.argument("word", StringArgumentType.word()).suggests(BLOCKS)
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 40)).executes(Qol.guard(c -> sphere(c, hollow))))));
    }

    static void add() {
        corner("pos1", 1);
        corner("pos2", 2);

        Qol.none(C, "sel", 0, "Show your selection", c -> {
            Sel s = sel(Qol.me(c));
            return Qol.ok(c, s.a.getX() + " " + s.a.getY() + " " + s.a.getZ() + " to " + s.b.getX() + " " + s.b.getY() + " "
                    + s.b.getZ() + ", " + volume(s.a, s.b) + " blocks.");
        });

        Qol.none(C, "clearsel", 0, "Forget your selection", c -> {
            SELS.remove(Qol.me(c).getUUID());
            return Qol.ok(c, "Selection cleared.");
        });

        boxCommand("fillsel", "Fill your whole selection with a block", (x, y, z, b) -> true);
        boxCommand("wallsel", "Build only the four walls of your selection", (x, y, z, b) -> x == b[0] || x == b[3] || z == b[2] || z == b[5]);
        boxCommand("hollowsel", "Build a hollow box: walls, floor and roof", (x, y, z, b) ->
                x == b[0] || x == b[3] || z == b[2] || z == b[5] || y == b[1] || y == b[4]);
        boxCommand("floorsel", "Build only the floor of your selection", (x, y, z, b) -> y == b[1]);

        Qol.custom(C, "replacesel", 2, "<from> <to>", "Replace one block with another inside your selection",
                Qol.lit("replacesel", 2).then(Commands.argument("from", StringArgumentType.word()).suggests(BLOCKS)
                        .then(Commands.argument("to", StringArgumentType.word()).suggests(BLOCKS)
                                .executes(Qol.guard(c -> {
                                    ServerPlayer p = Qol.me(c);
                                    Sel s = limited(p);
                                    BlockState from = block(StringArgumentType.getString(c, "from"));
                                    BlockState to = block(StringArgumentType.getString(c, "to"));
                                    int[] b = bounds(s);
                                    Job j = new Job(s.level);
                                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                                    for (int x = b[0]; x <= b[3]; x++) {
                                        for (int y = b[1]; y <= b[4]; y++) {
                                            for (int z = b[2]; z <= b[5]; z++) {
                                                m.set(x, y, z);
                                                if (s.level.getBlockState(m).getBlock() == from.getBlock()) {
                                                    j.set(m, to);
                                                }
                                            }
                                        }
                                    }
                                    return done(c, finish(p, j));
                                })))));

        sphereCommand("sphere", "Build a solid sphere around you", false);
        sphereCommand("hsphere", "Build a hollow sphere around you", true);

        Qol.custom(C, "cyl", 2, "<block> <radius> <height>", "Build a solid cylinder standing on your spot",
                Qol.lit("cyl", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(BLOCKS)
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 50))
                                .then(Commands.argument("h", IntegerArgumentType.integer(1, 256)).executes(Qol.guard(c -> {
                                    ServerPlayer p = Qol.me(c);
                                    BlockState st = block(StringArgumentType.getString(c, "word"));
                                    int r = IntegerArgumentType.getInteger(c, "n");
                                    int h = IntegerArgumentType.getInteger(c, "h");
                                    if (Math.PI * r * r * h > LIMIT) {
                                        throw new Bad("That is too big. The limit is " + LIMIT + " blocks.");
                                    }
                                    BlockPos base = p.blockPosition();
                                    Job j = new Job(p.serverLevel());
                                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                                    for (int x = -r; x <= r; x++) {
                                        for (int z = -r; z <= r; z++) {
                                            if (x * x + z * z <= r * r + r * 0.5) {
                                                for (int y = 0; y < h; y++) {
                                                    j.set(m.set(base.getX() + x, base.getY() + y, base.getZ() + z), st);
                                                }
                                            }
                                        }
                                    }
                                    return done(c, finish(p, j));
                                }))))));

        Qol.word(C, "lineset", 2, "<block>", "Draw a straight line of blocks from corner 1 to corner 2", false, BLOCKS, (c, w) -> {
            ServerPlayer p = Qol.me(c);
            Sel s = sel(p);
            BlockState st = block(w);
            int dx = s.b.getX() - s.a.getX();
            int dy = s.b.getY() - s.a.getY();
            int dz = s.b.getZ() - s.a.getZ();
            int n = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            if (n > 2000) {
                throw new Bad("That line is longer than 2000 blocks.");
            }
            Job j = new Job(s.level);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int i = 0; i <= n; i++) {
                double t = n == 0 ? 0.0 : i / (double) n;
                j.set(m.set(s.a.getX() + Math.round(dx * t), s.a.getY() + Math.round(dy * t), s.a.getZ() + Math.round(dz * t)), st);
            }
            return done(c, finish(p, j));
        });

        Qol.none(C, "copysel", 2, "Copy your selection so /paste can place it", c -> {
            ServerPlayer p = Qol.me(c);
            Sel s = limited(p);
            int[] b = bounds(s);
            List<BlockPos> offsets = new ArrayList<>();
            List<BlockState> states = new ArrayList<>();
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        offsets.add(new BlockPos(x - b[0], y - b[1], z - b[2]));
                        states.add(s.level.getBlockState(new BlockPos(x, y, z)));
                    }
                }
            }
            CLIPS.put(p.getUUID(), new Clip(offsets, states));
            return Qol.ok(c, "Copied " + states.size() + " blocks. /paste places them with the low corner where you stand.");
        });

        Qol.none(C, "paste", 2, "Paste what /copysel copied, with its low corner where you stand", c -> {
            ServerPlayer p = Qol.me(c);
            Clip clip = CLIPS.get(p.getUUID());
            if (clip == null) {
                throw new Bad("Nothing is copied. Use /copysel first.");
            }
            BlockPos base = p.blockPosition();
            Job j = new Job(p.serverLevel());
            for (int i = 0; i < clip.offsets().size(); i++) {
                j.set(base.offset(clip.offsets().get(i)), clip.states().get(i));
            }
            return done(c, finish(p, j));
        });

        Qol.none(C, "countsel", 0, "Count the kinds of block in your selection", c -> {
            ServerPlayer p = Qol.me(c);
            Sel s = limited(p);
            int[] b = bounds(s);
            Map<String, Integer> counts = new HashMap<>();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        counts.merge(BuiltInRegistries.BLOCK.getKey(s.level.getBlockState(m.set(x, y, z)).getBlock()).getPath(), 1, Integer::sum);
                    }
                }
            }
            List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
            list.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                sb.append(i == 0 ? "" : ", ").append(list.get(i).getKey()).append(" ").append(list.get(i).getValue());
            }
            return Qol.ok(c, "In your selection: " + sb);
        });

        Qol.none(C, "undo", 2, "Undo your last building edit (the last 10 are kept)", c -> {
            ServerPlayer p = Qol.me(c);
            Deque<Job> stack = UNDO.get(p.getUUID());
            if (stack == null || stack.isEmpty()) {
                throw new Bad("There is nothing to undo.");
            }
            Job j = stack.pop();
            for (int i = j.at.size() - 1; i >= 0; i--) {
                j.level.setBlock(j.at.get(i), j.old.get(i), 2);
            }
            return Qol.ok(c, "Put back " + j.at.size() + " blocks.");
        });
    }
}
