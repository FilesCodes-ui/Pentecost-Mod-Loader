package khi.pentecost.game.pregen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.core.ServerTuning;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pregenerator's front door: the runs that exist, the command, and the tick that drives them.
 * <p>
 * It is started by the game's server tick (see ServerTuning.serverTick), so it exists on every kind of server and
 * needs no client code. Commands, all for operators (permission level 2):
 * <pre>
 *   /pentecost pregen start (radius in chunks) [x z]   the area is a square of 2 * radius chunks, around you or x z
 *   /pentecost pregen pause | resume | stop | status
 *   /pentecost pregen speed auto | full                full keeps every core busy even with players online
 *   /pentecost pregen follow on | off | status         make the chunks ahead of a player flying fast and high
 * </pre>
 * On a dedicated server a run that was going when the server stopped carries on by itself at the next start
 * (setting pregen.autoResume overrides this either way). In a single player world it waits for the command.
 */
public final class Pregen {
    /** How many chunks may be saved and unloaded per tick while a run is going. The game's normal amount is far lower. */
    private static final int UNLOAD_BOOST = 96;

    private static final Map<ServerLevel, ChunkPregen> RUNS = new ConcurrentHashMap<>();
    /** The last server that ticked. Weak, so a world the player left can be freed. */
    private static java.lang.ref.WeakReference<MinecraftServer> known = new java.lang.ref.WeakReference<>(null);
    private static int sinceCheck = 1000;

    private Pregen() {}

    /** Called once, by reflection, the first time a server ticks. */
    public static void install() {
        ServerTuning.ticker = Pregen::tick;
    }

    /**
     * Client side, once per frame. When no world is open, the runs and the flight follower let go of the last server.
     * They hold its levels, and a closed world would otherwise stay in memory until the next one starts.
     */
    public static void forgetStoppedServer(net.minecraft.client.Minecraft mc) {
        if (mc.getSingleplayerServer() == null && (known.get() != null || !RUNS.isEmpty())) {
            known.clear();
            RUNS.clear();
            FlightFollow.reset();
        }
    }

    private static void tick(Object o) {
        MinecraftServer server = (MinecraftServer) o;
        if (server != known.get()) {
            known = new java.lang.ref.WeakReference<>(server);
            RUNS.clear();
            FlightFollow.reset();
            sinceCheck = 1000;
            autoResume(server);
        }
        if (++sinceCheck >= 200) {
            sinceCheck = 0;
            ensureCommand(server); // a /reload throws registered commands away, so look again now and then
            khi.pentecost.game.qol.Qol.ensure(server);
        }
        khi.pentecost.game.qol.Qol.tick(server);
        boolean following = FlightFollow.tick(server);
        boolean running = false;
        for (ChunkPregen run : RUNS.values()) {
            run.setYielding(following);
            run.tick();
            if (run.state() == ChunkPregen.State.RUNNING) {
                running = true;
            }
        }
        ServerTuning.unloadBoost = running || following ? UNLOAD_BOOST : 0;
    }

    // ---- Runs ----

    private static Path fileFor(MinecraftServer server, ServerLevel level) {
        String name = level.dimension().location().toString().replace(':', '_').replace('/', '_');
        return server.getWorldPath(LevelResource.ROOT).resolve("pentecost").resolve("pregen").resolve(name + ".json");
    }

    private static ChunkPregen begin(MinecraftServer server, ServerLevel level, int cx, int cz, int radius) {
        Path file = fileFor(server, level);
        ChunkPregen.Saved s = ChunkPregen.load(file);
        ChunkPregen run;
        if (s != null && s.centerX() == cx && s.centerZ() == cz && s.radius() == radius && !"done".equals(s.state())) {
            run = new ChunkPregen(server, level, cx, cz, radius, file, s.next(), s.done(), s.failed(), s.elapsedMs());
        } else {
            run = new ChunkPregen(server, level, cx, cz, radius, file, 0, 0, 0, 0);
        }
        RUNS.put(level, run);
        return run;
    }

    private static void autoResume(MinecraftServer server) {
        String v = Settings.get("pregen.autoResume");
        boolean auto = v.isEmpty() ? server.isDedicatedServer() : Boolean.parseBoolean(v);
        if (!auto) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            ChunkPregen.Saved s = ChunkPregen.load(fileFor(server, level));
            if (s != null && "running".equals(s.state())) {
                ChunkPregen run = begin(server, level, s.centerX(), s.centerZ(), s.radius());
                Log.info("Pregen " + run.dimension + " carries on from before the restart: " + run.plan);
            }
        }
    }

    // ---- Command ----

    private static void ensureCommand(MinecraftServer server) {
        try {
            var dispatcher = server.getCommands().getDispatcher();
            var existing = dispatcher.getRoot().getChild("pentecost");
            if (existing != null && existing.getChild("pregen") != null) {
                return;
            }
            LiteralArgumentBuilder<CommandSourceStack> pregen = Commands.literal("pregen")
                    .then(Commands.literal("start")
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 4000))
                                    .executes(c -> start(c, null, null))
                                    .then(Commands.argument("x", IntegerArgumentType.integer())
                                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                                    .executes(c -> start(c, IntegerArgumentType.getInteger(c, "x"),
                                                            IntegerArgumentType.getInteger(c, "z")))))))
                    .then(Commands.literal("pause").executes(c -> control(c, "pause")))
                    .then(Commands.literal("resume").executes(c -> control(c, "resume")))
                    .then(Commands.literal("stop").executes(c -> control(c, "stop")))
                    .then(Commands.literal("status").executes(Pregen::status))
                    .then(Commands.literal("follow")
                            .then(Commands.literal("on").executes(c -> follow(c, true)))
                            .then(Commands.literal("off").executes(c -> follow(c, false)))
                            .then(Commands.literal("status").executes(Pregen::followStatus)))
                    .then(Commands.literal("speed")
                            .then(Commands.literal("auto").executes(c -> speed(c, "auto")))
                            .then(Commands.literal("full").executes(c -> speed(c, "full"))));
            dispatcher.register(Commands.literal("pentecost").requires(s -> s.hasPermission(2)).then(pregen));
        } catch (RuntimeException | LinkageError e) {
            Log.error("Pregen: could not register the command: " + e);
        }
    }

    private static int start(CommandContext<CommandSourceStack> c, Integer blockX, Integer blockZ) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        ChunkPregen old = RUNS.get(level);
        if (old != null && (old.state() == ChunkPregen.State.RUNNING || old.state() == ChunkPregen.State.PAUSED)) {
            src.sendFailure(Component.literal("A pregen is already " + old.state().name().toLowerCase()
                    + " in this dimension. Use /pentecost pregen stop first."));
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(c, "radius");
        int bx = blockX != null ? blockX : Mth.floor(src.getPosition().x);
        int bz = blockZ != null ? blockZ : Mth.floor(src.getPosition().z);
        ChunkPregen run = begin(src.getServer(), level, Math.floorDiv(bx, 16), Math.floorDiv(bz, 16), radius);
        src.sendSuccess(() -> Component.literal("Pregen started in " + run.dimension + ": " + run.plan
                + ". Progress is in the log every few seconds, or /pentecost pregen status."), true);
        return 1;
    }

    private static int control(CommandContext<CommandSourceStack> c, String what) {
        CommandSourceStack src = c.getSource();
        ChunkPregen run = RUNS.get(src.getLevel());
        if (run == null) {
            src.sendFailure(Component.literal("No pregen in this dimension."));
            return 0;
        }
        switch (what) {
            case "pause" -> run.pause();
            case "resume" -> run.resume();
            default -> run.stop();
        }
        src.sendSuccess(() -> Component.literal("Pregen " + run.dimension + ": " + run.status()), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (RUNS.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No pregen has been started since the server came up."), false);
            return 1;
        }
        for (Map.Entry<ServerLevel, ChunkPregen> e : RUNS.entrySet()) {
            String line = "Pregen " + e.getValue().dimension + ": " + e.getValue().status();
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int follow(CommandContext<CommandSourceStack> c, boolean on) {
        Settings.set("pregen.follow", on ? "true" : "false");
        c.getSource().sendSuccess(() -> Component.literal(on
                ? "Pregen follow is on: while a player flies fast and high, the chunks ahead of them are made first."
                : "Pregen follow is off."), true);
        return 1;
    }

    private static int followStatus(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal("Pregen " + FlightFollow.status()), false);
        return 1;
    }

    private static int speed(CommandContext<CommandSourceStack> c, String mode) {
        Settings.set("pregen.speed", mode);
        c.getSource().sendSuccess(() -> Component.literal("Pregen speed: " + mode
                + ("full".equals(mode) ? " (every core, even with players online)"
                : " (full speed when empty, gentle with players online)")), true);
        return 1;
    }
}
