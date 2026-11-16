package khi.pentecost.crash;

import khi.pentecost.boot.Dirs;
import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.ModInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes the crash report for a crash while Pentecost starts up. Crashes in the running game go through
 * Minecraft's own report, which {@link CrashDetails#decorate} adds the same summary and details to.
 */
public final class CrashReport {
    private CrashReport() {}

    public static Path write(Throwable error, List<ModInfo> mods, GameClassLoader loader) {
        String text = build(error, mods, loader);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
        Path file = Dirs.crashReports().resolve("crash-" + stamp + "-startup.txt");
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
        List<ModInfo> all = khi.pentecost.core.PentecostRuntime.allMods.isEmpty() ? mods : khi.pentecost.core.PentecostRuntime.allMods;
        Throwable root = CrashDetails.unwrap(error);
        Throwable deepest = CrashDetails.deepest(root);

        StringBuilder sb = new StringBuilder();
        sb.append("==== PENTECOST CRASH REPORT (startup) ====\n\n");
        try {
            sb.append(CrashDetails.summary(error, all, loader, false));
        } catch (Throwable e) {
            sb.append("WHAT BROKE\n  ").append(CrashDetails.sentence(deepest)).append("\n(the analysis itself failed: ").append(e).append(")\n");
        }

        sb.append("\nSHORT TRACE (deepest cause)\n");
        int shown = 0;
        for (StackTraceElement f : deepest.getStackTrace()) {
            if (shown++ >= 14) {
                sb.append("  ...\n");
                break;
            }
            String id = null;
            try {
                id = loader == null ? null : loader.modOf(f.getClassName());
            } catch (RuntimeException e) {
                // no mod name for this frame
            }
            sb.append("  ").append(f).append(id == null ? "" : "   <-- mod " + id).append('\n');
        }
        sb.append('\n');
        sb.append(CrashDetails.details(all, loader, error, false));

        StringWriter sw = new StringWriter();
        root.printStackTrace(new PrintWriter(sw));
        sb.append("\nFULL TRACE (for developers)\n").append(sw);
        return CrashDetails.redact(sb.toString());
    }
}
