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
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutlineHookTest {
    private static final Path GAME = Path.of("cache", "remapped", "client-1.20.1-47.4.10-forge-named.jar");

    @Test
    void levelRendererIsRewrittenAndVerifies() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(GAME), "No remapped game jar");
        String cls = "net.minecraft.client.renderer.LevelRenderer";
        byte[] original;
        try (JarFile jf = new JarFile(GAME.toFile()); InputStream in = jf.getInputStream(jf.getEntry(cls.replace('.', '/') + ".class"))) {
            original = in.readAllBytes();
        }
        byte[] patched = ClientHooks.transformer().transform(cls, original);
        String text = new String(patched, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("(Ljava/lang/Object;Ljava/lang/Object;DDDLjava/lang/Object;Ljava/lang/Object;)Z"), "no outline hook");
        List<URL> urls = new ArrayList<>();
        urls.add(GAME.toUri().toURL());
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
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), OutlineHookTest.class.getClassLoader())) {
            org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(patched), cl, false, new java.io.PrintWriter(out));
        }
        assertFalse(out.toString().contains("Exception"), out.toString());
    }
}
