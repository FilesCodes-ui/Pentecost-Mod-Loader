package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** More for admins: healing everyone, looking into other players' things, clean-ups, personal time and weather, items. */
final class Admin2Cmds {
    private static final String C = "Admin";

    private static final Map<UUID, Long> PTIME = new HashMap<>();
    private static final Map<UUID, String> PWEATHER = new HashMap<>();

    private static final SuggestionProvider<CommandSourceStack> ENCHANTS = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.ENCHANTMENT.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);

    private Admin2Cmds() {}

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    static void clear() {
        PTIME.clear();
        PWEATHER.clear();
    }

    /** Driven by Qol.tick: keeps personal time and weather in place. */
    static void tick(MinecraftServer server) {
        long t = server.getTickCount();
        if (!PTIME.isEmpty() && t % 5L == 0L) {
            for (Map.Entry<UUID, Long> e : new ArrayList<>(PTIME.entrySet())) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) {
                    p.connection.send(new ClientboundSetTimePacket(p.level().getGameTime(), e.getValue(), false));
                }
            }
        }
        if (!PWEATHER.isEmpty() && t % 100L == 0L) {
            for (Map.Entry<UUID, String> e : new ArrayList<>(PWEATHER.entrySet())) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) {
                    sendWeather(p, e.getValue());
                }
            }
        }
    }

    private static void sendWeather(ServerPlayer p, String mode) {
        boolean rain;
        float rainLevel;
        float thunderLevel;
        switch (mode) {
            case "clear" -> {
                rain = false;
                rainLevel = 0.0F;
                thunderLevel = 0.0F;
            }
            case "rain" -> {
                rain = true;
                rainLevel = 1.0F;
                thunderLevel = 0.0F;
            }
            case "thunder" -> {
                rain = true;
                rainLevel = 1.0F;
                thunderLevel = 1.0F;
            }
            default -> {
                ServerLevel l = p.serverLevel();
                rain = l.isRaining();
                rainLevel = l.getRainLevel(1.0F);
                thunderLevel = l.getThunderLevel(1.0F);
            }
        }
        p.connection.send(new ClientboundGameEventPacket(rain ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0.0F));
        p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel));
        p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel));
    }

    private static long timeOf(String w) {
        return switch (w.toLowerCase(Locale.ROOT)) {
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "sunset" -> 12000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            case "sunrise" -> 23000L;
            default -> {
                try {
                    long n = Long.parseLong(w);
                    if (n < 0L || n > 24000L) {
                        throw new Bad("Pick a time from 0 to 24000, or day, noon, sunset, night, midnight, sunrise or reset.");
                    }
                    yield n;
                } catch (NumberFormatException e) {
                    throw new Bad("Pick a time from 0 to 24000, or day, noon, sunset, night, midnight, sunrise or reset.");
                }
            }
        };
    }

    private static ItemStack held(ServerPlayer p) {
        ItemStack s = p.getMainHandItem();
        if (s.isEmpty()) {
            throw new Bad("Hold an item first.");
        }
        return s;
    }

    static void add() {
        // ---- everyone at once
        Qol.none(C, "healall", 2, "Fully heal everyone", c -> {
            int n = 0;
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                p.setHealth(p.getMaxHealth());
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(20.0F);
                p.clearFire();
                n++;
            }
            return Qol.ok(c, "Healed " + n + " player" + (n == 1 ? "" : "s") + ".");
        });

        Qol.none(C, "feedall", 2, "Fill everyone's food bar", c -> {
            int n = 0;
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                p.getFoodData().setFoodLevel(20);
                p.getFoodData().setSaturation(20.0F);
                n++;
            }
            return Qol.ok(c, "Fed " + n + " player" + (n == 1 ? "" : "s") + ".");
        });

        // ---- looking into other players' things
        Qol.playerReq(C, "ecsee", 2, "Open a player's ender chest", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            me.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv, t.getEnderChestInventory()),
                    Component.literal(name(t) + "'s ender chest")));
            return 1;
        });

        Qol.playerReq(C, "invcopy", 3, "Copy a player's whole inventory over yours", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (me == t) {
                throw new Bad("That is you.");
            }
            me.getInventory().load(t.getInventory().save(new ListTag()));
            me.inventoryMenu.broadcastChanges();
            return Qol.ok(c, "Copied " + name(t) + "'s inventory.");
        });

        Qol.playerReq(C, "ip", 3, "Show a player's address", (c, t) -> Qol.ok(c, name(t) + " connects from " + t.getIpAddress() + "."));

        Qol.player(C, "effects", 2, "List the effects a player has", (c, t) -> {
            List<String> out = new ArrayList<>();
            for (MobEffectInstance e : t.getActiveEffects()) {
                int secs = e.getDuration() / 20;
                out.add(e.getEffect().getDisplayName().getString() + " " + (e.getAmplifier() + 1) + (e.getDuration() < 0 ? " (until removed)" : " (" + secs + "s)"));
            }
            return Qol.ok(c, name(t) + (out.isEmpty() ? " has no effects." : ": " + String.join(", ", out)));
        });

        // ---- other players' homes
        Qol.playerReq(C, "homesof", 2, "List a player's homes", (c, t) -> {
            var names = Store.homeNames(t);
            return Qol.ok(c, name(t) + (names.isEmpty() ? " has no homes." : " has: " + String.join(", ", names)));
        });

        Qol.playerText(C, "visithome", 2, "<player> <home>", "Go to one of a player's homes", (c, who, home) -> {
            ServerPlayer t = Qol.one(c.getSource(), who);
            Where.Spot s = Store.home(t, home.trim());
            if (s == null) {
                throw new Bad(name(t) + " has no home called " + home.trim() + ".");
            }
            Qol.go(Qol.me(c), s);
            return Qol.ok(c, "At " + name(t) + "'s home " + home.trim() + ".");
        });

        Qol.playerText(C, "delhomeof", 3, "<player> <home>", "Delete one of a player's homes", (c, who, home) -> {
            ServerPlayer t = Qol.one(c.getSource(), who);
            if (!Store.delHome(t, home.trim())) {
                throw new Bad(name(t) + " has no home called " + home.trim() + ".");
            }
            return Qol.ok(c, "Deleted " + name(t) + "'s home " + home.trim() + ".");
        });

        // ---- clean-ups
        Qol.none(C, "killpassive", 3, "Remove every farm animal that has no name and is not tamed, in every loaded dimension", c -> {
            int n = 0;
            for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
                List<Entity> gone = new ArrayList<>();
                for (Entity e : l.getAllEntities()) {
                    if (e instanceof Animal a && !a.hasCustomName() && !a.isLeashed() && !(a instanceof TamableAnimal t && t.isTame())) {
                        gone.add(e);
                    }
                }
                for (Entity e : gone) {
                    e.discard();
                    n++;
                }
            }
            return Qol.ok(c, "Removed " + n + " animals.");
        });

        Qol.integer(C, "killmobs", 3, "<radius>", "Remove every creature without a name and not tamed within a radius of you", 1, 200, false, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            AABB box = p.getBoundingBox().inflate(n);
            int count = 0;
            for (LivingEntity e : p.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                    x -> !(x instanceof Player) && !x.hasCustomName() && !(x instanceof TamableAnimal t && t.isTame()))) {
                e.discard();
                count++;
            }
            return Qol.ok(c, "Removed " + count + " creatures.");
        });

        // ---- personal time and weather
        Qol.custom(C, "ptime", 2, "<0-24000|day|noon|night|midnight|reset> [player]", "Show a player their own time of day (nobody else sees it)",
                Qol.lit("ptime", 2).then(Commands.argument("word", StringArgumentType.word())
                        .suggests(Qol.options("day", "noon", "sunset", "night", "midnight", "sunrise", "reset"))
                        .executes(Qol.guard(c -> setPtime(c.getSource(), List.of(Qol.me(c)), StringArgumentType.getString(c, "word"))))
                        .then(Commands.argument("player", EntityArgument.players())
                                .executes(Qol.guard(c -> setPtime(c.getSource(), new java.util.ArrayList<>(EntityArgument.getPlayers(c, "player")),
                                        StringArgumentType.getString(c, "word")))))));

        Qol.custom(C, "pweather", 2, "<clear|rain|thunder|reset> [player]", "Show a player their own weather (nobody else sees it)",
                Qol.lit("pweather", 2).then(Commands.argument("word", StringArgumentType.word())
                        .suggests(Qol.options("clear", "rain", "thunder", "reset"))
                        .executes(Qol.guard(c -> setPweather(c.getSource(), List.of(Qol.me(c)), StringArgumentType.getString(c, "word"))))
                        .then(Commands.argument("player", EntityArgument.players())
                                .executes(Qol.guard(c -> setPweather(c.getSource(), new java.util.ArrayList<>(EntityArgument.getPlayers(c, "player")),
                                        StringArgumentType.getString(c, "word")))))));

        // ---- the item in your hand
        Qol.none("Items", "stack", 2, "Fill the stack in your hand to its largest size", c -> {
            ItemStack s = held(Qol.me(c));
            s.setCount(s.getMaxStackSize());
            Qol.me(c).containerMenu.broadcastChanges();
            return Qol.ok(c, "Now " + s.getCount() + ".");
        });

        Qol.text("Items", "lore", 2, "<text|->", "Write a line of lore on the item in your hand (a dash removes it)", (c, t) -> {
            ItemStack s = held(Qol.me(c));
            CompoundTag display = s.getOrCreateTagElement("display");
            if (t.trim().equals("-")) {
                display.remove("Lore");
                return Qol.ok(c, "Lore removed.");
            }
            ListTag lore = new ListTag();
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(t))));
            display.put("Lore", lore);
            return Qol.ok(c, "Lore set.");
        });

        Qol.none("Items", "unenchant", 2, "Strip every enchantment from the item in your hand", c -> {
            ItemStack s = held(Qol.me(c));
            s.removeTagKey("Enchantments");
            s.removeTagKey("StoredEnchantments");
            return Qol.ok(c, "Enchantments removed.");
        });

        Qol.custom("Items", "uenchant", 2, "<enchantment> <level 1-255>", "Put any enchantment at any level on the item in your hand",
                Qol.lit("uenchant", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(ENCHANTS)
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 255))
                                .executes(Qol.guard(c -> {
                                    String w = StringArgumentType.getString(c, "word");
                                    ResourceLocation rl = ResourceLocation.tryParse(w.contains(":") ? w : "minecraft:" + w.toLowerCase(Locale.ROOT));
                                    Enchantment e = rl == null ? null : BuiltInRegistries.ENCHANTMENT.getOptional(rl).orElse(null);
                                    if (e == null) {
                                        throw new Bad("There is no enchantment called " + w + ".");
                                    }
                                    held(Qol.me(c)).enchant(e, IntegerArgumentType.getInteger(c, "n"));
                                    return Qol.ok(c, w + " " + IntegerArgumentType.getInteger(c, "n") + " added.");
                                })))));
    }

    private static int setPtime(CommandSourceStack src, List<ServerPlayer> who, String w) {
        boolean reset = w.equalsIgnoreCase("reset");
        long t = reset ? 0L : timeOf(w);
        for (ServerPlayer p : who) {
            if (reset) {
                PTIME.remove(p.getUUID());
                p.connection.send(new ClientboundSetTimePacket(p.level().getGameTime(), p.level().getDayTime(),
                        p.level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
            } else {
                PTIME.put(p.getUUID(), t);
            }
        }
        int n = who.size();
        src.sendSuccess(() -> Component.literal(reset ? "Time is the server's again for " + n + " player(s)." : "Time is fixed at " + t + " for " + n + " player(s)."), false);
        return n;
    }

    private static int setPweather(CommandSourceStack src, List<ServerPlayer> who, String w) {
        String mode = w.toLowerCase(Locale.ROOT);
        if (!mode.equals("clear") && !mode.equals("rain") && !mode.equals("thunder") && !mode.equals("reset")) {
            throw new Bad("Pick clear, rain, thunder or reset.");
        }
        for (ServerPlayer p : who) {
            if (mode.equals("reset")) {
                PWEATHER.remove(p.getUUID());
            } else {
                PWEATHER.put(p.getUUID(), mode);
            }
            sendWeather(p, mode);
        }
        int n = who.size();
        src.sendSuccess(() -> Component.literal("Weather is " + mode + " for " + n + " player(s)."), false);
        return n;
    }
}
