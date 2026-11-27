package khi.pentecost.heal;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Self-healing for mods that Pentecost cannot start.
 *
 * What it does:
 *  1. Stands in for missing Fabric loader exception classes, so a mod that only names them
 *     in a catch clause or a signature loads instead of being disabled.
 *  2. Remembers which mods failed and why (run/pentecost-heal.properties). A mod that fails twice
 *     with the same error on the same Pentecost build and the same mod list is skipped, so the
 *     same crash does not repeat on every launch.
 *  3. Forgets all of it when Pentecost is rebuilt or the mod list changes, so every mod gets
 *     another try as soon as something that could fix it has changed.
 *
 * Switches: -Dpentecost.heal=off (no memory, no stand-ins), -Dpentecost.heal=reset (forget once).
 */
public final class Heal {
    private static final int QUARANTINE_AFTER = 2;
    private static final Properties P = new Properties();
    private static final Set<String> GAPS = ConcurrentHashMap.newKeySet();
    private static Path file;
    private static boolean enabled = true;
    private static boolean ready;
    /** Tests set this so they do not touch the real log files. */
    static volatile boolean quiet;

    private Heal() {}

    public static synchronized void begin(List<String> modIds) {
        if (ready) {
            return;
        }
        ready = true;
        String mode = System.getProperty("pentecost.heal", "on");
        if (mode.equalsIgnoreCase("off")) {
            enabled = false;
            Log.info("Heal: off (-Dpentecost.heal=off)");
            return;
        }
        file = Dirs.home().resolve("run").resolve("pentecost-heal.properties");
        try (InputStream in = Files.newInputStream(file)) {
            P.load(in);
        } catch (IOException | RuntimeException ignored) {
        }
        List<String> sorted = new ArrayList<>(modIds);
        Collections.sort(sorted);
        String world = Long.toHexString(buildStamp()) + "-" + Integer.toHexString(String.join(",", sorted).hashCode());
        boolean reset = mode.equalsIgnoreCase("reset");
        if (reset || !world.equals(P.getProperty("world"))) {
            int old = (int) P.stringPropertyNames().stream().filter(k -> k.endsWith(".count")).count();
            if (old > 0) {
                Log.info("Heal: " + (reset ? "reset asked for" : "Pentecost or the mod list changed")
                        + ", so " + old + " earlier failure(s) are forgotten and those mods get another try");
            }
            P.clear();
            P.setProperty("world", world);
            save();
        }
    }

    public static synchronized boolean quarantined(String modId) {
        if (!enabled) {
            return false;
        }
        return count(modId) >= QUARANTINE_AFTER;
    }

    public static synchronized String reason(String modId) {
        return P.getProperty("mod." + modId + ".kind", "failed before");
    }

    public static synchronized void record(String modId, Throwable cause) {
        if (!enabled) {
            return;
        }
        String sig = cause.getClass().getName() + ":" + clip(String.valueOf(cause.getMessage()), 160);
        int n = sig.equals(P.getProperty("mod." + modId + ".sig")) ? count(modId) + 1 : 1;
        P.setProperty("mod." + modId + ".sig", sig);
        P.setProperty("mod." + modId + ".count", Integer.toString(n));
        String kind = kind(cause);
        P.setProperty("mod." + modId + ".kind", kind);
        save();
        Log.warn("Heal: " + modId + " failed (" + kind + "), seen " + n + " time(s) on this build"
                + (n >= QUARANTINE_AFTER ? "; it is skipped from the next start until Pentecost or the mod list changes" : ""));
    }

    public static synchronized void clear(String modId) {
        if (!enabled) {
            return;
        }
        boolean had = P.remove("mod." + modId + ".count") != null;
        P.remove("mod." + modId + ".sig");
        P.remove("mod." + modId + ".kind");
        if (had) {
            Log.info("Heal: " + modId + " started cleanly, its failure record is cleared");
            save();
        }
    }

    /** Empty stand-in for a Fabric loader exception class that Pentecost's loader shim lacks. */
    public static byte[] stubFor(String className) {
        if (!enabled || !className.startsWith("net.fabricmc.loader.") && !className.startsWith("net.fabricmc.api.")) {
            return null;
        }
        boolean exception = className.endsWith("Exception");
        if (GAPS.add(className) && !quiet) {
            Log.warn("Heal: Pentecost has no " + className + (exception
                    ? ", so an empty stand-in was made"
                    : " and it cannot be faked safely. Add it to src/main/java (tools/scan-loader-refs.ps1 lists such gaps)"));
        }
        if (!exception) {
            return null;
        }
        String internal = className.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internal, null, "java/lang/Exception", null);
        String[] sigs = {"()V", "(Ljava/lang/String;)V", "(Ljava/lang/Throwable;)V",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V"};
        for (String sig : sigs) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", sig, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            int args = sig.equals("()V") ? 0 : sig.equals("(Ljava/lang/String;Ljava/lang/Throwable;)V") ? 2 : 1;
            for (int i = 1; i <= args; i++) {
                mv.visitVarInsn(Opcodes.ALOAD, i);
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Exception", "<init>", sig, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    public static String kind(Throwable t) {
        int depth = 0;
        for (Throwable c = t; c != null && depth < 8; c = c.getCause(), depth++) {
            String msg = String.valueOf(c.getMessage());
            if ((c instanceof NoClassDefFoundError || c instanceof ClassNotFoundException)
                    && (msg.contains("net/fabricmc/loader") || msg.contains("net.fabricmc.loader")
                    || msg.contains("net/fabricmc/api") || msg.contains("net.fabricmc.api"))) {
                return "Pentecost lacks a Fabric loader class: " + clip(msg, 120);
            }
            if (c instanceof ClassCastException && msg.contains("Holder$Reference")) {
                return "registry adapter returned the wrong type";
            }
            if (c instanceof NullPointerException && msg.contains("forgespi")) {
                return "Forge config stand-in is incomplete";
            }
        }
        return "mod error: " + t.getClass().getSimpleName();
    }

    private static int count(String modId) {
        try {
            return Integer.parseInt(P.getProperty("mod." + modId + ".count", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String clip(String s, int max) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() > max ? one.substring(0, max) : one;
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                P.store(out, "Pentecost self-healing memory. Delete this file or start with -Dpentecost.heal=reset to forget.");
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Newest class file time of Pentecost's own code: changes whenever Pentecost is rebuilt. */
    private static long buildStamp() {
        long best = 0;
        List<Path> roots = new ArrayList<>();
        try {
            roots.add(Path.of(Heal.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (Exception ignored) {
        }
        String glue = System.getProperty("pentecost.forgeglue");
        if (glue != null) {
            for (String p : glue.split(File.pathSeparator)) {
                roots.add(Path.of(p));
            }
        }
        for (Path root : roots) {
            try {
                if (Files.isDirectory(root)) {
                    try (Stream<Path> s = Files.walk(root)) {
                        for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                            best = Math.max(best, Files.getLastModifiedTime(p).toMillis());
                        }
                    }
                } else if (Files.exists(root)) {
                    best = Math.max(best, Files.getLastModifiedTime(root).toMillis());
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return best;
    }
}
