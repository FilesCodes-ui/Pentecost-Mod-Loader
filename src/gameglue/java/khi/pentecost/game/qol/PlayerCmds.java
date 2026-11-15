package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/** What a player is: health, food, flight, game mode, effects, riding, and a few loud ways to get attention. */
final class PlayerCmds {
    private static final String C = "Player";
    private static final Random RANDOM = new Random();

    private PlayerCmds() {}

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    /** Switches an effect on or off. It lasts until switched off, with no particles. */
    private static boolean toggle(ServerPlayer p, MobEffect effect, int amplifier) {
        if (p.hasEffect(effect)) {
            p.removeEffect(effect);
            return false;
        }
        p.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier, false, false, true));
        return true;
    }

    static void effect(String command, String desc, MobEffect effect) {
        // /name  /name <level 1-10>  /name <level> <player>
        LiteralArgumentBuilder<CommandSourceStack> b = Qol.lit(command, 2)
                .executes(Qol.guard(c -> {
                    ServerPlayer p = Qol.me(c);
                    return Qol.ok(c, command + (toggle(p, effect, 0) ? " on." : " off."));
                }))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 10))
                        .executes(Qol.guard(c -> {
                            ServerPlayer p = Qol.me(c);
                            return Qol.ok(c, command + (toggle(p, effect, IntegerArgumentType.getInteger(c, "n") - 1) ? " on." : " off."));
                        }))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(Qol.PLAYERS)
                                .executes(Qol.guard(c -> {
                                    int n = 0;
                                    for (ServerPlayer p : Qol.resolve(c.getSource(), StringArgumentType.getString(c, "player"))) {
                                        toggle(p, effect, IntegerArgumentType.getInteger(c, "n") - 1);
                                        n++;
                                    }
                                    return Qol.ok(c, "Toggled " + command + " for " + n + " player" + (n == 1 ? "" : "s") + ".");
                                }))));
        Qol.custom(C, command, 2, "[level] [player]", desc, b);
    }

    private static void mode(String command, String label, GameType type) {
        Qol.player(C, command, 2, "Switch to " + label + " mode", (c, p) -> {
            p.setGameMode(type);
            return Qol.ok(c, name(p) + " is now in " + label + " mode.");
        });
    }

    static void bolt(ServerLevel l, Vec3 at) {
        LightningBolt b = EntityType.LIGHTNING_BOLT.create(l);
        if (b != null) {
            b.moveTo(at);
            l.addFreshEntity(b);
        }
    }

    static void firework(ServerLevel l, Vec3 at) {
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        CompoundTag fw = rocket.getOrCreateTagElement("Fireworks");
        fw.putByte("Flight", (byte) 1);
        ListTag list = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putByte("Type", (byte) RANDOM.nextInt(5));
        e.putIntArray("Colors", new int[]{RANDOM.nextInt(0xFFFFFF + 1), RANDOM.nextInt(0xFFFFFF + 1)});
        list.add(e);
        fw.put("Explosions", list);
        l.addFreshEntity(new FireworkRocketEntity(l, at.x, at.y, at.z, rocket));
    }

    static void add() {
        Qol.player(C, "heal", 2, "Full health, food and no fire", (c, p) -> {
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(20.0F);
            p.clearFire();
            return Qol.ok(c, "Healed " + name(p) + ".");
        });
        Qol.player(C, "feed", 2, "Fill the food bar", (c, p) -> {
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(20.0F);
            return Qol.ok(c, "Fed " + name(p) + ".");
        });
        Qol.player(C, "fly", 2, "Switch flying on or off", (c, p) -> {
            var a = p.getAbilities();
            a.mayfly = !a.mayfly;
            if (!a.mayfly) {
                a.flying = false;
            }
            p.onUpdateAbilities();
            return Qol.ok(c, "Flying " + (a.mayfly ? "on" : "off") + " for " + name(p) + ".");
        });
        Qol.custom(C, "flyspeed", 2, "<1-10> [player]", "Set how fast you fly (1 is normal)",
                Qol.lit("flyspeed", 2).then(Commands.argument("n", IntegerArgumentType.integer(1, 10))
                        .executes(Qol.guard(c -> setFlySpeed(c.getSource(), Qol.me(c), IntegerArgumentType.getInteger(c, "n"))))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(Qol.PLAYERS)
                                .executes(Qol.guard(c -> {
                                    int n = 0;
                                    for (ServerPlayer p : Qol.resolve(c.getSource(), StringArgumentType.getString(c, "player"))) {
                                        n += setFlySpeed(c.getSource(), p, IntegerArgumentType.getInteger(c, "n"));
                                    }
                                    return n;
                                })))));
        Qol.player(C, "god", 2, "Switch invulnerability on or off", (c, p) -> {
            var a = p.getAbilities();
            a.invulnerable = !a.invulnerable;
            p.onUpdateAbilities();
            return Qol.ok(c, "God mode " + (a.invulnerable ? "on" : "off") + " for " + name(p) + ".");
        });
        Qol.player(C, "extinguish", 2, "Put out the fire", (c, p) -> {
            p.clearFire();
            return Qol.ok(c, "Put out " + name(p) + ".");
        });
        Qol.player(C, "breath", 2, "Fill the air bar", (c, p) -> {
            p.setAirSupply(p.getMaxAirSupply());
            return Qol.ok(c, "Breathing room for " + name(p) + ".");
        });
        Qol.player(C, "cure", 2, "Remove every effect", (c, p) -> {
            p.removeAllEffects();
            return Qol.ok(c, "Cured " + name(p) + ".");
        });
        Qol.none(C, "suicide", 0, "Kill yourself", c -> {
            Qol.me(c).kill();
            return 1;
        });
        Qol.integer(C, "lvl", 2, "<levels>", "Set your experience level", 0, 30000, false, (c, n) -> {
            Qol.me(c).setExperienceLevels(n);
            return Qol.ok(c, "Level " + n + ".");
        });

        mode("gms", "survival", GameType.SURVIVAL);
        mode("gmc", "creative", GameType.CREATIVE);
        mode("gma", "adventure", GameType.ADVENTURE);
        mode("gmsp", "spectator", GameType.SPECTATOR);

        effect("nightvision", "See in the dark until you switch it off", MobEffects.NIGHT_VISION);
        effect("invis", "Turn invisible until you switch it off", MobEffects.INVISIBILITY);
        effect("glow", "Glow so others can see you through walls", MobEffects.GLOWING);
        effect("swift", "Move faster", MobEffects.MOVEMENT_SPEED);
        effect("leap", "Jump higher", MobEffects.JUMP);
        effect("haste", "Mine faster", MobEffects.DIG_SPEED);
        effect("might", "Hit harder", MobEffects.DAMAGE_BOOST);
        effect("regen", "Heal over time", MobEffects.REGENERATION);
        effect("shield", "Take less damage", MobEffects.DAMAGE_RESISTANCE);
        effect("gills", "Breathe underwater", MobEffects.WATER_BREATHING);
        effect("fireproof", "Fire and lava cannot hurt you", MobEffects.FIRE_RESISTANCE);

        Qol.player(C, "freeze", 2, "Hold a player still", (c, p) -> {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MobEffectInstance.INFINITE_DURATION, 255, false, false, false));
            p.addEffect(new MobEffectInstance(MobEffects.JUMP, MobEffectInstance.INFINITE_DURATION, 200, false, false, false));
            return Qol.ok(c, "Froze " + name(p) + ". /unfreeze lets them go.");
        });
        Qol.player(C, "unfreeze", 2, "Let a frozen player move again", (c, p) -> {
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            p.removeEffect(MobEffects.JUMP);
            return Qol.ok(c, "Unfroze " + name(p) + ".");
        });

        Qol.playerReq(C, "ride", 2, "Climb onto a player's head", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (t == me) {
                throw new Bad("That is you.");
            }
            me.startRiding(t, true);
            return Qol.ok(c, "Riding " + name(t) + ".");
        });
        Qol.playerReq(C, "carry", 2, "Make a player climb onto you", (c, t) -> {
            ServerPlayer me = Qol.me(c);
            if (t == me) {
                throw new Bad("That is you.");
            }
            t.startRiding(me, true);
            return Qol.ok(c, "Carrying " + name(t) + ".");
        });
        Qol.none(C, "dismount", 2, "Get off whatever you are riding", c -> {
            Qol.me(c).stopRiding();
            return Qol.ok(c, "Down.");
        });
        Qol.none(C, "eject", 2, "Throw off everything riding you", c -> {
            Qol.me(c).ejectPassengers();
            return Qol.ok(c, "Clear.");
        });

        Qol.player(C, "burn", 2, "Set a player on fire for 10 seconds", (c, p) -> {
            p.setSecondsOnFire(10);
            return Qol.ok(c, name(p) + " is on fire.");
        });
        Qol.playerReq(C, "smite", 2, "Strike a player with lightning", (c, p) -> {
            bolt(p.serverLevel(), p.position());
            return Qol.ok(c, "Smote " + name(p) + ".");
        });
        Qol.whereOpt(C, "lightning", 2, "Strike a place with lightning (where you look when left out)", (c, s) -> {
            bolt(s.level(), s.pos());
            return Qol.ok(c, "Lightning at " + Where.describe(s) + ".");
        });
        Qol.whereOpt(C, "firework", 2, "Launch a firework at a place (where you look when left out)", (c, s) -> {
            firework(s.level(), s.pos());
            return 1;
        });
        Qol.none(C, "celebrate", 2, "Launch a firework above every player", c -> {
            int n = 0;
            for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
                firework(p.serverLevel(), p.position().add(0.0, 1.0, 0.0));
                n++;
            }
            return Qol.ok(c, "Fireworks for " + n + ".");
        });
        Qol.integer(C, "boom", 2, "[power 1-10]", "Blow up the block you are looking at (default power 3)", 1, 10, true, (c, n) -> {
            Where.Spot s = Where.look(c.getSource());
            s.level().explode(null, s.pos().x, s.pos().y, s.pos().z, n == null ? 3 : n, Level.ExplosionInteraction.TNT);
            return Qol.ok(c, "Boom.");
        });
    }

    private static int setFlySpeed(CommandSourceStack src, ServerPlayer p, int n) {
        p.getAbilities().setFlyingSpeed(n * 0.05F);
        p.onUpdateAbilities();
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Fly speed " + n + " for " + name(p) + "."), false);
        return 1;
    }
}
