package khi.pentecost.crash;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.PentecostMain;
import khi.pentecost.control.Choices;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.core.Timeline;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CrashDetails {
    public static final String MARK = "==== PENTECOST CRASH SUMMARY ====";

    private CrashDetails() {}

    public record Blame(ModInfo mod, String id, StackTraceElement firstFrame, int frames) {
        boolean library() { return mod != null && mod.loader() == LoaderKind.LIBRARY; }
        String name() { return mod == null ? id : mod.name() + " (" + id + ")"; }
    }

    private static volatile Object lastReport;
    private static volatile String[] lastParts;

    public static String decorate(Object report, String text) {
        try {
            if (text == null || text.contains(MARK)) {
                return text;
            }
            String[] parts;
            synchronized (CrashDetails.class) {
                if (lastReport != report || lastParts == null) {
                    Throwable t = null;
                    try {
                        t = (Throwable) report.getClass().getMethod("getException").invoke(report);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                    }
                    List<ModInfo> mods = PentecostRuntime.allMods.isEmpty() ? PentecostRuntime.mods : PentecostRuntime.allMods;
                    String guard = null;
                    try {
                        String blamed = null;
                        if (t != null) {
                            for (Blame b : blame(unwrap(t), mods, PentecostRuntime.loader)) {
                                if (!b.library()) {
                                    blamed = b.mod() == null ? b.id() : b.mod().id();
                                    break;
                                }
                            }
                        }
                        guard = khi.pentecost.boot.StartupGuard.onCrash(text, blamed, mods);
                    } catch (Throwable ignored) {
                    }
                    lastParts = new String[]{
                            redact(summary(t, mods, PentecostRuntime.loader, true)) + (guard == null ? "" : "\n" + guard + "\n"),
                            redact(details(mods, PentecostRuntime.loader, t, true))};
                    lastReport = report;
                }
                parts = lastParts;
            }
            int at = text.indexOf("\nTime: ");
            String top = at >= 0 ? text.substring(0, at + 1) + parts[0] + "\n" + text.substring(at + 1) : parts[0] + "\n" + text;
            return top + "\n" + parts[1];
        } catch (Throwable e) {
            return text;
        }
    }

    public static String summary(Throwable error, List<ModInfo> mods, GameClassLoader loader, boolean inGame) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARK).append("\n");
        sb.append("Pentecost ").append(PentecostMain.VERSION).append(" | Minecraft ").append(PentecostMain.MINECRAFT)
                .append(" | ").append(mods.stream().filter(m -> m.loader() != LoaderKind.LIBRARY).count()).append(" mods")
                .append(" | report ").append(UUID.randomUUID().toString().substring(0, 8))
                .append(" | ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        if (error == null) {
            sb.append("The game did not hand over an error to read. The details below still apply.\n");
            return sb.toString();
        }
        Throwable root = unwrap(error);
        Throwable deepest = deepest(root);
        List<Blame> blame = blame(root, mods, loader);
        Blame main = blame.stream().filter(b -> !b.library()).findFirst().orElse(blame.isEmpty() ? null : blame.get(0));
        ModInfo mixinMod = mixinOwner(root, mods);

        sb.append("WHAT BROKE\n  ").append(sentence(deepest)).append('\n');
        if (deepest != root) {
            sb.append("  Reported as: ").append(root.getClass().getSimpleName()).append(root.getMessage() == null ? "" : " - " + shorten(root.getMessage(), 160)).append('\n');
        }
        sb.append("\nMOST LIKELY CAUSE\n");
        if (mixinMod != null) {
            sb.append("  ").append(mixinMod.label()).append(" has a mixin that failed to apply.\n");
            sb.append("  Confidence: high. The error names that mod's mixin file.\n");
        } else if (main != null) {
            sb.append("  ").append(main.mod() == null ? main.id() : main.mod().label()).append('\n');
            sb.append("  File: ").append(main.mod() == null ? "unknown" : main.mod().jar().getFileName()).append('\n');
            sb.append("  First frame: ").append(main.firstFrame()).append('\n');
            sb.append("  Confidence: ").append(confidence(blame, main, deepest)).append('\n');
        } else {
            sb.append("  No mod appears in the error. The fault sits in Pentecost, Forge or Minecraft itself.\n");
        }
        List<Blame> others = new ArrayList<>();
        for (Blame b : blame) {
            if (b != main && !b.library()) {
                others.add(b);
            }
        }
        if (!others.isEmpty()) {
            sb.append("\nALSO IN THE TRACE (mods whose code ran just before or after)\n");
            for (Blame b : others.stream().limit(6).toList()) {
                sb.append("  ").append(b.name()).append(", ").append(b.frames()).append(b.frames() == 1 ? " frame" : " frames").append('\n');
            }
        }
        sb.append("\nWHAT TO TRY\n");
        for (String line : advice(root, deepest, main, mixinMod, inGame)) {
            sb.append("  - ").append(line).append('\n');
        }
        sb.append("\nIf you ask for help, send this whole file. The summary at the top and the details at the bottom are made for that.\n");
        return sb.toString();
    }

    public static List<Blame> blame(Throwable root, List<ModInfo> mods, GameClassLoader loader) {
        Map<String, Blame> found = new LinkedHashMap<>();
        if (loader == null || root == null) {
            return List.of();
        }
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = root; t != null && chain.size() < 12; t = t.getCause() == t ? null : t.getCause()) {
            chain.add(0, t);
            for (Throwable s : t.getSuppressed()) {
                if (chain.size() < 12) {
                    chain.add(s);
                }
            }
        }
        for (Throwable t : chain) {
            for (StackTraceElement f : t.getStackTrace()) {
                String id;
                try {
                    id = loader.modOf(f.getClassName());
                } catch (RuntimeException e) {
                    id = null;
                }
                if (id == null) {
                    continue;
                }
                ModInfo mod = null;
                for (ModInfo m : mods) {
                    if (m.id().equals(id)) {
                        mod = m;
                        break;
                    }
                }
                Blame old = found.get(id);
                found.put(id, old == null ? new Blame(mod, id, f, 1) : new Blame(old.mod(), id, old.firstFrame(), old.frames() + 1));
            }
        }
        return new ArrayList<>(found.values());
    }

    private static String confidence(List<Blame> blame, Blame main, Throwable deepest) {
        long real = blame.stream().filter(b -> !b.library()).count();
        if (real <= 1) {
            return "high. It is the only mod in the trace.";
        }
        if (deepest instanceof NullPointerException || deepest instanceof ClassCastException || deepest instanceof IllegalStateException) {
            return "medium. This mod hit the error, but the bad value may have come from one of the other mods listed below.";
        }
        return "medium. Several mods are in the trace. This one is where the error started.";
    }

    private static final Pattern MIXIN_CONFIG = Pattern.compile("([A-Za-z0-9_.\\-]+\\.mixins?\\.json)");

    private static ModInfo mixinOwner(Throwable root, List<ModInfo> mods) {
        for (Throwable t = root; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String text = t.getClass().getName() + " " + t.getMessage();
            if (!text.toLowerCase().contains("mixin")) {
                continue;
            }
            Matcher m = MIXIN_CONFIG.matcher(String.valueOf(t.getMessage()));
            while (m.find()) {
                String config = m.group(1);
                for (ModInfo mod : mods) {
                    for (String c : mod.mixins()) {
                        if (c.equals(config) || c.endsWith("/" + config)) {
                            return mod;
                        }
                    }
                }
            }
        }
        return null;
    }

    static List<String> advice(Throwable root, Throwable deepest, Blame main, ModInfo mixinMod, boolean inGame) {
        List<String> out = new ArrayList<>();
        String name = main == null ? "the mod" : main.name();
        String jar = main == null || main.mod() == null ? "its file" : main.mod().jar().getFileName().toString();
        String msg = String.valueOf(deepest.getMessage());
        if (deepest instanceof OutOfMemoryError) {
            out.add("Give the game more memory in Pentecost's settings (Java memory). If it already has 6 GB or more, a mod is leaking memory: remove the newest one.");
        } else if (deepest instanceof StackOverflowError) {
            out.add("Two mods are calling each other in a loop. Remove the newest mod and start again.");
        } else if (deepest instanceof UnsupportedClassVersionError) {
            out.add("A mod needs a newer Java than the one running. Pentecost needs Java 21; check the Java line in the details below.");
        } else if (deepest instanceof ClassNotFoundException || deepest instanceof NoClassDefFoundError) {
            out.add(name + " needs a library or another mod that is not installed. Look for a Requires or Dependencies list on its Modrinth page.");
            out.add("If the missing class belongs to Forge or Fabric Loader themselves, report it to Pentecost: a loader feature is missing.");
        } else if (deepest instanceof NoSuchMethodError || deepest instanceof NoSuchFieldError) {
            out.add("Check that " + name + " is built for Minecraft 1.20.1, and that its needed libraries are the right version.");
            out.add("If it is, a game call it uses is missing from Pentecost's conversion. Report it.");
        } else if (mixinMod != null) {
            out.add("Update " + mixinMod.name() + ". Its mixin changes game code that another mod, or Pentecost, also changes.");
            out.add("If an update does not help, remove " + mixinMod.jar().getFileName() + " or the mod it clashes with (the mods in the trace below).");
        } else if (deepest instanceof NullPointerException && msg.contains("is null")) {
            out.add(name + " received an empty value (" + shorten(msg, 140) + "). Updating " + name + " is the first thing to try.");
            out.add("If it still crashes, another mod may be sending that empty value. Remove the other mods listed under ALSO IN THE TRACE, one at a time.");
        } else if (main != null) {
            out.add("Update " + name + " to its newest version for Minecraft 1.20.1.");
        }
        if (main != null) {
            out.add("To confirm it, turn " + name + " off in the Pentecost mods screen (or move " + jar
                    + " out of the mods folder) and repeat what you did. If the crash is gone, that mod is the cause.");
            if (main.mod() != null && main.mod().loader() == LoaderKind.FABRIC) {
                out.add(name + " is a Fabric mod running through Pentecost. If a Forge build of it exists, try that one; if the same crash happens on plain Fabric, report it to its author instead.");
            }
        } else {
            out.add("No mod is in the trace, so try the same steps with all added mods off. If it stays, report this file to the Pentecost developer.");
        }
        if (inGame) {
            out.add("Your world is safe: the game saves on the way out of a crash. Make a backup of the saves folder before you try to reproduce it.");
        }
        return out;
    }

    public static String details(List<ModInfo> mods, GameClassLoader loader, Throwable error, boolean inGame) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== PENTECOST DETAILS ====\n");
        try { pentecost(sb, inGame); } catch (Throwable e) { sb.append("(Pentecost section failed: ").append(e).append(")\n"); }
        try { machine(sb); } catch (Throwable e) { sb.append("(machine section failed: ").append(e).append(")\n"); }
        try { modList(sb, mods, loader, error); } catch (Throwable e) { sb.append("(mod list failed: ").append(e).append(")\n"); }
        try { gameSettings(sb); } catch (Throwable e) { sb.append("(settings failed: ").append(e).append(")\n"); }
        try { logSections(sb); } catch (Throwable e) { sb.append("(log failed: ").append(e).append(")\n"); }
        try { earlierCrashes(sb); } catch (Throwable e) { sb.append("(earlier crashes failed: ").append(e).append(")\n"); }
        return sb.toString();
    }

    private static void pentecost(StringBuilder sb, boolean inGame) {
        sb.append("\nPENTECOST\n");
        sb.append("  Version: ").append(PentecostMain.VERSION).append(" for Minecraft ").append(PentecostMain.MINECRAFT).append('\n');
        sb.append("  Started by: ").append("true".equals(System.getProperty("pentecost.fromLauncher"))
                ? "the Minecraft Launcher (a profile)" : "Pentecost directly (shortcut, manager or command line)").append('\n');
        sb.append("  Forge layer: ").append(PentecostRuntime.forgeLayer ? "on (Forge mods run on Forge's own code)" : "off").append('\n');
        sb.append("  Game folder: ").append(PentecostRuntime.gameDir == null ? Dirs.run() : PentecostRuntime.gameDir).append('\n');
        sb.append("  Install folder: ").append(Dirs.home()).append('\n');
        sb.append("  Running for: ").append(seconds((System.currentTimeMillis() - Timeline.startMillis()) / 1000.0)).append('\n');
        Map<String, Double> marks = Timeline.marks();
        if (!marks.isEmpty()) {
            sb.append("  Startup steps (seconds since start):\n");
            for (Map.Entry<String, Double> e : marks.entrySet()) {
                sb.append(String.format(java.util.Locale.ROOT, "    %7.2f  %s%n", e.getValue(), e.getKey()));
            }
        }
        List<String> off = new ArrayList<>();
        for (ModInfo m : PentecostRuntime.allMods) {
            if (Choices.isModOff(m.id())) {
                off.add(m.name() + " (" + m.id() + ")");
            }
        }
        sb.append("  Mods the player turned off: ").append(off.isEmpty() ? "none" : String.join(", ", off)).append('\n');
        try {
            sb.append("  Pending restart changes: ").append(Choices.pendingRestart()).append('\n');
        } catch (RuntimeException e) {
        }
        String cmd = System.getProperty("sun.java.command");
        if (cmd != null) {
            sb.append("  Command line: ").append(maskSecrets(shorten(cmd, 600))).append('\n');
        }
    }

    private static void machine(StringBuilder sb) throws Exception {
        Runtime rt = Runtime.getRuntime();
        sb.append("\nMACHINE AND JAVA\n");
        sb.append("  Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append("), ")
                .append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.vm.version")).append('\n');
        sb.append("  Java home: ").append(System.getProperty("java.home")).append('\n');
        sb.append("  OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(' ').append(System.getProperty("os.arch")).append('\n');
        sb.append("  CPU threads: ").append(rt.availableProcessors()).append('\n');
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            sb.append("  RAM: ").append(os.getTotalMemorySize() / 1048576).append(" MB total, ")
                    .append(os.getFreeMemorySize() / 1048576).append(" MB free\n");
        }
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var non = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        sb.append("  Game heap: ").append(heap.getUsed() / 1048576).append(" MB used, ").append(heap.getCommitted() / 1048576)
                .append(" MB reserved, ").append(rt.maxMemory() / 1048576).append(" MB allowed\n");
        sb.append("  Non-heap (classes, code): ").append(non.getUsed() / 1048576).append(" MB used\n");
        StringBuilder gc = new StringBuilder();
        for (var b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.append(gc.length() == 0 ? "" : "; ").append(b.getName()).append(": ").append(b.getCollectionCount())
                    .append(" runs, ").append(b.getCollectionTime()).append(" ms");
        }
        sb.append("  Memory cleanup: ").append(gc).append('\n');
        sb.append("  Threads: ").append(ManagementFactory.getThreadMXBean().getThreadCount())
                .append(", classes loaded: ").append(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()).append('\n');
        sb.append("  Java arguments: ").append(maskSecrets(String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()))).append('\n');
        try {
            sb.append("  Disk free where the game lives: ").append(Files.getFileStore(Dirs.run()).getUsableSpace() / 1048576).append(" MB\n");
        } catch (java.io.IOException e) {
        }
    }

    private static void modList(StringBuilder sb, List<ModInfo> mods, GameClassLoader loader, Throwable error) {
        Set<String> inTrace = new java.util.HashSet<>();
        if (error != null) {
            for (Blame b : blame(unwrap(error), mods, loader)) {
                inTrace.add(b.id());
            }
        }
        long real = mods.stream().filter(m -> m.loader() != LoaderKind.LIBRARY).count();
        Map<LoaderKind, Integer> perLoader = new LinkedHashMap<>();
        for (ModInfo m : mods) {
            perLoader.merge(m.loader(), 1, Integer::sum);
        }
        sb.append("\nMODS (").append(real).append(" mods and ").append(mods.size() - real).append(" libraries; by loader: ").append(perLoader).append(")\n");
        sb.append("  Format: id | name | version | loader | state | file (size, sha-256 start)\n");
        long deadline = System.nanoTime() + 15_000_000_000L;
        for (ModInfo m : mods) {
            boolean off = Choices.isModOff(m.id());
            String hash = hash(m.jar(), deadline);
            long size = 0;
            try {
                size = Files.size(m.jar());
            } catch (java.io.IOException | RuntimeException e) {
            }
            sb.append("  ").append(m.id()).append(" | ").append(m.name()).append(" | ").append(m.version()).append(" | ")
                    .append(m.loader().name().toLowerCase()).append(" | ")
                    .append(off ? "turned off by player" : "on").append(inTrace.contains(m.id()) ? ", IN TRACE" : "").append(" | ")
                    .append(m.jar().getFileName()).append(" (").append(size / 1024).append(" KB, ").append(hash).append(")\n");
        }
    }

    private static void gameSettings(StringBuilder sb) throws java.io.IOException {
        sb.append("\nGAME SETTINGS\n");
        Path options = Dirs.run().resolve("options.txt");
        if (Files.isRegularFile(options)) {
            Set<String> keys = Set.of("lang", "renderDistance", "simulationDistance", "graphicsMode", "maxFps", "enableVsync",
                    "fullscreen", "resourcePacks", "incompatibleResourcePacks", "particles", "biomeBlendRadius", "mipmapLevels");
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                int c = line.indexOf(':');
                if (c > 0 && keys.contains(line.substring(0, c))) {
                    sb.append("  ").append(shorten(line, 300)).append('\n');
                }
            }
        } else {
            sb.append("  options.txt not found\n");
        }
        for (String file : new String[]{"oculus.properties", "iris.properties"}) {
            Path p = Dirs.config().resolve(file);
            if (Files.isRegularFile(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.startsWith("shaderPack") || line.startsWith("enableShaders")) {
                        sb.append("  ").append(file).append(": ").append(line.trim()).append('\n');
                    }
                }
            }
        }
        Path settings = Dirs.config().resolve("pentecost").resolve("settings.json");
        if (Files.isRegularFile(settings)) {
            sb.append("  Pentecost settings (settings.json):\n");
            String text = Files.readString(settings, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            sb.append("    ").append(shorten(text, 1500)).append('\n');
        }
    }

    private static final Pattern LEVEL = Pattern.compile("^\\[[^\\]]*\\] \\[([^/\\]]*)/(ERROR|FATAL|WARN)\\] \\[[^\\]]*\\]: (.*)$");

    private static void logSections(StringBuilder sb) throws java.io.IOException {
        Path log = Dirs.run().resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) {
            sb.append("\nLOG\n  run/logs/latest.log not found\n");
            return;
        }
        long len = Files.size(log);
        String head = slice(log, 0, Math.min(len, 8L << 20));
        String tail = len > (8L << 20) ? slice(log, Math.max(0, len - (4L << 20)), len) : head;
        Map<String, Integer> errors = new LinkedHashMap<>();
        int warns = 0;
        for (String line : head.split("\\R")) {
            Matcher m = LEVEL.matcher(line);
            if (!m.matches()) {
                continue;
            }
            if (m.group(2).equals("WARN")) {
                warns++;
            } else {
                errors.merge(shorten(m.group(3), 170), 1, Integer::sum);
            }
        }
        sb.append("\nERRORS EARLIER IN THIS RUN (from run/logs/latest.log; ").append(warns).append(" warnings not listed)\n");
        if (errors.isEmpty()) {
            sb.append("  none\n");
        } else {
            int shown = 0;
            for (Map.Entry<String, Integer> e : errors.entrySet()) {
                if (shown++ == 12) {
                    sb.append("  ... and ").append(errors.size() - 12).append(" more kinds\n");
                    break;
                }
                sb.append("  ").append(e.getValue() > 1 ? e.getValue() + "x " : "").append(e.getKey()).append('\n');
            }
        }
        String[] lines = tail.split("\\R");
        sb.append("\nLAST LOG LINES BEFORE THE CRASH\n");
        for (int i = Math.max(0, lines.length - 40); i < lines.length; i++) {
            sb.append("  ").append(shorten(lines[i], 260)).append('\n');
        }
    }

    private static void earlierCrashes(StringBuilder sb) throws java.io.IOException {
        sb.append("\nEARLIER CRASH REPORTS (last 24 hours)\n");
        long since = System.currentTimeMillis() - 24L * 3600_000L;
        int shown = 0;
        try (var files = Files.list(Dirs.crashReports())) {
            List<Path> recent = files.filter(p -> p.toFile().lastModified() >= since)
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified())).toList();
            for (Path p : recent) {
                if (shown++ == 6) {
                    sb.append("  ... ").append(recent.size() - 6).append(" more\n");
                    break;
                }
                sb.append("  ").append(p.getFileName()).append('\n');
            }
            if (recent.isEmpty()) {
                sb.append("  none. This is the first one in the last day.\n");
            }
        }
    }

    public static Throwable unwrap(Throwable error) {
        Throwable root = error;
        while ((root instanceof InvocationTargetException || root instanceof ExceptionInInitializerError
                || root.getClass().getSimpleName().equals("ReportedException")) && root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    public static Throwable deepest(Throwable root) {
        Throwable d = root;
        for (int i = 0; i < 40 && d.getCause() != null && d.getCause() != d; i++) {
            d = d.getCause();
        }
        return d;
    }

    public static String sentence(Throwable t) {
        String msg = t.getMessage();
        String type = t.getClass().getSimpleName();
        if (t instanceof ClassNotFoundException || t instanceof NoClassDefFoundError) {
            return "The game asked for the class " + msg + " and no installed mod or library provides it.";
        }
        if (t instanceof NoSuchMethodError || t instanceof NoSuchFieldError) {
            return "A mod called " + msg + ", which does not exist in this game version or in Pentecost yet.";
        }
        if (t instanceof OutOfMemoryError) {
            return "The game ran out of memory (" + msg + ").";
        }
        if (t instanceof StackOverflowError) {
            return "Code called itself without end until the stack ran out.";
        }
        if (t instanceof UnsupportedClassVersionError) {
            return "A file was built for a newer Java than the one running (" + msg + ").";
        }
        if (t instanceof NullPointerException) {
            return "A value was missing where the code expected one (" + (msg == null ? "no detail" : shorten(msg, 220)) + ").";
        }
        if (t instanceof ClassCastException) {
            return "Code treated an object as the wrong kind (" + (msg == null ? "no detail" : shorten(msg, 220)) + ").";
        }
        return type + (msg == null ? "" : ": " + shorten(msg, 300));
    }

    static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\r', ' ').replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static String seconds(double s) {
        return String.format(java.util.Locale.ROOT, s >= 120 ? "%.1f minutes" : "%.0f seconds", s >= 120 ? s / 60 : s);
    }

    static String maskSecrets(String s) {
        return s.replaceAll("(--(?:accessToken|uuid|xuid|clientId|userProperties))[ =]\\S+", "$1 <hidden>")
                .replaceAll("(?i)(token|session)=\\S+", "$1=<hidden>");
    }

    static String redact(String text) {
        String home = System.getProperty("user.home");
        if (home != null && home.length() > 3) {
            text = text.replace(home, "<home>").replace(home.replace('\\', '/'), "<home>");
        }
        return text;
    }

    private static String hash(Path jar, long deadline) {
        if (System.nanoTime() > deadline) {
            return "not hashed";
        }
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= 12) {
                    break;
                }
            }
            return hex.toString();
        } catch (Exception e) {
            return "unreadable";
        }
    }

    private static String slice(Path file, long from, long to) throws java.io.IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(from);
            byte[] buf = new byte[(int) (to - from)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
