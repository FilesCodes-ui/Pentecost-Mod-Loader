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

class ParallelGenTest {
    private static final Path GAME = Path.of("cache", "remapped", "client-1.20.1-47.4.10-forge-named.jar");

    @Test
    void chunkStatusIsRewrittenAndVerifies() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(GAME), "No remapped game jar");
        String cls = "net.minecraft.world.level.chunk.ChunkStatus";
        byte[] original;
        try (JarFile jf = new JarFile(GAME.toFile()); InputStream in = jf.getInputStream(jf.getEntry(cls.replace('.', '/') + ".class"))) {
            original = in.readAllBytes();
        }
        byte[] patched = ParallelGen.transformer().transform(cls, original);
        assertNotSame(original, patched, "ChunkStatus was not rewritten");
        boolean[] seen = {false, false};
        new org.objectweb.asm.ClassReader(patched).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                if (n.equals("generate")) {
                    seen[0] = true;
                }
                if (n.equals("generate$pentecost") && (a & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0) {
                    seen[1] = true;
                }
                return null;
            }
        }, 0);
        assertTrue(seen[0] && seen[1], "generate and a public generate$pentecost should both exist");
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
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ParallelGenTest.class.getClassLoader())) {
            org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(patched), cl, false, new java.io.PrintWriter(out));
        }
        assertFalse(out.toString().contains("Exception"), out.toString());
    }
}
