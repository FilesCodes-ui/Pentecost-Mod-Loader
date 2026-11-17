package khi.pentecost.boot;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Small logger with a time stamp since launch. Every line goes to the console and to run/logs/pentecost.log.
 * The file matters: a launcher that starts the game without a console (the Manager, a shortcut) throws the console away,
 * and with it every Mixin warning and every loader message. The last launch's file is kept as pentecost-previous.log.
 */
public final class Log {
    private static final long START = System.nanoTime();
    private static PrintWriter file;
    private static boolean opened;

    private Log() {}

    public static void info(String msg) { write("INFO", msg); }
    public static void warn(String msg) { write("WARN", msg); }
    public static void error(String msg) { write("ERROR", msg); }

    public static double seconds() {
        return (System.nanoTime() - START) / 1_000_000_000.0;
    }

    private static String prefix(String level) {
        return String.format("[Pentecost %7.2fs %s] ", seconds(), level);
    }

    private static synchronized void write(String level, String msg) {
        String line = prefix(level) + msg;
        System.out.println(line);
        if (!opened) {
            opened = true;
            file = open();
        }
        if (file != null) {
            file.println(line);
            file.flush();
        }
    }

    /** Opens the file. A failure here must never stop the game, so it only means no file. */
    private static PrintWriter open() {
        try {
            Path dir = Dirs.home().resolve("run").resolve("logs");
            Files.createDirectories(dir);
            Path now = dir.resolve("pentecost.log");
            boolean again = System.getProperty("pentecost.relaunched") != null;
            if (!again && Files.exists(now)) {
                Files.move(now, dir.resolve("pentecost-previous.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            return new PrintWriter(Files.newBufferedWriter(now, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
