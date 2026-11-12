package khi.pentecost.boot;

import khi.pentecost.core.Hooks;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.crash.CrashReport;
import khi.pentecost.install.MinecraftInstall;
import khi.pentecost.install.MinecraftInstall.GameFiles;
import khi.pentecost.loader.AccessWidener;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mixin.MixinHost;
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
    public static final String VERSION = "0.3.0";

    private static List<ModInfo> mods = List.of();
    private static GameClassLoader loader;
    private static boolean forge;

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
                case "--forge" -> forge = true;
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

    /** Installs the game and converts it and the mods to readable names. */
    private static GameFiles prepare(List<Path> modDirs, boolean assets) throws Exception {
        GameFiles vanilla = MinecraftInstall.install(MINECRAFT, assets);
        RemapService remap = new RemapService(vanilla);
        GameFiles game = remap.game();
        List<ModInfo> found = new ArrayList<>();
        for (Path dir : modDirs) {
            found.addAll(ModDiscovery.scan(dir));
        }
        if (forge && new khi.pentecost.adapter.forge.ForgeFiles().installed()) {
            found = khi.pentecost.adapter.forge.FabricBridge.apply(found, MINECRAFT);
        }
        khi.pentecost.control.Choices.load();
        PentecostRuntime.allMods = List.copyOf(found);
        List<ModInfo> on = new ArrayList<>();
        for (ModInfo m : found) {
            if (khi.pentecost.control.Choices.isModOff(m.id())) {
                Log.info("Player choice: " + m.name() + " is turned off");
            } else {
                on.add(m);
            }
        }
        mods = khi.pentecost.control.Features.apply(remap.mods(on));
        PentecostRuntime.remap = remap;
        PentecostRuntime.mods = mods;
        PentecostRuntime.gameDir = Dirs.run();
        return game;
    }

    /** A jar's library name without Pentecost's cache prefix and without its version. */
    private static String artifact(Path jar) {
        String n = jar.getFileName().toString().replaceFirst("^[0-9a-f]{12}-", "").replaceFirst("\\.jar$", "");
        return n.replaceFirst("-[0-9][^-]*(-[A-Za-z0-9.+]+)*$", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Builds the class loader with access wideners, the mod start hook, and Mixin. */
    private static ModRunner boot(GameFiles game) throws Exception {
        khi.pentecost.adapter.forge.ForgeFiles forgeFiles = new khi.pentecost.adapter.forge.ForgeFiles();
        boolean forgeOn = forge && forgeFiles.installed() && System.getProperty("pentecost.forgeglue") != null;
        List<Path> first = new ArrayList<>();
        List<Path> afterGame = new ArrayList<>();
        Path forgeJar = null;
        String gameGlue = System.getProperty("pentecost.gameglue");
        if (gameGlue != null) {
            for (String dir : gameGlue.split(java.io.File.pathSeparator)) {
                first.add(Path.of(dir));
            }
        }
        if (forge && !forgeOn) {
            Log.error("The Forge layer was asked for and is not installed. Run .tools\\forge-install.ps1 first.");
        }
        if (forgeOn) {
            forgeJar = forgeFiles.universal(PentecostRuntime.remap);
            for (String dir : System.getProperty("pentecost.forgeglue").split(java.io.File.pathSeparator)) {
                first.add(Path.of(dir));
            }
            afterGame.add(forgeJar);
            java.util.Set<String> modLibraries = new java.util.HashSet<>();
            for (ModInfo m : mods) {
                modLibraries.add(artifact(m.jar()));
            }
            for (Path lib : forgeFiles.runtimeLibraries()) {
                if (modLibraries.contains(artifact(lib))) {
                    Log.info("Library " + lib.getFileName() + " from Forge steps aside: a mod brings its own copy");
                } else {
                    afterGame.add(lib);
                }
            }
            PentecostRuntime.forgeLayer = true;
        }
        loader = GameClassLoader.create(game, mods, first, afterGame);
        PentecostRuntime.loader = loader;
        Log.info("Found " + mods.size() + " mod(s)");
        for (ModInfo m : mods) {
            if (!m.jar().getFileName().toString().matches("[0-9a-f]{12}-.*") || mods.size() <= 12
                    || !m.id().startsWith("fabric-") && m.loader() != khi.pentecost.mods.LoaderKind.LIBRARY) {
                Log.info("  " + m.label());
            }
        }
        ModRunner runner = new ModRunner(loader, mods);
        runner.checkDependencies();
        PentecostRuntime.runner = runner;
        java.util.Set<String> forgeCovers = forgeOn
                ? java.util.Set.of("fabric-loot-tables-v1") : java.util.Set.of();
        PentecostRuntime.forgeCovers = forgeCovers;
        for (String id : forgeCovers) {
            if (mods.stream().anyMatch(m -> m.id().equals(id))) {
                Log.info("Forge layer on: " + id + " stays loaded as a library, Forge handles its job");
            }
        }
        List<ModInfo> usable = mods.stream()
                .filter(m -> !runner.missing().containsKey(m.id()) && !forgeCovers.contains(m.id())).toList();

        AccessWidener widener = AccessWidener.load(usable);
        List<Path> forgeModJars = new ArrayList<>();
        if (forgeOn) {
            for (ModInfo m : usable) {
                if (m.loader() == khi.pentecost.mods.LoaderKind.FORGE && !forgeModJars.contains(m.jar())) {
                    forgeModJars.add(m.jar());
                }
            }
            List<Path> atJars = new ArrayList<>(forgeModJars);
            atJars.add(0, forgeJar);
            widener.addAccessTransformers(atJars, new khi.pentecost.remap.TextRemapper(PentecostRuntime.remap.modTable()));
            loader.addTransformer(khi.pentecost.adapter.forge.ForgeRedirects.transformer());
            loader.addTransformer((khi.pentecost.loader.ClassTransformer) Class
                    .forName("khi.pentecost.forgeglue.LaunchPlugins", true, loader)
                    .getConstructor(ClassLoader.class).newInstance(loader));
        }
        if (!widener.isEmpty()) {
            loader.addTransformer(widener);
        }
        if (forgeOn) {
            Thread.currentThread().setContextClassLoader(loader);
            Class.forName("khi.pentecost.forgeglue.ForgeBoot", true, loader)
                    .getMethod("prepare", Path.class, Path.class, Path.class, List.class, String.class, String.class)
                    .invoke(null, Dirs.run(), game.clientJar(), forgeJar, forgeModJars, MINECRAFT,
                            khi.pentecost.adapter.forge.ForgeFiles.VERSION.substring(MINECRAFT.length() + 1));
        }
        loader.addTransformer(Hooks.transformer());
        loader.addTransformer(Hooks.packTransformer());
        if (forgeOn) {
            java.util.Set<String> softMods = new java.util.HashSet<>();
            for (ModInfo m : usable) {
                if (m.loader() == khi.pentecost.mods.LoaderKind.FABRIC
                        || m.loader() == khi.pentecost.mods.LoaderKind.QUILT) {
                    softMods.add(m.id());
                }
            }
            khi.pentecost.mixin.PentecostMixinService.softMods = softMods;
            if (usable.stream().anyMatch(m -> m.id().equals("modmenu"))) {
                khi.pentecost.adapter.fabric.ModMenuAdapter.oneModsButton();
            }
            if (!softMods.isEmpty()) {
                Log.info("Forge layer on: hooks from " + softMods.size()
                        + " Fabric/Quilt mod(s) skip a missing target. Forge mods and the bridge stay strict");
            }
        }
        MixinHost.start(loader, usable);
        return runner;
    }

    private static int check(List<Path> modDirs, boolean selftest) throws Exception {
        GameFiles game = prepare(modDirs, false);
        ModRunner runner = boot(game);

        // Proves the converted game resolves through the Pentecost class loader under readable names.
        Class<?> main = Class.forName(game.mainClass(), false, loader);
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
        boolean ours = main.getClassLoader() == loader && minecraft.getClassLoader() == loader;
        Log.info("Loaded " + main.getName() + " and " + minecraft.getName() + " through " + (ours ? "Pentecost" : "another loader"));

        runner.runAll(false);

        StringBuilder report = new StringBuilder("Pentecost check\n");
        report.append("mods found: ").append(mods.size()).append('\n');
        report.append("started: ").append(runner.started()).append('\n');
        report.append("missing dependencies: ").append(runner.missing()).append('\n');
        report.append("failed: ").append(runner.failures().keySet()).append('\n');
        Files.writeString(Dirs.reports().resolve("check.txt"), report);

        boolean ok = ours && runner.failures().isEmpty();
        if (selftest) {
            ok = ok && !runner.started().isEmpty();
            Log.info(ok ? "SELFTEST PASSED" : "SELFTEST FAILED");
        }
        return ok ? 0 : 1;
    }

    private static void verify(List<Path> modDirs) throws Exception {
        GameFiles game = prepare(modDirs, false);
        khi.pentecost.adapter.forge.ForgeFiles forgeFiles = new khi.pentecost.adapter.forge.ForgeFiles();
        boolean useForge = forge && forgeFiles.installed();
        if (useForge) {
            game = forgeFiles.game(game, PentecostRuntime.remap);
        }
        RefVerifier verifier = new RefVerifier(game.clientJar());
        if (useForge) {
            List<Path> jars = new ArrayList<>(forgeFiles.rawLibraries());
            jars.add(forgeFiles.universal(PentecostRuntime.remap));
            verifier.addProvided(jars);
            Log.info("Counting Forge " + khi.pentecost.adapter.forge.ForgeFiles.VERSION + " as provided");
        }
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
        if (forge && new khi.pentecost.adapter.forge.ForgeFiles().installed()) {
            game = new khi.pentecost.adapter.forge.ForgeFiles().game(game, PentecostRuntime.remap);
        }
        ModRunner runner = boot(game);
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
        String world = System.getenv("PENTECOST_WORLD");
        if (world != null && !world.isBlank()) {
            gameArgs = java.util.Arrays.copyOf(gameArgs, gameArgs.length + 2);
            gameArgs[gameArgs.length - 2] = "--quickPlaySingleplayer";
            gameArgs[gameArgs.length - 1] = world;
            Log.info("Opening world: " + world);
        }
        PentecostRuntime.launchArgs = gameArgs;

        Thread.currentThread().setContextClassLoader(loader);
        runner.runPreLaunch();
        Log.info("Starting Minecraft");
        Class<?> main = Class.forName(game.mainClass(), true, loader);
        main.getMethod("main", String[].class).invoke(null, (Object) gameArgs);
    }
}
