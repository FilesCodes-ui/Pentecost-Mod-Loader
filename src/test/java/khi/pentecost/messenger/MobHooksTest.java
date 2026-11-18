package khi.pentecost.messenger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the mob thread patches to the real game classes and checks every method's stack and types.
 * A wrong frame or stack size fails here, not in the player's game. Skipped when the game jar is not in cache.
 */
class MobHooksTest {
    private static Path gameJar() throws IOException {
        Path dir = Path.of("cache", "remapped");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("client-.*-forge-named\\.jar")).findFirst().orElse(null);
        }
    }

    private static URLClassLoader loader(Path jar) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        try (Stream<Path> s = Files.list(jar.getParent())) {
            for (Path p : (Iterable<Path>) s.filter(p -> p.getFileName().toString().matches("forge-.*-universal-named\\.jar"))::iterator) {
                urls.add(p.toUri().toURL());
            }
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
        return new URLClassLoader(urls.toArray(new URL[0]), MobHooksTest.class.getClassLoader());
    }

    private static byte[] read(Path jar, String cls) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile()); InputStream in = jf.getInputStream(jf.getEntry(cls.replace('.', '/') + ".class"))) {
            return in.readAllBytes();
        }
    }

    private static void verify(byte[] patched, URLClassLoader cl, String cls) {
        java.io.StringWriter out = new java.io.StringWriter();
        org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(patched), cl, false, new java.io.PrintWriter(out));
        assertFalse(out.toString().contains("Exception"), cls + ":\n" + out);
    }

    @Test
    void gamePatchesMatchAndVerify() throws Exception {
        Path jar = gameJar();
        Assumptions.assumeTrue(jar != null, "No game jar in cache/remapped");
        try (URLClassLoader cl = loader(jar)) {
            for (String cls : new String[]{"net.minecraft.server.level.ServerLevel", "net.minecraft.world.level.Level",
                    "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback", "net.minecraft.server.level.ServerChunkCache"}) {
                byte[] original = read(jar, cls);
                byte[] patched = MobHooks.transformer().transform(cls, original);
                assertNotSame(original, patched, cls + " was not patched");
                patched = MobHooks.callSites().transform(cls, patched);
                verify(patched, cl, cls);
            }
        }
    }

    @Test
    void callSitesRewriteHurtAndRandomAndVerify() throws Exception {
        Path jar = gameJar();
        Assumptions.assumeTrue(jar != null, "No game jar in cache/remapped");
        try (URLClassLoader cl = loader(jar)) {
            for (String cls : new String[]{"net.minecraft.world.entity.Mob", "net.minecraft.world.entity.monster.Zombie",
                    "net.minecraft.world.entity.animal.Wolf"}) {
                byte[] original = read(jar, cls);
                byte[] patched = MobHooks.callSites().transform(cls, original);
                assertNotSame(original, patched, cls + " had no call site to change");
                verify(patched, cl, cls);
            }
        }
    }

    @Test
    void ownClassesAreLeftAlone() {
        byte[] b = {1, 2, 3};
        assertSame(b, MobHooks.callSites().transform("khi.pentecost.game.MobThreads", b));
        assertTrue(MobHooks.contains("abc hurt xyz".getBytes(), "hurt".getBytes()));
        assertFalse(MobHooks.contains("abc".getBytes(), "hurt".getBytes()));
    }

    @Test
    void outsideThePhaseNothingIsDeferred() {
        assertFalse(MobHooks.deferring());
        assertFalse(MobHooks.setBlock(new Object(), null, null, 3, 512));
        assertFalse(MobHooks.addEntity(new Object(), null));
        Object rnd = new Object();
        assertSame(rnd, MobHooks.random(new Object(), rnd));
    }
}
