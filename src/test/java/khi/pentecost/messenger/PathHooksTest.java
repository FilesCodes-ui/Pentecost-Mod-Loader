package khi.pentecost.messenger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the path patch to the real PathNavigation from the game jar and makes the JVM load and verify it.
 * A wrong stack size or frame would fail here, not in the player's game. Skipped when the game jar is not in cache.
 */
class PathHooksTest {
    private static final String NAME = "net.minecraft.world.entity.ai.navigation.PathNavigation";

    private static Path gameJar() throws IOException {
        Path dir = Path.of("cache", "remapped");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("client-.*-forge-named\\.jar")).findFirst().orElse(null);
        }
    }

    @Test
    void patchedPathNavigationLoadsAndVerifies() throws Exception {
        Path jar = gameJar();
        Assumptions.assumeTrue(jar != null, "No game jar in cache/remapped");
        byte[] original;
        try (JarFile jf = new JarFile(jar.toFile()); InputStream in = jf.getInputStream(jf.getEntry(NAME.replace('.', '/') + ".class"))) {
            original = in.readAllBytes();
        }
        byte[] patched = PathHooks.transformer().transform(NAME, original);
        assertNotSame(original, patched, "The patch did not match the game's PathNavigation");

        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        try (Stream<Path> s = Files.list(jar.getParent())) {
            for (Path p : (Iterable<Path>) s.filter(p -> p.getFileName().toString().matches("forge-.*-universal-named\\.jar"))::iterator) {
                urls.add(p.toUri().toURL());
            }
        }
        Path libs = Path.of("cache", "libraries");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> s = Files.walk(libs)) {
                for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                    urls.add(p.toUri().toURL());
                }
            }
        }
        ClassLoader parent = PathHooksTest.class.getClassLoader();
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), parent) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(n)) {
                    Class<?> c = findLoadedClass(n);
                    if (c == null && n.equals(NAME)) {
                        c = defineClass(n, patched, 0, patched.length);
                    } else if (c == null && (n.startsWith("net.minecraft.") || n.startsWith("net.minecraftforge.")
                            || n.startsWith("com.mojang."))) {
                        c = findClass(n);
                    }
                    if (c != null) {
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                    return super.loadClass(n, resolve);
                }
            }
        }) {
            Class<?> nav = Class.forName(NAME, false, cl);
            // Verification runs when the class links. Asking for a method handle on a patched method forces it.
            Method settled = nav.getMethod("pentecost$settled");
            assertEquals(void.class, settled.getReturnType());
            java.lang.invoke.MethodHandles.privateLookupIn(nav, java.lang.invoke.MethodHandles.lookup()).ensureInitialized(nav);
            assertTrue(nav.getDeclaredMethods().length > 10);
        }
    }

    @Test
    void frameProbePatchesLoadAndVerify() throws Exception {
        Path jar = gameJar();
        Assumptions.assumeTrue(jar != null, "No game jar in cache/remapped");
        for (String cls : new String[]{"net.minecraft.client.renderer.ItemInHandRenderer", "com.mojang.blaze3d.platform.Window",
                "net.minecraft.client.renderer.entity.EntityRenderDispatcher"}) {
            byte[] original;
            try (JarFile jf = new JarFile(jar.toFile()); InputStream in = jf.getInputStream(jf.getEntry(cls.replace('.', '/') + ".class"))) {
                original = in.readAllBytes();
            }
            byte[] patched = cls.endsWith("EntityRenderDispatcher")
                    ? khi.pentecost.core.CullHooks.transformer().transform(cls, original)
                    : khi.pentecost.core.FrameProbe.transformer().transform(cls, original);
            assertNotSame(original, patched, cls + " was not patched");
            org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(patched);
            java.io.StringWriter out = new java.io.StringWriter();
            // Checks every method's stack and types without loading the game.
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
            try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), PathHooksTest.class.getClassLoader())) {
                org.objectweb.asm.util.CheckClassAdapter.verify(r, cl, false, new java.io.PrintWriter(out));
            }
            assertTrue(!out.toString().contains("Exception"), out.toString());
        }
    }

    @Test
    void hudPatchOnForgeGuiVerifies() throws Exception {
        Path jar = gameJar();
        Assumptions.assumeTrue(jar != null, "No game jar in cache/remapped");
        Path forge;
        try (Stream<Path> s = Files.list(jar.getParent())) {
            forge = s.filter(p -> p.getFileName().toString().matches("forge-.*-universal-named\\.jar")).findFirst().orElse(null);
        }
        Assumptions.assumeTrue(forge != null, "No Forge jar");
        String cls = "net.minecraftforge.client.gui.overlay.ForgeGui";
        byte[] original;
        try (JarFile jf = new JarFile(forge.toFile()); InputStream in = jf.getInputStream(jf.getEntry(cls.replace('.', '/') + ".class"))) {
            original = in.readAllBytes();
        }
        byte[] patched = khi.pentecost.core.HudOwner.forgeTransformer().transform(cls, original);
        org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(patched);
        java.io.StringWriter out = new java.io.StringWriter();
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        urls.add(forge.toUri().toURL());
        for (Path libs : List.of(Path.of("cache", "libraries"), Path.of("cache", "forge-install", "libraries"))) {
        if (Files.isDirectory(libs)) {
            try (Stream<Path> s = Files.walk(libs)) {
                for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                    urls.add(p.toUri().toURL());
                }
            }
        }
        }
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), PathHooksTest.class.getClassLoader())) {
            org.objectweb.asm.util.CheckClassAdapter.verify(r, cl, false, new java.io.PrintWriter(out));
        }
        assertTrue(!out.toString().contains("Exception"), out.toString());
        assertTrue(new String(patched, java.nio.charset.StandardCharsets.ISO_8859_1).contains("pentecost$renderFood"));
    }

    @Test
    void otherClassesAreLeftAlone() {
        byte[] b = {1, 2, 3};
        assertEquals(b, PathHooks.transformer().transform("net.minecraft.world.entity.Mob", b));
    }
}
