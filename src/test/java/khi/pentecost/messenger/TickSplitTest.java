package khi.pentecost.messenger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class TickSplitTest {
    private static Path eventBusJar() throws Exception {
        Path nested = Path.of("cache", "nested");
        if (!Files.isDirectory(nested)) {
            return null;
        }
        try (Stream<Path> s = Files.walk(nested)) {
            return s.filter(p -> p.getFileName().toString().matches("eventbus-.*\\.jar")).findFirst().orElse(null);
        }
    }

    @Test
    void eventBusPatchMatchesAndVerifies() throws Exception {
        Path jar = eventBusJar();
        Assumptions.assumeTrue(jar != null, "No Forge event bus jar in cache/nested");
        String cls = "net.minecraftforge.eventbus.EventBus";
        byte[] original;
        try (JarFile jf = new JarFile(jar.toFile()); InputStream in = jf.getInputStream(jf.getEntry(cls.replace('.', '/') + ".class"))) {
            original = in.readAllBytes();
        }
        byte[] patched = TickSplit.transformer().transform(cls, original);
        assertNotSame(original, patched, "The event bus was not patched");
        java.util.List<URL> urls = new java.util.ArrayList<>();
        urls.add(jar.toUri().toURL());
        for (Path libs : java.util.List.of(Path.of("cache", "libraries"), Path.of("cache", "forge-install", "libraries"))) {
            if (Files.isDirectory(libs)) {
                try (Stream<Path> s = Files.walk(libs)) {
                    for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                        urls.add(p.toUri().toURL());
                    }
                }
            }
        }
        java.io.StringWriter out = new java.io.StringWriter();
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), TickSplitTest.class.getClassLoader())) {
            org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(patched), cl, false, new java.io.PrintWriter(out));
        }
        assertFalse(out.toString().contains("Exception"), out.toString());
    }

    @Test
    void withoutGlueTheListIsUntouched() {
        Object[] list = {"a", "b"};
        assertSame(list, TickSplit.split(list, new Object()));
    }
}
