package khi.pentecost.messenger;

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

class HomeOnlyTest {
    private static final Path GAME = Path.of("cache", "remapped", "client-1.20.1-47.4.10-forge-named.jar");
    private static final Path FORGE = Path.of("cache", "remapped", "forge-1.20.1-47.4.10-universal-named.jar");

    @Test
    void guardsAreAddedAndVerify() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(GAME), "No remapped game jar");
        List<URL> urls = new ArrayList<>();
        urls.add(GAME.toUri().toURL());
        if (Files.isRegularFile(FORGE)) {
            urls.add(FORGE.toUri().toURL());
        }
        for (Path libs : List.of(Path.of("cache", "libraries"), Path.of("cache", "forge-install", "libraries"))) {
            if (Files.isDirectory(libs)) {
                try (Stream<Path> s = Files.walk(libs)) {
                    for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                        urls.add(p.toUri().toURL());
                    }
                }
            }
        }
        String[][] cases = {
                {"net.minecraft.world.entity.Entity", "2"},
                {"net.minecraft.server.level.ServerPlayer", "3"},
                {"net.minecraft.server.level.ServerLevel", "6"},
                {"net.minecraft.server.players.PlayerList", "1"}};
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), HomeOnlyTest.class.getClassLoader())) {
            for (String[] c : cases) {
                byte[] original;
                try (JarFile jf = new JarFile(GAME.toFile()); InputStream in = jf.getInputStream(jf.getEntry(c[0].replace('.', '/') + ".class"))) {
                    original = in.readAllBytes();
                }
                byte[] patched = HomeOnly.transformer().transform(c[0], original);
                int[] checks = {0};
                new org.objectweb.asm.ClassReader(patched).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                if (owner.equals("khi/pentecost/messenger/HomeOnly")) {
                                    checks[0]++;
                                }
                            }
                        };
                    }
                }, 0);
                assertTrue(checks[0] >= Integer.parseInt(c[1]), c[0] + ": " + checks[0] + " guards");
                java.io.StringWriter out = new java.io.StringWriter();
                org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(patched), cl, false, new java.io.PrintWriter(out));
                assertFalse(out.toString().contains("Exception"), c[0] + ": " + out);
            }
        }
    }

    @Test
    void sendHomeIsFoundWhenWrapped() {
        RuntimeException wrapped = new RuntimeException(new IllegalStateException(new HomeOnly.SendHome("x")));
        assertTrue(HomeOnly.isSendHome(wrapped));
        assertFalse(HomeOnly.isSendHome(new RuntimeException("no")));
    }
}
