package khi.pentecost.game.qol;

import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WorldCmds {
    private static final String C = "World";

    private WorldCmds() {}

    private static void time(String name, long ticks) {
        Qol.none(C, name, 2, "Set the time to " + name + " (" + ticks + " ticks)", c -> {
            ServerLevel l = c.getSource().getServer().overworld();
            l.setDayTime(l.getDayTime() - l.getDayTime() % 24000L + ticks);
            return Qol.ok(c, "It is now " + name + ".");
        });
    }

    private static void rule(String name, String desc, GameRules.Key<GameRules.BooleanValue> key, boolean inverted, String onText, String offText) {
        Qol.none(C, name, 2, desc, c -> {
            MinecraftServer server = c.getSource().getServer();
            GameRules.BooleanValue v = server.getGameRules().getRule(key);
            v.set(!v.get(), server);
            boolean shown = inverted ? !v.get() : v.get();
            return Qol.ok(c, shown ? onText : offText);
        });
    }

    private static AABB around(ServerPlayer p, int r) {
        return p.getBoundingBox().inflate(r);
    }

    private static int radius(Integer n) {
        return n == null ? 64 : n;
    }

    static void add() {
        time("day", 1000);
        time("noon", 6000);
        time("sunset", 12000);
        time("night", 13000);
        time("midnight", 18000);
        time("sunrise", 23000);

        Qol.none(C, "sun", 2, "Clear the weather", c -> {
            c.getSource().getServer().overworld().setWeatherParameters(6000, 0, false, false);
            return Qol.ok(c, "Clear skies.");
        });
        Qol.none(C, "rain", 2, "Make it rain", c -> {
            c.getSource().getServer().overworld().setWeatherParameters(0, 6000, true, false);
            return Qol.ok(c, "Rain.");
        });
        Qol.none(C, "storm", 2, "Make a thunderstorm", c -> {
            c.getSource().getServer().overworld().setWeatherParameters(0, 6000, true, true);
            return Qol.ok(c, "Thunderstorm.");
        });

        rule("daylock", "Stop or restart the day and night cycle", GameRules.RULE_DAYLIGHT, true,
                "Time is locked.", "Time is running again.");
        rule("weatherlock", "Stop or restart weather changes", GameRules.RULE_WEATHER_CYCLE, true,
                "Weather is locked.", "Weather changes again.");
        rule("keepinv", "Keep your items when you die, or lose them again", GameRules.RULE_KEEPINVENTORY, false,
                "You keep your items when you die.", "You drop your items when you die.");
        rule("mobgrief", "Let mobs break blocks, or stop them", GameRules.RULE_MOBGRIEFING, false,
                "Mobs can break blocks.", "Mobs can no longer break blocks.");
        rule("firespread", "Let fire spread, or stop it", GameRules.RULE_DOFIRETICK, false,
                "Fire spreads.", "Fire stays where it is.");

        Qol.word(C, "diff", 2, "<peaceful|easy|normal|hard>", "Change the difficulty", false,
                Qol.options("peaceful", "easy", "normal", "hard"), (c, w) -> {
                    Difficulty d = Difficulty.byName(w.toLowerCase(Locale.ROOT));
                    if (d == null) {
                        throw new Bad("Pick peaceful, easy, normal or hard.");
                    }
                    c.getSource().getServer().setDifficulty(d, true);
                    return Qol.ok(c, "Difficulty is " + d.getKey() + ".");
                });

        Qol.word(C, "pvp", 2, "<on|off>", "Allow or stop players fighting each other", false, Qol.options("on", "off"), (c, w) -> {
            boolean on = w.equalsIgnoreCase("on");
            if (!on && !w.equalsIgnoreCase("off")) {
                throw new Bad("Say on or off.");
            }
            c.getSource().getServer().setPvpAllowed(on);
            return Qol.ok(c, "PvP is " + (on ? "on" : "off") + ".");
        });

        Qol.none(C, "setspawn", 2, "Make where you stand the world spawn", c -> {
            ServerPlayer p = Qol.me(c);
            p.serverLevel().setDefaultSpawnPos(p.blockPosition(), p.getYRot());
            return Qol.ok(c, "World spawn is now " + Where.describe(Where.of(p)) + ".");
        });

        Qol.integer(C, "killhostile", 2, "[radius]", "Kill hostile mobs around you (default 64 blocks)", 1, 512, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            int count = 0;
            for (LivingEntity e : p.serverLevel().getEntitiesOfClass(LivingEntity.class, around(p, radius(n)), x -> x instanceof Enemy)) {
                e.kill();
                count++;
            }
            return Qol.ok(c, "Killed " + count + " hostile mob" + (count == 1 ? "" : "s") + ".");
        });

        Qol.integer(C, "killitems", 2, "[radius]", "Remove dropped items around you (default 64 blocks)", 1, 512, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            int count = 0;
            for (ItemEntity e : p.serverLevel().getEntitiesOfClass(ItemEntity.class, around(p, radius(n)))) {
                e.discard();
                count++;
            }
            return Qol.ok(c, "Removed " + count + " dropped item stack" + (count == 1 ? "" : "s") + ".");
        });

        Qol.integer(C, "butcher", 2, "[radius]", "Kill every mob around you except named ones and tamed pets (default 64 blocks)", 1, 512, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            int count = 0;
            for (LivingEntity e : p.serverLevel().getEntitiesOfClass(LivingEntity.class, around(p, radius(n)),
                    x -> !(x instanceof ServerPlayer) && !x.hasCustomName() && !(x instanceof TamableAnimal t && t.isTame()))) {
                e.kill();
                count++;
            }
            return Qol.ok(c, "Killed " + count + " mob" + (count == 1 ? "" : "s") + ".");
        });

        Qol.integer(C, "census", 0, "[radius]", "Count the creatures around you by kind (default 64 blocks)", 1, 512, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            Map<String, Integer> counts = new HashMap<>();
            for (Entity e : p.serverLevel().getEntities((Entity) null, around(p, radius(n)), x -> true)) {
                counts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), 1, Integer::sum);
            }
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                sb.append(i == 0 ? "" : ", ").append(sorted.get(i).getKey()).append(" ").append(sorted.get(i).getValue());
            }
            return Qol.ok(c, sorted.isEmpty() ? "Nothing here." : "Around you: " + sb);
        });

        Qol.integer(C, "platform", 2, "[radius]", "Put a glass floor under you (default radius 2)", 1, 16, true, (c, n) -> {
            ServerPlayer p = Qol.me(c);
            ServerLevel l = p.serverLevel();
            int r = n == null ? 2 : n;
            BlockPos base = p.blockPosition().below();
            int placed = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos bp = base.offset(dx, 0, dz);
                    if (l.getBlockState(bp).isAir()) {
                        l.setBlockAndUpdate(bp, Blocks.GLASS.defaultBlockState());
                        placed++;
                    }
                }
            }
            return Qol.ok(c, "Placed " + placed + " glass blocks.");
        });
    }
}
