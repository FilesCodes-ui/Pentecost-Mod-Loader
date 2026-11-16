package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commands in groups, and the switches that decide who may use Pentecost's commands.
 * /run does several commands in a row (with waits), /group saves them under a name, /cmdlevel changes the
 * permission level of one command and /cmdgroup switches a whole group of commands off or on.
 * Everything is kept in the world's pentecost/qol.json, so it survives a restart.
 */
final class GroupCmds {
    private static final String C = "Groups";
    private static final String CONFIG = "Config";
    /** Groups that always work, so nobody can lock themselves out. */
    private static final Set<String> LOCKED = Set.of("groups", "config", "info");
    /** Commands that cannot be given another level. */
    private static final Set<String> PROTECTED = Set.of("cmdlevel", "cmdgroup", "cmdconfig", "qol");
    private static final int MAX_COMMANDS = 100;
    private static final int MAX_WAITING = 64;

    private record Pending(long at, CommandSourceStack src, List<String> rest) {}

    private static final List<Pending> QUEUE = new ArrayList<>();
    private static int depth;

    private GroupCmds() {}

    /** Whether a command may be used by this source, counting the operator's switches. */
    static boolean allowed(String cat, String name, int perm, CommandSourceStack s) {
        if (!LOCKED.contains(cat.toLowerCase(Locale.ROOT)) && Store.groupOff(cat)) {
            return false;
        }
        int level = Store.levelOf(name, perm);
        return level <= 0 || s.hasPermission(level);
    }

    static void clear() {
        QUEUE.clear();
        depth = 0;
    }

    static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Pending p : new ArrayList<>(QUEUE)) {
            if (now >= p.at()) {
                QUEUE.remove(p);
                try {
                    runList(server, p.src(), p.rest());
                } catch (RuntimeException e) {
                    p.src().sendFailure(net.minecraft.network.chat.Component.literal("A waiting group stopped: " + e.getMessage()));
                }
            }
        }
    }

    // ---------------------------------------------------------------- running

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(";")) {
            String s = part.trim();
            if (s.startsWith("/")) {
                s = s.substring(1).trim();
            }
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Runs the commands in order. A "wait <seconds>" line pauses, and the rest runs later. Returns how many ran now. */
    static int runList(MinecraftServer server, CommandSourceStack src, List<String> cmds) {
        if (depth >= 4) {
            throw new Qol.Bad("Groups were running groups too deep, so this stopped.");
        }
        depth++;
        try {
            int ran = 0;
            for (int i = 0; i < cmds.size(); i++) {
                String cmd = cmds.get(i);
                String low = cmd.toLowerCase(Locale.ROOT);
                if (low.equals("wait") || low.startsWith("wait ")) {
                    double secs = 1;
                    try {
                        secs = Double.parseDouble(cmd.substring(4).trim().replace("s", ""));
                    } catch (RuntimeException e) {
                        secs = 1;
                    }
                    secs = Math.max(0, Math.min(600, secs));
                    if (QUEUE.size() >= MAX_WAITING) {
                        throw new Qol.Bad("Too many groups are waiting already.");
                    }
                    QUEUE.add(new Pending(System.currentTimeMillis() + (long) (secs * 1000), src,
                            new ArrayList<>(cmds.subList(i + 1, cmds.size()))));
                    return ran;
                }
                server.getCommands().performPrefixedCommand(src, cmd);
                ran++;
            }
            return ran;
        } finally {
            depth--;
        }
    }

    private static int runText(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, String text) {
        List<String> list = split(text);
        if (list.isEmpty()) {
            throw new Qol.Bad("There is nothing to run.");
        }
        if (list.size() > MAX_COMMANDS) {
            throw new Qol.Bad("That is more than " + MAX_COMMANDS + " commands.");
        }
        int ran = runList(c.getSource().getServer(), c.getSource(), list);
        return Qol.ok(c, ran >= list.size() ? "Ran " + ran + " command" + (ran == 1 ? "" : "s") + "."
                : "Ran " + ran + " now. The rest comes after the wait.");
    }

    private static final SuggestionProvider<CommandSourceStack> GROUPS =
            (c, b) -> SharedSuggestionProvider.suggest(Store.groupNames(), b);

    private static List<String> categories() {
        Set<String> out = new LinkedHashSet<>();
        for (Qol.Info i : Qol.INFOS) {
            out.add(i.cat().toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }

    private static List<String> commandNames() {
        List<String> out = new ArrayList<>();
        for (Qol.Info i : Qol.INFOS) {
            out.add(i.name());
        }
        return out;
    }

    private static void refresh(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(p);
        }
    }

    private static String cleanName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.length() > 32) {
            throw new Qol.Bad("Group names can be up to 32 characters.");
        }
        return n;
    }

    // ---------------------------------------------------------------- the commands

    static void add() {
        Qol.text(C, "run", 2, "<command ; command ; wait 2 ; command>",
                "Run several commands in a row. Separate them with ; and use wait <seconds> to pause", GroupCmds::runText);

        LiteralArgumentBuilder<CommandSourceStack> g = Qol.lit("group", 2);
        g.then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.word())
                .then(Commands.argument("commands", StringArgumentType.greedyString()).executes(Qol.guard(c -> {
                    String name = cleanName(StringArgumentType.getString(c, "name"));
                    List<String> list = split(StringArgumentType.getString(c, "commands"));
                    if (list.isEmpty()) {
                        throw new Qol.Bad("Add the commands after the name, separated by ;");
                    }
                    if (list.size() > MAX_COMMANDS) {
                        throw new Qol.Bad("That is more than " + MAX_COMMANDS + " commands.");
                    }
                    Store.saveGroup(name, String.join(" ; ", list));
                    return Qol.ok(c, "Saved group " + name + " with " + list.size() + " command" + (list.size() == 1 ? "" : "s")
                            + ". Run it with /group run " + name);
                })))));
        g.then(Commands.literal("run").then(Commands.argument("name", StringArgumentType.word()).suggests(GROUPS)
                .executes(Qol.guard(c -> {
                    String name = cleanName(StringArgumentType.getString(c, "name"));
                    String text = Store.group(name);
                    if (text == null) {
                        throw new Qol.Bad("There is no group called " + name + ". /group list shows them.");
                    }
                    return runText(c, text);
                }))));
        g.then(Commands.literal("list").executes(Qol.guard(c -> {
            List<String> names = Store.groupNames();
            if (names.isEmpty()) {
                return Qol.ok(c, "No groups yet. Make one with /group save <name> <command ; command>");
            }
            Qol.ok(c, "Groups (" + names.size() + "): " + String.join(", ", names));
            return names.size();
        })));
        g.then(Commands.literal("show").then(Commands.argument("name", StringArgumentType.word()).suggests(GROUPS)
                .executes(Qol.guard(c -> {
                    String name = cleanName(StringArgumentType.getString(c, "name"));
                    String text = Store.group(name);
                    if (text == null) {
                        throw new Qol.Bad("There is no group called " + name + ".");
                    }
                    return Qol.ok(c, name + ": " + text);
                }))));
        g.then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).suggests(GROUPS)
                .executes(Qol.guard(c -> {
                    String name = cleanName(StringArgumentType.getString(c, "name"));
                    if (!Store.deleteGroup(name)) {
                        throw new Qol.Bad("There is no group called " + name + ".");
                    }
                    return Qol.ok(c, "Deleted group " + name + ".");
                }))));
        Qol.custom(C, "group", 2, "save|run|list|show|delete <name> [commands]", "Save commands under a name and run them together", g);

        Qol.custom(CONFIG, "cmdlevel", 3, "<command> <0-4|default>", "Choose which permission level a Pentecost command needs (0 is everyone)",
                Qol.lit("cmdlevel", 3).then(Commands.argument("command", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(commandNames(), b))
                        .then(Commands.argument("level", StringArgumentType.word())
                                .suggests(Qol.options("0", "1", "2", "3", "4", "default"))
                                .executes(Qol.guard(c -> {
                                    String name = StringArgumentType.getString(c, "command").toLowerCase(Locale.ROOT);
                                    String word = StringArgumentType.getString(c, "level").toLowerCase(Locale.ROOT);
                                    Qol.Info info = null;
                                    for (Qol.Info i : Qol.INFOS) {
                                        if (i.name().equals(name)) {
                                            info = i;
                                        }
                                    }
                                    if (info == null) {
                                        throw new Qol.Bad("Pentecost has no command called " + name + ". /qol " + name + " searches.");
                                    }
                                    if (PROTECTED.contains(name)) {
                                        throw new Qol.Bad("/" + name + " is protected, so nobody can lock themselves out.");
                                    }
                                    if (word.equals("default")) {
                                        Store.setLevel(name, null);
                                        refresh(c.getSource().getServer());
                                        return Qol.ok(c, "/" + name + " needs level " + info.perm() + " again.");
                                    }
                                    int level;
                                    try {
                                        level = Integer.parseInt(word);
                                    } catch (NumberFormatException e) {
                                        throw new Qol.Bad("The level is 0 to 4, or default.");
                                    }
                                    if (level < 0 || level > 4) {
                                        throw new Qol.Bad("The level is 0 to 4, or default.");
                                    }
                                    Store.setLevel(name, level);
                                    refresh(c.getSource().getServer());
                                    return Qol.ok(c, "/" + name + " now needs " + (level == 0 ? "no operator level (everyone)" : "level " + level)
                                            + ". It was " + info.perm() + ".");
                                })))));

        Qol.custom(CONFIG, "cmdgroup", 3, "<group> <on|off>", "Switch a whole group of Pentecost commands off or on (Fun, Build, Move...)",
                Qol.lit("cmdgroup", 3).then(Commands.argument("group", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(categories(), b))
                        .then(Commands.argument("state", StringArgumentType.word()).suggests(Qol.options("on", "off"))
                                .executes(Qol.guard(c -> {
                                    String name = StringArgumentType.getString(c, "group").toLowerCase(Locale.ROOT);
                                    String state = StringArgumentType.getString(c, "state").toLowerCase(Locale.ROOT);
                                    if (!categories().contains(name)) {
                                        throw new Qol.Bad("There is no group called " + name + ". Try: " + String.join(", ", categories()));
                                    }
                                    if (LOCKED.contains(name)) {
                                        throw new Qol.Bad("The " + name + " group always stays on.");
                                    }
                                    if (!state.equals("on") && !state.equals("off")) {
                                        throw new Qol.Bad("Say on or off.");
                                    }
                                    Store.setGroupOff(name, state.equals("off"));
                                    refresh(c.getSource().getServer());
                                    return Qol.ok(c, "The " + name + " commands are " + state + ".");
                                })))));

        Qol.none(CONFIG, "cmdconfig", 3, "Show the command groups that are off and the levels that were changed", c -> {
            List<String> off = Store.offGroups();
            Map<String, Integer> levels = new LinkedHashMap<>(Store.levels());
            Qol.ok(c, off.isEmpty() ? "Every command group is on." : "Switched off: " + String.join(", ", off));
            if (levels.isEmpty()) {
                Qol.ok(c, "No command has a changed level.");
            } else {
                levels.forEach((k, v) -> Qol.ok(c, "/" + k + " needs level " + v));
            }
            return 1;
        });
    }
}
