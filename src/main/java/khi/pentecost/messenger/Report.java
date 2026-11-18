package khi.pentecost.messenger;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that went wrong in the messenger, written so a person can read it: when, which sender, which route,
 * on which thread, what kind of failure, and the stack trace. A new file each session. After MAX entries only the
 * count goes on, so a mod that fails every tick cannot fill the disk.
 */
final class Report {
    static final int MAX = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Path file;
    private final List<String> recent = new ArrayList<>();
    private int written;
    private int dropped;
    private boolean started;

    Report(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    synchronized void add(String kind, String from, String route, Lane lane, String thread, Throwable error, String note) {
        StringBuilder b = new StringBuilder();
        b.append(LocalDateTime.now().format(TIME)).append("  ").append(kind.toUpperCase()).append('\n');
        b.append("  sender:  ").append(from).append('\n');
        b.append("  route:   ").append(route).append('\n');
        b.append("  lane:    ").append(lane).append("   thread: ").append(thread).append('\n');
        if (note != null && !note.isEmpty()) {
            b.append("  what:    ").append(note).append('\n');
        }
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String[] lines = sw.toString().split("\\R");
            for (int i = 0; i < Math.min(lines.length, 30); i++) {
                b.append("    ").append(lines[i]).append('\n');
            }
            if (lines.length > 30) {
                b.append("    ... ").append(lines.length - 30).append(" more lines\n");
            }
        }
        String entry = b.toString();
        recent.add(entry);
        if (recent.size() > 20) {
            recent.remove(0);
        }
        if (written >= MAX) {
            dropped++;
            return;
        }
        written++;
        write(entry + '\n');
    }

    synchronized void note(String line) {
        write(LocalDateTime.now().format(TIME) + "  " + line + "\n");
    }

    synchronized List<String> recent() {
        return List.copyOf(recent);
    }

    synchronized int dropped() {
        return dropped;
    }

    private void write(String text) {
        if (file == null) {
            return;
        }
        try {
            if (!started) {
                started = true;
                Files.createDirectories(file.getParent());
                String head = "Pentecost messenger report\n"
                        + "Started " + LocalDateTime.now() + "\n"
                        + "Java " + Runtime.version() + ", " + Runtime.getRuntime().availableProcessors() + " cores\n"
                        + "Each entry: what failed, who sent it, where it was going, and the stack trace.\n"
                        + "A failed job falls back to the old single-thread path. A sender that keeps failing is quarantined:\n"
                        + "it stays on the old path until the game restarts.\n\n";
                Files.writeString(file, head, StandardCharsets.UTF_8);
            }
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The report is a help, never a reason to fail.
        }
    }
}
