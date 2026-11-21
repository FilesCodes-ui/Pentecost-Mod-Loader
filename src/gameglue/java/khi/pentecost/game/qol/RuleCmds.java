package khi.pentecost.game.qol;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import khi.pentecost.game.qol.Qol.Bad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

final class RuleCmds {
    private static final String C = "Rules";
    private static final Map<String, GameRules.Key<?>> KEYS = new TreeMap<>();
    private static final Map<String, String> DEFAULTS = new TreeMap<>();
    private static final Random RANDOM = new Random();

    private static final double DAY_MIN = 13000.0 / 1200.0;
    private static final double NIGHT_MIN = 11000.0 / 1200.0;

    private static double acc;
    private static Boolean fireLast;

    static final SuggestionProvider<CommandSourceStack> NAMES = (c, b) -> {
        scan();
        return SharedSuggestionProvider.suggest(KEYS.keySet(), b);
    };

    private static final SuggestionProvider<CommandSourceStack> PRESET_NAMES = (c, b) -> {
        TreeSet<String> all = new TreeSet<>(RuleCmds.PRESETS.keySet());
        all.addAll(Store.presetNames());
        return SharedSuggestionProvider.suggest(all, b);
    };

    private static final SuggestionProvider<CommandSourceStack> CUSTOM_PRESET_NAMES = (c, b) ->
            SharedSuggestionProvider.suggest(Store.presetNames(), b);

    private RuleCmds() {}

    private static void scan() {
        if (!KEYS.isEmpty()) {
            return;
        }
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                KEYS.put(key.getId(), key);
                DEFAULTS.put(key.getId(), type.createRule().serialize());
            }
        });
    }

    private static <T extends GameRules.Value<T>> T rule(MinecraftServer s, GameRules.Key<T> k) {
        return s.getGameRules().getRule(k);
    }

    static String get(MinecraftServer s, String name) {
        scan();
        GameRules.Key<?> k = KEYS.get(name);
        return k == null ? null : rule(s, k).serialize();
    }

    static void set(MinecraftServer s, String name, String value) {
        scan();
        GameRules.Key<?> k = KEYS.get(name);
        if (k == null) {
            throw new Bad("There is no game rule called " + name + ". /gr lists them.");
        }
        GameRules.Value<?> cur = rule(s, k);
        if (cur instanceof GameRules.BooleanValue b) {
            if (!value.equals("true") && !value.equals("false")) {
                throw new Bad(name + " is true or false.");
            }
            b.set(Boolean.parseBoolean(value), s);
        } else if (cur instanceof GameRules.IntegerValue i) {
            try {
                i.set(Integer.parseInt(value), s);
            } catch (NumberFormatException e) {
                throw new Bad(name + " takes a whole number.");
            }
        } else {
            throw new Bad("That rule cannot be changed here.");
        }
    }

    private static void toggle(String cmd, String rule, String desc, boolean inverted) {
        scan();
        if (!KEYS.containsKey(rule)) {
            return;
        }
        Qol.none(C, cmd, 2, desc, c -> {
            MinecraftServer s = c.getSource().getServer();
            boolean now = !Boolean.parseBoolean(get(s, rule));
            set(s, rule, String.valueOf(now));
            boolean feature = inverted != now;
            return Qol.ok(c, cmd + " is now " + (feature ? "on" : "off") + " (" + rule + " is " + now + ").");
        });
    }

    private static void tuner(String cmd, String rule, String desc, int min, int max) {
        scan();
        if (!KEYS.containsKey(rule)) {
            return;
        }
        Qol.integer(C, cmd, 2, "[value]", desc, min, max, true, (c, n) -> {
            MinecraftServer s = c.getSource().getServer();
            if (n == null) {
                return Qol.ok(c, rule + " is " + get(s, rule) + " (normal is " + DEFAULTS.get(rule) + ").");
            }
            set(s, rule, String.valueOf(n));
            return Qol.ok(c, rule + " is now " + n + ".");
        });
    }

    private static final Map<String, String[]> PRESETS = new LinkedHashMap<>();

    private static void preset(String name, String... pairs) {
        PRESETS.put(name, pairs);
    }

    static {
        preset("builder", "doDaylightCycle=false", "doWeatherCycle=false", "doMobSpawning=false", "mobGriefing=false",
                "keepInventory=true", "doFireTick=false", "doInsomnia=false", "announceAdvancements=false",
                "doPatrolSpawning=false", "doTraderSpawning=false");
        preset("chill", "keepInventory=true", "doInsomnia=false", "mobGriefing=false", "playersSleepingPercentage=1",
                "announceAdvancements=false", "doPatrolSpawning=false");
        preset("chaos", "randomTickSpeed=30", "mobGriefing=true", "doFireTick=true", "keepInventory=false",
                "doImmediateRespawn=true", "maxEntityCramming=100", "playersSleepingPercentage=100");
        preset("hardcore", "naturalRegeneration=false", "keepInventory=false", "showDeathMessages=true",
                "doImmediateRespawn=false", "universalAnger=true", "forgiveDeadPlayers=false", "reducedDebugInfo=true");
        preset("pvp", "keepInventory=false", "fallDamage=true", "doImmediateRespawn=true", "naturalRegeneration=true",
                "showDeathMessages=true", "doMobSpawning=false");
        preset("speedrun", "doInsomnia=false", "playersSleepingPercentage=1", "doPatrolSpawning=false",
                "doTraderSpawning=false", "announceAdvancements=false", "doImmediateRespawn=true");
        preset("peaceful", "doMobSpawning=false", "mobGriefing=false", "doPatrolSpawning=false", "doTraderSpawning=false",
                "disableRaids=true", "doInsomnia=false", "universalAnger=false");
        preset("farm", "randomTickSpeed=12", "mobGriefing=false", "doInsomnia=false", "doFireTick=false");
        preset("vanilla", "doDaylightCycle=true", "doWeatherCycle=true", "doMobSpawning=true", "mobGriefing=true",
                "keepInventory=false", "doFireTick=true", "doInsomnia=true", "announceAdvancements=true",
                "doPatrolSpawning=true", "doTraderSpawning=true", "randomTickSpeed=3", "playersSleepingPercentage=100",
                "naturalRegeneration=true", "fallDamage=true", "fireDamage=true", "drowningDamage=true", "freezeDamage=true",
                "doImmediateRespawn=false", "spawnRadius=10", "maxEntityCramming=24", "universalAnger=false",
                "forgiveDeadPlayers=true", "disableRaids=false", "doLimitedCrafting=false", "reducedDebugInfo=false",
                "showDeathMessages=true", "doTileDrops=true", "doMobLoot=true", "doEntityDrops=true",
                "sendCommandFeedback=true", "logAdminCommands=true", "commandBlockOutput=true",
                "snowAccumulationHeight=1", "maxCommandChainLength=65536");
    }

    private static int applyPairs(MinecraftServer s, Iterable<String> pairs) {
        int n = 0;
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq > 0 && KEYS.containsKey(p.substring(0, eq))) {
                set(s, p.substring(0, eq), p.substring(eq + 1));
                n++;
            }
        }
        return n;
    }

    private static int setDay(CommandContext<CommandSourceStack> c, double day, double night, String text) {
        MinecraftServer s = c.getSource().getServer();
        Store.setDayFactors(day, night);
        acc = 0.0;
        boolean frozen = day == 0.0 && night == 0.0;
        set(s, "doDaylightCycle", frozen ? "false" : "true");
        return Qol.ok(c, text);
    }

    private static String minutes(double base, double factor) {
        return factor <= 0.0 ? "never" : String.format(Locale.ROOT, "%.1f", base / factor);
    }

    static void tick(MinecraftServer server) {
        int pct = Store.firePct();
        if (pct < 100) {
            boolean want = pct > 0 && RANDOM.nextInt(100) < pct;
            if (fireLast == null || fireLast != want) {
                fireLast = want;
                server.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(want, server);
            }
        } else if (fireLast != null) {
            fireLast = null;
            server.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(true, server);
        }

        double df = Store.dayFactor();
        double nf = Store.nightFactor();
        if (df == 1.0 && nf == 1.0) {
            return;
        }
        ServerLevel o = server.overworld();
        if (!o.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            return;
        }
        long t = o.getDayTime();
        double f = t % 24000L < 13000L ? df : nf;
        acc += f - 1.0;
        long add = (long) acc;
        acc -= add;
        if (add != 0L && t + add >= 0L) {
            o.setDayTime(t + add);
        }
    }

    static void add() {
        scan();

        Qol.custom(C, "gr", 2, "[rule] [value]", "Show, read or change any game rule (tab completes the names)",
                Qol.lit("gr", 2).executes(Qol.guard(c -> {
                            MinecraftServer s = c.getSource().getServer();
                            Map<String, List<String>> groups = new TreeMap<>();
                            for (Map.Entry<String, GameRules.Key<?>> e : KEYS.entrySet()) {
                                groups.computeIfAbsent(e.getValue().getCategory().name().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                                        .add(e.getKey() + "=" + get(s, e.getKey()));
                            }
                            for (Map.Entry<String, List<String>> g : groups.entrySet()) {
                                Qol.ok(c, g.getKey() + ":");
                                List<String> list = g.getValue();
                                for (int i = 0; i < list.size(); i += 3) {
                                    Qol.ok(c, "  " + String.join(", ", list.subList(i, Math.min(list.size(), i + 3))));
                                }
                            }
                            return KEYS.size();
                        }))
                        .then(Commands.argument("rule", StringArgumentType.word()).suggests(NAMES)
                                .executes(Qol.guard(c -> {
                                    String name = StringArgumentType.getString(c, "rule");
                                    String v = get(c.getSource().getServer(), name);
                                    if (v == null) {
                                        throw new Bad("There is no game rule called " + name + ".");
                                    }
                                    return Qol.ok(c, name + " is " + v + " (normal is " + DEFAULTS.get(name) + ").");
                                }))
                                .then(Commands.argument("value", StringArgumentType.word()).suggests(Qol.options("true", "false", "0", "1", "3", "20", "100"))
                                        .executes(Qol.guard(c -> {
                                            String name = StringArgumentType.getString(c, "rule");
                                            set(c.getSource().getServer(), name, StringArgumentType.getString(c, "value"));
                                            return Qol.ok(c, name + " is now " + get(c.getSource().getServer(), name) + ".");
                                        })))));

        Qol.text(C, "grfind", 2, "<word>", "Search the game rules by name", (c, t) -> {
            String f = t.toLowerCase(Locale.ROOT).trim();
            int n = 0;
            for (String k : KEYS.keySet()) {
                if (k.toLowerCase(Locale.ROOT).contains(f)) {
                    Qol.ok(c, k + " = " + get(c.getSource().getServer(), k));
                    n++;
                }
            }
            if (n == 0) {
                throw new Bad("No game rule has " + f + " in its name.");
            }
            return n;
        });

        Qol.none(C, "grdiff", 2, "List every game rule that is not at its normal value", c -> {
            MinecraftServer s = c.getSource().getServer();
            int n = 0;
            for (String k : KEYS.keySet()) {
                String v = get(s, k);
                if (!v.equals(DEFAULTS.get(k))) {
                    Qol.ok(c, k + " = " + v + " (normal " + DEFAULTS.get(k) + ")");
                    n++;
                }
            }
            if (n == 0) {
                Qol.ok(c, "Every game rule is at its normal value.");
            }
            return n;
        });

        Qol.custom(C, "grreset", 2, "<rule>", "Put one game rule back to its normal value",
                Qol.lit("grreset", 2).then(Commands.argument("rule", StringArgumentType.word()).suggests(NAMES)
                        .executes(Qol.guard(c -> {
                            String name = StringArgumentType.getString(c, "rule");
                            String def = DEFAULTS.get(name);
                            if (def == null) {
                                throw new Bad("There is no game rule called " + name + ".");
                            }
                            set(c.getSource().getServer(), name, def);
                            return Qol.ok(c, name + " is back to " + def + ".");
                        }))));

        Qol.none(C, "grresetall", 3, "Put every game rule back to its normal value", c -> {
            MinecraftServer s = c.getSource().getServer();
            int n = 0;
            for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
                set(s, e.getKey(), e.getValue());
                n++;
            }
            Store.setFirePct(100);
            Store.setDayFactors(1.0, 1.0);
            return Qol.ok(c, n + " game rules are back to normal, with fire speed and day length too.");
        });

        toggle("daycycle", "doDaylightCycle", "Stop or start the sun and moon", false);
        toggle("weathercycle", "doWeatherCycle", "Stop or start weather changes", false);
        toggle("mobgrief", "mobGriefing", "Switch creepers, endermen and friends changing blocks on or off", false);
        toggle("keepinv", "keepInventory", "Switch keeping your items when you die on or off", false);
        toggle("firetick", "doFireTick", "Switch fire spreading and burning out on or off", false);
        toggle("mobspawn", "doMobSpawning", "Switch natural mob spawning on or off", false);
        toggle("mobloot", "doMobLoot", "Switch mob drops on or off", false);
        toggle("blockdrops", "doTileDrops", "Switch block drops on or off", false);
        toggle("entitydrops", "doEntityDrops", "Switch drops from boats, minecarts and frames on or off", false);
        toggle("insomnia", "doInsomnia", "Switch phantoms on or off", false);
        toggle("patrols", "doPatrolSpawning", "Switch pillager patrols on or off", false);
        toggle("traders", "doTraderSpawning", "Switch wandering traders on or off", false);
        toggle("wardens", "doWardenSpawning", "Switch wardens spawning from sculk on or off", false);
        toggle("raids", "disableRaids", "Switch raids on or off", true);
        toggle("instantrespawn", "doImmediateRespawn", "Skip the death screen, or bring it back", false);
        toggle("falldamage", "fallDamage", "Switch fall damage on or off", false);
        toggle("firedamage", "fireDamage", "Switch fire damage on or off", false);
        toggle("drowning", "drowningDamage", "Switch drowning on or off", false);
        toggle("freezedamage", "freezeDamage", "Switch freezing damage on or off", false);
        toggle("naturalregen", "naturalRegeneration", "Switch natural healing on or off", false);
        toggle("advancements", "announceAdvancements", "Switch advancement messages on or off", false);
        toggle("deathmessages", "showDeathMessages", "Switch death messages on or off", false);
        toggle("cmdfeedback", "sendCommandFeedback", "Switch command feedback on or off", false);
        toggle("cmdlog", "logAdminCommands", "Switch logging of operator commands on or off", false);
        toggle("cmdblockout", "commandBlockOutput", "Switch command block chatter on or off", false);
        toggle("forgive", "forgiveDeadPlayers", "Switch angry mobs forgiving dead players on or off", false);
        toggle("anger", "universalAnger", "Switch angry mobs attacking any player on or off", false);
        toggle("limitedcraft", "doLimitedCrafting", "Only craft recipes you have unlocked, or craft anything", false);
        toggle("elytracheck", "disableElytraMovementCheck", "Switch the elytra speed check on or off", true);
        toggle("specchunks", "spectatorsGenerateChunks", "Let spectators generate new chunks, or not", false);
        toggle("reduceddebug", "reducedDebugInfo", "Hide or show coordinates on the debug screen", false);
        toggle("waterconvert", "waterSourceConversion", "Switch infinite water sources on or off", false);
        toggle("lavaconvert", "lavaSourceConversion", "Switch lava source conversion on or off", false);
        toggle("globalsounds", "globalSoundEvents", "Switch the world-wide boss and portal sounds on or off", false);

        tuner("randomtick", "randomTickSpeed", "How fast crops, leaves and ice change (3 is normal)", 0, 4096);
        tuner("sleeppct", "playersSleepingPercentage", "How many players must sleep to skip the night (percent)", 0, 100);
        tuner("spawnradius", "spawnRadius", "How far from the spawn new players appear", 0, 1000);
        tuner("cramming", "maxEntityCramming", "How many creatures can squash in one block before they suffocate", 0, 1000);
        tuner("cmdchain", "maxCommandChainLength", "How long a chain of command blocks may run", 1, 10_000_000);
        tuner("snowheight", "snowAccumulationHeight", "How many layers of snow can pile up", 0, 8);

        Qol.integer(C, "firespeed", 2, "[percent]",
                "How fast fire spreads and burns, 0 to 100 percent of normal (the game has no rule for faster)",
                0, 100, true, (c, n) -> {
                    if (n == null) {
                        return Qol.ok(c, "Fire runs at " + Store.firePct() + " percent of normal speed.");
                    }
                    Store.setFirePct(n);
                    return Qol.ok(c, n == 100 ? "Fire is back to normal speed." : "Fire now runs at " + n + " percent of normal speed.");
                });

        Qol.custom(C, "preset", 2, "<name>", "Apply a whole set of game rules at once (/presets lists them)",
                Qol.lit("preset", 2).then(Commands.argument("word", StringArgumentType.word()).suggests(PRESET_NAMES)
                        .executes(Qol.guard(c -> {
                            String name = StringArgumentType.getString(c, "word").toLowerCase(Locale.ROOT);
                            MinecraftServer s = c.getSource().getServer();
                            Map<String, String> mine = Store.customPreset(name);
                            int n;
                            if (mine != null) {
                                List<String> pairs = new ArrayList<>();
                                for (Map.Entry<String, String> e : mine.entrySet()) {
                                    pairs.add(e.getKey() + "=" + e.getValue());
                                }
                                n = applyPairs(s, pairs);
                            } else if (PRESETS.containsKey(name)) {
                                n = applyPairs(s, List.of(PRESETS.get(name)));
                            } else {
                                throw new Bad("There is no preset called " + name + ". /presets lists them.");
                            }
                            return Qol.ok(c, "Preset " + name + " applied: " + n + " rules set.");
                        }))));

        Qol.none(C, "presets", 2, "List the game rule presets, built in and your own", c -> {
            for (Map.Entry<String, String[]> e : PRESETS.entrySet()) {
                Qol.ok(c, "  " + e.getKey() + " (" + e.getValue().length + " rules)");
            }
            for (String n : Store.presetNames()) {
                Qol.ok(c, "  " + n + " (yours, " + Store.customPreset(n).size() + " rules)");
            }
            return PRESETS.size() + Store.presetNames().size();
        });

        Qol.word(C, "grsave", 2, "<name>", "Save every game rule as a preset of your own", false, Qol.options("mine"), (c, w) -> {
            String name = w.toLowerCase(Locale.ROOT);
            if (PRESETS.containsKey(name)) {
                throw new Bad(name + " is a built in preset. Pick another name.");
            }
            MinecraftServer s = c.getSource().getServer();
            Map<String, String> snap = new LinkedHashMap<>();
            for (String k : KEYS.keySet()) {
                snap.put(k, get(s, k));
            }
            Store.savePreset(name, snap);
            return Qol.ok(c, "Saved " + snap.size() + " rules as " + name + ". /preset " + name + " brings them back.");
        });

        Qol.word(C, "grdelete", 2, "<name>", "Delete a preset of your own", false, CUSTOM_PRESET_NAMES, (c, w) -> {
            if (!Store.delPreset(w)) {
                throw new Bad("You have no preset called " + w + ".");
            }
            return Qol.ok(c, "Deleted " + w + ".");
        });

        Qol.custom(C, "daylength", 2, "<minutes> [night minutes] | speed <x> | normal | freeze",
                "Set how many real minutes a day and a night last (about 11 and 9 is normal)",
                Qol.lit("daylength", 2)
                        .executes(Qol.guard(c -> Qol.ok(c, Store.dayFactor() == 1.0 && Store.nightFactor() == 1.0
                                ? String.format(Locale.ROOT, "Days are the normal length (%.1f minutes of day, %.1f of night).", DAY_MIN, NIGHT_MIN)
                                : "Day lasts " + minutes(DAY_MIN, Store.dayFactor()) + " minutes, night " + minutes(NIGHT_MIN, Store.nightFactor()) + " minutes.")))
                        .then(Commands.literal("normal").executes(Qol.guard(c -> setDay(c, 1.0, 1.0, "Days are the normal length again."))))
                        .then(Commands.literal("freeze").executes(Qol.guard(c -> setDay(c, 0.0, 0.0, "Time is frozen. /daylength normal starts it again."))))
                        .then(Commands.literal("speed").then(Commands.argument("x", DoubleArgumentType.doubleArg(0.05, 100.0))
                                .executes(Qol.guard(c -> {
                                    double x = DoubleArgumentType.getDouble(c, "x");
                                    return setDay(c, x, x, "Time now runs " + x + " times as fast as normal.");
                                }))))
                        .then(Commands.argument("day", DoubleArgumentType.doubleArg(0.1, 1440.0))
                                .executes(Qol.guard(c -> {
                                    double m = DoubleArgumentType.getDouble(c, "day");
                                    return setDay(c, DAY_MIN / m, NIGHT_MIN / m, "Day and night now last " + m + " minutes each.");
                                }))
                                .then(Commands.argument("night", DoubleArgumentType.doubleArg(0.1, 1440.0))
                                        .executes(Qol.guard(c -> {
                                            double d = DoubleArgumentType.getDouble(c, "day");
                                            double n = DoubleArgumentType.getDouble(c, "night");
                                            return setDay(c, DAY_MIN / d, NIGHT_MIN / n, "Day lasts " + d + " minutes, night " + n + ".");
                                        })))));
    }
}
