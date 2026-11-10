package khi.pentecost.crash;

import khi.pentecost.boot.Dirs;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Writes a crash report a player can read: cause, blamed mod, fix, then the details. */
public final class CrashReport {
    private CrashReport() {}

    public static Path write(Throwable error, List<ModInfo> mods, GameClassLoader loader) {
        String text = build(error, mods, loader);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
        Path file = Dirs.crashReports().resolve("crash-" + stamp + ".txt");
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            System.err.println("Could not save the crash report: " + e);
        }
        System.err.println(text);
        System.err.println("Saved to " + file);
        return file;
    }

    public static String build(Throwable error, List<ModInfo> mods, GameClassLoader loader) {
        Throwable root = error;
        while ((root instanceof InvocationTargetException || root instanceof ExceptionInInitializerError)
                && root.getCause() != null) {
            root = root.getCause();
        }
        Throwable deepest = root;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }

        ModInfo blamed = null;
        StackTraceElement blamedFrame = null;
        if (loader != null) {
            outer:
            for (Throwable t = root; t != null; t = t.getCause() == t ? null : t.getCause()) {
                for (StackTraceElement f : t.getStackTrace()) {
                    String id = loader.modOf(f.getClassName());
                    if (id != null) {
                        for (ModInfo m : mods) {
                            if (m.id().equals(id)) {
                                blamed = m;
                                blamedFrame = f;
                                break outer;
                            }
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("==== PENTECOST CRASH REPORT ====\n\n");
        sb.append("WHAT BROKE\n  ").append(sentence(deepest)).append("\n\n");
        sb.append("BLAMED MOD\n  ");
        if (blamed != null) {
            sb.append(blamed.label()).append("\n  File: ").append(blamed.jar().getFileName());
            sb.append("\n  At: ").append(blamedFrame);
        } else {
            sb.append("No mod appears in the error. The fault sits in Pentecost or Minecraft.");
        }
        sb.append("\n\nWHAT TO DO\n  ").append(advice(deepest, blamed)).append("\n\n");

        sb.append("SHORT TRACE\n");
        int shown = 0;
        for (StackTraceElement f : deepest.getStackTrace()) {
            if (shown++ >= 12) {
                sb.append("  ...\n");
                break;
            }
            String id = loader == null ? null : loader.modOf(f.getClassName());
            sb.append("  ").append(f).append(id == null ? "" : "   <-- mod " + id).append('\n');
        }

        Runtime rt = Runtime.getRuntime();
        sb.append("\nSYSTEM\n");
        sb.append("  Java ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("  OS ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append('\n');
        sb.append("  Memory ").append(rt.totalMemory() / 1048576).append(" MB used of ").append(rt.maxMemory() / 1048576).append(" MB allowed\n");
        sb.append("  CPU threads ").append(rt.availableProcessors()).append('\n');

        sb.append("\nMODS (").append(mods.size()).append(")\n");
        for (ModInfo m : mods) {
            sb.append("  ").append(m.label()).append('\n');
        }

        StringWriter sw = new StringWriter();
        root.printStackTrace(new PrintWriter(sw));
        sb.append("\nFULL TRACE (for developers)\n").append(sw);
        return sb.toString();
    }

    private static String sentence(Throwable t) {
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
        if (t instanceof NullPointerException) {
            return "A value was missing where the code expected one (" + (msg == null ? "no detail" : msg) + ").";
        }
        return type + (msg == null ? "" : ": " + msg);
    }

    private static String advice(Throwable t, ModInfo blamed) {
        if (t instanceof OutOfMemoryError) {
            return "Give the game more memory, or remove large mods.";
        }
        if (t instanceof ClassNotFoundException || t instanceof NoClassDefFoundError) {
            return (blamed == null ? "A mod" : blamed.name())
                    + " needs a library that is not installed, or calls a loader feature Pentecost lacks. "
                    + "Install the library from Modrinth. If the class belongs to Forge or Fabric Loader, report it to Pentecost.";
        }
        if (t instanceof NoSuchMethodError || t instanceof NoSuchFieldError) {
            return "Check that " + (blamed == null ? "the mod" : blamed.name())
                    + " targets Minecraft 1.20.1. If it does, report the missing call to Pentecost.";
        }
        if (blamed != null) {
            return "Update " + blamed.name() + ". If the crash stays, remove " + blamed.jar().getFileName()
                    + " from the mods folder and report this file to its author.";
        }
        return "Send this file to the Pentecost developer.";
    }
}
