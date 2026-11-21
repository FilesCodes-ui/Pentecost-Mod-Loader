package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AdminCmds {
    private static final String C = "Admin";

    private static final SuggestionProvider<CommandSourceStack> ITEMS = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.ITEM.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);

    private AdminCmds() {}

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    private static Item item(String s) {
        ResourceLocation rl = ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s.toLowerCase(Locale.ROOT));
        if (rl == null) {
            throw new Bad("\"" + s + "\" is not a valid item name.");
        }
        Item it = BuiltInRegistries.ITEM.getOptional(rl).orElseThrow(() -> new Bad("There is no item called " + s + "."));
        if (it == Items.AIR) {
            throw new Bad("Air cannot be given.");
        }
        return it;
    }

    private static void give(ServerPlayer p, Item it, int count) {
        int left = count;
        while (left > 0) {
            ItemStack s = new ItemStack(it, Math.min(left, it.getMaxStackSize()));
            left -= s.getCount();
            if (!p.getInventory().add(s)) {
                p.drop(s, false);
            }
        }
    }

    private static int giveTo(CommandSourceStack src, List<ServerPlayer> who, String word, int n) {
        Item it = item(word);
        for (ServerPlayer p : who) {
            give(p, it, n);
        }
        int count = who.size();
        src.sendSuccess(() -> Component.literal("Gave " + n + " " + word + " to " + count + " player" + (count == 1 ? "" : "s") + "."), false);
        return count;
    }

    private static void speed(String name, boolean fly) {
        Qol.custom(C, name, 2, "<1-10> [player]", fly ? "Set how fast you fly (1 is normal)" : "Set how fast you walk (1 is normal)",
                Qol.lit(name, 2).then(Commands.argument("n", IntegerArgumentType.integer(1, 10))
                        .executes(Qol.guard(c -> setSpeed(c.getSource(), Qol.me(c), IntegerArgumentType.getInteger(c, "n"), fly)))
                        .then(Commands.argument("player", EntityArgument.players())
                                .executes(Qol.guard(c -> {
                                    int total = 0;
                                    for (ServerPlayer p : new java.util.ArrayList<>(EntityArgument.getPlayers(c, "player"))) {
                                        total += setSpeed(c.getSource(), p, IntegerArgumentType.getInteger(c, "n"), fly);
                                    }
                                    return total;
                                })))));
    }

    private static int setSpeed(CommandSourceStack src, ServerPlayer p, int n, boolean fly) {
        if (fly) {
            p.getAbilities().setFlyingSpeed(n * 0.05F);
        } else {
            p.getAbilities().setWalkingSpeed(n * 0.1F);
        }
        p.onUpdateAbilities();
        src.sendSuccess(() -> Component.literal((fly ? "Fly" : "Walk") + " speed " + n + " for " + name(p) + "."), false);
        return 1;
    }

    private static String pos(BlockPos bp) {
        return bp.getX() + " " + bp.getY() + " " + bp.getZ();
    }

    static void add() {
        Qol.none(C, "tpall", 2, "Bring everyone to you", c -> {
            ServerPlayer me = Qol.me(c);
            int n = 0;
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                if (p != me) {
                    Qol.go(p, Where.of(me));
                    n++;
                }
            }
            return Qol.ok(c, "Brought " + n + " player" + (n == 1 ? "" : "s") + " to you.");
        });

        Qol.custom(C, "kickall", 3, "[reason]", "Kick everyone who is not an operator",
                Qol.lit("kickall", 3).executes(Qol.guard(c -> kickAll(c.getSource(), "The server is closing for a moment.")))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(Qol.guard(c -> kickAll(c.getSource(), StringArgumentType.getString(c, "text"))))));

        Qol.playerReq(C, "clearinv", 2, "Empty a player's inventory", (c, t) -> {
            t.getInventory().clearContent();
            t.containerMenu.broadcastChanges();
            return Qol.ok(c, "Emptied " + name(t) + "'s inventory.");
        });

        Qol.playerReq(C, "clearec", 2, "Empty a player's ender chest", (c, t) -> {
            t.getEnderChestInventory().clearContent();
            return Qol.ok(c, "Emptied " + name(t) + "'s ender chest.");
        });

        Qol.playerReq(C, "tpbed", 2, "Go to a player's bed", (c, t) -> {
            BlockPos bed = t.getRespawnPosition();
            ServerLevel l = bed == null ? null : c.getSource().getServer().getLevel(t.getRespawnDimension());
            if (bed == null || l == null) {
                throw new Bad(name(t) + " has no bed or respawn anchor.");
            }
            Qol.go(Qol.me(c), new Where.Spot(l, net.minecraft.world.phys.Vec3.atBottomCenterOf(bed), 0.0F, 0.0F));
            return Qol.ok(c, "Teleported to " + name(t) + "'s bed.");
        });

        Qol.none(C, "setspawnpoint", 2, "Make where you stand your own respawn point", c -> {
            ServerPlayer p = Qol.me(c);
            p.setRespawnPosition(p.serverLevel().dimension(), p.blockPosition(), p.getYRot(), true, true);
            return 1;
        });

        Qol.word(C, "gmall", 2, "<survival|creative|adventure|spectator>", "Put everyone in a game mode", false,
                Qol.options("survival", "creative", "adventure", "spectator"), (c, w) -> {
                    GameType t = switch (w.toLowerCase(Locale.ROOT)) {
                        case "survival" -> GameType.SURVIVAL;
                        case "creative" -> GameType.CREATIVE;
                        case "adventure" -> GameType.ADVENTURE;
                        case "spectator" -> GameType.SPECTATOR;
                        default -> throw new Bad("Pick survival, creative, adventure or spectator.");
                    };
                    int n = 0;
                    for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                        p.setGameMode(t);
                        n++;
                    }
                    return Qol.ok(c, n + " player" + (n == 1 ? " is" : "s are") + " now in " + w.toLowerCase(Locale.ROOT) + " mode.");
                });

        Qol.none(C, "gamemodes", 2, "Show everyone's game mode", c -> {
            List<String> out = new ArrayList<>();
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                out.add(name(p) + " " + p.gameMode.getGameModeForPlayer().getName());
            }
            return Qol.ok(c, String.join(", ", out));
        });

        speed("walkspeed", false);

        Qol.player(C, "resetspeed", 2, "Put walking and flying speed back to normal", (c, t) -> {
            t.getAbilities().setFlyingSpeed(0.05F);
            t.getAbilities().setWalkingSpeed(0.1F);
            t.onUpdateAbilities();
            return Qol.ok(c, "Speeds reset for " + name(t) + ".");
        });

        Qol.integer(C, "givexp", 2, "<points>", "Give yourself experience points", 1, 1_000_000, false, (c, n) -> {
            Qol.me(c).giveExperiencePoints(n);
            return Qol.ok(c, "Gave " + n + " experience points.");
        });

        Qol.playerText(C, "sudo", 3, "<player> <command>", "Make a player run a command, with their own permissions", (c, who, cmd) -> {
            MinecraftServer server = c.getSource().getServer();
            int n = 0;
            for (ServerPlayer t : Qol.resolve(c.getSource(), who)) {
                server.getCommands().performPrefixedCommand(t.createCommandSourceStack(), cmd);
                n++;
            }
            return Qol.ok(c, "Ran /" + cmd + " as " + n + " player" + (n == 1 ? "" : "s") + ".");
        });

        Qol.none(C, "setjail", 2, "Make where you stand the jail", c -> {
            Store.setWarp("jail", Where.of(Qol.me(c)));
            return Qol.ok(c, "Jail set at " + Where.describe(Where.of(Qol.me(c))) + ".");
        });

        Qol.playerReq(C, "jail", 2, "Send a player to the jail (see /setjail)", (c, t) -> {
            Where.Spot jail = Store.warp("jail");
            if (jail == null) {
                throw new Bad("There is no jail yet. Stand somewhere and use /setjail.");
            }
            Qol.go(t, jail);
            return Qol.ok(c, "Jailed " + name(t) + ".");
        });

        Qol.playerReq(C, "unjail", 2, "Let a player out of jail, to the world spawn", (c, t) -> {
            Qol.go(t, Where.resolve(c.getSource(), "spawn"));
            return Qol.ok(c, "Released " + name(t) + ".");
        });

        Qol.custom("Items", "i", 2, "<item> [count]", "Give yourself an item (any amount)",
                Qol.lit("i", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(ITEMS)
                        .executes(Qol.guard(c -> giveTo(c.getSource(), List.of(Qol.me(c)), StringArgumentType.getString(c, "word"), 1)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 2304))
                                .executes(Qol.guard(c -> giveTo(c.getSource(), List.of(Qol.me(c)), StringArgumentType.getString(c, "word"),
                                        IntegerArgumentType.getInteger(c, "n")))))));

        Qol.custom("Items", "giveall", 2, "<item> [count]", "Give everyone an item",
                Qol.lit("giveall", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(ITEMS)
                        .executes(Qol.guard(c -> giveTo(c.getSource(), c.getSource().getServer().getPlayerList().getPlayers(), StringArgumentType.getString(c, "word"), 1)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 2304))
                                .executes(Qol.guard(c -> giveTo(c.getSource(), c.getSource().getServer().getPlayerList().getPlayers(),
                                        StringArgumentType.getString(c, "word"), IntegerArgumentType.getInteger(c, "n")))))));

        Qol.none("Items", "maxenchant", 2, "Put every fitting enchantment at its top level on the item in your hand", c -> {
            ItemStack s = Qol.me(c).getMainHandItem();
            if (s.isEmpty()) {
                throw new Bad("Hold an item first.");
            }
            int n = 0;
            for (Enchantment e : BuiltInRegistries.ENCHANTMENT) {
                if (!e.isCurse() && e.canEnchant(s)) {
                    s.enchant(e, e.getMaxLevel());
                    n++;
                }
            }
            return Qol.ok(c, n + " enchantment" + (n == 1 ? "" : "s") + " added.");
        });

        Qol.none("Items", "iteminfo", 0, "Show what the item in your hand is, with its data", c -> {
            ItemStack s = Qol.me(c).getMainHandItem();
            if (s.isEmpty()) {
                throw new Bad("Hold an item first.");
            }
            Qol.ok(c, BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount()
                    + (s.isDamageableItem() ? ", damage " + s.getDamageValue() + " of " + s.getMaxDamage() : ""));
            String tag = s.hasTag() ? s.getTag().toString() : "no extra data";
            return Qol.ok(c, tag.length() > 400 ? tag.substring(0, 400) + "..." : tag);
        });

        Qol.custom("Items", "whohas", 2, "<item>", "List who carries an item",
                Qol.lit("whohas", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(ITEMS)
                        .executes(Qol.guard(c -> {
                            Item it = item(StringArgumentType.getString(c, "word"));
                            List<String> out = new ArrayList<>();
                            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                                int n = p.getInventory().countItem(it);
                                if (n > 0) {
                                    out.add(name(p) + " " + n);
                                }
                            }
                            return Qol.ok(c, out.isEmpty() ? "Nobody has any." : "Carrying it: " + String.join(", ", out));
                        }))));

        Qol.integer(C, "border", 2, "<size>", "Set the world border's width", 1, 59_999_968, false, (c, n) -> {
            c.getSource().getServer().overworld().getWorldBorder().setSize(n);
            return Qol.ok(c, "The border is now " + n + " blocks wide.");
        });

        Qol.none(C, "bordercenter", 2, "Centre the world border where you stand", c -> {
            ServerPlayer p = Qol.me(c);
            c.getSource().getServer().overworld().getWorldBorder().setCenter(p.getX(), p.getZ());
            return Qol.ok(c, "The border is centred on " + Math.round(p.getX()) + " " + Math.round(p.getZ()) + ".");
        });

        Qol.none(C, "borderoff", 2, "Make the world border as wide as it can be", c -> {
            c.getSource().getServer().overworld().getWorldBorder().setSize(5.9999968E7D);
            return Qol.ok(c, "The border is out of the way.");
        });

        Qol.integer(C, "settime", 2, "<0-24000>", "Set the time of day in ticks", 0, 24000, false, (c, n) -> {
            ServerLevel l = c.getSource().getServer().overworld();
            l.setDayTime(l.getDayTime() - l.getDayTime() % 24000L + n);
            return Qol.ok(c, "Time is " + n + ".");
        });

        Qol.integer(C, "addtime", 2, "<ticks>", "Move the time forward", 1, 240000, false, (c, n) -> {
            ServerLevel l = c.getSource().getServer().overworld();
            l.setDayTime(l.getDayTime() + n);
            return Qol.ok(c, "Moved the time forward " + n + " ticks.");
        });

        Qol.none(C, "skip", 2, "Skip to the morning when it is night", c -> {
            ServerLevel l = c.getSource().getServer().overworld();
            if (!l.isNight()) {
                throw new Bad("It is not night.");
            }
            l.setDayTime(l.getDayTime() - l.getDayTime() % 24000L + 24000L);
            return Qol.ok(c, "Good morning.");
        });

        Qol.none(C, "clearlag", 3, "Remove every dropped item and experience orb in every loaded dimension", c -> {
            int n = 0;
            for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
                List<Entity> gone = new ArrayList<>();
                for (Entity e : l.getAllEntities()) {
                    if (e instanceof ItemEntity || e instanceof ExperienceOrb) {
                        gone.add(e);
                    }
                }
                for (Entity e : gone) {
                    e.discard();
                    n++;
                }
            }
            return Qol.ok(c, "Removed " + n + " things.");
        });

        Qol.custom(C, "killall", 3, "<creature>", "Remove every creature of a kind in every loaded dimension",
                Qol.lit("killall", 3).then(Commands.argument("word", StringArgumentType.word()).suggests(FunCmds.ENTITIES)
                        .executes(Qol.guard(c -> {
                            String word = StringArgumentType.getString(c, "word");
                            ResourceLocation rl = ResourceLocation.tryParse(word.contains(":") ? word : "minecraft:" + word.toLowerCase(Locale.ROOT));
                            if (rl == null || BuiltInRegistries.ENTITY_TYPE.getOptional(rl).isEmpty()) {
                                throw new Bad("There is no creature called " + word + ".");
                            }
                            var type = BuiltInRegistries.ENTITY_TYPE.get(rl);
                            int n = 0;
                            for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
                                List<Entity> gone = new ArrayList<>();
                                for (Entity e : l.getAllEntities()) {
                                    if (e.getType() == type && !(e instanceof ServerPlayer)) {
                                        gone.add(e);
                                    }
                                }
                                for (Entity e : gone) {
                                    e.discard();
                                    n++;
                                }
                            }
                            return Qol.ok(c, "Removed " + n + " " + word + ".");
                        }))));

        Qol.none(C, "savenow", 3, "Save the world right now", c -> {
            c.getSource().getServer().saveEverything(true, false, true);
            return Qol.ok(c, "Saved.");
        });

        Qol.none(C, "gc", 3, "Ask Java to free memory and show the result", c -> {
            Runtime r = Runtime.getRuntime();
            long before = (r.totalMemory() - r.freeMemory()) >> 20;
            System.gc();
            long after = (r.totalMemory() - r.freeMemory()) >> 20;
            return Qol.ok(c, "Memory use went from " + before + " MB to " + after + " MB.");
        });

        Qol.word(C, "lockdown", 3, "<on|off>", "Turn the whitelist on or off", false, Qol.options("on", "off"), (c, w) -> {
            if (!w.equalsIgnoreCase("on") && !w.equalsIgnoreCase("off")) {
                throw new Bad("Say on or off.");
            }
            MinecraftServer s = c.getSource().getServer();
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "whitelist " + w.toLowerCase(Locale.ROOT));
            return 1;
        });

        Qol.text(C, "setmotd", 3, "<text>", "Change the server's message of the day", (c, t) -> {
            c.getSource().getServer().setMotd(t);
            return Qol.ok(c, "Message of the day changed.");
        });

        Qol.none(C, "slots", 0, "Show how many players are online out of how many fit", c -> {
            MinecraftServer s = c.getSource().getServer();
            return Qol.ok(c, s.getPlayerCount() + " of " + s.getMaxPlayers() + " places taken.");
        });

        Qol.none("Locate", "blockinfo", 0, "Show the block you are looking at", c -> {
            ServerPlayer p = Qol.me(c);
            HitResult hit = p.pick(300.0, 1.0F, false);
            if (hit.getType() != HitResult.Type.BLOCK) {
                throw new Bad("You are not looking at a block (it has to be within 300 blocks).");
            }
            BlockPos bp = ((BlockHitResult) hit).getBlockPos();
            BlockState st = p.serverLevel().getBlockState(bp);
            Qol.ok(c, BuiltInRegistries.BLOCK.getKey(st.getBlock()) + " at " + pos(bp));
            return Qol.ok(c, st.toString().replace("Block{", "").replace("}", ""));
        });

        Qol.none("Locate", "deathpos", 0, "Show where you last died", c -> {
            var death = Qol.me(c).getLastDeathLocation();
            if (death.isEmpty()) {
                throw new Bad("You have not died yet.");
            }
            return Qol.ok(c, "You died at " + pos(death.get().pos()) + " in " + death.get().dimension().location() + ".");
        });

        Qol.none("Locate", "spawnpoint", 0, "Show where you will respawn", c -> {
            ServerPlayer p = Qol.me(c);
            BlockPos bed = p.getRespawnPosition();
            if (bed == null) {
                return Qol.ok(c, "You will respawn at the world spawn, " + pos(c.getSource().getServer().overworld().getSharedSpawnPos()) + ".");
            }
            return Qol.ok(c, "You will respawn at " + pos(bed) + " in " + p.getRespawnDimension().location() + ".");
        });
    }

    private static int kickAll(CommandSourceStack src, String reason) throws CommandSyntaxException {
        int n = 0;
        for (ServerPlayer p : new ArrayList<>(src.getServer().getPlayerList().getPlayers())) {
            if (!p.hasPermissions(2)) {
                p.connection.disconnect(Component.literal(reason));
                n++;
            }
        }
        final int kicked = n;
        src.sendSuccess(() -> Component.literal("Kicked " + kicked + " player" + (kicked == 1 ? "" : "s") + "."), false);
        return kicked;
    }
}
