package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** More fun: talk games, polls and trivia, gestures, rain of things, dancing, earthquakes and harmless pranks. */
final class Fun2Cmds {
    private static final String C = "Fun";
    private static final Random RANDOM = new Random();

    private static final String[] JOKES = {
            "Why did the creeper cross the road? To get to the other sssside.",
            "What do you call a sleeping bull in Minecraft? A bulldozer.",
            "Why do skeletons never fight? They have no guts.",
            "How does an enderman pay for things? With ender pearls, after a quick teleport.",
            "Why was the zombie bad at hide and seek? He kept groaning.",
            "What is a miner's favourite music? Rock.",
            "Why did the chicken join the server? To lay some eggs in the lobby.",
            "What do you call a pig that does karate? Pork chop.",
            "Why did the villager get lost? He kept saying hmm hmm and walking into walls.",
            "How do you fix a broken pickaxe? With a new iron strategy.",
            "Why are ghasts bad at poker? Everyone sees the tears coming.",
            "What did the redstone say to the lever? You really flip me."};

    private static final String[] FACTS = {
            "A full day and night in Minecraft lasts 20 real minutes.",
            "Cats scare creepers away.",
            "Endermen are hurt by water.",
            "A chicken lays an egg every 5 to 10 minutes.",
            "One block in the Nether is 8 blocks in the Overworld.",
            "Bedrock cannot be broken in survival mode.",
            "Ender pearls stack to 16, not 64.",
            "The first Ender Dragon kill gives 12000 experience points.",
            "Wolves are tamed with bones.",
            "Bees lose their stinger and die when they sting you.",
            "A sheep can be dyed any of 16 colours.",
            "Dolphins lead you to treasure if you feed them raw fish."};

    private static final String[] NICE = {
            "mines faster than anyone on this server.",
            "builds like a master architect.",
            "would survive any night in hardcore.",
            "is the reason this server is fun.",
            "has the best taste in blocks.",
            "never gets lost, even in the Nether.",
            "could out-farm a village.",
            "is a legend with a pickaxe."};

    private static final String[] ROASTS = {
            "once got lost in a one block hole.",
            "digs straight down and calls it a strategy.",
            "has fallen into lava with fire resistance in the inventory.",
            "builds a house and then forgets the door.",
            "feeds a creeper and is still surprised.",
            "named their pickaxe and still lost it.",
            "has a stack of dirt in every chest.",
            "thinks a torch is a weapon."};

    private static final String[] FORTUNES = {
            "You will find diamonds, right after the lava.",
            "A creeper is closer than you think.",
            "Today is a good day to dig sideways.",
            "Someone will give you a gift. Check your chest.",
            "Beware of gravel above your head.",
            "A long trip leads to a great base.",
            "Your next villager trade is a good one.",
            "Sleep soon. The night is long."};

    private static final String[][] TRIVIA = {
            {"What mob drops ender pearls?", "enderman"},
            {"What does a creeper do when it gets close to you?", "explode"},
            {"What item tames a wolf?", "bone"},
            {"What is the highest level of the Sharpness enchantment in vanilla?", "5"},
            {"Which dimension is home to ghasts?", "nether"},
            {"What do you light a Nether portal with? (flint and ...)", "steel"},
            {"How many obsidian blocks does the smallest Nether portal need, without corners?", "10"},
            {"Which boss is built from soul sand and three skulls?", "wither"},
            {"How many hearts does a player start with?", "10"},
            {"What block do you smelt to get iron ingots?", "iron ore"}};

    private static final String[] REELS = {"Cherry", "Lemon", "Bell", "Diamond", "Seven"};

    private static final MobEffect[] WILD = {MobEffects.MOVEMENT_SPEED, MobEffects.JUMP, MobEffects.SLOW_FALLING, MobEffects.NIGHT_VISION,
            MobEffects.GLOWING, MobEffects.LEVITATION, MobEffects.BLINDNESS, MobEffects.MOVEMENT_SLOWDOWN, MobEffects.DIG_SPEED,
            MobEffects.CONFUSION, MobEffects.INVISIBILITY, MobEffects.DAMAGE_BOOST, MobEffects.HUNGER};

    private static final SuggestionProvider<CommandSourceStack> ITEMS = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.ITEM.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);
    private static final SuggestionProvider<CommandSourceStack> SOUNDS = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.SOUND_EVENT.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);

    // poll and trivia state, kept in memory
    private static String pollQuestion;
    private static final Map<UUID, Boolean> VOTES = new HashMap<>();
    private static String triviaAnswer;
    private static String triviaQuestion;

    private Fun2Cmds() {}

    // ---------------------------------------------------------------- helpers

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    private static String pick(String[] a) {
        return a[RANDOM.nextInt(a.length)];
    }

    private static void burst(ServerPlayer t, ParticleOptions o, int n) {
        t.serverLevel().sendParticles(o, t.getX(), t.getY() + 1.0, t.getZ(), n, 0.4, 0.5, 0.4, 0.05);
    }

    private static Item findItem(String s) {
        ResourceLocation rl = ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s.toLowerCase(Locale.ROOT));
        if (rl == null) {
            throw new Bad("\"" + s + "\" is not a valid name.");
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

    /** Runs something every few ticks, a set number of times. */
    private static void repeat(UUID owner, String label, long every, int times, Runnable r) {
        int[] left = {times};
        int[] id = {0};
        id[0] = Timers.add(owner, label, every, every, () -> {
            r.run();
            if (--left[0] <= 0) {
                Timers.cancel(owner, id[0]);
            }
        });
    }

    private static void gesture(String cmd, String phrase, ParticleOptions o, String desc) {
        Qol.playerReq(C, cmd, 0, desc, (c, t) -> {
            burst(t, o, 12);
            ChatCmds.sound(t, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F);
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.LIGHT_PURPLE, name(Qol.me(c)) + " " + phrase + " " + name(t) + ".");
            return 1;
        });
    }

    private static int hash(String s) {
        return Math.floorMod(s.toLowerCase(Locale.ROOT).hashCode() * 31 + 17, 1_000_003);
    }

    private static void toggleEffects(ServerPlayer p, MobEffect[] effects, int[] levels) {
        boolean on = p.hasEffect(effects[0]);
        for (int i = 0; i < effects.length; i++) {
            if (on) {
                p.removeEffect(effects[i]);
            } else {
                p.addEffect(new MobEffectInstance(effects[i], MobEffectInstance.INFINITE_DURATION, levels[i], false, false, true));
            }
        }
    }

    // ---------------------------------------------------------------- commands

    static void add() {
        // ---- talk
        Qol.none(C, "joke", 0, "Tell a joke to everyone", c -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.YELLOW, pick(JOKES));
            return 1;
        });

        Qol.none(C, "fact", 0, "Share a Minecraft fact with everyone", c -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, "Fact: " + pick(FACTS));
            return 1;
        });

        Qol.playerReq(C, "compliment", 0, "Say something nice about a player", (c, t) -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.GREEN, name(t) + " " + pick(NICE));
            return 1;
        });

        Qol.playerReq(C, "roast", 0, "A gentle roast of a player", (c, t) -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.GOLD, name(t) + " " + pick(ROASTS));
            return 1;
        });

        Qol.none(C, "fortune", 0, "Get your fortune, just for you", c -> Qol.ok(c, pick(FORTUNES)));

        Qol.text(C, "rate", 0, "<thing>", "Rate anything out of 10 (the same thing always gets the same mark)", (c, t) -> {
            int n = hash(t) % 11;
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, name(Qol.me(c)) + " rates " + t + ": " + n + " out of 10.");
            return n;
        });

        Qol.wordText(C, "ship", 0, "<name> <name>", "How well do two names get along", Qol.PLAYERS, (c, a, b) -> {
            String x = a.toLowerCase(Locale.ROOT);
            String y = b.trim().toLowerCase(Locale.ROOT);
            String key = x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
            int pct = hash(key) % 101;
            String verdict = pct >= 90 ? "a perfect pair." : pct >= 65 ? "a good match." : pct >= 35 ? "friends at least." : "better apart.";
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.LIGHT_PURPLE, a + " and " + b.trim() + ": " + pct + " percent, " + verdict);
            return pct;
        });

        Qol.text(C, "mock", 0, "<text>", "sAy iT lIkE tHiS", (c, t) -> {
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (char ch : t.toCharArray()) {
                if (Character.isLetter(ch)) {
                    sb.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
                    up = !up;
                } else {
                    sb.append(ch);
                }
            }
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.WHITE, name(Qol.me(c)) + ": " + sb);
            return 1;
        });

        Qol.text(C, "reverse", 0, "<text>", "Say something backwards", (c, t) -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.WHITE, name(Qol.me(c)) + ": " + new StringBuilder(t).reverse());
            return 1;
        });

        Qol.text(C, "uwu", 0, "<text>", "Say something in uwu", (c, t) -> {
            String s = t.replace('r', 'w').replace('l', 'w').replace('R', 'W').replace('L', 'W')
                    .replace("na", "nya").replace("ne", "nye").replace("no", "nyo").replace("nu", "nyu");
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.LIGHT_PURPLE, name(Qol.me(c)) + ": " + s + " uwu");
            return 1;
        });

        Qol.text(C, "clap", 0, "<text>", "Put a clap between every word", (c, t) -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.YELLOW, name(Qol.me(c)) + ": " + String.join(" [clap] ", t.trim().split("\\s+")));
            return 1;
        });

        // ---- games
        Qol.none(C, "slotmachine", 0, "Pull the lever on a slot machine", c -> {
            String a = pick(REELS);
            String b = pick(REELS);
            String d = pick(REELS);
            String result = a.equals(b) && b.equals(d) ? " JACKPOT!" : a.equals(b) || b.equals(d) || a.equals(d) ? " Two of a kind." : "";
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.GOLD, name(Qol.me(c)) + " pulls the lever: [" + a + "] [" + b + "] [" + d + "]" + result);
            return 1;
        });

        Qol.none(C, "trivia", 0, "Ask everyone a Minecraft question (answer with /answer)", c -> {
            if (triviaAnswer != null) {
                throw new Bad("A question is open: " + triviaQuestion);
            }
            String[] q = TRIVIA[RANDOM.nextInt(TRIVIA.length)];
            triviaQuestion = q[0];
            triviaAnswer = q[1];
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, "Trivia: " + q[0] + " Answer with /answer <your answer>.");
            return 1;
        });

        Qol.text(C, "answer", 0, "<answer>", "Answer the open trivia question", (c, t) -> {
            if (triviaAnswer == null) {
                throw new Bad("There is no open question. Start one with /trivia.");
            }
            if (t.toLowerCase(Locale.ROOT).contains(triviaAnswer)) {
                String right = triviaAnswer;
                triviaAnswer = null;
                triviaQuestion = null;
                ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.GREEN, name(Qol.me(c)) + " got it: " + right + ".");
                return 1;
            }
            return Qol.ok(c, "Not quite. Try again.");
        });

        Qol.text(C, "poll", 2, "<question>", "Ask everyone a yes or no question", (c, t) -> {
            pollQuestion = t;
            VOTES.clear();
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, "Poll: " + t + " Vote with /vote yes or /vote no.");
            return 1;
        });

        Qol.word(C, "vote", 0, "<yes|no>", "Vote in the open poll", false, Qol.options("yes", "no"), (c, w) -> {
            if (pollQuestion == null) {
                throw new Bad("There is no open poll.");
            }
            boolean yes = w.equalsIgnoreCase("yes");
            if (!yes && !w.equalsIgnoreCase("no")) {
                throw new Bad("Say yes or no.");
            }
            VOTES.put(Qol.me(c).getUUID(), yes);
            return Qol.ok(c, "You voted " + (yes ? "yes" : "no") + ".");
        });

        Qol.none(C, "pollresult", 0, "See how the open poll stands", c -> {
            if (pollQuestion == null) {
                throw new Bad("There is no open poll.");
            }
            long yes = VOTES.values().stream().filter(v -> v).count();
            return Qol.ok(c, pollQuestion + " Yes " + yes + ", no " + (VOTES.size() - yes) + ".");
        });

        Qol.none(C, "pollend", 2, "Close the poll and announce the result", c -> {
            if (pollQuestion == null) {
                throw new Bad("There is no open poll.");
            }
            long yes = VOTES.values().stream().filter(v -> v).count();
            long no = VOTES.size() - yes;
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, "Poll closed: " + pollQuestion + " Yes " + yes + ", no " + no
                    + (yes == no ? ". A tie." : yes > no ? ". Yes wins." : ". No wins."));
            pollQuestion = null;
            VOTES.clear();
            return 1;
        });

        Qol.custom(C, "lottery", 2, "<item> [count]", "Give an item to a random online player",
                Qol.lit("lottery", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(ITEMS)
                        .executes(Qol.guard(c -> lottery(c.getSource(), StringArgumentType.getString(c, "word"), 1)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 2304))
                                .executes(Qol.guard(c -> lottery(c.getSource(), StringArgumentType.getString(c, "word"), IntegerArgumentType.getInteger(c, "n")))))));

        Qol.none(C, "shuffle", 2, "Swap everyone's places at random", c -> {
            List<ServerPlayer> players = new ArrayList<>(c.getSource().getServer().getPlayerList().getPlayers());
            if (players.size() < 2) {
                throw new Bad("You need at least two players online.");
            }
            Collections.shuffle(players, RANDOM);
            List<Where.Spot> spots = new ArrayList<>();
            for (ServerPlayer p : players) {
                spots.add(Where.of(p));
            }
            for (int i = 0; i < players.size(); i++) {
                Qol.go(players.get(i), spots.get((i + 1) % players.size()));
            }
            return Qol.ok(c, "Shuffled " + players.size() + " players.");
        });

        Qol.player(C, "randomeffect", 2, "Give a player a random effect for 30 seconds", (c, t) -> {
            MobEffect e = WILD[RANDOM.nextInt(WILD.length)];
            t.addEffect(new MobEffectInstance(e, 600, 0));
            ChatCmds.tell(t, ChatFormatting.LIGHT_PURPLE, "You got " + e.getDisplayName().getString() + " for 30 seconds.");
            return Qol.ok(c, name(t) + " got " + e.getDisplayName().getString() + ".");
        });

        Qol.playerReq(C, "roulette", 2, "Spin the wheel for a player: a gift, a trick or nothing", (c, t) -> {
            String what;
            switch (RANDOM.nextInt(6)) {
                case 0 -> {
                    give(t, Items.DIAMOND, 1);
                    what = "wins a diamond";
                }
                case 1 -> {
                    give(t, Items.GOLDEN_APPLE, 3);
                    what = "wins 3 golden apples";
                }
                case 2 -> {
                    t.setHealth(t.getMaxHealth());
                    burst(t, ParticleTypes.HEART, 20);
                    what = "is fully healed";
                }
                case 3 -> {
                    PlayerCmds.firework(t.serverLevel(), t.position().add(0.0, 1.0, 0.0));
                    what = "gets a firework";
                }
                case 4 -> {
                    t.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 60, 1));
                    t.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0));
                    what = "floats up for a moment";
                }
                default -> {
                    ChatCmds.sound(t, SoundEvents.ANVIL_LAND, 1.8F);
                    what = "gets nothing but a bonk";
                }
            }
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.GOLD, "Roulette: " + name(t) + " " + what + ".");
            return 1;
        });

        // ---- gestures
        gesture("wave", "waves at", ParticleTypes.HAPPY_VILLAGER, "Wave at a player");
        gesture("highfive", "high fives", ParticleTypes.CRIT, "High five a player");
        gesture("salute", "salutes", ParticleTypes.END_ROD, "Salute a player");
        gesture("cheers", "clinks a glass with", ParticleTypes.GLOW, "Cheers with a player");

        Qol.none(C, "cheer", 0, "Everyone gets hearts and a happy sound", c -> {
            MinecraftServer s = c.getSource().getServer();
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                burst(p, ParticleTypes.HEART, 8);
                ChatCmds.sound(p, SoundEvents.PLAYER_LEVELUP, 1.0F);
            }
            ChatCmds.broadcast(s, ChatFormatting.YELLOW, name(Qol.me(c)) + " cheers for everyone!");
            return 1;
        });

        // ---- things from the sky
        Qol.custom(C, "itemrain", 2, "<item> [count]", "Rain an item down on yourself",
                Qol.lit("itemrain", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(ITEMS)
                        .executes(Qol.guard(c -> itemRain(c.getSource(), StringArgumentType.getString(c, "word"), 20)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 200))
                                .executes(Qol.guard(c -> itemRain(c.getSource(), StringArgumentType.getString(c, "word"), IntegerArgumentType.getInteger(c, "n")))))));

        Qol.custom(C, "mobrain", 2, "<creature> [count]", "Rain creatures down on yourself (they fall gently)",
                Qol.lit("mobrain", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(FunCmds.ENTITIES)
                        .executes(Qol.guard(c -> mobRain(c.getSource(), StringArgumentType.getString(c, "word"), 10)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 30))
                                .executes(Qol.guard(c -> mobRain(c.getSource(), StringArgumentType.getString(c, "word"), IntegerArgumentType.getInteger(c, "n")))))));

        Qol.integer(C, "fireworkshow", 2, "[seconds]", "A firework show above every player (default 10 seconds)", 1, 60, true, (c, n) -> {
            int secs = n == null ? 10 : n;
            MinecraftServer s = c.getSource().getServer();
            repeat(Qol.me(c).getUUID(), "fireworkshow", 6L, secs * 20 / 6, () -> {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                    PlayerCmds.firework(p.serverLevel(), p.position().add(RANDOM.nextInt(15) - 7, 2.0, RANDOM.nextInt(15) - 7));
                }
            });
            return Qol.ok(c, "Show time for " + secs + " seconds.");
        });

        Qol.integer(C, "disco", 2, "[seconds]", "Music notes and flashes around everyone (default 10 seconds)", 1, 60, true, (c, n) -> {
            int secs = n == null ? 10 : n;
            MinecraftServer s = c.getSource().getServer();
            repeat(Qol.me(c).getUUID(), "disco", 4L, secs * 5, () -> {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                    burst(p, ParticleTypes.NOTE, 4);
                    burst(p, RANDOM.nextBoolean() ? ParticleTypes.END_ROD : ParticleTypes.FIREWORK, 3);
                    ChatCmds.sound(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F + RANDOM.nextFloat() * 1.5F);
                }
            });
            return Qol.ok(c, "Disco for " + secs + " seconds.");
        });

        Qol.player(C, "dance", 2, "Make a player dance for 5 seconds", (c, t) -> {
            int[] ticks = {0};
            repeat(t.getUUID(), "dance", 2L, 50, () -> {
                if (t.isRemoved()) {
                    return;
                }
                ticks[0]++;
                t.teleportTo(t.serverLevel(), t.getX(), t.getY(), t.getZ(), t.getYRot() + 30.0F, t.getXRot());
                if (ticks[0] % 5 == 0 && t.onGround()) {
                    t.setDeltaMovement(t.getDeltaMovement().add(0.0, 0.42, 0.0));
                    t.hurtMarked = true;
                    burst(t, ParticleTypes.NOTE, 3);
                }
            });
            return Qol.ok(c, name(t) + " dances.");
        });

        Qol.integer(C, "earthquake", 2, "[seconds]", "Shake everyone's screen for a while (default 5 seconds)", 1, 30, true, (c, n) -> {
            int secs = n == null ? 5 : n;
            MinecraftServer s = c.getSource().getServer();
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                ChatCmds.sound(p, SoundEvents.GENERIC_EXPLODE, 0.5F);
            }
            repeat(Qol.me(c).getUUID(), "earthquake", 2L, secs * 10, () -> {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                    p.setDeltaMovement(p.getDeltaMovement().add((RANDOM.nextDouble() - 0.5) * 0.25, 0.0, (RANDOM.nextDouble() - 0.5) * 0.25));
                    p.hurtMarked = true;
                }
            });
            return Qol.ok(c, "The ground shakes for " + secs + " seconds.");
        });

        Qol.integer(C, "bounce", 2, "[times]", "Bounce yourself up and down (default 6 times)", 1, 30, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            int times = n == null ? 6 : n;
            repeat(p.getUUID(), "bounce", 12L, times, () -> {
                if (p.isRemoved()) {
                    return;
                }
                p.resetFallDistance();
                p.setDeltaMovement(p.getDeltaMovement().add(0.0, 0.9, 0.0));
                p.hurtMarked = true;
                ChatCmds.sound(p, SoundEvents.SLIME_JUMP, 1.0F);
            });
            return Qol.ok(c, "Boing.");
        });

        // ---- harmless pranks and toys
        Qol.player(C, "pumpkin", 2, "Put a carved pumpkin on a head, or take it off again", (c, t) -> {
            ItemStack head = t.getItemBySlot(EquipmentSlot.HEAD);
            if (head.is(Items.CARVED_PUMPKIN)) {
                t.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                return Qol.ok(c, name(t) + " has a free head again.");
            }
            if (!head.isEmpty() && !t.getInventory().add(head.copy())) {
                t.drop(head.copy(), false);
            }
            t.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            return Qol.ok(c, name(t) + " is wearing a pumpkin.");
        });

        Qol.none(C, "snowfight", 2, "Give everyone 64 snowballs", c -> {
            int n = 0;
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                give(p, Items.SNOWBALL, 64);
                n++;
            }
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, "Snowball fight!");
            return n;
        });

        Qol.playerReq(C, "fakeop", 2, "Show a player a made up message that they were made an operator", (c, t) -> {
            t.sendSystemMessage(Component.translatable("chat.type.admin", "Server", Component.translatable("commands.op.success", name(t)))
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return Qol.ok(c, "Only " + name(t) + " saw it.");
        });

        Qol.playerText(C, "swapinv", 3, "<player> <player>", "Swap the whole inventories of two players", (c, a, b) -> {
            ServerPlayer x = Qol.one(c.getSource(), a);
            ServerPlayer y = Qol.one(c.getSource(), b.trim());
            if (x == y) {
                throw new Bad("Pick two different players.");
            }
            ListTag lx = x.getInventory().save(new ListTag());
            ListTag ly = y.getInventory().save(new ListTag());
            x.getInventory().load(ly);
            y.getInventory().load(lx);
            x.inventoryMenu.broadcastChanges();
            y.inventoryMenu.broadcastChanges();
            return Qol.ok(c, "Swapped the inventories of " + name(x) + " and " + name(y) + ".");
        });

        Qol.player(C, "moonjump", 2, "Jump very high and fall slowly, until you switch it off", (c, t) -> {
            toggleEffects(t, new MobEffect[]{MobEffects.JUMP, MobEffects.SLOW_FALLING}, new int[]{4, 0});
            return Qol.ok(c, "Moon jump " + (t.hasEffect(MobEffects.JUMP) ? "on" : "off") + " for " + name(t) + ".");
        });

        Qol.player(C, "turbo", 2, "Run and dig much faster, until you switch it off", (c, t) -> {
            toggleEffects(t, new MobEffect[]{MobEffects.MOVEMENT_SPEED, MobEffects.DIG_SPEED}, new int[]{3, 2});
            return Qol.ok(c, "Turbo " + (t.hasEffect(MobEffects.MOVEMENT_SPEED) ? "on" : "off") + " for " + name(t) + ".");
        });

        Qol.word(C, "soundall", 2, "<sound>", "Play a game sound for everyone", false, SOUNDS, (c, w) -> {
            ResourceLocation rl = ResourceLocation.tryParse(w.contains(":") ? w : "minecraft:" + w.toLowerCase(Locale.ROOT));
            SoundEvent se = rl == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(rl).orElse(null);
            if (se == null) {
                throw new Bad("There is no sound called " + w + ".");
            }
            int n = 0;
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                p.playNotifySound(se, SoundSource.MASTER, 1.0F, 1.0F);
                n++;
            }
            return Qol.ok(c, "Played it for " + n + " player" + (n == 1 ? "" : "s") + ".");
        });
    }

    private static int lottery(CommandSourceStack src, String word, int n) throws CommandSyntaxException {
        Item it = findItem(word);
        List<ServerPlayer> all = src.getServer().getPlayerList().getPlayers();
        if (all.isEmpty()) {
            throw new Bad("Nobody is online.");
        }
        ServerPlayer w = all.get(RANDOM.nextInt(all.size()));
        give(w, it, n);
        ChatCmds.broadcast(src.getServer(), ChatFormatting.GOLD, "Lottery: " + name(w) + " wins " + n + " " + word + "!");
        return 1;
    }

    private static int itemRain(CommandSourceStack src, String word, int n) throws CommandSyntaxException {
        Item it = findItem(word);
        ServerPlayer p = Qol.self(src);
        ServerLevel l = p.serverLevel();
        for (int i = 0; i < n; i++) {
            ItemEntity e = new ItemEntity(l, p.getX() + RANDOM.nextInt(9) - 4, p.getY() + 8.0, p.getZ() + RANDOM.nextInt(9) - 4, new ItemStack(it));
            e.setDeltaMovement((RANDOM.nextDouble() - 0.5) * 0.1, 0.0, (RANDOM.nextDouble() - 0.5) * 0.1);
            l.addFreshEntity(e);
        }
        src.sendSuccess(() -> Component.literal("It is raining " + word + "."), false);
        return n;
    }

    private static int mobRain(CommandSourceStack src, String word, int n) throws CommandSyntaxException {
        ResourceLocation rl = ResourceLocation.tryParse(word.contains(":") ? word : "minecraft:" + word.toLowerCase(Locale.ROOT));
        EntityType<?> et = rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        if (et == null) {
            throw new Bad("There is no creature called " + word + ".");
        }
        ServerPlayer p = Qol.self(src);
        ServerLevel l = p.serverLevel();
        int made = 0;
        for (int i = 0; i < n; i++) {
            Entity e = et.create(l);
            if (e == null || e instanceof ServerPlayer) {
                throw new Bad("That cannot be spawned.");
            }
            e.moveTo(p.getX() + RANDOM.nextInt(7) - 3, p.getY() + 10.0, p.getZ() + RANDOM.nextInt(7) - 3, RANDOM.nextFloat() * 360.0F, 0.0F);
            if (e instanceof Mob m) {
                m.finalizeSpawn(l, l.getCurrentDifficultyAt(e.blockPosition()), MobSpawnType.COMMAND, null, null);
            }
            if (e instanceof LivingEntity le) {
                le.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0));
            }
            l.addFreshEntity(e);
            made++;
        }
        final int count = made;
        src.sendSuccess(() -> Component.literal("It is raining " + word + " (" + count + ")."), false);
        return made;
    }
}
