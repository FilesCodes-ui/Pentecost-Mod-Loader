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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartProbeTest {
    private static final Path GAME = Path.of("cache", "remapped", "client-1.20.1-47.4.10-forge-named.jar");
    private static final Path FORGE = Path.of("cache", "remapped", "forge-1.20.1-47.4.10-universal-named.jar");
    private static final Path OCULUS = Path.of("mods", "oculus-mc1.20.1-1.8.0.jar");

    @Test
    void everyTargetIsTimedAndVerifies() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(GAME), "No remapped game jar");
        List<Object[]> cases = new ArrayList<>(List.of(
                new Object[]{GAME, "net.minecraft.client.renderer.LevelRenderer", 8},
                new Object[]{GAME, "net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher", 1},
                new Object[]{GAME, "net.minecraft.client.particle.ParticleEngine", 2},
                new Object[]{GAME, "net.minecraft.client.gui.Gui", 1},
                new Object[]{GAME, "net.minecraft.client.Minecraft", 1},
                new Object[]{GAME, "net.minecraft.client.renderer.GameRenderer", 1},
                new Object[]{GAME, "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", 4},
                new Object[]{GAME, "net.minecraft.world.level.chunk.ChunkGenerator", 1},
                new Object[]{GAME, "net.minecraft.server.level.ThreadedLevelLightEngine", 1},
                new Object[]{GAME, "net.minecraft.server.level.ChunkMap", 1},
                new Object[]{GAME, "net.minecraft.server.level.ServerChunkCache", 1},
                new Object[]{GAME, "net.minecraft.server.MinecraftServer", 1}));
        if (Files.isRegularFile(FORGE)) {
            cases.add(new Object[]{FORGE, "net.minecraftforge.client.gui.overlay.ForgeGui", 1});
        }
        if (Files.isRegularFile(OCULUS)) {
            cases.add(new Object[]{OCULUS, "net.irisshaders.iris.shadows.ShadowRenderer", 1});
        }
        List<URL> urls = new ArrayList<>();
        for (Path p : List.of(GAME, FORGE, OCULUS)) {
            if (Files.isRegularFile(p)) {
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
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), PartProbeTest.class.getClassLoader())) {
            for (Object[] c : cases) {
                Path jar = (Path) c[0];
                String cls = (String) c[1];
                int wanted = (Integer) c[2];
                byte[] original;
                try (JarFile jf = new JarFile(jar.toFile())) {
                    var entry = jf.getEntry(cls.replace('.', '/') + ".class");
                    assertNotNull(entry, cls + " is not in " + jar);
                    try (InputStream in = jf.getInputStream(entry)) {
                        original = in.readAllBytes();
                    }
                }
                byte[] patched = PartProbe.transformer().transform(cls, original);
                assertNotSame(original, patched, cls + " was not timed");
                int[] enters = {0};
                new org.objectweb.asm.ClassReader(patched).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                if (owner.equals("khi/pentecost/core/PartProbe") && name.equals("enter")) {
                                    enters[0]++;
                                }
                            }
                        };
                    }
                }, 0);
                assertTrue(enters[0] >= wanted, cls + ": " + enters[0] + " timed methods, wanted " + wanted);
                java.io.StringWriter out = new java.io.StringWriter();
                org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(patched), cl, false,
                        new java.io.PrintWriter(out));
                assertFalse(out.toString().contains("Exception"), cls + ": " + out);
            }
        }
    }

    @Test
    void stepsCountOnlyWhileOn() {
        PartProbe.on = false;
        PartProbe.enter(PartProbe.NOISE);
        PartProbe.exit(PartProbe.NOISE);
        PartProbe.on = true;
        PartProbe.enter(PartProbe.NOISE);
        PartProbe.exit(PartProbe.NOISE);
        PartProbe.on = false;
    }
}
