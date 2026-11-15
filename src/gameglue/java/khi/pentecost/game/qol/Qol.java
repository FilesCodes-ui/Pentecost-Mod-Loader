package khi.pentecost.game.qol;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.boot.Log;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pentecost's quality-of-life commands. They live in the game glue and are registered by the pregenerator's tick
 * (see Pregen), so they exist on a dedicated server, a LAN world and a WWLS world alike, with no client code.
 * Every command goes through the helpers below, which give it the same behaviour: a permission level, a mistake
 * that shows as a plain error line, and an entry in /qol so nothing is hidden.
 * Wherever a command takes a place, it takes a Where: a player, a named place, or x y z with players allowed
 * in any coordinate (see Where).
 */
public final class Qol {
    public interface Run { int run(CommandContext<CommandSourceStack> c) throws CommandSyntaxException; }
    public interface PlayerRun { int run(CommandContext<CommandSourceStack> c, ServerPlayer target) throws CommandSyntaxException; }
    public interface WordRun { int run(CommandContext<CommandSourceStack> c, String word) throws CommandSyntaxException; }
    public interface IntRun { int run(CommandContext<CommandSourceStack> c, Integer n) throws CommandSyntaxException; }
    public interface SpotRun { int run(CommandContext<CommandSourceStack> c, Where.Spot spot) throws CommandSyntaxException; }

    /** A mistake the player can fix. The message is shown as the command's error line. */
    public static final class Bad extends RuntimeException {
        public Bad(String message) {
            super(message, null, false, false);
        }
    }

    record Info(String cat, String name, String usage, String desc, int perm) {}

    static final List<Info> INFOS = new ArrayList<>();
    private static final List<LiteralArgumentBuilder<CommandSourceStack>> BUILT = new ArrayList<>();
    private static boolean initialised;
    private static MinecraftServer bound;
    static long startedAt;

    private Qol() {}

    // ---------------------------------------------------------------- install and tick

    /** Registers every command the server does not already have. Safe to call again and again. */
    public static void ensure(MinecraftServer server) {
        try {
            init();
            bind(server);
            var dispatcher = server.getCommands().getDispatcher();
            boolean added = false;
            for (LiteralArgumentBuilder<CommandSourceStack> b : BUILT) {
                if (dispatcher.getRoot().getChild(b.getLiteral()) == null) {
                    dispatcher.register(b);
                    added = true;
                }
            }
            if (added) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    server.getCommands().sendCommands(p);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Log.error("QOL commands: could not register: " + e);
        }
    }

    private static void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        MoveCmds.add();
        LocateCmds.add();
        PlayerCmds.add();
        ItemCmds.add();
        WorldCmds.add();
        MenuCmds.add();
        InfoCmds.add();
        ChatCmds.add();
        FunCmds.add();
        TimerCmds.add();
        AdminCmds.add();
        BuildCmds.add();
        RuleCmds.add();
        OpCmds.add();
        ServerCmds.add();
        Fun2Cmds.add();
        Admin2Cmds.add();
        help();
        Log.info("QOL commands ready: " + INFOS.size());
    }

    private static void bind(MinecraftServer server) {
        if (server != bound) {
            bound = server;
            startedAt = System.currentTimeMillis();
            Timers.clear();
            Tracker.clear();
            Admin2Cmds.clear();
            ServerCmds.reset();
            Store.bind(server);
        }
    }

    /** Called every server tick. */
    public static void tick(MinecraftServer server) {
        try {
            Timers.tick(server);
            Tracker.tick(server);
            RuleCmds.tick(server);
            ServerCmds.tick(server);
            Admin2Cmds.tick(server);
        } catch (RuntimeException e) {
            Log.warn("QOL tick: " + e);
        }
    }

    // ---------------------------------------------------------------- helpers for commands

    static LiteralArgumentBuilder<CommandSourceStack> lit(String name, int perm) {
        return Commands.literal(name).requires(s -> perm <= 0 || s.hasPermission(perm));
    }

    static Command<CommandSourceStack> guard(Run r) {
        return c -> {
            try {
                return r.run(c);
            } catch (Bad e) {
                c.getSource().sendFailure(Component.literal(e.getMessage()));
                return 0;
            } catch (RuntimeException e) {
                Log.error("QOL command failed: " + e);
                c.getSource().sendFailure(Component.literal("That did not work: " + e));
                return 0;
            }
        };
    }

    private static void add(LiteralArgumentBuilder<CommandSourceStack> b, String cat, String name, String usage, String desc, int perm) {
        BUILT.add(b);
        INFOS.add(new Info(cat, name, usage, desc, perm));
    }

    static int ok(CommandContext<CommandSourceStack> c, String message) {
        c.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    static void say(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    static ServerPlayer me(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return c.getSource().getPlayerOrException();
    }

    static final SuggestionProvider<CommandSourceStack> PLAYERS = (c, b) -> {
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
            names.add(p.getGameProfile().getName());
        }
        names.add("@a");
        names.add("@s");
        return SharedSuggestionProvider.suggest(names, b);
    };

    static SuggestionProvider<CommandSourceStack> options(String... values) {
        return (c, b) -> SharedSuggestionProvider.suggest(List.of(values), b);
    }

    /** "Steve", "*" or "@a" for everyone, "@s" or "me" for yourself, "@p" for the nearest other player. */
    static List<ServerPlayer> resolve(CommandSourceStack src, String who) throws CommandSyntaxException {
        MinecraftServer server = src.getServer();
        List<ServerPlayer> all = server.getPlayerList().getPlayers();
        String w = who.trim();
        if (w.equals("*") || w.equalsIgnoreCase("@a")) {
            return new ArrayList<>(all);
        }
        if (w.equalsIgnoreCase("@s") || w.equalsIgnoreCase("me")) {
            return List.of(src.getPlayerOrException());
        }
        if (w.equalsIgnoreCase("@r") && !all.isEmpty()) {
            return List.of(all.get(new java.util.Random().nextInt(all.size())));
        }
        if (w.equalsIgnoreCase("@p")) {
            ServerPlayer self = src.getPlayer();
            ServerPlayer best = null;
            double d = Double.MAX_VALUE;
            for (ServerPlayer p : all) {
                if (p != self && p.level() == src.getLevel() && p.distanceToSqr(src.getPosition()) < d) {
                    d = p.distanceToSqr(src.getPosition());
                    best = p;
                }
            }
            if (best != null) {
                return List.of(best);
            }
        }
        for (ServerPlayer p : all) {
            if (p.getGameProfile().getName().equalsIgnoreCase(w)) {
                return List.of(p);
            }
        }
        throw new Bad("No player named " + w + " is online.");
    }

    static ServerPlayer one(CommandSourceStack src, String who) throws CommandSyntaxException {
        List<ServerPlayer> list = resolve(src, who);
        return list.get(0);
    }

    private static int each(CommandContext<CommandSourceStack> c, String who, PlayerRun r) throws CommandSyntaxException {
        List<ServerPlayer> list = who == null ? List.of(c.getSource().getPlayerOrException()) : resolve(c.getSource(), who);
        int n = 0;
        for (ServerPlayer p : list) {
            n += r.run(c, p);
        }
        return n;
    }

    // ---------------------------------------------------------------- command shapes

    /** /name */
    static void none(String cat, String name, int perm, String desc, Run r) {
        add(lit(name, perm).executes(guard(r)), cat, name, "", desc, perm);
    }

    /** /name [player]: yourself when left out. "@a" and "*" mean everyone. */
    static void player(String cat, String name, int perm, String desc, PlayerRun r) {
        add(lit(name, perm).executes(guard(c -> each(c, null, r)))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(guard(c -> each(c, StringArgumentType.getString(c, "player"), r)))),
                cat, name, "[player]", desc, perm);
    }

    /** /name <player> */
    static void playerReq(String cat, String name, int perm, String desc, PlayerRun r) {
        add(lit(name, perm).then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(guard(c -> each(c, StringArgumentType.getString(c, "player"), r)))),
                cat, name, "<player>", desc, perm);
    }

    /** /name <text...> */
    static void text(String cat, String name, int perm, String usage, String desc, WordRun r) {
        add(lit(name, perm).then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(guard(c -> r.run(c, StringArgumentType.getString(c, "text"))))),
                cat, name, usage, desc, perm);
    }

    /** /name <word> or /name [word]. The word is null when it is left out. */
    static void word(String cat, String name, int perm, String usage, String desc, boolean optional,
                     SuggestionProvider<CommandSourceStack> suggestions, WordRun r) {
        LiteralArgumentBuilder<CommandSourceStack> b = lit(name, perm);
        if (optional) {
            b.executes(guard(c -> r.run(c, null)));
        }
        b.then(Commands.argument("word", StringArgumentType.word()).suggests(suggestions)
                .executes(guard(c -> r.run(c, StringArgumentType.getString(c, "word")))));
        add(b, cat, name, usage, desc, perm);
    }

    /** /name <n> or /name [n]. The number is null when it is left out. */
    static void integer(String cat, String name, int perm, String usage, String desc, int min, int max, boolean optional, IntRun r) {
        LiteralArgumentBuilder<CommandSourceStack> b = lit(name, perm);
        if (optional) {
            b.executes(guard(c -> r.run(c, null)));
        }
        b.then(Commands.argument("n", IntegerArgumentType.integer(min, max))
                .executes(guard(c -> r.run(c, IntegerArgumentType.getInteger(c, "n")))));
        add(b, cat, name, usage, desc, perm);
    }

    /** /name <where...> */
    static void where(String cat, String name, int perm, String desc, SpotRun r) {
        add(lit(name, perm).then(Commands.argument("where", StringArgumentType.greedyString()).suggests(Where::suggest)
                        .executes(guard(c -> r.run(c, Where.resolve(c.getSource(), StringArgumentType.getString(c, "where")))))),
                cat, name, "<where>", desc, perm);
    }

    /** /name [where...]: the block you are looking at when left out. */
    static void whereOpt(String cat, String name, int perm, String desc, SpotRun r) {
        add(lit(name, perm).executes(guard(c -> r.run(c, Where.look(c.getSource()))))
                        .then(Commands.argument("where", StringArgumentType.greedyString()).suggests(Where::suggest)
                                .executes(guard(c -> r.run(c, Where.resolve(c.getSource(), StringArgumentType.getString(c, "where")))))),
                cat, name, "[where]", desc, perm);
    }

    public interface TwoRun { int run(CommandContext<CommandSourceStack> c, String word, String text) throws CommandSyntaxException; }
    public interface NumTextRun { int run(CommandContext<CommandSourceStack> c, int n, String text) throws CommandSyntaxException; }

    /** /name <word> <text...> */
    static void wordText(String cat, String name, int perm, String usage, String desc,
                         SuggestionProvider<CommandSourceStack> suggestions, TwoRun r) {
        add(lit(name, perm).then(Commands.argument("word", StringArgumentType.word()).suggests(suggestions)
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(guard(c -> r.run(c, StringArgumentType.getString(c, "word"), StringArgumentType.getString(c, "text")))))),
                cat, name, usage, desc, perm);
    }

    /** /name <n> <text...> */
    static void numText(String cat, String name, int perm, String usage, String desc, int min, int max, NumTextRun r) {
        add(lit(name, perm).then(Commands.argument("n", IntegerArgumentType.integer(min, max))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(guard(c -> r.run(c, IntegerArgumentType.getInteger(c, "n"), StringArgumentType.getString(c, "text")))))),
                cat, name, usage, desc, perm);
    }
    /** A command built by hand. The builder comes from lit(). */
    static void custom(String cat, String name, int perm, String usage, String desc, LiteralArgumentBuilder<CommandSourceStack> b) {
        add(b, cat, name, usage, desc, perm);
    }

    // ---------------------------------------------------------------- moving players

    static void go(ServerPlayer p, Where.Spot s) {
        Store.remember(p);
        p.resetFallDistance();
        p.stopRiding();
        p.teleportTo(s.level(), s.pos().x, s.pos().y, s.pos().z, s.yRot(), s.xRot());
    }

    // ---------------------------------------------------------------- /qol

    private static void help() {
        add(lit("qol", 0).executes(guard(c -> {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (Info i : INFOS) {
                        if (c.getSource().hasPermission(i.perm())) {
                            counts.merge(i.cat(), 1, Integer::sum);
                        }
                    }
                    ok(c, "Pentecost commands you can use: " + counts.values().stream().mapToInt(Integer::intValue).sum());
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        ok(c, "  " + e.getKey() + " (" + e.getValue() + "): /qol " + e.getKey().toLowerCase(Locale.ROOT));
                    }
                    ok(c, "Search with /qol <word>. Places accept players, spawn, bed, death, home:name, warp:name or x y z, "
                            + "where any number can be a player's coordinate like Steve.x or ~ for here.");
                    return 1;
                }))
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .suggests(options("move", "locate", "player", "items", "world", "menus", "info", "chat", "fun", "timers", "build", "admin", "rules", "ops", "server"))
                        .executes(guard(c -> {
                            String f = StringArgumentType.getString(c, "filter").toLowerCase(Locale.ROOT).trim();
                            int n = 0;
                            for (Info i : INFOS) {
                                if (!c.getSource().hasPermission(i.perm())) {
                                    continue;
                                }
                                if (i.cat().equalsIgnoreCase(f) || i.name().contains(f) || i.desc().toLowerCase(Locale.ROOT).contains(f)) {
                                    ok(c, "/" + i.name() + (i.usage().isEmpty() ? "" : " " + i.usage()) + " - " + i.desc());
                                    n++;
                                }
                            }
                            if (n == 0) {
                                throw new Bad("Nothing matches " + f + ". Try /qol on its own for the groups.");
                            }
                            return n;
                        }))),
                "Info", "qol", "[group or word]", "List Pentecost's commands, or search them", 0);
    }

    // ---------------------------------------------------------------- shared state used by the command files

    static final Map<UUID, String> TPA = new HashMap<>();
}
