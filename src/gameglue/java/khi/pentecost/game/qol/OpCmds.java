package khi.pentecost.game.qol;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

final class OpCmds {
    private static final String C = "Ops";
    private static final String[] LEVELS = {
            "player", "moderator (bypass spawn protection)", "gamemaster (command blocks, most commands)",
            "admin (kick, ban, op)", "owner (stop, save, everything)"};

    private static final SuggestionProvider<CommandSourceStack> OP_AND_ONLINE = (c, b) -> {
        TreeSet<String> names = new TreeSet<>();
        PlayerList pl = c.getSource().getServer().getPlayerList();
        for (ServerPlayer p : pl.getPlayers()) {
            names.add(p.getGameProfile().getName());
        }
        names.addAll(java.util.Arrays.asList(pl.getOps().getUserList()));
        return SharedSuggestionProvider.suggest(names, b);
    };

    private OpCmds() {}

    static int levelOf(CommandSourceStack src) {
        for (int i = 4; i >= 1; i--) {
            if (src.hasPermission(i)) {
                return i;
            }
        }
        return 0;
    }

    private static int levelOf(MinecraftServer s, GameProfile gp) {
        ServerOpListEntry e = s.getPlayerList().getOps().get(gp);
        if (e != null) {
            return e.getLevel();
        }
        ServerPlayer p = s.getPlayerList().getPlayer(gp.getId());
        if (p != null) {
            for (int i = 4; i >= 1; i--) {
                if (p.hasPermissions(i)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private static GameProfile profile(CommandSourceStack src, String name) {
        MinecraftServer s = src.getServer();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                return p.getGameProfile();
            }
        }
        if (s.getProfileCache() != null) {
            Optional<GameProfile> gp = s.getProfileCache().get(name);
            if (gp.isPresent()) {
                return gp.get();
            }
        }
        throw new Bad("The server does not know a player called " + name + " (they have to have joined once).");
    }

    private static void apply(MinecraftServer s, GameProfile gp, int level) {
        PlayerList pl = s.getPlayerList();
        if (level <= 0) {
            pl.deop(gp);
        } else {
            ServerOpListEntry old = pl.getOps().get(gp);
            pl.getOps().add(new ServerOpListEntry(gp, level, old != null && old.getBypassesPlayerLimit()));
            ServerPlayer p = pl.getPlayer(gp.getId());
            if (p != null) {
                pl.sendPlayerPermissionLevel(p);
            }
        }
        ServerPlayer p = pl.getPlayer(gp.getId());
        if (p != null) {
            s.getCommands().sendCommands(p);
        }
    }

    private static int change(net.minecraft.commands.CommandSourceStack src, String name, int level) {
        GameProfile gp = profile(src, name);
        MinecraftServer s = src.getServer();
        int mine = levelOf(src);
        int before = levelOf(s, gp);
        ServerPlayer self = src.getPlayer();
        if (self != null && self.getUUID().equals(gp.getId())) {
            throw new Bad("Ask another operator to change your own level, so you cannot lock yourself out.");
        }
        if (level > mine) {
            throw new Bad("You hold level " + mine + ", so you cannot give level " + level + ".");
        }
        if (before > mine) {
            throw new Bad(gp.getName() + " holds level " + before + ", above your level " + mine + ".");
        }
        if (before == level) {
            throw new Bad(gp.getName() + " is already level " + level + ".");
        }
        apply(s, gp, level);
        String text = gp.getName() + " went from level " + before + " to level " + level
                + (level == 0 ? " (no longer an operator)." : " (" + LEVELS[level] + ").");
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), true);
        ServerPlayer target = s.getPlayerList().getPlayer(gp.getId());
        if (target != null) {
            ChatCmds.tell(target, net.minecraft.ChatFormatting.YELLOW, "Your operator level is now " + level + (level == 0 ? "." : ": " + LEVELS[level] + "."));
        }
        return 1;
    }

    private static void give(ServerPlayer p, Item it) {
        ItemStack s = new ItemStack(it, 1);
        if (!p.getInventory().add(s)) {
            p.drop(s, false);
        }
    }

    static void add() {
        Qol.none(C, "ops", 2, "List every operator with their level", c -> {
            var entries = c.getSource().getServer().getPlayerList().getOps().getEntries();
            if (entries.isEmpty()) {
                return Qol.ok(c, "There are no operators.");
            }
            String[] opNames = c.getSource().getServer().getPlayerList().getOps().getUserList();
            List<String> out = new ArrayList<>();
            int idx = 0;
            for (ServerOpListEntry e : entries) {
                String who = opNames.length == entries.size() ? opNames[idx] : "operator " + (idx + 1);
                idx++;
                out.add(who + " " + e.getLevel() + (e.getBypassesPlayerLimit() ? " (joins a full server)" : ""));
            }            return Qol.ok(c, entries.size() + " operator" + (entries.size() == 1 ? "" : "s") + ": " + String.join(", ", out));
        });

        Qol.none(C, "mylevel", 0, "Show your operator level and what it lets you do", c -> {
            int l = levelOf(c.getSource());
            return Qol.ok(c, "You are level " + l + ": " + LEVELS[l] + ".");
        });

        Qol.custom(C, "opcheck", 2, "<player>", "Show a player's operator level",
                Qol.lit("opcheck", 2).then(Commands.argument("name", StringArgumentType.word()).suggests(OP_AND_ONLINE)
                        .executes(Qol.guard(c -> {
                            GameProfile gp = profile(c.getSource(), StringArgumentType.getString(c, "name"));
                            int l = levelOf(c.getSource().getServer(), gp);
                            return Qol.ok(c, gp.getName() + " is level " + l + ": " + LEVELS[l] + ".");
                        }))));

        Qol.custom(C, "oplevel", 3, "<player> <0-4>", "Make a player an operator at an exact level (0 removes them)",
                Qol.lit("oplevel", 3).then(Commands.argument("name", StringArgumentType.word()).suggests(OP_AND_ONLINE)
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 4))
                                .executes(Qol.guard(c -> change(c.getSource(), StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "n")))))));

        Qol.custom(C, "promote", 3, "<player>", "Raise a player's operator level by one",
                Qol.lit("promote", 3).then(Commands.argument("name", StringArgumentType.word()).suggests(OP_AND_ONLINE)
                        .executes(Qol.guard(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            int now = levelOf(c.getSource().getServer(), profile(c.getSource(), n));
                            if (now >= 4) {
                                throw new Bad(n + " is already at the top level.");
                            }
                            return change(c.getSource(), n, now + 1);
                        }))));

        Qol.custom(C, "demote", 3, "<player>", "Lower a player's operator level by one",
                Qol.lit("demote", 3).then(Commands.argument("name", StringArgumentType.word()).suggests(OP_AND_ONLINE)
                        .executes(Qol.guard(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            int now = levelOf(c.getSource().getServer(), profile(c.getSource(), n));
                            if (now <= 0) {
                                throw new Bad(n + " is not an operator.");
                            }
                            return change(c.getSource(), n, now - 1);
                        }))));

        Qol.custom(C, "deopall", 4, "confirm", "Remove every operator except yourself",
                Qol.lit("deopall", 4).then(Commands.literal("confirm").executes(Qol.guard(c -> {
                    MinecraftServer s = c.getSource().getServer();
                    ServerPlayer self = c.getSource().getPlayer();
                    int n = 0;
                    for (String nm : s.getPlayerList().getOps().getUserList()) {
                        Optional<GameProfile> gp = s.getProfileCache() == null ? Optional.empty() : s.getProfileCache().get(nm);
                        if (gp.isEmpty() || (self != null && self.getUUID().equals(gp.get().getId()))) {
                            continue;
                        }
                        apply(s, gp.get(), 0);
                        n++;
                    }                    return Qol.ok(c, "Removed " + n + " operator" + (n == 1 ? "" : "s") + ".");
                }))));

        Qol.custom(C, "opbypass", 3, "<player> <on|off>", "Let an operator join even when the server is full",
                Qol.lit("opbypass", 3).then(Commands.argument("name", StringArgumentType.word()).suggests(OP_AND_ONLINE)
                        .then(Commands.argument("word", StringArgumentType.word()).suggests(Qol.options("on", "off"))
                                .executes(Qol.guard(c -> {
                                    GameProfile gp = profile(c.getSource(), StringArgumentType.getString(c, "name"));
                                    String w = StringArgumentType.getString(c, "word");
                                    if (!w.equals("on") && !w.equals("off")) {
                                        throw new Bad("Say on or off.");
                                    }
                                    PlayerList pl = c.getSource().getServer().getPlayerList();
                                    ServerOpListEntry e = pl.getOps().get(gp);
                                    if (e == null) {
                                        throw new Bad(gp.getName() + " is not an operator.");
                                    }
                                    pl.getOps().add(new ServerOpListEntry(gp, e.getLevel(), w.equals("on")));
                                    return Qol.ok(c, gp.getName() + (w.equals("on") ? " can" : " can no longer") + " join a full server.");
                                })))));

        Qol.none(C, "opreload", 3, "Read the operator list from disk again", c -> {
            MinecraftServer s = c.getSource().getServer();
            try {
                s.getPlayerList().getOps().load();
            } catch (java.io.IOException e) {
                throw new Bad("The operator list could not be read: " + e.getMessage());
            }
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                s.getPlayerList().sendPlayerPermissionLevel(p);
                s.getCommands().sendCommands(p);
            }
            return Qol.ok(c, "Operator list reloaded.");
        });

        Qol.none(C, "optab", 0, "Check the creative Operator Items tab and refresh your permissions", c -> {
            ServerPlayer p = Qol.me(c);
            MinecraftServer s = c.getSource().getServer();
            s.getPlayerList().sendPlayerPermissionLevel(p);
            s.getCommands().sendCommands(p);
            int l = levelOf(c.getSource());
            if (l < 2) {
                throw new Bad("The tab needs operator level 2 or more. You are level " + l + ". An operator can use /oplevel " + p.getGameProfile().getName() + " 2.");
            }
            Qol.ok(c, "Level " + l + ": the server side is fine.");
            if (!p.getAbilities().instabuild) {
                Qol.ok(c, "Switch to creative mode (/gmc). The tab only shows in creative.");
            }
            return Qol.ok(c, "Then open the creative inventory. If the tab is missing, turn on Settings > Building > Creative inventory > Operator items tab. That switch lives in your own game, the server cannot flip it.");
        });

        Qol.player(C, "opkit", 2, "Give the operator blocks: command blocks, structure block, barrier, light and more", (c, p) -> {
            Item[] kit = {Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK, Items.COMMAND_BLOCK_MINECART,
                    Items.STRUCTURE_BLOCK, Items.STRUCTURE_VOID, Items.JIGSAW, Items.BARRIER, Items.LIGHT, Items.DEBUG_STICK,
                    Items.KNOWLEDGE_BOOK, Items.SPAWNER};
            for (Item it : kit) {
                give(p, it);
            }
            return Qol.ok(c, "Gave " + kit.length + " operator items to " + p.getGameProfile().getName() + ".");
        });
    }
}
