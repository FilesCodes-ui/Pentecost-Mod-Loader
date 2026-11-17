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
    /** The build's version. Read from a resource the build writes, so it cannot drift from the jar's real version. */
    public static final String VERSION = readVersion();

    private static List<ModInfo> mods = List.of();
    private static GameClassLoader loader;
    private static boolean forge;
    /** A Minecraft session handed in by a launcher (Modrinth App wrapper, the official launcher). Never logged. */
    private static LaunchSession session = LaunchSession.NONE;
    private static final java.util.Set<String> COMMANDS = java.util.Set.of("install", "check", "launch", "fetch", "verify", "setup");

    private PentecostMain() {}

    private static String readVersion() {
        try (java.io.InputStream in = PentecostMain.class.getResourceAsStream("version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (java.io.IOException e) {
            // falls through to the development marker
        }
        return "0.0.0-dev";
    }

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
                case "--username", "--uuid", "--accessToken", "--userType" -> i++;
                // Other game arguments a launcher passes. Pentecost builds its own, so these are read and dropped.
                case "--version", "--gameDir", "--assetsDir", "--assetIndex", "--clientId", "--xuid", "--versionType",
                        "--width", "--height" -> i++;
                default -> {
                    if (COMMANDS.contains(args[i])) {
                        command = args[i];
                    }
                }
            }
        }
        session = LaunchSession.parse(args);
        if (session.real()) {
            System.setProperty("pentecost.fromLauncher", "true");
        }
        Log.info("Pentecost " + VERSION + " for Minecraft " + MINECRAFT + ", Java " + System.getProperty("java.version"));
        int exit = 0;
        try {
            if (modDirs.isEmpty()) {
                modDirs.add(Dirs.mods());
            }
            switch (command) {
                case "install" -> MinecraftInstall.install(MINECRAFT, true);
                case "setup" -> {
                    MinecraftInstall.install(MINECRAFT, true);
                    khi.pentecost.install.ForgeSetup.ensure();
                }
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
        khi.pentecost.core.Timeline.step("game install checked");
        RemapService remap = new RemapService(vanilla);
        GameFiles game = remap.game();
        khi.pentecost.core.Timeline.step("game remapped or cached");
        List<ModInfo> found = new ArrayList<>();
        for (Path dir : modDirs) {
            found.addAll(ModDiscovery.scan(dir));
        }
        if (forge && new khi.pentecost.adapter.forge.ForgeFiles().installed()) {
            found = khi.pentecost.adapter.forge.FabricBridge.apply(found, MINECRAFT);
        }
        khi.pentecost.core.Timeline.step("mods scanned and bridged");
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
        khi.pentecost.core.Timeline.step("mods remapped or cached");
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
            Log.error("The Forge layer was asked for and is not installed. Run the setup command first.");
        }
        if (forgeOn) {
            forgeJar = forgeFiles.universal(PentecostRuntime.remap);
            khi.pentecost.core.Timeline.step("Forge jar ready");
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
        khi.pentecost.core.Timeline.step("class loader built");
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
        khi.pentecost.core.Timeline.step("dependencies checked");
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
        khi.pentecost.core.Timeline.step("access wideners loaded");
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
            khi.pentecost.core.Timeline.step("access transformers read");
            loader.addTransformer(khi.pentecost.adapter.forge.ForgeRedirects.transformer());
            loader.addTransformer((khi.pentecost.loader.ClassTransformer) Class
                    .forName("khi.pentecost.forgeglue.LaunchPlugins", true, loader)
                    .getConstructor(ClassLoader.class).newInstance(loader));
            khi.pentecost.core.Timeline.step("launch plugins loaded");
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
        khi.pentecost.core.Timeline.step("Forge prepared");
        loader.addTransformer(Hooks.transformer());
        loader.addTransformer(Hooks.packTransformer());
        loader.addTransformer(Hooks.hudTransformer());
        loader.addTransformer(Hooks.forgeHudTransformer());
        loader.addTransformer(khi.pentecost.core.HudOwner.forgeTransformer());
        loader.addTransformer(Hooks.styleTransformer());
        loader.addTransformer(Hooks.registryTransformer());
        loader.addTransformer(Hooks.windowTransformer());
        loader.addTransformer(khi.pentecost.core.Branding.titleTransformer());
        loader.addTransformer(khi.pentecost.core.Branding.sparkTransformer());
        loader.addTransformer(Hooks.fabricRegistryTransformer());
        loader.addTransformer(Hooks.tickTransformer());
        loader.addTransformer(Hooks.lootNameTransformer());
        loader.addTransformer(khi.pentecost.core.Background.transformer());
        loader.addTransformer(khi.pentecost.core.CostMeter.transformer());
        loader.addTransformer(khi.pentecost.core.ZoomHooks.transformer());
        loader.addLateTransformer(khi.pentecost.core.TagHooks.transformer());
        loader.addTransformer(khi.pentecost.core.ChatHooks.transformer());
        loader.addTransformer(khi.pentecost.core.MenuDistance.transformer());
        loader.addTransformer(khi.pentecost.core.ClientHooks.transformer());
        // These rename a game method and wrap it. They run after Mixin, so every mod hook sees the real body.
        loader.addLateTransformer(khi.pentecost.core.ClientHooks.lateTransformer());
        loader.addLateTransformer(khi.pentecost.core.ServerTuning.transformer());
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
        khi.pentecost.core.Timeline.step("Mixin started");
        loader.useCache(khi.pentecost.loader.ClassCache.openFor(loader, khi.pentecost.control.Choices.launchFingerprint()));
        khi.pentecost.core.Timeline.step("class cache opened");
        if (forgeOn) {
            Class.forName("khi.pentecost.forgeglue.CoreMods", true, loader).getMethod("awaitLoaded").invoke(null);
            khi.pentecost.core.Timeline.step("core mods ready");
        }
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

    private static boolean onClasspath(String className) {
        try {
            Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Starts this same launch again in a new JVM whose classpath also has Forge's securejarhandler, and exits with its code. */
    private static void relaunch(List<Path> modDirs) throws Exception {
        String extra = khi.pentecost.install.ForgeSetup.secureJarHandlerClasspath();
        if (extra == null) {
            Log.error("Forge's securejarhandler is missing after the Forge install. Starting without the Forge layer's loader library.");
            return;
        }
        Log.info("Restarting once with Forge's loader library on the classpath");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString());
        for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!a.startsWith("-Djava.class.path") && !a.equals("-cp") && !a.equals("-classpath")) {
                cmd.add(a);
            }
        }
        cmd.add("-Dpentecost.relaunched=true");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path") + java.io.File.pathSeparator + extra);
        cmd.add("khi.pentecost.boot.PentecostMain");
        cmd.add("launch");
        cmd.add("--forge");
        for (Path m : modDirs) {
            cmd.add("--mods");
            cmd.add(m.toString());
        }
        if (session.real()) {
            cmd.addAll(List.of("--username", session.name(), "--uuid", session.uuid(), "--accessToken", session.token()));
            if (session.type() != null && !session.type().isBlank()) {
                cmd.addAll(List.of("--userType", session.type()));
            }
        }
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        System.exit(p.waitFor());
    }

    private static void launch(List<Path> modDirs) throws Exception {
        khi.pentecost.core.WallProfiler.startIfAsked(Thread.currentThread());
        if (forge && System.getProperty("pentecost.forgeglue") != null) {
            // A copy of Pentecost without Gradle installs Forge itself on the first run. The JVM needs Forge's
            // securejarhandler on its own classpath, so after a fresh install it starts once more with it added.
            khi.pentecost.install.ForgeSetup.ensure();
            if (!onClasspath("cpw.mods.jarhandling.SecureJar") && System.getProperty("pentecost.relaunched") == null) {
                relaunch(modDirs);
                return;
            }
        }
        GameFiles game = prepare(modDirs, true);
        if (forge && new khi.pentecost.adapter.forge.ForgeFiles().installed()) {
            game = new khi.pentecost.adapter.forge.ForgeFiles().game(game, PentecostRuntime.remap);
        }
        ModRunner runner = boot(game);
        if (session.real()) {
            Log.info("Session from the launcher: " + session.name());
        }
        String[] gameArgs = {
                "--username", session.gameName(),
                "--version", "Pentecost-" + MINECRAFT,
                "--gameDir", Dirs.run().toString(),
                "--assetsDir", game.assetsDir().toString(),
                "--assetIndex", game.assetIndex(),
                "--uuid", session.gameUuid(),
                "--accessToken", session.gameToken(),
                "--userType", session.gameType(),
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
        khi.pentecost.core.Timeline.mark("Loader ready, game starting");

        Thread.currentThread().setContextClassLoader(loader);
        runner.runPreLaunch();
        khi.pentecost.core.Timeline.step("pre-launch entrypoints done");
        Log.info("Starting Minecraft");
        Class<?> main = Class.forName(game.mainClass(), true, loader);
        main.getMethod("main", String[].class).invoke(null, (Object) gameArgs);
    }
}
