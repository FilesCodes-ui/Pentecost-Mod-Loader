package khi.pentecost.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudEventsTest {
    private static final Path GAME = Path.of("cache", "remapped", "client-1.20.1-47.4.10-forge-named.jar");
    private static final Path FORGE = Path.of("cache", "forge-install", "libraries", "net", "minecraftforge", "forge",
            "1.20.1-47.4.10", "forge-1.20.1-47.4.10-universal.jar");

    @Test
    void guiGetsBarCalls() throws Exception {
        check(GAME, "net.minecraft.client.gui.Gui", HudOwner.guiTransformer(), "bar");
    }

    @Test
    void forgeGuiEventsAreWrapped() throws Exception {
        check(FORGE, "net.minecraftforge.client.gui.overlay.ForgeGui", HudOwner.forgeTransformer(), "pentecost$pre");
    }

    private static void check(Path jar, String cls, khi.pentecost.loader.ClassTransformer t, String mark) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(jar) && Files.isRegularFile(GAME), "No game jars");
        byte[] original;
        try (JarFile jf = new JarFile(jar.toFile()); InputStream in = jf.getInputStream(jf.getEntry(cls.replace('.', '/') + ".class"))) {
            original = in.readAllBytes();
        }
        byte[] patched = t.transform(cls, original);
        assertNotSame(original, patched, cls + " was not rewritten");
        assertTrue(new String(patched, java.nio.charset.StandardCharsets.ISO_8859_1).contains(mark), "no hook in " + cls);
        List<URL> urls = new ArrayList<>();
        urls.add(GAME.toUri().toURL());
        urls.add(FORGE.toUri().toURL());
        for (Path libs : List.of(Path.of("cache", "libraries"), Path.of("cache", "forge-install", "libraries"))) {
            if (Files.isDirectory(libs)) {
                try (Stream<Path> s = Files.walk(libs)) {
                    for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                        urls.add(p.toUri().toURL());
                    }
                }
            }
        }
        java.io.StringWriter out = new java.io.StringWriter();
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), HudEventsTest.class.getClassLoader())) {
            org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(patched), cl, false, new java.io.PrintWriter(out));
        }
        assertFalse(out.toString().contains("Exception"), out.toString());
    }
}
