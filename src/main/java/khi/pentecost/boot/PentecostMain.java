package khi.pentecost.boot;

import khi.pentecost.crash.CrashReport;
import khi.pentecost.install.MinecraftInstall;
import khi.pentecost.install.MinecraftInstall.GameFiles;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.ModDiscovery;
import khi.pentecost.mods.ModInfo;
import khi.pentecost.mods.ModRunner;
import khi.pentecost.remap.RemapService;
import khi.pentecost.testing.ModFetcher;
import khi.pentecost.testing.RefVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point.
 *   install                      download Minecraft 1.20.1
 *   check  [--mods D]            read, convert, and start mods without opening the game
 *   launch [--mods D]            start the game (default)
 *   fetch  --loader L --count N  download the top N mods for a loader from Modrinth
 *   verify [--mods D]            convert mods, check every game call, list missing loader classes
 */
public final class PentecostMain {
    public static final String MINECRAFT = "1.20.1";
    public static final String VERSION = "0.2.0";

    private static List<ModInfo> mods = List.of();
    private static GameClassLoader loader;

    private PentecostMain() {}

    public static void main(String[] args) {
        String command = "launch";
        List<Path> modDirs = new ArrayList<>();
        boolean selftest = false;
        String fetchLoader = "fabric";
        int count = 20;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mods" -> modDirs.add(Path.of(args[++i]));
                case "--selftest" -> selftest = true;
                case "--loader" -> fetchLoader = args[++i];
                case "--count" -> count = Integer.parseInt(args[++i]);
                default -> command = args[i];
            }
        }
        Log.info("Pentecost " + VERSION + " for Minecraft " + MINECRAFT + ", Java " + System.getProperty("java.version"));
        int exit = 0;
        try {
            if (modDirs.isEmpty()) {
                modDirs.add(Dirs.mods());
            }
            switch (command) {
                case "install" -> MinecraftInstall.install(MINECRAFT, true);
                case "check" -> exit = check(modDirs, selftest);
                case "launch" -> launch(modDirs);
                case "fetch" -> ModFetcher.fetchTop(MINECRAFT, fetchLoader, count);
                case "verify" -> verify(modDirs);
                default -> {
                    Log.error("Unknown command " + command + ". Use install, check, launch, fetch, or verify.");
                    exit = 2;
                }
            }
        } catch (Throwable t) {
            CrashReport.write(t, mods, loader);
            exit = 1;
        }
        System.exit(exit);
    }

    /** Installs the game, converts it and the mods to readable names, and builds the class loader. */
    private static GameFiles prepare(List<Path> modDirs, boolean assets) throws Exception {
        GameFiles vanilla = MinecraftInstall.install(MINECRAFT, assets);
        RemapService remap = new RemapService(vanilla);
        GameFiles game = remap.game();
        List<ModInfo> found = new ArrayList<>();
        for (Path dir : modDirs) {
            found.addAll(ModDiscovery.scan(dir));
        }
        mods = remap.mods(found);
        return game;
    }

    private static int check(List<Path> modDirs, boolean selftest) throws Exception {
        GameFiles game = prepare(modDirs, false);
        loader = GameClassLoader.create(game, mods);
        Log.info("Found " + mods.size() + " mod(s)");
        for (ModInfo m : mods) {
            Log.info("  " + m.label());
        }

        // Proves the converted game resolves through the Pentecost class loader under readable names.
        Class<?> main = Class.forName(game.mainClass(), false, loader);
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
        boolean ours = main.getClassLoader() == loader && minecraft.getClassLoader() == loader;
        Log.info("Loaded " + main.getName() + " and " + minecraft.getName() + " through " + (ours ? "Pentecost" : "another loader"));

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

    private static void verify(List<Path> modDirs) throws Exception {
        GameFiles game = prepare(modDirs, false);
        RefVerifier verifier = new RefVerifier(game.clientJar());
        Log.info("Game index holds " + verifier.gameClassCount() + " classes. Checking " + mods.size() + " mod(s)");
        List<RefVerifier.ModResult> results = new ArrayList<>();
        for (ModInfo m : mods) {
            results.add(verifier.verify(m));
        }
        String report = RefVerifier.report(results);
        Path file = Dirs.reports().resolve("verify.txt");
        Files.writeString(file, report);
        int total = report.indexOf("TOTAL");
        System.out.println(report.substring(total, report.indexOf('\n', report.indexOf("LOADER API"))));
        Log.info("Full report: " + file);
    }

    private static void launch(List<Path> modDirs) throws Exception {
        GameFiles game = prepare(modDirs, true);
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
