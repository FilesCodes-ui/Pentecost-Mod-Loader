package khi.pentecost.boot;

import khi.pentecost.crash.CrashReport;
import khi.pentecost.install.MinecraftInstall;
import khi.pentecost.install.MinecraftInstall.GameFiles;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.ModDiscovery;
import khi.pentecost.mods.ModInfo;
import khi.pentecost.mods.ModRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point.
 *   install          download Minecraft 1.20.1
 *   check [--mods D] read and start mods without opening the game
 *   launch [--mods D] start the game (default)
 */
public final class PentecostMain {
    public static final String MINECRAFT = "1.20.1";
    public static final String VERSION = "0.1.0";

    private static List<ModInfo> mods = List.of();
    private static GameClassLoader loader;

    private PentecostMain() {}

    public static void main(String[] args) {
        String command = "launch";
        Path modsDir = null;
        boolean selftest = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mods" -> modsDir = Path.of(args[++i]);
                case "--selftest" -> selftest = true;
                default -> command = args[i];
            }
        }
        Log.info("Pentecost " + VERSION + " for Minecraft " + MINECRAFT + ", Java " + System.getProperty("java.version"));
        int exit = 0;
        try {
            if (modsDir == null) {
                modsDir = Dirs.mods();
            }
            switch (command) {
                case "install" -> MinecraftInstall.install(MINECRAFT, true);
                case "check" -> exit = check(modsDir, selftest);
                case "launch" -> launch(modsDir);
                default -> {
                    Log.error("Unknown command " + command + ". Use install, check, or launch.");
                    exit = 2;
                }
            }
        } catch (Throwable t) {
            CrashReport.write(t, mods, loader);
            exit = 1;
        }
        System.exit(exit);
    }

    private static int check(Path modsDir, boolean selftest) throws Exception {
        GameFiles game = MinecraftInstall.install(MINECRAFT, false);
        mods = ModDiscovery.scan(modsDir);
        loader = GameClassLoader.create(game, mods);
        Log.info("Found " + mods.size() + " mod(s) in " + modsDir);
        for (ModInfo m : mods) {
            Log.info("  " + m.label());
        }

        // Proves the vanilla jar and libraries resolve through the Pentecost class loader.
        Class<?> main = Class.forName(game.mainClass(), false, loader);
        boolean ours = main.getClassLoader() == loader;
        Log.info("Minecraft main class " + main.getName() + " loaded by " + (ours ? "Pentecost" : "another loader"));

        ModRunner runner = new ModRunner(loader, mods);
        runner.checkDependencies();
        runner.runAll(false);

        StringBuilder report = new StringBuilder("Pentecost check\n");
        report.append("mods found: ").append(mods.size()).append('\n');
        report.append("started: ").append(runner.started()).append('\n');
        report.append("missing dependencies: ").append(runner.missing()).append('\n');
        report.append("failed: ").append(runner.failures().keySet()).append('\n');
        Files.writeString(Dirs.reports().resolve("check.txt"), report);
        Log.info(report.toString().trim().replace("\n", " | "));

        boolean ok = ours && runner.failures().isEmpty();
        if (selftest) {
            ok = ok && !runner.started().isEmpty();
            Log.info(ok ? "SELFTEST PASSED" : "SELFTEST FAILED");
        }
        return ok ? 0 : 1;
    }

    private static void launch(Path modsDir) throws Exception {
        GameFiles game = MinecraftInstall.install(MINECRAFT, true);
        mods = ModDiscovery.scan(modsDir);
        loader = GameClassLoader.create(game, mods);
        Log.info("Starting Minecraft with " + mods.size() + " mod(s)");

        ModRunner runner = new ModRunner(loader, mods);
        runner.checkDependencies();
        runner.runAll(true);

        Thread.currentThread().setContextClassLoader(loader);
        Class<?> main = Class.forName(game.mainClass(), true, loader);
        String[] gameArgs = {
                "--username", "Dev",
                "--version", "Pentecost-" + MINECRAFT,
                "--gameDir", Dirs.run().toString(),
                "--assetsDir", game.assetsDir().toString(),
                "--assetIndex", game.assetIndex(),
                "--uuid", "00000000-0000-0000-0000-000000000000",
                "--accessToken", "0",
                "--userType", "legacy",
                "--versionType", "Pentecost"
        };
        main.getMethod("main", String[].class).invoke(null, (Object) gameArgs);
    }
}
