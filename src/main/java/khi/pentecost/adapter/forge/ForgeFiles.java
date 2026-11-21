package khi.pentecost.adapter.forge;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.install.MinecraftInstall.GameFiles;
import khi.pentecost.remap.JarRemapper;
import khi.pentecost.remap.NameRemapper;
import khi.pentecost.remap.RemapService;
import khi.pentecost.remap.TextRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ForgeFiles {
    public static final String VERSION = "1.20.1-47.4.10";

    private final Path libraries = Dirs.cache().resolve("forge-install").resolve("libraries");

    public boolean installed() {
        return intact(patchedClient()) && brokenFiles().isEmpty();
    }

    public List<Path> brokenFiles() {
        List<Path> out = new ArrayList<>();
        for (Path p : List.of(patchedClient(), universalJar())) {
            if (Files.exists(p) && !intact(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private Path universalJar() {
        return libraries.resolve("net/minecraftforge/forge/" + VERSION + "/forge-" + VERSION + "-universal.jar");
    }

    public static boolean intact(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.size() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Path patchedClient() {
        return libraries.resolve("net/minecraftforge/forge/" + VERSION + "/forge-" + VERSION + "-client.jar");
    }

    public List<Path> rawLibraries() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String group : List.of("net/minecraftforge", "cpw/mods")) {
            Path dir = libraries.resolve(group);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-client.jar"))
                        .sorted().forEach(out::add);
            }
        }
        return out;
    }

    public List<Path> runtimeLibraries() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(libraries)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(p -> {
                String rel = libraries.relativize(p).toString().replace('\\', '/');
                String name = p.getFileName().toString();
                boolean skip = rel.startsWith("net/minecraft/") || rel.startsWith("de/oceanlabs/")
                        || rel.startsWith("org/spongepowered/") || rel.startsWith("org/ow2/")
                        || name.endsWith("-client.jar") || name.endsWith("-universal.jar")
                        || name.matches(".*(installertools|jarsplitter|ForgeAutoRenamingTool|binarypatcher|srgutils|fmlearlydisplay).*");
                if (!skip) {
                    out.add(p);
                }
            });
        }
        return out;
    }

    public Path universal(RemapService remap) throws IOException {
        Path in = libraries.resolve("net/minecraftforge/forge/" + VERSION + "/forge-" + VERSION + "-universal.jar");
        Path out = Dirs.cache().resolve("remapped").resolve("forge-" + VERSION + "-universal-named.jar");
        if (!Files.isRegularFile(out)) {
            JarRemapper.remap(in, out, new NameRemapper(remap.modTable()), new TextRemapper(remap.modTable()));
        }
        return out;
    }

    public GameFiles game(GameFiles named, RemapService remap) throws IOException {
        Path out = Dirs.cache().resolve("remapped").resolve("client-" + VERSION + "-forge-named.jar");
        if (!Files.isRegularFile(out)) {
            long t = System.nanoTime();
            Path patched = Dirs.cache().resolve("remapped").resolve("forge-" + VERSION + "-patches-named.jar");
            JarRemapper.remap(patchedClient(), patched, new NameRemapper(remap.modTable()), null);
            overlay(named.clientJar(), patched, out);
            Log.info(String.format("Built the Forge-patched game in %.1f s", (System.nanoTime() - t) / 1e9));
        }
        return new GameFiles(named.version(), out, named.clientMappings(), named.libraries(),
                named.assetsDir(), named.assetIndex(), named.mainClass());
    }

    private static void overlay(Path base, Path top, Path out) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        Set<String> seen = new HashSet<>();
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.setLevel(1);
            for (Path jar : List.of(top, base)) {
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory() || !seen.add(e.getName())) {
                            continue;
                        }
                        zos.putNextEntry(new ZipEntry(e.getName()));
                        try (InputStream in = z.getInputStream(e)) {
                            in.transferTo(zos);
                        }
                        zos.closeEntry();
                    }
                }
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }
}
