package khi.pentecost.boot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Dirs {
    private static final Path HOME =
            Path.of(System.getProperty("pentecost.home", ".")).toAbsolutePath().normalize();

    private Dirs() {}

    public static Path home() { return HOME; }
    public static Path cache() { return mk(HOME.resolve("cache")); }
    public static Path run() { return mk(HOME.resolve("run")); }
    public static Path mods() { return mk(run().resolve("mods")); }
    public static Path config() { return mk(run().resolve("config")); }
    public static Path crashReports() { return mk(run().resolve("crash-reports")); }
    public static Path reports() { return mk(HOME.resolve("build").resolve("reports").resolve("pentecost")); }

    public static Path mk(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
