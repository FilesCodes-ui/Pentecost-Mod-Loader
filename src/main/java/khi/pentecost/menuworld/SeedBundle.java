package khi.pentecost.menuworld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A finished menu-world cache packed into one zip, so a launch can unpack it instead of generating the world.
 * The zip holds exactly what {@link SeedPregen} writes: meta.json, level.dat and region/r.X.Z.mca.
 * It lives at {@code <home>/menu_world/<seed>.zip}.
 * <p>
 * Make one: generate the seed once (the menu world does it, or {@code SeedPregen}), then run
 * {@code SeedBundle <seed>}. Unpacking only copies files. It never starts a server.
 */
public final class SeedBundle {
    private SeedBundle() {}

    public static Path bundleFile(Path home, long seed) {
        return home.resolve("menu_world").resolve(seed + ".zip");
    }

    /**
     * Unpacks the seed's zip into the cache when there is one. Returns true when the cache is then complete for the
     * area. A zip that is missing, damaged or too small for the area leaves the cache without a meta.json, so the
     * caller generates it the normal way.
     */
    public static boolean install(Path home, Path cacheDir, SeedPregen.Area area) {
        Path zip = bundleFile(home, area.seed());
        if (!Files.isRegularFile(zip)) {
            return false;
        }
        Path tmp = null;
        try {
            Files.createDirectories(cacheDir);
            Files.deleteIfExists(cacheDir.resolve("meta.json"));
            Files.deleteIfExists(cacheDir.resolve("progress.json")); // it described the files this replaces
            tmp = Files.createTempDirectory(cacheDir, ".bundle-");
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    Path target = tmp.resolve(e.getName()).normalize();
                    if (!target.startsWith(tmp)) {
                        throw new IOException("Bad entry in the bundle: " + e.getName());
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path meta = tmp.resolve("meta.json");
            if (!Files.isRegularFile(meta)) {
                return false;
            }
            Path region = tmp.resolve("region");
            if (Files.isDirectory(region)) {
                Path dst = Files.createDirectories(cacheDir.resolve("region"));
                try (Stream<Path> s = Files.list(region)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        Files.move(p, dst.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Path level = tmp.resolve("level.dat");
            if (Files.isRegularFile(level)) {
                Files.move(level, cacheDir.resolve("level.dat"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(meta, cacheDir.resolve("meta.json"), StandardCopyOption.REPLACE_EXISTING); // last: it says "complete"
            if (SeedPregen.isComplete(cacheDir, area)) {
                return true;
            }
            Files.deleteIfExists(cacheDir.resolve("meta.json"));
            return false;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(cacheDir.resolve("meta.json"));
            } catch (IOException ignored) {
            }
            return false;
        } finally {
            if (tmp != null) {
                deleteTree(tmp);
            }
        }
    }

    public static void export(Path cacheDir, Path zip) throws IOException {
        if (!Files.isRegularFile(cacheDir.resolve("meta.json"))) {
            throw new IOException("Nothing finished in " + cacheDir);
        }
        Files.createDirectories(zip.toAbsolutePath().getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(out, cacheDir.resolve("level.dat"), "level.dat");
            Path region = cacheDir.resolve("region");
            // Only the region files the meta lists: a cache that was moved or shrunk can hold more.
            java.util.Set<String> listed = new java.util.HashSet<>();
            try {
                var regions = com.google.gson.JsonParser.parseString(Files.readString(cacheDir.resolve("meta.json")))
                        .getAsJsonObject().getAsJsonArray("regions");
                if (regions != null) regions.forEach(e -> listed.add(e.getAsString()));
            } catch (RuntimeException ignored) {
            }
            if (Files.isDirectory(region)) {
                try (Stream<Path> s = Files.list(region)) {
                    for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                        String name = p.getFileName().toString();
                        if (name.endsWith(".mca") && (listed.isEmpty() || listed.contains(name))) {
                            add(out, p, "region/" + name);
                        }
                    }
                }
            }
            add(out, cacheDir.resolve("meta.json"), "meta.json");
        }
    }

    private static void add(ZipOutputStream out, Path file, String name) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        out.putNextEntry(new ZipEntry(name));
        Files.copy(file, out);
        out.closeEntry();
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    /** Command line: SeedBundle seed. Reads config/pentecost/menu_world/seed, writes menu_world/seed.zip under pentecost.home. */
    public static void main(String[] args) throws Exception {
        String seed = args[0];
        Path home = Path.of(System.getProperty("pentecost.home", "."));
        Path cache = Path.of("config", "pentecost", "menu_world", seed).toAbsolutePath();
        Path zip = bundleFile(home, Long.parseLong(seed)).toAbsolutePath();
        export(cache, zip);
        System.out.println("Wrote " + zip + " (" + Files.size(zip) / 1_000_000 + " MB)");
    }
}
