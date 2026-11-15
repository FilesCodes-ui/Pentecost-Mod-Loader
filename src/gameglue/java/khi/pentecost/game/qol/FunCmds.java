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
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Games, pranks, mobs, particles and sounds. */
final class FunCmds {
    private static final String C = "Fun";
    private static final Random RANDOM = new Random();
    private static final Pattern DICE = Pattern.compile("(\\d{1,2})?d(\\d{1,4})", Pattern.CASE_INSENSITIVE);
    private static final String[] BALL = {"It is certain.", "Without a doubt.", "Yes.", "Most likely.", "Signs point to yes.",
            "Ask again later.", "I cannot say right now.", "Do not count on it.", "My sources say no.", "Very doubtful."};

    static final SuggestionProvider<CommandSourceStack> ENTITIES = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);
    private static final SuggestionProvider<CommandSourceStack> PARTICLES = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.PARTICLE_TYPE.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);
    private static final SuggestionProvider<CommandSourceStack> SOUNDS = (c, b) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.SOUND_EVENT.keySet().stream().map(r -> r.getNamespace().equals("minecraft") ? r.getPath() : r.toString()), b);

    private FunCmds() {}

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    private static ResourceLocation id(String s) {
        ResourceLocation rl = ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s.toLowerCase(Locale.ROOT));
        if (rl == null) {
            throw new Bad("\"" + s + "\" is not a valid name.");
        }
        return rl;
    }

    private static void burst(ServerPlayer t, ParticleOptions o, int n) {
        t.serverLevel().sendParticles(o, t.getX(), t.getY() + 1.0, t.getZ(), n, 0.4, 0.5, 0.4, 0.05);
    }

    private static void fx(String name, String desc, ParticleOptions o, int n) {
        Qol.player(C, name, 0, desc, (c, t) -> {
            burst(t, o, n);
            return 1;
        });
    }

    private static int spawn(CommandSourceStack src, String type, int n) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.getOptional(id(type)).orElseThrow(() -> new Bad("There is no creature called " + type + "."));
        Where.Spot at;
        try {
            at = Where.look(src);
        } catch (Bad | CommandSyntaxException e) {
            at = Where.of(p);
        }
        ServerLevel l = at.level();
        int made = 0;
        for (int i = 0; i < n; i++) {
            Entity e = et.create(l);
            if (e == null) {
                throw new Bad("That cannot be spawned.");
            }
            e.moveTo(at.pos().x, at.pos().y, at.pos().z, p.getYRot(), 0.0F);
            if (e instanceof Mob m) {
                m.finalizeSpawn(l, l.getCurrentDifficultyAt(e.blockPosition()), MobSpawnType.COMMAND, null, null);
            }
            l.addFreshEntity(e);
            made++;
        }
        final int count = made;
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Spawned " + count + " " + type + "."), false);
        return made;
    }

    static void add() {
        // ---- games
        Qol.integer(C, "dice", 0, "[sides]", "Roll a die for everyone to see (default 6 sides)", 2, 1000, true, (c, n) -> {
            int sides = n == null ? 6 : n;
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA,
                    name(Qol.me(c)) + " rolled a " + (RANDOM.nextInt(sides) + 1) + " (d" + sides + ").");
            return 1;
        });

        Qol.none(C, "coinflip", 0, "Flip a coin for everyone to see", c -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA,
                    name(Qol.me(c)) + " flipped " + (RANDOM.nextBoolean() ? "heads." : "tails."));
            return 1;
        });

        Qol.word(C, "roll", 0, "<NdM>", "Roll dice such as 2d6 or d20", false, Qol.options("d6", "2d6", "d20", "d100"), (c, w) -> {
            Matcher m = DICE.matcher(w);
            if (!m.matches()) {
                throw new Bad("Write it like 2d6 or d20.");
            }
            int count = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            int sides = Integer.parseInt(m.group(2));
            if (count < 1 || sides < 2) {
                throw new Bad("Roll at least one die with at least two sides.");
            }
            int total = 0;
            StringBuilder parts = new StringBuilder();
            for (int i = 0; i < count; i++) {
                int v = RANDOM.nextInt(sides) + 1;
                total += v;
                parts.append(i == 0 ? "" : " + ").append(v);
            }
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA,
                    name(Qol.me(c)) + " rolled " + w + ": " + parts + (count > 1 ? " = " + total : "") + ".");
            return total;
        });

        Qol.text(C, "pick", 0, "<a, b, c...>", "Pick one of the options, separated by commas or spaces", (c, t) -> {
            String[] options = t.contains(",") ? t.split("\\s*,\\s*") : t.split("\\s+");
            String choice = options[RANDOM.nextInt(options.length)];
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, name(Qol.me(c)) + " asks the dice: " + choice + ".");
            return 1;
        });

        Qol.text(C, "eightball", 0, "<question>", "Ask the magic 8-ball", (c, t) -> {
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.LIGHT_PURPLE,
                    name(Qol.me(c)) + " asks: " + t + " - " + BALL[RANDOM.nextInt(BALL.length)]);
            return 1;
        });

        Qol.word(C, "rps", 0, "<rock|paper|scissors>", "Play rock paper scissors against the server", false,
                Qol.options("rock", "paper", "scissors"), (c, w) -> {
                    String[] hands = {"rock", "paper", "scissors"};
                    int mine = -1;
                    for (int i = 0; i < 3; i++) {
                        if (hands[i].equalsIgnoreCase(w)) {
                            mine = i;
                        }
                    }
                    if (mine < 0) {
                        throw new Bad("Pick rock, paper or scissors.");
                    }
                    int theirs = RANDOM.nextInt(3);
                    String result = mine == theirs ? "a draw" : (mine - theirs + 3) % 3 == 1 ? "you win" : "you lose";
                    return Qol.ok(c, "You: " + hands[mine] + ", server: " + hands[theirs] + " - " + result + ".");
                });

        Qol.none(C, "randplayer", 0, "Pick a random online player", c -> {
            var list = c.getSource().getServer().getPlayerList().getPlayers();
            if (list.isEmpty()) {
                throw new Bad("Nobody is online.");
            }
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.AQUA, "The dice pick " + name(list.get(RANDOM.nextInt(list.size()))) + ".");
            return 1;
        });

        // ---- friendly and not so friendly gestures
        Qol.playerReq(C, "hug", 0, "Give a player a hug", (c, t) -> {
            burst(t, ParticleTypes.HEART, 12);
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.LIGHT_PURPLE, name(Qol.me(c)) + " hugs " + name(t) + ".");
            return 1;
        });
        Qol.playerReq(C, "pat", 0, "Pat a player on the head", (c, t) -> {
            burst(t, ParticleTypes.HAPPY_VILLAGER, 10);
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.GREEN, name(Qol.me(c)) + " pats " + name(t) + ".");
            return 1;
        });
        Qol.playerReq(C, "bonk", 0, "Bonk a player (a sound and sparks, no damage)", (c, t) -> {
            burst(t, ParticleTypes.CRIT, 15);
            ChatCmds.sound(t, SoundEvents.ANVIL_LAND, 1.8F);
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.YELLOW, name(Qol.me(c)) + " bonks " + name(t) + ".");
            return 1;
        });
        Qol.playerReq(C, "slap", 2, "Slap a player lightly (1 damage and a shove)", (c, t) -> {
            Vec3 look = Qol.me(c).getLookAngle();
            t.hurt(t.damageSources().generic(), 1.0F);
            t.setDeltaMovement(t.getDeltaMovement().add(look.x * 0.6, 0.3, look.z * 0.6));
            t.hurtMarked = true;
            ChatCmds.broadcast(c.getSource().getServer(), ChatFormatting.RED, name(Qol.me(c)) + " slaps " + name(t) + ".");
            return 1;
        });
        Qol.playerReq(C, "yeet", 2, "Throw a player a long way", (c, t) -> {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            t.setDeltaMovement(Math.cos(a) * 3.0, 1.6, Math.sin(a) * 3.0);
            t.hurtMarked = true;
            return Qol.ok(c, "Yeeted " + name(t) + ".");
        });
        Qol.playerReq(C, "toss", 2, "Throw a player straight up", (c, t) -> {
            t.setDeltaMovement(t.getDeltaMovement().add(0.0, 2.5, 0.0));
            t.hurtMarked = true;
            return Qol.ok(c, "Tossed " + name(t) + ".");
        });
        Qol.playerReq(C, "spin", 2, "Spin a player round for a second", (c, t) -> {
            int[] left = {20};
            int[] timer = {0};
            timer[0] = Timers.add(t.getUUID(), "spin", 1, 1, () -> {
                if (t.isRemoved() || --left[0] <= 0) {
                    Timers.cancel(t.getUUID(), timer[0]);
                }
                t.teleportTo(t.serverLevel(), t.getX(), t.getY(), t.getZ(), t.getYRot() + 36.0F, t.getXRot());
            });
            return Qol.ok(c, "Spinning " + name(t) + ".");
        });
        Qol.playerReq(C, "anvildrop", 2, "Drop an anvil on a player", (c, t) -> {
            ServerLevel l = t.serverLevel();
            BlockPos at = t.blockPosition().above(6);
            if (!l.getBlockState(at).isAir()) {
                throw new Bad("There is no room above " + name(t) + ".");
            }
            FallingBlockEntity.fall(l, at, Blocks.ANVIL.defaultBlockState());
            return Qol.ok(c, "Anvil away.");
        });
        Qol.playerReq(C, "scare", 2, "Give a player a fright", (c, t) -> {
            ChatCmds.title(t, "BOO!", "", 0, 20, 10);
            ChatCmds.sound(t, SoundEvents.CREEPER_PRIMED, 1.0F);
            return Qol.ok(c, "Boo.");
        });
        Qol.whereOpt(C, "tnt", 2, "Light a TNT block at a place (where you look when left out)", (c, s) -> {
            s.level().addFreshEntity(new PrimedTnt(s.level(), s.pos().x, s.pos().y, s.pos().z, c.getSource().getPlayer()));
            return Qol.ok(c, "Fuse lit.");
        });

        // ---- looks and sounds
        fx("hearts", "Hearts around a player", ParticleTypes.HEART, 20);
        fx("poof", "A puff of cloud around a player", ParticleTypes.CLOUD, 25);
        fx("sparkle", "Sparkles around a player", ParticleTypes.END_ROD, 25);

        Qol.none(C, "confetti", 0, "Fireworks all round you", c -> {
            ServerPlayer p = Qol.me(c);
            for (int i = 0; i < 6; i++) {
                PlayerCmds.firework(p.serverLevel(), p.position().add(RANDOM.nextInt(9) - 4, 1.0, RANDOM.nextInt(9) - 4));
            }
            return 1;
        });

        Qol.custom(C, "particles", 2, "<name> [count]", "Show a particle effect around you",
                Qol.lit("particles", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(PARTICLES)
                        .executes(Qol.guard(c -> particles(c.getSource(), StringArgumentType.getString(c, "word"), 20)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 500))
                                .executes(Qol.guard(c -> particles(c.getSource(), StringArgumentType.getString(c, "word"), IntegerArgumentType.getInteger(c, "n")))))));

        Qol.word(C, "sound", 0, "<name>", "Play a game sound for yourself", false, SOUNDS, (c, w) -> {
            SoundEvent s = BuiltInRegistries.SOUND_EVENT.getOptional(id(w)).orElseThrow(() -> new Bad("There is no sound called " + w + "."));
            Qol.me(c).playNotifySound(s, SoundSource.MASTER, 1.0F, 1.0F);
            return 1;
        });

        // ---- creatures
        Qol.custom(C, "spawnmob", 2, "<creature> [count]", "Spawn creatures where you look",
                Qol.lit("spawnmob", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(ENTITIES)
                        .executes(Qol.guard(c -> spawn(c.getSource(), StringArgumentType.getString(c, "word"), 1)))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 50))
                                .executes(Qol.guard(c -> spawn(c.getSource(), StringArgumentType.getString(c, "word"), IntegerArgumentType.getInteger(c, "n")))))));

        Qol.none(C, "horse", 2, "Summon a saddled horse and ride it", c -> {
            ServerPlayer p = Qol.me(c);
            ServerLevel l = p.serverLevel();
            Horse h = EntityType.HORSE.create(l);
            if (h == null) {
                throw new Bad("A horse could not be made.");
            }
            h.moveTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0.0F);
            h.finalizeSpawn(l, l.getCurrentDifficultyAt(p.blockPosition()), MobSpawnType.COMMAND, null, null);
            h.setTamed(true);
            h.equipSaddle(SoundSource.NEUTRAL);
            l.addFreshEntity(h);
            p.startRiding(h);
            return Qol.ok(c, "Yee-haw.");
        });

        // ---- effects that stay until switched off
        PlayerCmds.effect("slowfall", "Fall gently until you switch it off", MobEffects.SLOW_FALLING);
        PlayerCmds.effect("absorb", "Extra golden hearts", MobEffects.ABSORPTION);
        PlayerCmds.effect("luck", "Better loot luck", MobEffects.LUCK);
        PlayerCmds.effect("levitate", "Float upward", MobEffects.LEVITATION);
        PlayerCmds.effect("dolphin", "Swim faster", MobEffects.DOLPHINS_GRACE);
        PlayerCmds.effect("conduit", "Conduit power: breathe, see and mine underwater", MobEffects.CONDUIT_POWER);
        PlayerCmds.effect("hero", "Hero of the village discounts", MobEffects.HERO_OF_THE_VILLAGE);
        PlayerCmds.effect("blind", "Go blind", MobEffects.BLINDNESS);
        PlayerCmds.effect("nausea", "Wobbly screen", MobEffects.CONFUSION);
        PlayerCmds.effect("weak", "Hit softer", MobEffects.WEAKNESS);
        PlayerCmds.effect("saturate", "Never get hungry", MobEffects.SATURATION);
    }

    private static int particles(CommandSourceStack src, String word, int n) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        ParticleType<?> pt = BuiltInRegistries.PARTICLE_TYPE.getOptional(id(word)).orElseThrow(() -> new Bad("There is no particle called " + word + "."));
        if (!(pt instanceof ParticleOptions po)) {
            throw new Bad("That particle needs extra settings and cannot be shown this way.");
        }
        p.serverLevel().sendParticles(po, p.getX(), p.getY() + 1.0, p.getZ(), n, 0.6, 0.6, 0.6, 0.05);
        return 1;
    }
}
